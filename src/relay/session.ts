/**
 * RelaySession — the Durable Object that holds one ConversationRelay WebSocket
 * (Twilio <-> us) and the conversation state for a single screened call.
 *
 * Loop: caller speech -> Claude (turn engine) -> spoken reply, until Claude
 * reaches a terminal decision. Terminal effects use the Twilio REST API to
 * redirect the *live* call (bridge to the owner, or speak-and-hang-up), then
 * persist the verdict to D1 and text the owner.
 *
 * NOTE: the conversational loop + terminal effects are wired but only proven by
 * a real call (M2 live verification). The pure pieces they call (turn engine,
 * SMS formatting, cost, response parsing) are unit-tested.
 */
import type { Env } from "../config";
import type { SetupMessage } from "./protocol";
import { parseInbound, textToken } from "./protocol";
import type { LlmClient, LlmTurn } from "../screener/brain";
import { createAnthropicClient } from "../screener/anthropic-client";
import { runCallerTurn, type TerminalAction } from "../screener/conversation";
import { buildSystemPrompt, SCREENER_TOOLS } from "../screener/prompt";
import { estimateCallCost } from "../budget";
import { addToBlocklist, recordCall } from "../data/db";
import { sendSms } from "../notify/sms";
import { formatVerdictSms } from "../notify/format";
import { redirectToDial, redirectToHangup } from "../twilio/calls";

const MODEL = "claude-haiku-4-5-20251001"; // Haiku 4.5: fast + cheap for real-time turns
const MAX_TURNS = 4; // hard cap so a stalling caller can't run up minutes

export class RelaySession {
  private readonly state: DurableObjectState;
  private readonly env: Env;
  private readonly client: LlmClient;
  private readonly system: string;
  private callSid = "";
  private fromE164 = "";
  private toE164 = "";
  private startedAtMs = 0;
  private history: LlmTurn[] = [];

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
    this.client = createAnthropicClient({ apiKey: env.ANTHROPIC_API_KEY, model: MODEL });
    this.system = buildSystemPrompt(env.OWNER_NAME ?? "the owner");
  }

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket upgrade", { status: 426 });
    }
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.state.acceptWebSocket(server);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const raw = typeof message === "string" ? message : new TextDecoder().decode(message);
    const msg = parseInbound(raw);

    switch (msg.type) {
      case "setup": {
        const setup = msg as SetupMessage;
        this.callSid = typeof setup.callSid === "string" ? setup.callSid : "";
        const params = setup.customParameters ?? {};
        this.fromE164 = pick(params.from, setup.from);
        this.toE164 = pick(params.to, setup.to);
        this.startedAtMs = Date.now();
        break;
      }
      case "prompt": {
        const voicePrompt = typeof (msg as { voicePrompt?: unknown }).voicePrompt === "string" ? (msg as { voicePrompt: string }).voicePrompt : "";
        const outcome = await runCallerTurn(this.client, this.system, SCREENER_TOOLS, this.history, voicePrompt, MAX_TURNS);
        this.history = outcome.history;
        if (outcome.terminal !== undefined) {
          await this.handleTerminal(outcome.terminal);
        } else if (outcome.reply !== undefined) {
          ws.send(JSON.stringify(textToken(outcome.reply, true)));
        }
        break;
      }
      case "interrupt":
        // TODO(M2 live): cancel any in-flight generation for barge-in.
        break;
      default:
        break;
    }
  }

  private async handleTerminal(terminal: TerminalAction): Promise<void> {
    const id = crypto.randomUUID();
    const started = this.startedAtMs > 0 ? this.startedAtMs : Date.now();
    const startedAt = new Date(started).toISOString();
    const endedAt = new Date().toISOString();
    const elapsedSec = Math.max(1, Math.round((Date.now() - started) / 1000));
    const cost = estimateCallCost("converse", elapsedSec);
    const transcript = this.history.map((t) => `${t.role}: ${t.content}`).join("\n");

    if (terminal.kind === "connect") {
      await redirectToDial(this.env, this.callSid, this.env.USER_CELL_E164);
      await recordCall(this.env.DB, {
        id, fromE164: this.fromE164, toE164: this.toE164, startedAt, endedAt,
        outcomeStage: "conversation", verdict: "bridged",
        callerName: terminal.callerName, reason: terminal.reason, transcript, costEstimateUsd: cost,
      });
      return;
    }

    if (terminal.kind === "message") {
      await redirectToHangup(this.env, this.callSid, "Thanks — I'll pass your message along. Goodbye.");
      await recordCall(this.env.DB, {
        id, fromE164: this.fromE164, toE164: this.toE164, startedAt, endedAt,
        outcomeStage: "conversation", verdict: "message",
        callerName: terminal.callerName, reason: terminal.summary, transcript, costEstimateUsd: cost,
      });
      await this.notifyOwner({ verdict: "message", callerName: terminal.callerName, reason: terminal.summary, callbackNumber: terminal.callbackNumber, cost });
      return;
    }

    // spam: hang up, remember the number (learning blocklist), notify.
    await redirectToHangup(this.env, this.callSid, "This number isn't taking calls. Goodbye.");
    await addToBlocklist(this.env.DB, this.fromE164, `claude_spam:${terminal.reason}`);
    await recordCall(this.env.DB, {
      id, fromE164: this.fromE164, toE164: this.toE164, startedAt, endedAt,
      outcomeStage: "conversation", verdict: "spam",
      reason: terminal.reason, transcript, costEstimateUsd: cost,
    });
    await this.notifyOwner({ verdict: "spam", reason: terminal.reason, cost });
  }

  private async notifyOwner(args: {
    verdict: "message" | "spam";
    callerName?: string;
    reason?: string;
    callbackNumber?: string;
    cost: number;
  }): Promise<void> {
    const body = formatVerdictSms({
      verdict: args.verdict,
      fromE164: this.fromE164,
      callerName: args.callerName,
      reason: args.reason,
      callbackNumber: args.callbackNumber,
      costEstimateUsd: args.cost,
    });
    try {
      await sendSms(this.env, this.env.USER_CELL_E164, body);
    } catch {
      // best-effort notification — don't fail call handling on an SMS hiccup
    }
  }

  async webSocketClose(ws: WebSocket, _code: number, _reason: string, _wasClean: boolean): Promise<void> {
    try {
      ws.close();
    } catch {
      // socket already closing — ignore
    }
  }
}

function pick(a: string | undefined, b: string | undefined): string {
  if (typeof a === "string" && a !== "") return a;
  if (typeof b === "string" && b !== "") return b;
  return "";
}
