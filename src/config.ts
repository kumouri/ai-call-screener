/**
 * Worker environment bindings + helpers to derive runtime settings from them.
 * Secrets come from `.dev.vars` (local) or `wrangler secret put` (deployed);
 * plain config comes from `[vars]` in wrangler.toml.
 */
import type { PostGateAction, ScreenerSettings } from "./screener/decision";

export interface Env {
  // --- bindings ---
  DB: D1Database;
  RELAY_SESSION: DurableObjectNamespace;

  // --- secrets ---
  ANTHROPIC_API_KEY: string;
  TWILIO_ACCOUNT_SID: string;
  TWILIO_AUTH_TOKEN: string;
  TWILIO_NUMBER_E164: string;
  USER_CELL_E164: string;
  /** Public https base URL of this Worker / tunnel (no trailing slash). */
  PUBLIC_BASE_URL: string;

  // --- vars (wrangler.toml [vars]) ---
  GATE_PROMPT: string;
  POST_GATE_ACTION: string;
  REPUTATION_LOOKUP_ENABLED: string;
  DAILY_BUDGET_USD: string;
  OWNER_NAME?: string;
  /** Phonetic spelling of the owner's name for TTS (e.g. "Cerise" for "Ceryce"). */
  OWNER_NAME_SPOKEN?: string;
  /** Free-text description of the owner + who to put through vs. block. */
  OWNER_PROFILE?: string;
  /** Optional shared password for owner recognition (a running easter egg until set). */
  OWNER_PASSWORD?: string;
  /** Bearer secret the Google Contacts sync (Apps Script) must present to POST /sync-contacts. */
  CONTACTS_SYNC_SECRET?: string;
}

export function settingsFromEnv(env: Env): ScreenerSettings {
  return {
    userCellE164: env.USER_CELL_E164,
    gatePrompt: env.GATE_PROMPT,
    postGateAction: parsePostGate(env.POST_GATE_ACTION),
    reputationLookupEnabled: env.REPUTATION_LOOKUP_ENABLED === "true",
    dailyBudgetUsd: parseFloatOr(env.DAILY_BUDGET_USD, 0),
  };
}

function parsePostGate(v: string): PostGateAction {
  return v === "ring_through" ? "ring_through" : "converse";
}

function parseFloatOr(v: string, fallback: number): number {
  const n = Number.parseFloat(v);
  return Number.isFinite(n) ? n : fallback;
}
