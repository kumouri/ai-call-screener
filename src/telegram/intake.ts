/**
 * Margo's Telegram intake by PUSH — the webhook end and the held stream to the daemon.
 *
 * Ceryce, Telegram, 2026-10-28 14:21 CT, emphatically: nothing should be polling at all, as already
 * agreed; it should all be push. She ruled push-not-polling
 * on 2026-10-06 (`margo/docs/rulings.md` `2026-10-06-presence-signal-is-push`); the daemon's own
 * Telegram intake still long-polled `getUpdates`, and that day it stalled for minutes at a time while
 * her picker taps sat unanswered. Design: `margo/docs/telegram-push-intake-spec.md`.
 *
 * The path, end to end:
 *   1. Telegram → `POST /telegram-webhook` (this Worker is the one place already public). The
 *      `X-Telegram-Bot-Api-Secret-Token` header must equal the `TELEGRAM_WEBHOOK_SECRET` secret, or the
 *      request is refused. The update is handed to the `TelegramIntake` Durable Object, which STORES it
 *      before the Worker answers 200 — so a 200 means "durable here", and anything short of that is a
 *      non-2xx that makes Telegram retry.
 *   2. The daemon holds ONE outbound connection open: `GET /telegram-stream`, Server-Sent Events,
 *      bearer `TELEGRAM_INTAKE_TOKEN`. On connect the DO writes every stored (unacked) update, oldest
 *      first, then each new update the instant it is stored. No inbound port on her PC, no Tunnel.
 *   3. The daemon `POST /telegram-ack {update_ids}` once an update is PROCESSED (durably queued on her
 *      PC). Only then does the DO delete it. An empty ack is the daemon's heartbeat. A daemon that
 *      restarts mid-way reconnects and gets everything it never acked — nothing is lost in between.
 *
 * Heartbeats both ways: the DO writes an SSE comment every `HEARTBEAT_MS` (so the daemon can tell a
 * silently dead link and the edge never idles the connection out); the daemon's empty ack is its own
 * beat, reported by `GET /telegram-intake-status`.
 *
 * Delivery is AT-LEAST-ONCE by design (a Telegram retry, or a snapshot racing a live write, can deliver
 * one update twice). The daemon dedupes on `update_id` — the same key Telegram itself guarantees unique.
 *
 * Nothing here calls `setWebhook`. Pointing Telegram at this route is a host-side cutover step
 * (`margo/scripts/telegram_push_cutover.py`), never a deploy side effect.
 */
import type { Env } from "../config";

/** Storage key prefix for one buffered update; keys sort in update_id order (zero-padded). */
export const UPDATE_KEY_PREFIX = "u:";
/** Beyond this many unacked updates the webhook answers 503, so Telegram keeps them on ITS side. */
export const MAX_BUFFERED_UPDATES = 10_000;
/** DO → daemon heartbeat. The daemon treats ~3x this with no bytes as a dead link (keep in step with
 * `telegram_push.STREAM_READ_TIMEOUT_SEC`). Well under the edge's ~100 s idle cutoff. */
export const HEARTBEAT_MS = 25_000;
/** The single DO instance every route talks to. */
export const INTAKE_OBJECT_NAME = "telegram-intake";

const encoder = new TextEncoder();

/** Zero-padded so lexical key order == numeric update_id order (storage.list sorts by key). */
export function updateKey(updateId: number): string {
  return UPDATE_KEY_PREFIX + String(updateId).padStart(15, "0");
}

/** Length-independent comparison: a mismatch never returns faster for a longer shared prefix. */
export function constantTimeEqual(a: string, b: string): boolean {
  const ab = encoder.encode(a);
  const bb = encoder.encode(b);
  let diff = ab.length ^ bb.length;
  const n = Math.max(ab.length, bb.length);
  for (let i = 0; i < n; i++) diff |= (ab[i] ?? 0) ^ (bb[i] ?? 0);
  return diff === 0;
}

/** Telegram's webhook header against the configured secret. An unset/empty secret accepts NOTHING. */
export function webhookSecretOk(header: string | null, secret: string | undefined): boolean {
  if (secret === undefined || secret === "" || header === null) return false;
  return constantTimeEqual(header, secret);
}

/** `Authorization: Bearer <token>` against the configured token. Unset/empty accepts nothing. */
export function bearerOk(authorization: string | null, token: string | undefined): boolean {
  if (token === undefined || token === "" || authorization === null) return false;
  return constantTimeEqual(authorization, `Bearer ${token}`);
}

export interface StoredUpdate {
  update_id: number;
  /** Epoch ms the DO stored it — lets status say how long the oldest has waited. */
  received_at_ms: number;
  /** Telegram's Update object, verbatim. */
  update: Record<string, unknown>;
}

/** The Update as Telegram sent it, or null when it has no usable non-negative integer `update_id`. */
export function parseUpdate(body: unknown): Record<string, unknown> | null {
  if (typeof body !== "object" || body === null || Array.isArray(body)) return null;
  const id = (body as { update_id?: unknown }).update_id;
  if (typeof id !== "number" || !Number.isSafeInteger(id) || id < 0) return null;
  return body as Record<string, unknown>;
}

/** The ids an ack names; `[]` is a heartbeat. null for a malformed body. */
export function parseAckIds(body: unknown): number[] | null {
  if (typeof body !== "object" || body === null) return null;
  const ids = (body as { update_ids?: unknown }).update_ids;
  if (ids === undefined) return [];
  if (!Array.isArray(ids) || ids.length > 1000) return null;
  const out: number[] = [];
  for (const v of ids) {
    if (typeof v !== "number" || !Number.isSafeInteger(v) || v < 0) return null;
    out.push(v);
  }
  return out;
}

/** One SSE frame: `event: update`, the update_id as the event id, the whole Update on one line. */
export function sseFrame(stored: StoredUpdate): string {
  return `event: update\nid: ${stored.update_id}\ndata: ${JSON.stringify(stored.update)}\n\n`;
}

export const HEARTBEAT_FRAME = ": hb\n\n";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function intakeStub(env: Env): DurableObjectStub {
  return env.TELEGRAM_INTAKE.get(env.TELEGRAM_INTAKE.idFromName(INTAKE_OBJECT_NAME));
}

// ------------------------------------------------------------------------------------------ routes

/** `POST /telegram-webhook` — Telegram's delivery. 200 only once the update is stored in the DO. */
export async function handleTelegramWebhook(request: Request, env: Env): Promise<Response> {
  if (!webhookSecretOk(request.headers.get("X-Telegram-Bot-Api-Secret-Token"), env.TELEGRAM_WEBHOOK_SECRET)) {
    return new Response("unauthorized", { status: 401 });
  }
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return new Response("bad json", { status: 400 });
  }
  const update = parseUpdate(body);
  if (update === null) return new Response("no update_id", { status: 400 });
  try {
    // The DO's own status is passed through: 200 stored, 503 buffer full (Telegram retries).
    const res = await intakeStub(env).fetch("https://intake/update", {
      method: "POST",
      body: JSON.stringify(update),
    });
    return new Response(res.ok ? "ok" : "retry", { status: res.ok ? 200 : 503 });
  } catch (e) {
    // Not stored ⇒ never 200: a non-2xx is what makes Telegram try again.
    console.log(`telegram-webhook: store failed: ${String(e)}`);
    return new Response("retry", { status: 503 });
  }
}

/** The daemon's routes share one bearer token; each is forwarded to the DO unchanged. */
async function forwardDaemonRoute(request: Request, env: Env, path: string): Promise<Response> {
  if (!bearerOk(request.headers.get("Authorization"), env.TELEGRAM_INTAKE_TOKEN)) {
    return new Response("unauthorized", { status: 401 });
  }
  const init: RequestInit = { method: request.method, headers: { "Content-Type": "application/json" } };
  if (request.method === "POST") init.body = await request.text();
  return intakeStub(env).fetch(`https://intake${path}`, init);
}

/** `GET /telegram-stream` — the daemon's held SSE connection. */
export function handleTelegramStream(request: Request, env: Env): Promise<Response> {
  return forwardDaemonRoute(request, env, "/stream");
}

/** `POST /telegram-ack` — `{update_ids: [...]}` once processed; `{update_ids: []}` is a heartbeat. */
export function handleTelegramAck(request: Request, env: Env): Promise<Response> {
  return forwardDaemonRoute(request, env, "/ack");
}

/** `GET /telegram-intake-status` — buffered count, oldest wait, link + heartbeat. */
export function handleTelegramIntakeStatus(request: Request, env: Env): Promise<Response> {
  return forwardDaemonRoute(request, env, "/status");
}

// ------------------------------------------------------------------------------- Durable Object

interface Stream {
  id: string;
  writer: WritableStreamDefaultWriter<Uint8Array>;
  connectedAtMs: number;
}

/**
 * Durable Object: the ONE buffer between Telegram and the daemon. Every update is written to storage
 * before anyone is told it exists, and leaves storage only on the daemon's ack.
 *
 * Deliberately NOT the WebSocket Hibernation API: the daemon is stdlib-only, so its end is an SSE
 * reader over `http.client`, and an SSE response keeps this object resident while it is held. That
 * cost is measured in `margo/docs/telegram-push-intake-spec.md` §4.4.
 */
export class TelegramIntake {
  private state: DurableObjectState;
  private streams = new Map<string, Stream>();
  private heartbeat: ReturnType<typeof setInterval> | null = null;
  private pendingCount: number | null = null;
  private lastBeatMs: number | null = null;
  private lastAckMs: number | null = null;

  constructor(state: DurableObjectState, _env: Env) {
    this.state = state;
  }

  async fetch(request: Request): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (request.method === "POST" && pathname === "/update") return this.store(request);
    if (request.method === "GET" && pathname === "/stream") return this.openStream();
    if (request.method === "POST" && pathname === "/ack") return this.ack(request);
    if (request.method === "GET" && pathname === "/status") return this.status();
    return new Response("not found", { status: 404 });
  }

  private async listStored(limit?: number): Promise<StoredUpdate[]> {
    const opts: DurableObjectListOptions = { prefix: UPDATE_KEY_PREFIX };
    if (limit !== undefined) opts.limit = limit;
    const map = await this.state.storage.list<StoredUpdate>(opts);
    return [...map.values()];
  }

  private async pending(): Promise<number> {
    if (this.pendingCount === null) this.pendingCount = (await this.listStored()).length;
    return this.pendingCount;
  }

  /** Store first, then push to the held stream(s). A re-delivered id is stored once. */
  private async store(request: Request): Promise<Response> {
    const update = parseUpdate(await request.json().catch(() => null));
    if (update === null) return new Response("no update_id", { status: 400 });
    const id = update.update_id as number;
    const key = updateKey(id);
    let stored = await this.state.storage.get<StoredUpdate>(key);
    if (stored === undefined) {
      if ((await this.pending()) >= MAX_BUFFERED_UPDATES) {
        console.log(`telegram-intake: buffer full (${MAX_BUFFERED_UPDATES}); refusing ${id} so Telegram keeps it`);
        return new Response("buffer full", { status: 503 });
      }
      stored = { update_id: id, received_at_ms: Date.now(), update };
      await this.state.storage.put(key, stored);
      this.pendingCount = (this.pendingCount ?? 0) + 1;
    }
    this.broadcast(sseFrame(stored));
    return json({ ok: true, update_id: id, streams: this.streams.size });
  }

  /**
   * The daemon's held stream. Everything unacked goes first, oldest first, written in the SAME
   * synchronous run that registers the stream — so a live update stored afterwards can only land
   * behind the snapshot, never inside it. Newest connection wins: an older stream (a half-open link
   * the daemon already gave up on) is closed.
   */
  private async openStream(): Promise<Response> {
    const backlog = await this.listStored();
    this.pendingCount = backlog.length;
    const { readable, writable } = new TransformStream<Uint8Array, Uint8Array>();
    const writer = writable.getWriter();
    for (const old of this.streams.values()) this.dropStream(old, "replaced by a newer connection");
    const stream: Stream = { id: crypto.randomUUID().slice(0, 8), writer, connectedAtMs: Date.now() };
    this.streams.set(stream.id, stream);
    const snapshot = backlog.map(sseFrame).join("") + `: connected ${backlog.length} pending\n\n`;
    this.write(stream, snapshot);
    this.armHeartbeat();
    console.log(`telegram-intake: stream ${stream.id} UP, replayed ${backlog.length} unacked update(s)`);
    return new Response(readable, {
      headers: { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", "X-Accel-Buffering": "no" },
    });
  }

  private async ack(request: Request): Promise<Response> {
    const ids = parseAckIds(await request.json().catch(() => null));
    if (ids === null) return new Response("bad ack", { status: 400 });
    this.lastBeatMs = Date.now();
    if (ids.length > 0) {
      const keys = ids.map(updateKey);
      const existing = await this.state.storage.get<StoredUpdate>(keys);
      await this.state.storage.delete(keys);
      this.pendingCount = Math.max(0, (await this.pending()) - existing.size);
      this.lastAckMs = this.lastBeatMs;
    }
    return json({ ok: true, acked: ids.length, pending: await this.pending() });
  }

  private async status(): Promise<Response> {
    const backlog = await this.listStored();
    this.pendingCount = backlog.length;
    const oldest = backlog[0];
    const iso = (ms: number | null) => (ms === null ? null : new Date(ms).toISOString());
    return json({
      ok: true,
      pending: backlog.length,
      oldest_update_id: oldest?.update_id ?? null,
      oldest_received_at: oldest ? iso(oldest.received_at_ms) : null,
      connected: this.streams.size > 0,
      streams: [...this.streams.values()].map((s) => ({ id: s.id, connected_at: iso(s.connectedAtMs) })),
      last_daemon_beat_at: iso(this.lastBeatMs),
      last_ack_at: iso(this.lastAckMs),
    });
  }

  // --- stream plumbing ---------------------------------------------------------------------------

  private broadcast(text: string): void {
    for (const s of this.streams.values()) this.write(s, text);
  }

  /** Not awaited: a write to a vanished daemon rejects, and the rejection retires that stream. */
  private write(stream: Stream, text: string): void {
    stream.writer.write(encoder.encode(text)).catch(() => this.dropStream(stream, "write failed"));
  }

  private dropStream(stream: Stream, why: string): void {
    if (!this.streams.delete(stream.id)) return;
    stream.writer.close().catch(() => undefined);
    console.log(`telegram-intake: stream ${stream.id} DOWN: ${why}`);
    if (this.streams.size === 0 && this.heartbeat !== null) {
      clearInterval(this.heartbeat);
      this.heartbeat = null;
    }
  }

  private armHeartbeat(): void {
    if (this.heartbeat !== null) return;
    this.heartbeat = setInterval(() => this.broadcast(HEARTBEAT_FRAME), HEARTBEAT_MS);
  }
}
