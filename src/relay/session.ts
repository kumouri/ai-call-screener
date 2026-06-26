/**
 * RelaySession — the Durable Object that holds one ConversationRelay WebSocket
 * (Twilio <-> us) and the conversation state for a single screened call.
 *
 * M1 ships the WebSocket plumbing (upgrade + frame parsing). M2 fills the
 * `prompt` handler: feed transcribed speech into `brain.screenTurn`, stream the
 * reply tokens back, and act on connect/message/spam decisions.
 */
import type { Env } from "../config";
import { parseInbound, textToken } from "./protocol";

export class RelaySession {
  private readonly state: DurableObjectState;
  private readonly env: Env;
  private callSid = "";

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket upgrade", { status: 426 });
    }
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    // Hibernatable accept: the DO can sleep between turns without dropping the call.
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
      case "prompt":
        // TODO(M2): run brain.screenTurn with the Anthropic client, stream
        // tokens, and act on the resulting connect/message/spam decision.
        ws.send(JSON.stringify(textToken("One moment, please.", true)));
        break;
      case "interrupt":
        // TODO(M2): cancel any in-flight generation for barge-in.
        break;
      default:
        break;
    }
  }

  async webSocketClose(ws: WebSocket, _code: number, _reason: string, _wasClean: boolean): Promise<void> {
    // TODO(M3): persist the final transcript + verdict + cost for callSid.
    void this.callSid;
    void this.env;
    try {
      ws.close();
    } catch {
      // socket already closing — ignore
    }
  }
}
