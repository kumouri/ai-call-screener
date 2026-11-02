/**
 * "Call me until I answer" — an escalating outbound reminder call.
 *
 * `POST /push-call` with `{ escalate: true }` kicks off a `CallEscalation` Durable
 * Object: it places a call (speaking the reminder + "press 1 to stop"), then a
 * `storage.setAlarm()` re-fires the call on a cadence until she presses a digit
 * (Twilio POSTs `/push-call/ack`) or the attempt cap is hit. The loop lives here in
 * the Worker so it survives her desktop/daemon being off — the daemon only kicks it
 * off; Cloudflare owns the retries.
 *
 * ## Alert calls (`talk: true`, 2026-10-22)
 *
 * With `talk` set (and the call going to her own cell), each attempt is an ALERT call instead
 * (`../relay/alert.ts`): a talk-mode `RelaySession` call that opens by speaking the line, then stays
 * live listening for her — anything she says (that isn't a voicemail greeting) or any key press
 * acknowledges it (`/ack`, posted by the RelaySession). **The key-press call is the floor under
 * it**: an attempt whose talk call can't be placed — over the daily budget, the session seed or
 * the Twilio create failing — is placed as the ordinary `placeEscalationCall` `<Gather>` instead,
 * so an alert never degrades into a call that just ends. While an alert call is live
 * (`/connected` … `/ended`) the alarm holds the next re-call instead of ringing over it, bounded by
 * `IN_CALL_MAX_MS` in case `/ended` is lost.
 */
import type { Env } from "../config";
import { overBudget } from "../budget";
import { settingsFromEnv } from "../config";
import { sumSpendToday } from "../data/db";
import { placeAlertTalkCall, placeEscalationCall } from "../notify/call";

export const DEFAULT_INTERVAL_SEC = 120; // 2 minutes between callbacks
export const DEFAULT_MAX_ATTEMPTS = 15; // ~30 minutes of trying, then give up
/** How long a live alert call may hold the next re-call before it's assumed over (a lost `/ended`). */
export const IN_CALL_MAX_MS = 10 * 60 * 1000;

export interface EscalationState {
  attempt: number;
  maxAttempts: number;
  acked: boolean;
  /** An alert call from this escalation is live right now (see `isInCall`). */
  inCall?: boolean;
}

/**
 * Pure retry decision (unit-tested): stop once she's acked or the attempt cap is
 * reached; hold (`wait`) while an alert call is still live; otherwise place another
 * call. `attempt` is the number of calls already placed.
 */
export function nextEscalationStep(s: EscalationState): "call" | "stop" | "wait" {
  if (s.acked) return "stop";
  if (s.attempt >= s.maxAttempts) return "stop";
  if (s.inCall === true) return "wait";
  return "call";
}

/** Is an alert call live: connected, not ended, and not past `IN_CALL_MAX_MS` (pure — unit-tested). */
export function isInCall(connectedAtMs: number | undefined, endedAtMs: number | undefined, nowMs: number): boolean {
  if (connectedAtMs === undefined || connectedAtMs <= 0) return false;
  if (endedAtMs !== undefined && endedAtMs >= connectedAtMs) return false;
  return nowMs - connectedAtMs < IN_CALL_MAX_MS;
}

interface StartPayload {
  id: string;
  text: string;
  to: string;
  base: string;
  intervalSec?: number;
  maxAttempts?: number;
  /** Place each attempt as an alert (talk) call; honoured only when `to` is her own cell. */
  talk?: boolean;
  /** Seed context for the alert's talk session. */
  context?: string;
}

/** Durable Object: one instance per escalation id, driving the retry-until-ack loop. */
export class CallEscalation {
  private state: DurableObjectState;
  private env: Env;

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request: Request): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (pathname === "/start") return this.start(request);
    if (pathname === "/ack") return this.ack();
    if (pathname === "/connected") return this.mark("connectedAtMs");
    if (pathname === "/ended") return this.mark("endedAtMs");
    return new Response("not found", { status: 404 });
  }

  /** Persist config, place call #1, and arm the first retry alarm. */
  private async start(request: Request): Promise<Response> {
    const p = (await request.json()) as StartPayload;
    const intervalSec = p.intervalSec !== undefined && p.intervalSec > 0 ? p.intervalSec : DEFAULT_INTERVAL_SEC;
    const maxAttempts = p.maxAttempts !== undefined && p.maxAttempts > 0 ? p.maxAttempts : DEFAULT_MAX_ATTEMPTS;
    const to = p.to || this.env.USER_CELL_E164;
    await this.state.storage.put({
      id: p.id,
      text: p.text,
      to,
      base: p.base || this.env.PUBLIC_BASE_URL,
      intervalSec,
      maxAttempts,
      attempt: 0,
      acked: false,
      // Talk mode only ever rings her own phone — never a `to` override.
      talk: p.talk === true && to === this.env.USER_CELL_E164,
      context: typeof p.context === "string" ? p.context : "",
    });
    await this.placeAndArm();
    return new Response("ok");
  }

  /** She pressed a digit, or acknowledged an alert call — stop the loop. */
  private async ack(): Promise<Response> {
    await this.state.storage.put("acked", true);
    await this.state.storage.deleteAlarm();
    return new Response("ok");
  }

  /** An alert call's RelaySession reporting the call connected / ended. */
  private async mark(key: "connectedAtMs" | "endedAtMs"): Promise<Response> {
    await this.state.storage.put(key, Date.now());
    return new Response("ok");
  }

  /** Alarm wake: place the next call if we should still be trying. */
  async alarm(): Promise<void> {
    const acked = (await this.state.storage.get<boolean>("acked")) ?? false;
    const attempt = (await this.state.storage.get<number>("attempt")) ?? 0;
    const maxAttempts = (await this.state.storage.get<number>("maxAttempts")) ?? DEFAULT_MAX_ATTEMPTS;
    const inCall = isInCall(
      await this.state.storage.get<number>("connectedAtMs"),
      await this.state.storage.get<number>("endedAtMs"),
      Date.now(),
    );
    const step = nextEscalationStep({ attempt, maxAttempts, acked, inCall });
    if (step === "stop") return;
    if (step === "wait") {
      // She (or her voicemail) is on the line right now — don't ring over it; look again shortly.
      await this.state.storage.setAlarm(Date.now() + 30 * 1000);
      return;
    }
    await this.placeAndArm();
  }

  private async placeAndArm(): Promise<void> {
    const id = (await this.state.storage.get<string>("id")) ?? "";
    const text = (await this.state.storage.get<string>("text")) ?? "";
    const to = (await this.state.storage.get<string>("to")) ?? this.env.USER_CELL_E164;
    const base = (await this.state.storage.get<string>("base")) ?? this.env.PUBLIC_BASE_URL;
    const intervalSec = (await this.state.storage.get<number>("intervalSec")) ?? DEFAULT_INTERVAL_SEC;
    const attempt = (await this.state.storage.get<number>("attempt")) ?? 0;
    const talk = (await this.state.storage.get<boolean>("talk")) ?? false;
    const context = (await this.state.storage.get<string>("context")) ?? "";
    const ackUrl = `${base}/push-call/ack?id=${encodeURIComponent(id)}`;
    let placed = false;
    if (talk) {
      try {
        placed = await this.placeAlertTalk(id, attempt + 1, base, text, context);
      } catch (e) {
        console.log(`escalation ${id} attempt ${attempt + 1}: alert talk call failed, falling back to key press: ${String(e)}`);
      }
    }
    if (!placed) {
      try {
        await placeEscalationCall(this.env, to, text, ackUrl);
      } catch (e) {
        // A failed placement must not kill the loop — the next alarm retries.
        console.log(`escalation ${id} attempt ${attempt + 1} failed: ${String(e)}`);
      }
    }
    await this.state.storage.put("attempt", attempt + 1);
    await this.state.storage.setAlarm(Date.now() + intervalSec * 1000);
  }

  /**
   * One alert (talk) attempt: budget check, seed a fresh RelaySession (owner mode + the alert + this
   * escalation's id), place the call. `false` (over budget) or a throw sends the caller to the
   * key-press floor.
   */
  private async placeAlertTalk(id: string, attemptNo: number, base: string, text: string, context: string): Promise<boolean> {
    const settings = settingsFromEnv(this.env);
    const todayUtc = new Date().toISOString().slice(0, 10);
    const spent = await sumSpendToday(this.env.DB);
    if (overBudget({ dateUtc: todayUtc, spentUsd: spent }, todayUtc, settings.dailyBudgetUsd)) {
      console.log(`escalation ${id} attempt ${attemptNo}: daily budget reached — key-press call instead`);
      return false;
    }
    const sessionId = `${id}-${attemptNo}`;
    const stub = this.env.RELAY_SESSION.get(this.env.RELAY_SESSION.idFromName(sessionId));
    const seeded = await stub.fetch("https://relay/seed", {
      method: "POST",
      body: JSON.stringify({ mode: "owner", context, alert: text, escalationId: id }),
    });
    if (!seeded.ok) throw new Error(`relay seed ${seeded.status}`);
    // A new attempt starts a new call: forget the previous one's connected/ended marks.
    await this.state.storage.delete(["connectedAtMs", "endedAtMs"]);
    await placeAlertTalkCall(this.env, base, sessionId, text);
    return true;
  }
}
