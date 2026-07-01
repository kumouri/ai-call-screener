/**
 * Place an *outbound* call via Twilio's REST API. Unlike src/twilio/calls.ts
 * (which re-points a live inbound call), this originates a brand-new call that
 * speaks a short line and hangs up — Margo ringing Ceryce for a can't-miss
 * reminder. Uses Twilio's inline `Twiml` parameter, so no public webhook/URL is
 * needed: the whole `<Say>` script travels in the create request. `fetch`-based
 * so it runs in the Workers runtime.
 */
import type { Env } from "../config";
import { say } from "../twiml";

/** Twilio REST resource for creating a new call on an account (pure — unit-tested). */
export function callsCreateUrl(accountSid: string): string {
  return `https://api.twilio.com/2010-04-01/Accounts/${accountSid}/Calls.json`;
}

/**
 * Ring `toE164` from the Twilio number, speak `message`, and hang up. Returns the
 * new call SID. Inline TwiML (`<Say>…</Say><Hangup />`, ≤4000 chars) keeps this
 * self-contained; for a spoken reminder that's all we need (no ConversationRelay).
 */
export async function placeCall(env: Env, toE164: string, message: string): Promise<string> {
  const res = await fetch(callsCreateUrl(env.TWILIO_ACCOUNT_SID), {
    method: "POST",
    headers: {
      Authorization: `Basic ${btoa(`${env.TWILIO_ACCOUNT_SID}:${env.TWILIO_AUTH_TOKEN}`)}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({
      To: toE164,
      From: env.TWILIO_NUMBER_E164,
      Twiml: say(message, { hangup: true }),
    }),
  });
  if (!res.ok) {
    throw new Error(`Twilio call create ${res.status}: ${await res.text()}`);
  }
  const data = (await res.json()) as { sid?: string };
  return data.sid ?? "";
}
