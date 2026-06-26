/**
 * RelaySession — the Durable Object that holds one ConversationRelay WebSocket
 * (Twilio <-> us) and the conversation state for a single screened call.
 *
 * The conversational loop is wired: caller speech -> Claude (via the injected
 * client + turn engine) -> spoken reply. The *terminal effects* (transfer the
 * live call, persist the verdict, SMS the owner) are marked TODO(M2 live) —
 * they need a real call + credentials to verify, so they're not shipped blind.
 */
import type { Env } from "../config";
import { parseInbound, textToken } from "./protocol";
import type { LlmClient, LlmTurn } from "../screener/brain";
import { createAnthropicClient } from "../screener/anthropic-client";
import { runCallerTurn, type TerminalAction } from "../screener/conversation";
import { buildSystemPrompt, SCREENER_TOOLS } from "../screener/prompt";

const MODEL = "claude-haiku-4-5-20251001"; // Haiku 4.5: fast + cheap for real-time turns
const MAX_TURNS = 4; // hard cap so a stalling caller can't run up minutes

export class RelaySession {
  private readonly state: DurableObjectState;
  private readonly env: Env;
  private readonly client: LlmClient;
  private readonly system: string;
  private callSid = "";
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
      case "setup":
        this.callSid = typeof (msg as { callSid?: unknown }).callSid === "string" ? (msg as { callSid: string }).callSid : "";
        break;
      case "prompt": {
        const voicePrompt = typeof (msg as { voicePrompt?: unknown }).voicePrompt === "string" ? (msg as { voicePrompt: string }).voicePrompt : "";
        const outcome = await runCallerTurn(this.client, this.system, SCREENER_TOOLS, this.history, voicePrompt, MAX_TURNS);
        this.history = outcome.history;
        if (outcome.terminal !== undefined) {
          this.handleTerminal(ws, outcome.terminal);
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

  private handleTerminal(ws: WebSocket, terminal: TerminalAction): void {
    // TODO(M2 live): persist verdict+transcript (db.recordCall), SMS the owner
    // (notify/format + sms), and for "connect" redirect the live call to
    // <Dial> the user's cell via the Twilio REST API (using this.callSid).
    switch (terminal.kind) {
      case "connect":
        ws.send(JSON.stringify(textToken("Thanks — connecting you now.", true)));
        break;
      case "message":
        ws.send(JSON.stringify(textToken("I'll pass your message along. Goodbye.", true)));
        break;
      case "spam":
        ws.send(JSON.stringify(textToken("This number isn't taking calls. Goodbye.", true)));
        break;
    }
  }

  async webSocketClose(ws: WebSocket, _code: number, _reason: string, _wasClean: boolean): Promise<void> {
    // TODO(M3): persist the final transcript + verdict + cost for this.callSid.
    try {
      ws.close();
    } catch {
      // socket already closing — ignore
    }
  }
}
