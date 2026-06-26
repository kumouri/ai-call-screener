/**
 * Tiny, dependency-free TwiML builders. Twilio expects an XML document in the
 * webhook response; these return the full string (with XML declaration) for
 * each action the funnel can take. Pure functions => unit-testable.
 */

const XML_DECL = '<?xml version="1.0" encoding="UTF-8"?>';

/** Escape a string for safe inclusion in XML text / attribute values. */
export function escapeXml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function doc(body: string): string {
  return `${XML_DECL}<Response>${body}</Response>`;
}

/** Bridge the call to a number (used for allowlisted contacts and connect). */
export function dial(toE164: string, callerIdE164: string): string {
  return doc(`<Dial callerId="${escapeXml(callerIdE164)}">${escapeXml(toE164)}</Dial>`);
}

/** Decline the call *without answering it* — Twilio does not bill rejected calls. */
export function reject(reason: "rejected" | "busy" = "rejected"): string {
  return doc(`<Reject reason="${reason}" />`);
}

/** Speak a message, optionally hanging up afterward. */
export function say(message: string, opts: { hangup?: boolean } = {}): string {
  return doc(`<Say>${escapeXml(message)}</Say>${opts.hangup ? "<Hangup />" : ""}`);
}

export interface GateOptions {
  prompt: string;
  /** Absolute URL Twilio POSTs the pressed digit to (our /gate route). */
  actionUrl: string;
  numDigits?: number;
  timeoutSec?: number;
}

/**
 * Press-1 gate. If the caller enters nothing within `timeoutSec`, `<Gather>`
 * falls through to `<Hangup>` — which is exactly how we drop silent robodialers
 * for ~$0.
 */
export function gate(opts: GateOptions): string {
  const numDigits = opts.numDigits ?? 1;
  const timeout = opts.timeoutSec ?? 6;
  const gather =
    `<Gather numDigits="${numDigits}" timeout="${timeout}" action="${escapeXml(opts.actionUrl)}" method="POST">` +
    `<Say>${escapeXml(opts.prompt)}</Say>` +
    `</Gather>`;
  return doc(`${gather}<Hangup />`);
}

export interface ConnectRelayOptions {
  /** wss:// URL of our ConversationRelay WebSocket (the Durable Object). */
  wsUrl: string;
  welcomeGreeting: string;
}

/** Hand the answered call to Twilio ConversationRelay (the paid Claude stage). */
export function connectRelay(opts: ConnectRelayOptions): string {
  return doc(
    `<Connect>` +
      `<ConversationRelay url="${escapeXml(opts.wsUrl)}" welcomeGreeting="${escapeXml(opts.welcomeGreeting)}" />` +
      `</Connect>`,
  );
}
