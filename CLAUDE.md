# CLAUDE.md — ai-call-screener

Tiered, cost-optimized AI call screener with a persona ("Margo"). Fronts the owner's number, blocks
obvious spam for free, and only spends on a Claude conversation for callers worth it.

## Stack
- **Twilio ConversationRelay** (STT/TTS/turn-taking) + **Claude** (Haiku 4.5) over a WebSocket.
- **Cloudflare Workers + Durable Objects + D1** (always-on host; D1 = allowlist/blocklist/calls/settings).
- Deployed: `https://ai-call-screener.will-c-armstrong.workers.dev` · D1 `call_screener`.

## Architecture (the funnel)
Inbound call → Worker `POST /voice` runs the cheapest-first cascade (`src/screener/funnel.ts`):
1. **allowlisted** (D1 `contacts`) → `<Dial>` to the owner's cell (no screening).
2. **blocklisted** → `<Reject>` (free — Twilio only bills *answered* calls).
3. else → **press-1 gate** (`POST /gate`). Robodialers fail it cheaply; each failure (wrong key or
   silent timeout — the `<Gather>` uses `actionOnEmptyResult`) is recorded (`recordGateFail`, never
   counting allowlisted contacts). A **silent timeout blocklists on the first strike** (humans mash
   keys; robots say nothing); a **wrong key** gets two strikes of grace.
4. gate-pass → `<Connect><ConversationRelay>` → the `RelaySession` Durable Object runs the Claude
   conversation as **Margo**, then transfers / takes a message / marks spam (via Twilio REST), logs to D1,
   and notifies the owner — **Telegram when configured, SMS only as the fallback** (`src/notify/owner.ts`;
   ruling `2026-09-12-screener-notifies-telegram`).

A **live transfer** (a stage-1 allowlisted dial or a stage-4 connect) rings her **three times** (12 s
each, `/after-bridge?attempt=N`) before voicemail — `LIVE_TRANSFER_ATTEMPTS` in `src/twilio/transfer.ts`,
ruling `2026-09-12-live-transfer-three-attempts`. The reminder escalation's cap is a different constant.

**Critical:** the DO uses the **non-hibernating** WebSocket API (`server.accept()`), not
`state.acceptWebSocket()` — hibernation resets `callSid`/`history` every turn and breaks everything. Don't
"optimize" it back to hibernation.

## Key files
- `src/index.ts` — Worker router: `/voice`, `/gate`, `/ws`, `/status`, `/sync-contacts`, `/blocklist`,
  `/push-call` (+ `/push-call/ack`), `/after-bridge`, `/voicemail`, and Margo's Telegram intake
  (`/telegram-webhook`, `/telegram-stream`, `/telegram-ack`, `/telegram-intake-status`).
- `src/telegram/intake.ts` — **Margo's Telegram intake by push** + the `TelegramIntake` Durable
  Object: Telegram's webhook (secret header, stored before the 200), held until the daemon acks it,
  pushed down the daemon's own SSE stream. Nothing here calls `setWebhook` — the cutover is
  host-side (`margo/scripts/TELEGRAM_PUSH_SETUP.md`; design `margo/docs/telegram-push-intake-spec.md`).
- `src/screener/` — `funnel.ts` (routing), `conversation.ts` (turn engine + cap), `brain.ts` +
  `anthropic-client.ts` (LLM), `prompt.ts` (system prompt), `decision.ts` (types).
- `src/persona.ts` — **Margo**. A **GENERATED FILE** — margo-chief-warrior's
  `margo/scripts/persona_project.py` projects it from the canonical `persona/margo.md`
  (`--write` regenerates, `--check` is a CI gate); edit the canonical file and regenerate, never
  this one directly (R-C11/R-C12, `margo/docs/mmm-spec.md` §2.3). Voice = a raw ElevenLabs id
  (`Lily` = `pFZP5JQG7iQjIQuC4Bku`); preview in Twilio's CR picker. Since the voice-talk MVP
  (2026-09-26), it also carries `ownerDemeanor` — the *to-Ceryce* register, projected from the same
  source's "To Ceryce" bullet, for owner-mode calls only.
- `src/relay/session.ts` — the `RelaySession` Durable Object. Holds `mode: "screener" | "owner"`
  (default `"screener"`; a `POST` seed before the WebSocket upgrade — see `handlePushCallTalk` below —
  switches it to `"owner"`), branching the system prompt/tools/turn-engine on it; screener calls are
  unchanged. **The seed is persisted to `state.storage`, not just instance fields** (incident
  2026-09-26: the phone rings for several seconds between the seed POST and the ConversationRelay
  handshake, long enough for an idle DO to be evicted and a fresh instance to fall back to the
  screener default mid-call) — the constructor reloads it via `blockConcurrencyWhile` before any
  `fetch()` runs. Belt and braces: the talk TwiML also carries a `mode=owner` `<Parameter>`, and
  `resolveSessionMode` (pure, unit-tested) requires the persisted seed AND that parameter to agree
  before entering owner mode; either alone falls back to screener, logged loudly. `voice-call-spec.md`
  has the incident writeup. `src/relay/owner-prompt.ts` (owner-mode system prompt + its lone
  `end_call` tool) and `src/relay/owner-conversation.ts` (`runOwnerTurn`, the screener-free turn
  engine, capped at `MAX_OWNER_TURNS`) are its owner-mode siblings of `src/screener/prompt.ts` +
  `conversation.ts`. `src/twilio/calls.ts` — live-call transfer; `src/twilio/transfer.ts` — the
  three-attempt loop + `liveTransferTwiml`.
- **Voice-talk MVP (P1, `margo/docs/voice-call-spec.md`).** `POST /push-call` with
  `{"mode": "talk", "context"?: "..."}` (checked before the scripted-line `text` shape) dials
  `env.USER_CELL_E164` **only** — `notify/call.ts::placeTalkCall` takes no `to` parameter at all, so
  there is no way to redirect it — and connects the call to `RelaySession` in owner mode, seeded via a
  plain `fetch` POST to the Durable Object BEFORE the call is placed (same pattern as
  `CallEscalation`'s `/start`). Shares the `DAILY_BUDGET_USD` cap with inbound screening
  (`data/db.ts::sumSpendToday` + `budget.ts::overBudget`, checked before dialing; the call's own
  estimated cost is recorded back on hangup, `outcome_stage`/`verdict`: `"talk"`). Local trigger:
  `margo/scripts/push_call.py --talk`.
- `src/notify/owner.ts` — the one door for owner notifications (Telegram else SMS); `telegram.ts` talks
  to the Bot API directly (the Worker can't reach the daemon) and uploads voicemail audio.
- `src/notify/call.ts` — outbound reminder calls: `placeCall` (single ring) + `placeEscalationCall`.
  **Both speak in Margo's ElevenLabs voice** — `<Say voice="ElevenLabs.<id>">`, the id read from
  `MARGO.ttsProvider`/`MARGO.voice` via `twiml.ts::sayVoiceOf` (never a second hardcoded id). Twilio's
  default voice is a **runtime fallback only** (ruling `2026-09-12-calls-use-persona-voice`): a create
  Twilio refuses with the voice is re-placed once with a bare `<Say>`, and an in-call invalid voice is
  Twilio warning 13511, which speaks the default voice rather than dropping the call. ElevenLabs in
  `<Say>` is **public beta since 2026-09-09** (Twilio changelog); no account-side enablement is
  documented — if the account turns out to need it, the fallback path is what you'd hear, and
  `wrangler tail` / the Twilio debugger show the 13511s.
- `src/escalation/escalation.ts` — the **`CallEscalation`** Durable Object: `POST /push-call {escalate:true}`
  re-calls (default every 2 min, ≤15 tries) via a **storage alarm** until she presses a digit
  (`POST /push-call/ack`) or the cap is hit. Pure `nextEscalationStep` for the retry decision.
  With `talk: true` (her own cell only) each attempt is an **alert call** — `src/relay/alert.ts` +
  `notify/call.ts::placeAlertTalkCall`: the line opens an owner-mode `RelaySession` call that listens,
  and the session posts `/ack` on her reply or a key; the key-press call is each attempt's fallback.
  `margo/docs/voice-call-spec.md` § Alert calls.
- `src/data/db.ts` + `schema.sql` — D1 access incl. `syncGoogleContacts`/`reconcileContacts`.
- `scripts/google-contacts-sync.gs` — Apps Script for the contacts sync (see `docs/contacts-sync.md`).

## Config
- **`[vars]`** (wrangler.toml): `OWNER_NAME`="Ceryce", `OWNER_NAME_SPOKEN`="Cerise" (TTS pronunciation),
  `GATE_PROMPT`, `POST_GATE_ACTION`, `REPUTATION_LOOKUP_ENABLED`, `DAILY_BUDGET_USD`.
- **Secrets** (`wrangler secret put`): `ANTHROPIC_API_KEY`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`,
  `TWILIO_NUMBER_E164`, `USER_CELL_E164`, `OWNER_PROFILE`, `CONTACTS_SYNC_SECRET`, `OWNER_PASSWORD` (optional
  easter-egg), `TELEGRAM_BOT_TOKEN` + `TELEGRAM_CHAT_ID` (owner notifications; both or neither) +
  `TELEGRAM_THREAD_ID` (optional topic), `TELEGRAM_WEBHOOK_SECRET` + `TELEGRAM_INTAKE_TOKEN` (the push
  intake; both pushed by `deploy_screener.py` from `telegram.env`). Local copies live in `.dev.vars` (gitignored). Setting the Telegram
  secrets is a **host-side, ask-high step after merge** — `README.md` § Owner notifications.

## Workflow
- **Test:** `npm run typecheck` && `npm test` (vitest; pure logic only — no Workers runtime needed).
- **Deploy is ask-high** — a merged Worker change is *not* live until someone runs `wrangler deploy`
  (Ceryce's or Margo's call after merge, never a PR's).
- **Deploy:** wrangler reads `CLOUDFLARE_API_TOKEN`. The owner's token is in env var
  `CLOUDFLARE_KMOSF_DNS_AND_WORKER_API_TOKEN`, so prefix wrangler commands with
  `export CLOUDFLARE_API_TOKEN="$CLOUDFLARE_KMOSF_DNS_AND_WORKER_API_TOKEN"`. Then `wrangler deploy`.
- **Logs:** `wrangler tail` (the DO logs setup/prompt/reply/decision/errors). Sessions time out after a
  while; call records also persist to D1 (`SELECT … FROM calls`).
- **Git Flow:** `feature/*` → PR → **merge commit** to `develop` on **green CI only** (never red/pending).
  CI = `npm ci` + typecheck + test on develop & main.

## Gotchas
- `npm install` in this environment injects a junk `"most-capable-agent": "file:.."` dep into package.json/
  lock. Use **`npm ci`**; if you must `npm install`, scrub it before committing (CI uses `npm ci`).
- Sandbox `curl` to the Worker is flaky (returns `000`); use **WebFetch** to check liveness.

## Status
P0–P2 done (funnel, Margo persona, contacts-sync endpoint), all deployed. **Pending the owner:**
(a) run the Apps Script (`docs/contacts-sync.md`) to populate the allowlist; (b) **P3 — number routing**:
forward Google Voice + cell (conditional forwarding) to the Twilio number so real spam hits the screener
(plan in `docs/setup.md` + the approved plan). Also rotate the Anthropic + Twilio creds that passed through
chat during setup.
