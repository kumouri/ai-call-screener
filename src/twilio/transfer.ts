/**
 * Live transfer: ring the owner's cell up to LIVE_TRANSFER_ATTEMPTS times, then
 * take a voicemail. Ceryce's ask, 2026-09-12 (ruling
 * `2026-09-12-live-transfer-three-attempts` in margo/docs/rulings.md): "take a
 * message if I don't answer after three attempts".
 *
 * Every `<Dial>` that bridges a caller to her carries an `action` URL of
 * `${base}/after-bridge?attempt=N&from=…`; Twilio POSTs the DialCallStatus there
 * when the dial ends and `nextLiveTransferStep` (pure — unit-tested) decides:
 * answered ⇒ hang up, unanswered and attempts remain ⇒ dial again with N+1,
 * otherwise ⇒ voicemail exactly as before.
 *
 * NOT the reminder escalation's cap. `src/escalation/escalation.ts`'s
 * DEFAULT_MAX_ATTEMPTS (Margo calling *her* until she presses a digit) is a
 * different loop with a deliberately higher number and is untouched by this.
 */
import type { Env } from "../config";
import { dial } from "../twiml";

/** How many times a live transfer rings her before rolling to voicemail. */
export const LIVE_TRANSFER_ATTEMPTS = 3;

/**
 * Ring time per attempt. Short so the caller isn't held for minutes across three
 * tries, and — the load-bearing part — BELOW the owner's cell no-answer-forward timer,
 * so a missed bridge can't be forwarded back into Twilio and screened again.
 *
 * Ceryce's T-Mobile forward is set to 15 s (she shortened it because T-Mobile's own
 * voicemail was grabbing calls before Margo's forward fired), and the old 18–20 s ring
 * LOST that race: the bridge forwarded back to Margo, who screened the caller again —
 * the one caller who reached her phone four times that she reported 2026-09-12. Ruled
 * that night: keep this under the forward with margin, never assume ~25 s again.
 */
export const LIVE_TRANSFER_RING_SEC = 12;

export type LiveTransferStep = { kind: "hangup" } | { kind: "redial"; attempt: number } | { kind: "voicemail" };

/**
 * `attempt` is the 1-based number of the dial that just ended; `status` is
 * Twilio's DialCallStatus for it ("completed" = she answered).
 */
export function nextLiveTransferStep(status: string, attempt: number): LiveTransferStep {
  if (status === "completed") return { kind: "hangup" };
  const n = Number.isFinite(attempt) && attempt >= 1 ? Math.floor(attempt) : 1;
  if (n < LIVE_TRANSFER_ATTEMPTS) return { kind: "redial", attempt: n + 1 };
  return { kind: "voicemail" };
}

/** Parse the `attempt` query param of an /after-bridge callback; anything odd is attempt 1. */
export function parseAttempt(raw: string | null): number {
  const n = Number.parseInt(raw ?? "", 10);
  return Number.isFinite(n) && n >= 1 ? n : 1;
}

/** The /after-bridge action URL for a given attempt, carrying the caller for the voicemail leg. */
export function afterBridgeUrl(base: string, attempt: number, fromE164: string): string {
  const q = new URLSearchParams({ attempt: String(attempt) });
  if (fromE164 !== "") q.set("from", fromE164);
  return `${base}/after-bridge?${q.toString()}`;
}

/** TwiML for attempt N of a live transfer to the owner's cell. */
export function liveTransferTwiml(env: Env, base: string, attempt: number, fromE164: string): string {
  return dial(env.USER_CELL_E164, env.TWILIO_NUMBER_E164, {
    timeoutSec: LIVE_TRANSFER_RING_SEC,
    actionUrl: afterBridgeUrl(base, attempt, fromE164),
  });
}
