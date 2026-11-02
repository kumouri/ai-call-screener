import { describe, it, expect, vi, afterEach } from "vitest";
import { ALERT_KEY_ACK_REPLY, RelaySession, resolveSessionMode } from "../src/relay/session";
import type { Env } from "../src/config";

function fakeEnv(): Env {
  return {
    ANTHROPIC_API_KEY: "key",
    OWNER_NAME: "Ceryce",
    OWNER_NAME_SPOKEN: "Cerise",
    USER_CELL_E164: "+15551112222",
  } as unknown as Env;
}

/**
 * Minimal fake `DurableObjectState`. `store` is a plain Map the test can share across two
 * `RelaySession` instances to simulate the SAME Durable Object surviving an eviction (a fresh
 * instance, same underlying `state.storage`). `blockConcurrencyWhile` captures the promise so the
 * test can await it — standing in for the real runtime's "no fetch() until this resolves".
 */
function fakeState(store: Map<string, unknown> = new Map()) {
  let ready: Promise<unknown> = Promise.resolve();
  const state = {
    storage: {
      get: async (key: string) => store.get(key),
      put: async (arg: string | Record<string, unknown>, value?: unknown) => {
        if (typeof arg === "string") {
          store.set(arg, value);
        } else {
          for (const [k, v] of Object.entries(arg)) store.set(k, v);
        }
      },
    },
    blockConcurrencyWhile: (fn: () => Promise<unknown>) => {
      ready = fn();
      return ready;
    },
  };
  return { state: state as unknown as DurableObjectState, store, ready: () => ready };
}

/** Reach the private mode/context fields — TS `private` is compile-time only. */
function modeOf(session: RelaySession): { mode: string; ownerContext: string } {
  return session as unknown as { mode: string; ownerContext: string };
}

describe("resolveSessionMode", () => {
  it("is owner only when both the persisted seed and the TwiML param agree", () => {
    expect(resolveSessionMode("owner", "owner")).toBe("owner");
  });

  it("falls back to screener when the seed says owner but the param disagrees (or is missing)", () => {
    expect(resolveSessionMode("owner", undefined)).toBe("screener");
    expect(resolveSessionMode("owner", "screener")).toBe("screener");
  });

  it("falls back to screener when the param says owner but there is no real seed", () => {
    expect(resolveSessionMode("screener", "owner")).toBe("screener");
  });

  it("stays screener when neither signal claims owner", () => {
    expect(resolveSessionMode("screener", undefined)).toBe("screener");
  });
});

describe("RelaySession — seed persistence across an eviction", () => {
  it("a fresh instance defaults to screener mode when nothing was ever seeded", () => {
    const { state } = fakeState();
    const session = new RelaySession(state, fakeEnv());
    expect(modeOf(session).mode).toBe("screener");
  });

  it("POST /seed persists mode+context to storage, not just instance fields", async () => {
    const { state, store } = fakeState();
    const session = new RelaySession(state, fakeEnv());
    const res = await session.fetch(
      new Request("https://relay/seed", {
        method: "POST",
        body: JSON.stringify({ mode: "owner", context: "3pm dentist" }),
      }),
    );
    expect(res.status).toBe(200);
    expect(store.get("mode")).toBe("owner");
    expect(store.get("ownerContext")).toBe("3pm dentist");
    expect(modeOf(session).mode).toBe("owner");
  });

  it("a NEW instance over the SAME storage (simulated eviction) still loads owner mode + context", async () => {
    const store = new Map<string, unknown>();
    const first = fakeState(store);
    const seeder = new RelaySession(first.state, fakeEnv());
    await seeder.fetch(
      new Request("https://relay/seed", {
        method: "POST",
        body: JSON.stringify({ mode: "owner", context: "today's calendar: 3pm dentist" }),
      }),
    );

    // A fresh RelaySession instance, as the runtime would construct after evicting the first —
    // same underlying storage, no in-memory state carried over.
    const second = fakeState(store);
    const revived = new RelaySession(second.state, fakeEnv());
    await second.ready();

    expect(modeOf(revived).mode).toBe("owner");
    expect(modeOf(revived).ownerContext).toBe("today's calendar: 3pm dentist");
  });

  it("an inbound (screener) call never seeds owner mode — /seed only ever POSTs mode: owner from handlePushCallTalk", async () => {
    const { state, store } = fakeState();
    const session = new RelaySession(state, fakeEnv());
    await session.fetch(
      new Request("https://relay/seed", {
        method: "POST",
        body: JSON.stringify({ mode: "screener" }),
      }),
    );
    expect(store.has("mode")).toBe(false);
    expect(modeOf(session).mode).toBe("screener");
  });
});

/**
 * Alert calls (`src/relay/alert.ts`): the RelaySession tells its CallEscalation `connected`/`ack`
 * so the re-calls stop when — and only when — she acknowledged. Drives the private `onMessage`
 * with ConversationRelay frames; the LLM, Twilio and D1 are fakes.
 */
describe("RelaySession — alert calls", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function alertEnv() {
    const told: string[] = [];
    const env = {
      ...fakeEnv(),
      TWILIO_ACCOUNT_SID: "AC1",
      TWILIO_AUTH_TOKEN: "tok",
      DB: { prepare: () => ({ bind: () => ({ run: async () => ({}) }) }) },
      CALL_ESCALATION: {
        idFromName: (name: string) => name,
        get: (name: string) => ({
          fetch: async (url: string) => {
            told.push(`${name}:${new URL(url).pathname}`);
            return new Response("ok");
          },
        }),
      },
    } as unknown as Env;
    return { env, told };
  }

  async function seededAlert(alert = "The bunnies are 5 layers from the swap.") {
    const { env, told } = alertEnv();
    const { state } = fakeState();
    const session = new RelaySession(state, env);
    await session.fetch(
      new Request("https://relay/seed", {
        method: "POST",
        body: JSON.stringify({ mode: "owner", context: "", alert, escalationId: "esc1" }),
      }),
    );
    const llm: string[] = [];
    (session as unknown as { client: { respond: () => Promise<unknown> } }).client = {
      respond: async () => {
        llm.push("turn");
        return { text: "Okay — want me to time it for you?" };
      },
    };
    const sent: string[] = [];
    const ws = { send: (s: string) => sent.push(s) } as unknown as WebSocket;
    const on = (frame: Record<string, unknown>) =>
      (session as unknown as { onMessage: (w: WebSocket, d: string) => Promise<void> }).onMessage(ws, JSON.stringify(frame));
    const twilio = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}", { status: 200 }));
    await on({ type: "setup", callSid: "CA1", customParameters: { mode: "owner" } });
    return { session, told, sent, on, llm, twilio };
  }

  it("persists the alert and escalation id with the seed", async () => {
    const { state, store } = fakeState();
    const session = new RelaySession(state, alertEnv().env);
    await session.fetch(
      new Request("https://relay/seed", {
        method: "POST",
        body: JSON.stringify({ mode: "owner", alert: "swap soon", escalationId: "esc9" }),
      }),
    );
    expect(store.get("alert")).toBe("swap soon");
    expect(store.get("escalationId")).toBe("esc9");
  });

  it("tells the escalation the call connected", async () => {
    const { told } = await seededAlert();
    expect(told).toEqual(["esc1:/connected"]);
  });

  it("her first words acknowledge the alert, once, and the conversation carries on", async () => {
    const { told, on, sent, llm } = await seededAlert();
    await on({ type: "prompt", voicePrompt: "Okay, heading down." });
    await on({ type: "prompt", voicePrompt: "How long do I have?" });
    expect(told).toEqual(["esc1:/connected", "esc1:/ack"]);
    expect(llm).toHaveLength(2);
    expect(sent).toHaveLength(2);
  });

  it("a key press acknowledges it and keeps the line open", async () => {
    const { told, on, sent } = await seededAlert();
    await on({ type: "dtmf", digit: "1" });
    expect(told).toEqual(["esc1:/connected", "esc1:/ack"]);
    expect(sent).toEqual([JSON.stringify({ type: "text", token: ALERT_KEY_ACK_REPLY, last: true })]);
  });

  it("a voicemail greeting is never an ack: it hangs up so the escalation calls again", async () => {
    const { told, on, llm, twilio } = await seededAlert();
    await on({ type: "prompt", voicePrompt: "You've reached Ceryce. Please leave a message after the tone." });
    expect(told).toEqual(["esc1:/connected"]);
    expect(llm).toHaveLength(0);
    expect(twilio).toHaveBeenCalled(); // the hangup redirect
    expect(String(twilio.mock.calls[0]![0])).toContain("/Calls/CA1.json");
  });

  it("a screener fallback (no mode param) is never treated as an alert", async () => {
    const { env, told } = alertEnv();
    const { state } = fakeState();
    const session = new RelaySession(state, env);
    await session.fetch(
      new Request("https://relay/seed", {
        method: "POST",
        body: JSON.stringify({ mode: "owner", alert: "swap soon", escalationId: "esc1" }),
      }),
    );
    const ws = { send: () => undefined } as unknown as WebSocket;
    await (session as unknown as { onMessage: (w: WebSocket, d: string) => Promise<void> }).onMessage(
      ws,
      JSON.stringify({ type: "setup", callSid: "CA1", customParameters: {} }),
    );
    await (session as unknown as { onMessage: (w: WebSocket, d: string) => Promise<void> }).onMessage(
      ws,
      JSON.stringify({ type: "dtmf", digit: "1" }),
    );
    expect(told).toEqual([]);
  });

  it("an ordinary talk call (no alert) never tells any escalation anything", async () => {
    const { env, told } = alertEnv();
    const { state } = fakeState();
    const session = new RelaySession(state, env);
    await session.fetch(new Request("https://relay/seed", { method: "POST", body: JSON.stringify({ mode: "owner" }) }));
    const ws = { send: () => undefined } as unknown as WebSocket;
    await (session as unknown as { onMessage: (w: WebSocket, d: string) => Promise<void> }).onMessage(
      ws,
      JSON.stringify({ type: "setup", callSid: "CA1", customParameters: { mode: "owner" } }),
    );
    await (session as unknown as { onMessage: (w: WebSocket, d: string) => Promise<void> }).onMessage(
      ws,
      JSON.stringify({ type: "dtmf", digit: "1" }),
    );
    expect(told).toEqual([]);
  });
});
