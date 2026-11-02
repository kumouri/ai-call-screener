/**
 * Place an *outbound* call via Twilio's REST API. Unlike src/twilio/calls.ts
 * (which re-points a live inbound call), this originates a brand-new call that
 * speaks a short line and hangs up — Margo ringing Ceryce for a can't-miss
 * reminder. Uses Twilio's inline `Twiml` parameter, so no public webhook/URL is
 * needed: the whole `<Say>` script travels in the create request. `fetch`-based
 * so it runs in the Workers runtime. (`placeCall` is the only shape that still
 * hangs up after its line; an alert call — `placeAlertTalkCall`, or the key-press
 * `placeEscalationCall` floor under it — waits for her.)
 *
 * ## The voice
 *
 * Both call shapes speak in the persona's TTS voice — `MARGO.ttsProvider` +
 * `MARGO.voice`, the same ElevenLabs id the inbound screener hands to
 * ConversationRelay — via Twilio's `<Say voice="ElevenLabs.<id>">` (public beta
 * since 2026-09-09). Ruling `2026-09-12-calls-use-persona-voice`: the default
 * Twilio voice is a *runtime fallback only*, never the first choice. Two layers
 * keep a reminder from failing because a voice did:
 *
 * 1. **Create-time.** If Twilio rejects the create request carrying the persona
 *    voice (any non-2xx — e.g. the account can't use the beta provider and the
 *    inline TwiML fails validation), the same call is re-placed once with a bare
 *    `<Say>`. A non-2xx create means no call was placed, so the retry cannot
 *    double-ring.
 * 2. **In-call.** An invalid `voice` value at speak time is Twilio warning 13511
 *    ("Say: Invalid voice value", log level WARNING), which does not terminate
 *    the call — Twilio speaks with its default voice and logs the warning. That
 *    layer is Twilio's, not ours; `wrangler tail` / the Twilio debugger is where
 *    a persistently-defaulting voice shows up.
 */
import type { Env } from "../config";
import { MARGO } from "../persona";
import { connectRelay, escalationGather, say, sayVoiceOf, wssOf } from "../twiml";
import { OWNER_GREETING } from "../relay/owner-prompt";
import { alertGreeting } from "../relay/alert";

/** Twilio REST resource for creating a new call on an account (pure — unit-tested). */
export function callsCreateUrl(accountSid: string): string {
  return `https://api.twilio.com/2010-04-01/Accounts/${accountSid}/Calls.json`;
}

/** One Twilio call-create POST with inline TwiML. Throws on a non-2xx response. */
async function createCall(env: Env, toE164: string, twiml: string): Promise<string> {
  const res = await fetch(callsCreateUrl(env.TWILIO_ACCOUNT_SID), {
    method: "POST",
    headers: {
      Authorization: `Basic ${btoa(`${env.TWILIO_ACCOUNT_SID}:${env.TWILIO_AUTH_TOKEN}`)}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({
      To: toE164,
      From: env.TWILIO_NUMBER_E164,
      Twiml: twiml,
    }),
  });
  if (!res.ok) {
    throw new Error(`Twilio call create ${res.status}: ${await res.text()}`);
  }
  const data = (await res.json()) as { sid?: string };
  return data.sid ?? "";
}

/**
 * Place a call in the persona's voice; if Twilio refuses that create, place it
 * once more in the default voice. `build(voice)` renders the TwiML for a given
 * `<Say voice>` (undefined => plain `<Say>`). The fallback is skipped entirely
 * when the persona names no voice, so a voiceless persona is exactly one request.
 */
async function createCallInPersonaVoice(
  env: Env,
  toE164: string,
  build: (voice: string | undefined) => string,
): Promise<string> {
  const voice = sayVoiceOf(MARGO);
  if (voice === undefined) return createCall(env, toE164, build(undefined));
  try {
    return await createCall(env, toE164, build(voice));
  } catch (e) {
    // A reminder call never fails because a voice did: re-place it in Twilio's
    // default voice. If THAT fails too, the error is the real one and propagates.
    console.log(`push-call: persona voice ${voice} rejected at create, falling back to default voice: ${String(e)}`);
    return createCall(env, toE164, build(undefined));
  }
}

/**
 * Ring `toE164` from the Twilio number, speak `message`, and hang up. Returns the
 * new call SID. Inline TwiML (`<Say>…</Say><Hangup />`, ≤4000 chars) keeps this
 * self-contained; for a spoken reminder that's all we need (no ConversationRelay).
 */
export async function placeCall(env: Env, toE164: string, message: string): Promise<string> {
  return createCallInPersonaVoice(env, toE164, (voice) => say(message, { hangup: true, voice }));
}

/**
 * Place one *escalating* reminder call: speak `message`, then listen for a keypress
 * (via `<Gather action=ackUrl>`). If she presses a digit, Twilio POSTs `ackUrl` and
 * the CallEscalation Durable Object stops retrying; otherwise the DO's alarm calls
 * back on a cadence. Returns the new call SID. Sibling of `placeCall` (single ring).
 */
export async function placeEscalationCall(
  env: Env,
  toE164: string,
  message: string,
  ackUrl: string,
): Promise<string> {
  const prompt = `${message} — press 1 to let me know you got it, and I'll stop calling.`;
  return createCallInPersonaVoice(env, toE164, (voice) => escalationGather({ prompt, actionUrl: ackUrl, voice }));
}

/**
 * Place a talk-mode call: rings `env.USER_CELL_E164` — deliberately the only destination this
 * function can dial, no `to` parameter exists to override it — and connects live to the
 * `RelaySession` Durable Object named `sessionId`, running in owner mode (seeded by the caller
 * BEFORE this is called, so the ConversationRelay handshake finds it already configured). Unlike
 * `placeCall`/`placeEscalationCall`, this never falls back to a scripted `<Say>`: a garbled
 * ConversationRelay voice is Twilio's problem (warning 13511), not a reason to skip the
 * conversation Ceryce asked for. Also carries a `mode=owner` `<Parameter>` — belt and braces
 * alongside the Durable Object's own persisted seed (`RelaySession.loadSeed`/`seed`), since only
 * the Worker generates this TwiML, so the parameter is trusted the same way the seed POST is.
 */
export async function placeTalkCall(env: Env, base: string, sessionId: string): Promise<string> {
  return createCall(env, env.USER_CELL_E164, talkCallTwiml(base, sessionId));
}

/** The talk-call TwiML (pure — unit-tested). */
export function talkCallTwiml(base: string, sessionId: string): string {
  return connectRelay({
    wsUrl: `${wssOf(base)}/ws?s=${sessionId}`,
    welcomeGreeting: OWNER_GREETING,
    ttsProvider: MARGO.ttsProvider,
    voice: MARGO.voice,
    parameters: [{ name: "mode", value: "owner" }],
  });
}

/**
 * Place an ALERT call (`../relay/alert.ts`): a talk-mode call whose opening is the alert line
 * itself, spoken in Margo's ConversationRelay voice, after which the call stays live and listens —
 * her reply is an ordinary owner-mode conversation, and a key press is forwarded as a `dtmf` frame.
 * Like `placeTalkCall` it can only ring `env.USER_CELL_E164`; the caller seeds `RelaySession`
 * `sessionId` (owner mode + the alert + its escalation id) BEFORE placing it.
 */
export async function placeAlertTalkCall(env: Env, base: string, sessionId: string, alert: string): Promise<string> {
  return createCall(env, env.USER_CELL_E164, alertTalkCallTwiml(base, sessionId, alert));
}

/** The alert-call TwiML (pure — unit-tested). The greeting can't be cut off by her "hello?". */
export function alertTalkCallTwiml(base: string, sessionId: string, alert: string): string {
  return connectRelay({
    wsUrl: `${wssOf(base)}/ws?s=${sessionId}`,
    welcomeGreeting: alertGreeting(alert),
    welcomeGreetingInterruptible: "none",
    dtmfDetection: true,
    ttsProvider: MARGO.ttsProvider,
    voice: MARGO.voice,
    parameters: [{ name: "mode", value: "owner" }],
  });
}
