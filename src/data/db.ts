/**
 * D1 (SQLite) access. The two hot-path lookups (allowlist / blocklist) are the
 * cheap, deterministic rails the funnel runs before spending anything. D1 is a
 * rebuildable index over the canonical call records.
 */
import type { ListLookup, ScreenerSettings, Verdict } from "../screener/decision";

/** Both free list lookups in one place. Anonymous callers ("") match neither. */
export async function lookupLists(db: D1Database, fromE164: string): Promise<ListLookup> {
  if (fromE164 === "") return { isAllowlisted: false, isBlocklisted: false };
  const allow = await db.prepare("SELECT 1 FROM contacts WHERE number_e164 = ? LIMIT 1").bind(fromE164).first();
  const block = await db.prepare("SELECT 1 FROM blocklist WHERE number_e164 = ? LIMIT 1").bind(fromE164).first();
  return { isAllowlisted: allow !== null, isBlocklisted: block !== null };
}

/** Learning loop: remember a spam number so future calls are rejected for $0. */
export async function addToBlocklist(db: D1Database, fromE164: string, reason: string): Promise<void> {
  if (fromE164 === "") return;
  const now = new Date().toISOString();
  await db
    .prepare(
      `INSERT INTO blocklist (number_e164, reason, confidence, first_seen, last_seen, hit_count)
       VALUES (?1, ?2, 1.0, ?3, ?3, 1)
       ON CONFLICT(number_e164) DO UPDATE SET last_seen = ?3, hit_count = hit_count + 1`,
    )
    .bind(fromE164, reason, now)
    .run();
}

export interface CallRecord {
  id: string;
  fromE164: string;
  toE164: string;
  startedAt: string;
  endedAt: string;
  outcomeStage: string;
  verdict: Verdict;
  callerName?: string;
  reason?: string;
  transcript?: string;
  costEstimateUsd: number;
}

export async function recordCall(db: D1Database, rec: CallRecord): Promise<void> {
  await db
    .prepare(
      `INSERT INTO calls
        (id, from_e164, to_e164, started_at, ended_at, outcome_stage, verdict, caller_name, reason, transcript, cost_estimate_usd)
       VALUES (?,?,?,?,?,?,?,?,?,?,?)`,
    )
    .bind(
      rec.id,
      rec.fromE164,
      rec.toE164,
      rec.startedAt,
      rec.endedAt,
      rec.outcomeStage,
      rec.verdict,
      rec.callerName ?? null,
      rec.reason ?? null,
      rec.transcript ?? null,
      rec.costEstimateUsd,
    )
    .run();
}

/**
 * Single-row settings overrides. Env supplies defaults today; this lets the
 * owner tweak behavior at runtime in a later milestone.
 */
export async function loadSettingsRow(_db: D1Database): Promise<Partial<ScreenerSettings>> {
  // TODO(M3/M5): read overrides from the `settings` table.
  return {};
}
