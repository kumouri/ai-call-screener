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
  /** Escalating "call me until I answer" reminder loop (one DO instance per escalation id). */
  CALL_ESCALATION: DurableObjectNamespace;
  /** Margo's Telegram intake buffer + the daemon's held stream (`telegram/intake.ts`; one instance). */
  TELEGRAM_INTAKE: DurableObjectNamespace;

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
  /** Bearer secret the on-device blocker app must present to GET /blocklist. */
  BLOCKLIST_SYNC_SECRET?: string;
  /** Bearer secret Margo's local push_call.py must present to POST /push-call. */
  PUSH_CALL_SECRET?: string;

  // --- Telegram (secrets) — owner notifications go here when both are set; SMS otherwise ---
  /** Margo's bot token (the same bot the daemon uses). */
  TELEGRAM_BOT_TOKEN?: string;
  /** Ceryce's chat id with the bot. */
  TELEGRAM_CHAT_ID?: string;
  /** Optional private-chat topic (`message_thread_id`) to file notifications under. */
  TELEGRAM_THREAD_ID?: string;
  /** Optional Bot API base override (proxy / test harness only). */
  TELEGRAM_API_BASE?: string;
  /** Telegram's `X-Telegram-Bot-Api-Secret-Token` for `POST /telegram-webhook` (the `secret_token`
   * given to setWebhook). Unset ⇒ the webhook refuses everything. */
  TELEGRAM_WEBHOOK_SECRET?: string;
  /** Bearer the daemon presents on `/telegram-stream`, `/telegram-ack`, `/telegram-intake-status`. */
  TELEGRAM_INTAKE_TOKEN?: string;
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
