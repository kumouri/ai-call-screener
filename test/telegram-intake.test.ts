import { describe, it, expect, vi, afterEach } from "vitest";
import {
  HEARTBEAT_FRAME,
  HEARTBEAT_MS,
  MAX_BUFFERED_UPDATES,
  TelegramIntake,
  bearerOk,
  constantTimeEqual,
  handleTelegramAck,
  handleTelegramIntakeStatus,
  handleTelegramStream,
  handleTelegramWebhook,
  parseAckIds,
  parseUpdate,
  updateKey,
  webhookSecretOk,
} from "../src/telegram/intake";
import type { Env } from "../src/config";

const SECRET = "whsec_test-123";
const TOKEN = "intake-token-xyz";

/** In-memory DurableObjectState with the storage calls TelegramIntake makes (list sorts by key, as
 * the real storage does). */
function fakeIntakeState() {
  const store = new Map<string, unknown>();
  const storage = {
    get: async (key: string | string[]) => {
      if (Array.isArray(key)) {
        const out = new Map<string, unknown>();
        for (const k of key) if (store.has(k)) out.set(k, store.get(k));
        return out;
      }
      return store.get(key);
    },
    put: async (key: string, value: unknown) => {
      store.set(key, value);
    },
    delete: async (keys: string | string[]) => {
      for (const k of Array.isArray(keys) ? keys : [keys]) store.delete(k);
    },
    list: async (opts: { prefix?: string; limit?: number }) => {
      const keys = [...store.keys()].filter((k) => k.startsWith(opts.prefix ?? "")).sort();
      const limited = opts.limit === undefined ? keys : keys.slice(0, opts.limit);
      return new Map(limited.map((k) => [k, store.get(k)]));
    },
  };
  return { state: { storage } as unknown as DurableObjectState, store };
}

/** An Env whose TELEGRAM_INTAKE namespace routes every stub.fetch to ONE real TelegramIntake. */
function fakeEnv(overrides: Partial<Env> = {}) {
  const { state, store } = fakeIntakeState();
  const intake = new TelegramIntake(state, {} as Env);
  const ns = {
    idFromName: (name: string) => name,
    get: () => ({ fetch: (url: string, init?: RequestInit) => intake.fetch(new Request(url, init)) }),
  };
  const env = {
    TELEGRAM_INTAKE: ns,
    TELEGRAM_WEBHOOK_SECRET: SECRET,
    TELEGRAM_INTAKE_TOKEN: TOKEN,
    ...overrides,
  } as unknown as Env;
  return { env, intake, store };
}

function webhook(update: unknown, secret: string | null = SECRET): Request {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (secret !== null) headers["X-Telegram-Bot-Api-Secret-Token"] = secret;
  return new Request("https://w/telegram-webhook", { method: "POST", headers, body: JSON.stringify(update) });
}

function daemon(path: string, method = "GET", body?: unknown, token: string | null = TOKEN): Request {
  const headers: Record<string, string> = {};
  if (token !== null) headers.Authorization = `Bearer ${token}`;
  const init: RequestInit = { method, headers };
  if (body !== undefined) init.body = JSON.stringify(body);
  return new Request(`https://w${path}`, init);
}

const msg = (id: number, text = `m${id}`) => ({ update_id: id, message: { message_id: id, text, chat: { id: 1 } } });

/** Read SSE text off a held stream until `until` appears in what has been read so far. */
async function readUntil(reader: ReadableStreamDefaultReader<Uint8Array>, until: string, maxChunks = 50): Promise<string> {
  const dec = new TextDecoder();
  let text = "";
  for (let i = 0; i < maxChunks && !text.includes(until); i++) {
    const { value, done } = await reader.read();
    if (done) break;
    text += dec.decode(value);
  }
  return text;
}

function updateIdsIn(sse: string): number[] {
  return [...sse.matchAll(/^event: update\nid: (\d+)\n/gm)].map((m) => Number(m[1]));
}

afterEach(() => {
  vi.useRealTimers();
});

describe("auth helpers", () => {
  it("compares in constant time and still answers correctly", () => {
    expect(constantTimeEqual("abc", "abc")).toBe(true);
    expect(constantTimeEqual("abc", "abd")).toBe(false);
    expect(constantTimeEqual("abc", "abcd")).toBe(false);
  });

  it("an unset or empty secret accepts nothing — not even an empty header", () => {
    expect(webhookSecretOk("", undefined)).toBe(false);
    expect(webhookSecretOk("", "")).toBe(false);
    expect(webhookSecretOk(null, SECRET)).toBe(false);
    expect(webhookSecretOk(SECRET, SECRET)).toBe(true);
    expect(bearerOk(`Bearer ${TOKEN}`, TOKEN)).toBe(true);
    expect(bearerOk(TOKEN, TOKEN)).toBe(false);
    expect(bearerOk("Bearer ", "")).toBe(false);
  });

  it("parses updates and acks strictly", () => {
    expect(parseUpdate({ update_id: 5 })).toEqual({ update_id: 5 });
    expect(parseUpdate({ update_id: "5" })).toBeNull();
    expect(parseUpdate({ update_id: -1 })).toBeNull();
    expect(parseUpdate([1])).toBeNull();
    expect(parseAckIds({ update_ids: [1, 2] })).toEqual([1, 2]);
    expect(parseAckIds({})).toEqual([]);
    expect(parseAckIds({ update_ids: ["1"] })).toBeNull();
  });

  it("keys sort in update_id order", () => {
    expect([updateKey(10), updateKey(9), updateKey(100)].sort()).toEqual([updateKey(9), updateKey(10), updateKey(100)]);
  });
});

describe("POST /telegram-webhook", () => {
  it("good secret: stores the update and answers 200", async () => {
    const { env, store } = fakeEnv();
    const res = await handleTelegramWebhook(webhook(msg(7)), env);
    expect(res.status).toBe(200);
    expect(store.has(updateKey(7))).toBe(true);
  });

  it("bad secret: 401 and nothing stored", async () => {
    const { env, store } = fakeEnv();
    const res = await handleTelegramWebhook(webhook(msg(7), "wrong"), env);
    expect(res.status).toBe(401);
    expect(store.size).toBe(0);
  });

  it("missing header: 401", async () => {
    const { env, store } = fakeEnv();
    expect((await handleTelegramWebhook(webhook(msg(7), null), env)).status).toBe(401);
    expect(store.size).toBe(0);
  });

  it("no secret configured: refuses even a request that sends one", async () => {
    const { env, store } = fakeEnv({ TELEGRAM_WEBHOOK_SECRET: undefined });
    expect((await handleTelegramWebhook(webhook(msg(7)), env)).status).toBe(401);
    expect(store.size).toBe(0);
  });

  it("an update with no update_id is a 400, never stored", async () => {
    const { env, store } = fakeEnv();
    expect((await handleTelegramWebhook(webhook({ message: {} }), env)).status).toBe(400);
    expect(store.size).toBe(0);
  });

  it("a store failure is never a 200 — Telegram must retry", async () => {
    const env = {
      TELEGRAM_WEBHOOK_SECRET: SECRET,
      TELEGRAM_INTAKE: { idFromName: (n: string) => n, get: () => ({ fetch: async () => { throw new Error("DO down"); } }) },
    } as unknown as Env;
    expect((await handleTelegramWebhook(webhook(msg(7)), env)).status).toBe(503);
  });

  it("a Telegram retry of the same update is stored once", async () => {
    const { env, store } = fakeEnv();
    await handleTelegramWebhook(webhook(msg(7)), env);
    await handleTelegramWebhook(webhook(msg(7)), env);
    expect([...store.keys()].filter((k) => k.startsWith("u:"))).toHaveLength(1);
  });

  it("a full buffer answers 503 so the update stays on Telegram's side", async () => {
    const { env, store } = fakeEnv();
    for (let i = 0; i < MAX_BUFFERED_UPDATES; i++) store.set(updateKey(i), { update_id: i, received_at_ms: 0, update: {} });
    const res = await handleTelegramWebhook(webhook(msg(MAX_BUFFERED_UPDATES + 5)), env);
    expect(res.status).toBe(503);
    expect(store.has(updateKey(MAX_BUFFERED_UPDATES + 5))).toBe(false);
  });
});

describe("daemon routes need the bearer token", () => {
  it("stream, ack and status all 401 without it", async () => {
    const { env } = fakeEnv();
    expect((await handleTelegramStream(daemon("/telegram-stream", "GET", undefined, null), env)).status).toBe(401);
    expect((await handleTelegramStream(daemon("/telegram-stream", "GET", undefined, "nope"), env)).status).toBe(401);
    expect((await handleTelegramAck(daemon("/telegram-ack", "POST", { update_ids: [] }, null), env)).status).toBe(401);
    expect((await handleTelegramIntakeStatus(daemon("/telegram-intake-status", "GET", undefined, null), env)).status).toBe(401);
  });
});

describe("TelegramIntake buffers and replays until acked", () => {
  it("replays every unacked update, oldest first, after a disconnect", async () => {
    const { env } = fakeEnv();
    // Arrive out of order: the replay is by update_id, not arrival.
    for (const id of [102, 100, 101]) await handleTelegramWebhook(webhook(msg(id)), env);

    const first = await handleTelegramStream(daemon("/telegram-stream"), env);
    expect(first.headers.get("Content-Type")).toBe("text/event-stream");
    const r1 = first.body!.getReader();
    const snap1 = await readUntil(r1, ": connected");
    expect(updateIdsIn(snap1)).toEqual([100, 101, 102]);

    // A live update reaches the held stream the moment it is stored.
    await handleTelegramWebhook(webhook(msg(103)), env);
    expect(updateIdsIn(await readUntil(r1, "id: 103"))).toEqual([103]);

    // The daemon processed 100 and 102 only, then went away (restart).
    await handleTelegramAck(daemon("/telegram-ack", "POST", { update_ids: [100, 102] }), env);
    await r1.cancel();
    // Something arrives while it is gone.
    await handleTelegramWebhook(webhook(msg(104)), env);

    const second = await handleTelegramStream(daemon("/telegram-stream"), env);
    const snap2 = await readUntil(second.body!.getReader(), ": connected");
    expect(updateIdsIn(snap2)).toEqual([101, 103, 104]);
  });

  it("the snapshot carries the Update verbatim", async () => {
    const { env } = fakeEnv();
    await handleTelegramWebhook(webhook(msg(5, "hello there")), env);
    const res = await handleTelegramStream(daemon("/telegram-stream"), env);
    const text = await readUntil(res.body!.getReader(), ": connected");
    const data = text.split("\n").find((l) => l.startsWith("data: "))!;
    expect(JSON.parse(data.slice(6))).toEqual(msg(5, "hello there"));
  });

  it("nothing leaves storage without an ack", async () => {
    const { env, store } = fakeEnv();
    await handleTelegramWebhook(webhook(msg(1)), env);
    const res = await handleTelegramStream(daemon("/telegram-stream"), env);
    await readUntil(res.body!.getReader(), ": connected");
    expect(store.has(updateKey(1))).toBe(true); // delivered, NOT acked → still held
    await handleTelegramAck(daemon("/telegram-ack", "POST", { update_ids: [1] }), env);
    expect(store.has(updateKey(1))).toBe(false);
  });

  it("an empty ack is a heartbeat: it deletes nothing and shows in status", async () => {
    const { env, store } = fakeEnv();
    await handleTelegramWebhook(webhook(msg(1)), env);
    const ack = await handleTelegramAck(daemon("/telegram-ack", "POST", { update_ids: [] }), env);
    expect(await ack.json()).toMatchObject({ ok: true, acked: 0, pending: 1 });
    expect(store.has(updateKey(1))).toBe(true);
    const status = (await (await handleTelegramIntakeStatus(daemon("/telegram-intake-status"), env)).json()) as Record<string, unknown>;
    expect(status.pending).toBe(1);
    expect(status.oldest_update_id).toBe(1);
    expect(status.last_daemon_beat_at).not.toBeNull();
  });

  it("a newer connection replaces an older one", async () => {
    const { env } = fakeEnv();
    const a = await handleTelegramStream(daemon("/telegram-stream"), env);
    const ra = a.body!.getReader();
    await readUntil(ra, ": connected");
    const b = await handleTelegramStream(daemon("/telegram-stream"), env);
    await readUntil(b.body!.getReader(), ": connected");
    expect((await ra.read()).done).toBe(true); // the old stream was closed
    const status = (await (await handleTelegramIntakeStatus(daemon("/telegram-intake-status"), env)).json()) as { streams: unknown[] };
    expect(status.streams).toHaveLength(1);
  });

  it("writes a heartbeat to the held stream every HEARTBEAT_MS", async () => {
    vi.useFakeTimers();
    const { env } = fakeEnv();
    const res = await handleTelegramStream(daemon("/telegram-stream"), env);
    const reader = res.body!.getReader();
    await readUntil(reader, ": connected");
    vi.advanceTimersByTime(HEARTBEAT_MS + 10);
    expect(await readUntil(reader, HEARTBEAT_FRAME)).toContain(HEARTBEAT_FRAME);
    await reader.cancel();
    vi.advanceTimersByTime(HEARTBEAT_MS + 10); // the failed write retires the stream and its timer
  });
});
