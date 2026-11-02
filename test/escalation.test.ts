import { describe, it, expect, vi, afterEach } from "vitest";
import { CallEscalation, isInCall, nextEscalationStep, DEFAULT_MAX_ATTEMPTS, IN_CALL_MAX_MS } from "../src/escalation/escalation";
import { escalationGather } from "../src/twiml";
import type { Env } from "../src/config";

describe("nextEscalationStep", () => {
  it("keeps calling while under the cap and not acked", () => {
    expect(nextEscalationStep({ attempt: 1, maxAttempts: 15, acked: false })).toBe("call");
    expect(nextEscalationStep({ attempt: 14, maxAttempts: 15, acked: false })).toBe("call");
  });

  it("stops as soon as she's acked, even mid-sequence", () => {
    expect(nextEscalationStep({ attempt: 3, maxAttempts: 15, acked: true })).toBe("stop");
  });

  it("stops once the attempt cap is reached", () => {
    expect(nextEscalationStep({ attempt: 15, maxAttempts: 15, acked: false })).toBe("stop");
    expect(nextEscalationStep({ attempt: 16, maxAttempts: 15, acked: false })).toBe("stop");
  });

  it("defaults to a 15-attempt cap", () => {
    expect(DEFAULT_MAX_ATTEMPTS).toBe(15);
  });
});

describe("escalationGather", () => {
  it("wraps the prompt in a <Gather> wired to the ack URL, with a Hangup fallback", () => {
    const x = escalationGather({ prompt: "Wake up — press 1", actionUrl: "https://host/push-call/ack?id=x" });
    expect(x).toContain("<Gather");
    expect(x).toContain('action="https://host/push-call/ack?id=x"');
    expect(x).toContain("<Say>Wake up");
    // Hangup must follow the Gather so a no-input call ends (the DO's alarm calls back).
    expect(x.indexOf("<Gather")).toBeLessThan(x.indexOf("<Hangup"));
  });

  it("defaults to one digit and a 30s wait, and escapes the action URL", () => {
    const x = escalationGather({ prompt: "hi", actionUrl: "https://host/ack?id=a&b=1" });
    expect(x).toContain('numDigits="1"');
    expect(x).toContain('timeout="30"');
    expect(x).toContain("&amp;b=1"); // ampersand escaped for XML
  });
});

describe("nextEscalationStep — alert calls", () => {
  it("holds the next re-call while an alert call is live", () => {
    expect(nextEscalationStep({ attempt: 2, maxAttempts: 15, acked: false, inCall: true })).toBe("wait");
  });

  it("an ack or the cap still wins over a live call", () => {
    expect(nextEscalationStep({ attempt: 2, maxAttempts: 15, acked: true, inCall: true })).toBe("stop");
    expect(nextEscalationStep({ attempt: 15, maxAttempts: 15, acked: false, inCall: true })).toBe("stop");
  });
});

describe("isInCall", () => {
  const t0 = 1_000_000;
  it("is false before any call connected", () => {
    expect(isInCall(undefined, undefined, t0)).toBe(false);
  });
  it("is true between connected and ended", () => {
    expect(isInCall(t0, undefined, t0 + 60_000)).toBe(true);
  });
  it("is false once ended", () => {
    expect(isInCall(t0, t0 + 30_000, t0 + 60_000)).toBe(false);
  });
  it("gives up after IN_CALL_MAX_MS in case the ended signal was lost", () => {
    expect(isInCall(t0, undefined, t0 + IN_CALL_MAX_MS)).toBe(false);
  });
});

/** In-memory DurableObjectState with the storage calls CallEscalation makes. */
function fakeEscalationState() {
  const store = new Map<string, unknown>();
  let alarmAt: number | null = null;
  const state = {
    storage: {
      get: async (key: string) => store.get(key),
      put: async (arg: string | Record<string, unknown>, value?: unknown) => {
        if (typeof arg === "string") store.set(arg, value);
        else for (const [k, v] of Object.entries(arg)) store.set(k, v);
      },
      delete: async (keys: string | string[]) => {
        for (const k of Array.isArray(keys) ? keys : [keys]) store.delete(k);
      },
      setAlarm: async (at: number) => {
        alarmAt = at;
      },
      deleteAlarm: async () => {
        alarmAt = null;
      },
    },
  };
  return { state: state as unknown as DurableObjectState, store, alarm: () => alarmAt };
}

function fakeEscalationEnv(spentUsd = 0) {
  const seeds: Array<{ name: string; body: Record<string, unknown> }> = [];
  const env = {
    TWILIO_ACCOUNT_SID: "AC1",
    TWILIO_AUTH_TOKEN: "tok",
    TWILIO_NUMBER_E164: "+15550000000",
    USER_CELL_E164: "+15551112222",
    PUBLIC_BASE_URL: "https://host",
    DAILY_BUDGET_USD: "2.00",
    DB: {
      prepare: () => ({ bind: () => ({ first: async () => ({ total: spentUsd }) }) }),
    },
    RELAY_SESSION: {
      idFromName: (name: string) => name,
      get: (name: string) => ({
        fetch: async (_url: string, init: RequestInit) => {
          seeds.push({ name, body: JSON.parse(String(init.body)) as Record<string, unknown> });
          return new Response("ok");
        },
      }),
    },
  } as unknown as Env;
  return { env, seeds };
}

/** Mock Twilio's call-create endpoint; returns the TwiML of every call placed. */
function mockTwilio(failTalk = false): string[] {
  const placed: string[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (_url, init) => {
    const twiml = (init!.body as URLSearchParams).get("Twiml") ?? "";
    if (failTalk && twiml.includes("<ConversationRelay")) return new Response("nope", { status: 500 });
    placed.push(twiml);
    return new Response(JSON.stringify({ sid: `CA${placed.length}` }), { status: 201 });
  });
  return placed;
}

async function startEscalation(esc: CallEscalation, body: Record<string, unknown>): Promise<void> {
  await esc.fetch(new Request("https://escalation/start", { method: "POST", body: JSON.stringify(body) }));
}

describe("CallEscalation — alert (talk) attempts and the key-press floor", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  const base = { id: "esc1", text: "The bunnies are 5 layers from the swap.", to: "+15551112222", base: "https://host" };

  it("talk: seeds an owner-mode RelaySession with the alert + escalation id, then places an alert call", async () => {
    const placed = mockTwilio();
    const { env, seeds } = fakeEscalationEnv();
    const { state } = fakeEscalationState();
    await startEscalation(new CallEscalation(state, env), { ...base, talk: true, context: "printing bunnies" });
    expect(seeds).toEqual([
      { name: "esc1-1", body: { mode: "owner", context: "printing bunnies", alert: base.text, escalationId: "esc1" } },
    ]);
    expect(placed).toHaveLength(1);
    expect(placed[0]).toContain("<ConversationRelay");
    expect(placed[0]).toContain("The bunnies are 5 layers from the swap.");
  });

  it("without talk it is exactly the old key-press call", async () => {
    const placed = mockTwilio();
    const { env, seeds } = fakeEscalationEnv();
    const { state } = fakeEscalationState();
    await startEscalation(new CallEscalation(state, env), base);
    expect(seeds).toEqual([]);
    expect(placed[0]).toContain("<Gather");
    expect(placed[0]).toContain("press 1");
  });

  it("the floor: a failed talk call falls back to the key-press call in the same attempt", async () => {
    const placed = mockTwilio(true);
    const { env } = fakeEscalationEnv();
    const { state, store, alarm } = fakeEscalationState();
    await startEscalation(new CallEscalation(state, env), { ...base, talk: true });
    expect(placed).toHaveLength(1);
    expect(placed[0]).toContain("<Gather");
    expect(store.get("attempt")).toBe(1);
    expect(alarm()).not.toBeNull(); // and it will call again
  });

  it("the floor: over the daily budget, the attempt is a key-press call (no Claude spend)", async () => {
    const placed = mockTwilio();
    const { env, seeds } = fakeEscalationEnv(5);
    const { state } = fakeEscalationState();
    await startEscalation(new CallEscalation(state, env), { ...base, talk: true });
    expect(seeds).toEqual([]);
    expect(placed[0]).toContain("<Gather");
  });

  it("never talks to a to-override, even if asked", async () => {
    const placed = mockTwilio();
    const { env, seeds } = fakeEscalationEnv();
    const { state, store } = fakeEscalationState();
    await startEscalation(new CallEscalation(state, env), { ...base, to: "+15559998888", talk: true });
    expect(store.get("talk")).toBe(false);
    expect(seeds).toEqual([]);
    expect(placed[0]).toContain("<Gather");
  });

  it("holds the re-call while the alert call is live, then calls again once it ended unacknowledged", async () => {
    const placed = mockTwilio();
    const { env } = fakeEscalationEnv();
    const { state } = fakeEscalationState();
    const esc = new CallEscalation(state, env);
    await startEscalation(esc, { ...base, talk: true });
    await esc.fetch(new Request("https://escalation/connected", { method: "POST" }));
    await esc.alarm();
    expect(placed).toHaveLength(1); // held — she (or her voicemail) is on the line
    await esc.fetch(new Request("https://escalation/ended", { method: "POST" }));
    await esc.alarm();
    expect(placed).toHaveLength(2);
  });

  it("an ack from the alert call stops the loop", async () => {
    const placed = mockTwilio();
    const { env } = fakeEscalationEnv();
    const { state, alarm } = fakeEscalationState();
    const esc = new CallEscalation(state, env);
    await startEscalation(esc, { ...base, talk: true });
    await esc.fetch(new Request("https://escalation/ack", { method: "POST" }));
    expect(alarm()).toBeNull();
    await esc.alarm();
    expect(placed).toHaveLength(1);
  });

  it("maxAttempts 1 is one waiting call, never a re-call", async () => {
    const placed = mockTwilio();
    const { env } = fakeEscalationEnv();
    const { state } = fakeEscalationState();
    const esc = new CallEscalation(state, env);
    await startEscalation(esc, { ...base, talk: true, maxAttempts: 1 });
    await esc.alarm();
    expect(placed).toHaveLength(1);
  });
});
