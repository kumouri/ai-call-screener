/**
 * Worker entry / HTTP router. This is the tiered funnel in action:
 *   POST /voice — Twilio's inbound-call webhook. Runs the funnel and returns
 *                 TwiML: Dial (allow) / Reject (block) / Gather (press-1 gate).
 *   POST /gate  — the digit the caller pressed. "1" => ring through or hand to
 *                 ConversationRelay; anything else => hang up (robodialer).
 *   GET  /ws    — ConversationRelay WebSocket, handed to the RelaySession DO.
 *   GET  /status— liveness + effective settings (expanded into a dashboard in M5).
 */
import type { Env } from "./config";
import { settingsFromEnv } from "./config";
import type { CallerInfo } from "./screener/decision";
import { decideFunnel, decidePostGate } from "./screener/funnel";
import { lookupLists } from "./data/db";
import { connectRelay, dial, gate, reject, say } from "./twiml";

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

function wssBase(env: Env): string {
  return env.PUBLIC_BASE_URL.replace(/^http:/i, "ws:").replace(/^https:/i, "wss:");
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
      return xml(gate({ prompt: settings.gatePrompt, actionUrl: `${env.PUBLIC_BASE_URL}/gate` }));
  }
}

async function handleGate(request: Request, env: Env): Promise<Response> {
  const form = await request.formData();
  const digits = field(form, "Digits");
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
  return xml(
    connectRelay({
      wsUrl: `${wssBase(env)}/ws`,
      welcomeGreeting: "Hi, you've reached a call screener. May I ask who's calling and what it's about?",
    }),
  );
}

async function handleWs(request: Request, env: Env): Promise<Response> {
  if (request.headers.get("Upgrade") !== "websocket") {
    return new Response("expected websocket upgrade", { status: 426 });
  }
  // One session per call is fine for personal use; a unique name per CallSid
  // can be introduced later if concurrency grows.
  const id = env.RELAY_SESSION.idFromName("active-call");
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
