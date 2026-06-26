/**
 * Worker entry / HTTP router. This is the tiered funnel in action:
 *   POST /voice — Twilio's inbound-call webhook. Runs the funnel and returns
 *                 TwiML: Dial (allow) / Reject (block) / Gather (press-1 gate).
 *   POST /gate  — the digit the caller pressed. "1" => ring through or hand to
 *                 ConversationRelay; anything else => hang up (robodialer).
 *   GET  /ws    — ConversationRelay WebSocket, handed to the RelaySession DO.
 *   GET  /status— liveness + effective settings (expanded into a dashboard in M5).
 *
 * The public base URL is taken from PUBLIC_BASE_URL when set, otherwise derived
 * from the incoming request — so it works behind a dev tunnel or a deployed
 * Worker without extra config.
 */
import type { Env } from "./config";
import { settingsFromEnv } from "./config";
import type { CallerInfo } from "./screener/decision";
import { decideFunnel, decidePostGate } from "./screener/funnel";
import { lookupLists } from "./data/db";
import { connectRelay, dial, gate, reject, say } from "./twiml";
import { MARGO } from "./persona";

export { RelaySession } from "./relay/session";

export default {
  async fetch(request: Request, env: Env, _ctx: ExecutionContext): Promise<Response> {
    const { pathname } = new URL(request.url);
    const method = request.method;

    if (method === "POST" && pathname === "/voice") return handleVoice(request, env);
    if (method === "POST" && pathname === "/gate") return handleGate(request, env);
    if (pathname === "/ws") return handleWs(request, env);
    if (method === "GET" && pathname === "/status") return handleStatus(env);

    return new Response("not found", { status: 404 });
  },
};

function xml(body: string): Response {
  return new Response(body, { headers: { "Content-Type": "text/xml; charset=utf-8" } });
}

function field(form: FormData, key: string): string {
  const v = form.get(key);
  return typeof v === "string" ? v : "";
}

function baseUrlOf(request: Request, env: Env): string {
  const p = env.PUBLIC_BASE_URL;
  return p ? p : new URL(request.url).origin;
}

function wssOf(base: string): string {
  return base.replace(/^http:/i, "ws:").replace(/^https:/i, "wss:");
}

async function handleVoice(request: Request, env: Env): Promise<Response> {
  const form = await request.formData();
  const from = field(form, "From");
  const to = field(form, "To");
  const caller: CallerInfo = { fromE164: from, toE164: to, hasCallerId: from !== "" };

  const settings = settingsFromEnv(env);
  const lists = await lookupLists(env.DB, from);
  const decision = decideFunnel(caller, lists, settings);

  switch (decision.stage) {
    case "allow":
      return xml(dial(settings.userCellE164, env.TWILIO_NUMBER_E164));
    case "reject":
      return xml(reject());
    default:
      // "gate" (and any fallthrough) -> cheap press-1 challenge.
      return xml(gate({ prompt: settings.gatePrompt, actionUrl: `${baseUrlOf(request, env)}/gate` }));
  }
}

async function handleGate(request: Request, env: Env): Promise<Response> {
  const form = await request.formData();
  const digits = field(form, "Digits");
  const from = field(form, "From");
  const to = field(form, "To");
  const settings = settingsFromEnv(env);

  if (digits !== "1") {
    // No / wrong key within the timeout: almost certainly a robodialer.
    // TODO(M4): record gate_fail and escalate to the blocklist on repeats.
    return xml(say("No input received. Goodbye.", { hangup: true }));
  }

  const post = decidePostGate(settings);
  if (post.stage === "allow") {
    return xml(dial(settings.userCellE164, env.TWILIO_NUMBER_E164));
  }

  // Hand to Claude. A per-call session id isolates this call's Durable Object
  // instance; the caller/callee numbers are passed through so the DO can log,
  // blocklist, and transfer without another lookup.
  const base = baseUrlOf(request, env);
  const sessionId = crypto.randomUUID();
  const owner = env.OWNER_NAME ?? "the owner";
  const ownerSpoken = env.OWNER_NAME_SPOKEN ?? owner;
  return xml(
    connectRelay({
      wsUrl: `${wssOf(base)}/ws?s=${sessionId}`,
      welcomeGreeting: MARGO.greeting(ownerSpoken),
      ttsProvider: MARGO.ttsProvider,
      voice: MARGO.voice,
      parameters: [
        { name: "from", value: from },
        { name: "to", value: to },
      ],
    }),
  );
}

async function handleWs(request: Request, env: Env): Promise<Response> {
  if (request.headers.get("Upgrade") !== "websocket") {
    return new Response("expected websocket upgrade", { status: 426 });
  }
  const session = new URL(request.url).searchParams.get("s") ?? "active-call";
  const id = env.RELAY_SESSION.idFromName(session);
  return env.RELAY_SESSION.get(id).fetch(request);
}

function handleStatus(env: Env): Response {
  const settings = settingsFromEnv(env);
  const body = JSON.stringify({
    ok: true,
    postGateAction: settings.postGateAction,
    reputationLookupEnabled: settings.reputationLookupEnabled,
    dailyBudgetUsd: settings.dailyBudgetUsd,
  });
  return new Response(body, { headers: { "Content-Type": "application/json" } });
}
