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
3. else → **press-1 gate** (`POST /gate`). Robodialers fail it cheaply.
4. gate-pass → `<Connect><ConversationRelay>` → the `RelaySession` Durable Object runs the Claude
   conversation as **Margo**, then transfers / takes a message / marks spam (via Twilio REST), logs to D1,
   and SMSes the owner.

**Critical:** the DO uses the **non-hibernating** WebSocket API (`server.accept()`), not
`state.acceptWebSocket()` — hibernation resets `callSid`/`history` every turn and breaks everything. Don't
"optimize" it back to hibernation.

## Key files
- `src/index.ts` — Worker router: `/voice`, `/gate`, `/ws`, `/status`, `/sync-contacts`.
- `src/screener/` — `funnel.ts` (routing), `conversation.ts` (turn engine + cap), `brain.ts` +
  `anthropic-client.ts` (LLM), `prompt.ts` (system prompt), `decision.ts` (types).
- `src/persona.ts` — **Margo** (reusable identity; ElevenLabs voice `Lily` = `pFZP5JQG7iQjIQuC4Bku`).
- `src/relay/session.ts` — the Durable Object. `src/twilio/calls.ts` — live-call transfer.
- `src/data/db.ts` + `schema.sql` — D1 access incl. `syncGoogleContacts`/`reconcileContacts`.
- `scripts/google-contacts-sync.gs` — Apps Script for the contacts sync (see `docs/contacts-sync.md`).

## Config
- **`[vars]`** (wrangler.toml): `OWNER_NAME`="Ceryce", `OWNER_NAME_SPOKEN`="Cerise" (TTS pronunciation),
  `GATE_PROMPT`, `POST_GATE_ACTION`, `REPUTATION_LOOKUP_ENABLED`, `DAILY_BUDGET_USD`.
- **Secrets** (`wrangler secret put`): `ANTHROPIC_API_KEY`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`,
  `TWILIO_NUMBER_E164`, `USER_CELL_E164`, `OWNER_PROFILE`, `CONTACTS_SYNC_SECRET`, `OWNER_PASSWORD` (optional
  easter-egg). Local copies live in `.dev.vars` (gitignored).

## Workflow
- **Test:** `npm run typecheck` && `npm test` (vitest; pure logic only — no Workers runtime needed).
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
- Picking ElevenLabs voices: edit `src/persona.ts` `voice` (raw ElevenLabs id); preview in Twilio's
  ConversationRelay voice picker.

## Status
P0–P2 done (funnel, Margo persona, contacts-sync endpoint), all deployed. **Pending the owner:**
(a) run the Apps Script (`docs/contacts-sync.md`) to populate the allowlist; (b) **P3 — number routing**:
forward Google Voice + cell (conditional forwarding) to the Twilio number so real spam hits the screener
(plan in `docs/setup.md` + the approved plan). Also rotate the Anthropic + Twilio creds that passed through
chat during setup.
