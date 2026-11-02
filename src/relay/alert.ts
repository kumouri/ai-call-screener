/**
 * Alert calls — a short line Margo has to get to Ceryce (a print about to pause for a filament swap,
 * a Call Me reminder), spoken as the opening of a live talk-mode call that then WAITS for her
 * instead of hanging up (her ask, 2026-10-22 19:45 CT: *"wait for me to say something back. At the
 * very least … wait for a button press"*). `margo/docs/voice-call-spec.md` § Alert calls.
 *
 * What "she got it" means is decided here, in pure functions, so the rule is unit-tested rather
 * than left to the model:
 * - a key press always counts;
 * - anything she SAYS counts, unless it reads like a voicemail greeting or a call-screening robot
 *   (`looksLikeVoicemail`) — the one way an answered call can produce speech without her on it.
 *   A voicemail pickup is hung up on and re-called later, never taken as an acknowledgement.
 */

/** Phrases a carrier/phone voicemail greeting or a call-screening assistant says, and she wouldn't. */
const VOICEMAIL_PATTERNS: RegExp[] = [
  /leave (?:a|your) (?:message|name)/i,
  /after the (?:tone|beep)/i,
  /at the (?:tone|beep)/i,
  /voice ?mail/i,
  /mailbox/i,
  /(?:is|are) not available/i,
  /(?:isn't|is unavailable|aren't) available/i,
  /the (?:person|party|number) you(?: are| have|'re|’re| were)? ?(?:calling|trying|dialed|reached)/i,
  /record your (?:message|name)/i,
  /(?:screening|screen) (?:service|this call|calls)/i,
  /state your name/i,
  /please stay on the line/i,
  /google (?:assistant|voice)/i,
  /bixby/i,
];

/** True when a transcribed utterance looks like a voicemail greeting / screening robot, not her. */
export function looksLikeVoicemail(text: string): boolean {
  const t = text.trim();
  if (t === "") return false;
  return VOICEMAIL_PATTERNS.some((re) => re.test(t));
}

/** Does this utterance acknowledge the alert? Anything she says, except a voicemail greeting. */
export function isAlertAck(voicePrompt: string): boolean {
  const t = voicePrompt.trim();
  return t !== "" && !looksLikeVoicemail(t);
}

/** Spoken after the alert line so she knows the call is waiting for her. */
export const ALERT_LISTENING_LINE = "I'm on the line — say something, or press 1, so I know you got it.";

/** The opening of an alert call: the alert itself, then the listening line. */
export function alertGreeting(alert: string): string {
  const line = alert.trim();
  return line === "" ? ALERT_LISTENING_LINE : `${line} ${ALERT_LISTENING_LINE}`;
}
