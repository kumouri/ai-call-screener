# ai-call-screener

A tiered, **cost-optimized** AI call screener. It fronts your phone number, blocks obvious spam for
free, and only spends money letting Claude talk to the callers that prove they're worth it.

Built for an **Android + Google Voice** setup, but the core is carrier-agnostic.

## Why tiered?

Answering and conversing with every robocaller is expensive. On Twilio you're only billed once a call is
**answered**, so the whole design maximizes free rejects and minimizes answered minutes:

```
 Inbound call
   1. Allowlisted contact?  -> Dial straight through         (free)
   2. Blocklisted number?   -> Reject (declined, not answered)(free, $0)
   3. Press-1 gate          -> robodialers can't press 1      (~$0.01–0.02)
        pressed 1 (human) -> 4. Claude screens the caller     (~$0.10)
                               -> connect / take message / mark spam
```

A **connect** (and an allowlisted dial) rings the owner's cell up to **three times** (~20 s each) before
rolling to voicemail; the voicemail's transcript — and, over Telegram, the recording itself — then goes to
the owner. See [Owner notifications](#owner-notifications).

Spam that gets flagged is remembered, so repeat offenders are rejected for $0 forever after. Target cost
at ~8 calls/day: **~$5–10/mo**.

## Stack

- **Twilio ConversationRelay** ($0.07/min) — speech-to-text, text-to-speech, interruption handling.
- **Claude** (Haiku 4.5) — the screening brain, over a WebSocket (bring-your-own-LLM).
- **Cloudflare Workers + Durable Objects + D1** — always-on host; D1 holds allowlist / blocklist / call log.

## Quickstart (local)

```bash
npm install          # NOTE: see the package-lock caveat in docs/runbook.md
npm run typecheck
npm test
cp .dev.vars.example .dev.vars   # then fill in real values
npm run dev          # wrangler dev; expose with a tunnel for Twilio
```

## Status

**All four funnel stages are built**, not just the first three. The cost-saving funnel (allowlist /
blocklist / press-1 gate) is implemented and unit-tested offline, **and so is stage 4** — the
`RelaySession` Durable Object (`src/relay/session.ts`) runs the Claude conversation (Haiku 4.5,
`claude-haiku-4-5-20251001`), transfers/takes a message/marks spam via the Twilio REST client
(`src/twilio/calls.ts`), and notifies the owner (`src/notify/` — Telegram, or SMS as the fallback; see
[Owner notifications](#owner-notifications)). Both Durable Objects and the D1
binding are declared in `wrangler.toml`. *(The old "M0–M1 complete, stage-4 lands in M2–M5" line
here was stale — it described work that had already shipped. `CLAUDE.md` in this directory tracks
milestones as **P0–P2 done, all deployed**; whether a given commit is live on the Worker is a host
fact this repo cannot confirm.)*

Beyond the screener itself the Worker also carries **outbound reminder calls** — `POST /push-call`
places a single ring, and `{escalate: true}` hands off to the `CallEscalation` Durable Object, which
re-calls on a storage alarm (default every 2 min, ≤15 tries) until `POST /push-call/ack` or the cap.
Adding `talk: true` makes each attempt an **alert call** (since 2026-10-22): the line is spoken as the
opening of an owner-mode ConversationRelay call that then listens, and her reply or a key press
acknowledges it; an attempt whose talk call can't be placed (budget, a Twilio refusal) is the
key-press call instead (`margo/docs/voice-call-spec.md` § Alert calls).
Both speak in Margo's own ElevenLabs voice (the persona's id, via Twilio's `<Say voice="ElevenLabs.…">`,
public beta since 2026-09-09); Twilio's default voice is only a runtime fallback, so a reminder call
never fails because a voice did.

**`POST /push-call` with `{mode: "talk"}`** (voice-talk MVP P1, `margo/docs/voice-call-spec.md`) is a
third shape: instead of a scripted line, it dials `USER_CELL_E164` — the only number this mode can
ever reach — and hands the answered call to `RelaySession` running in an **owner-mode** register
(Margo's *to-Ceryce* voice, not the screener's gatekeeper character), for a real back-and-forth
conversation rather than a reminder announcement. It shares the screener's `DAILY_BUDGET_USD` cap.
Local trigger: `python margo/scripts/push_call.py --talk`.

The **learning blocklist** is live: a Claude spam verdict blocklists immediately, a **silent** press-1
timeout blocklists on the first strike (humans mash keys; robots say nothing), and a wrong-key press
gets two strikes of grace — blocked numbers are rejected for $0 ever after and synced to the
on-device blocker app. See [docs/architecture.md](docs/architecture.md)
and [docs/setup.md](docs/setup.md).

## Owner notifications

Every note to the owner — the verdict after a screened call (message / spam), the real-time
*"Connecting X — pick up!"* alert, and a voicemail transcript — goes through one door,
`src/notify/owner.ts`, which picks the channel:

- **Telegram, when configured.** The Worker talks to the Bot API directly (it cannot reach Margo's
  desktop daemon), using the same bot and chat the daemon uses. Plain text; a voicemail arrives as
  **two messages** — the transcript first, then the recording as an audio file (the Worker fetches
  Twilio's auth-protected `RecordingUrl` as `.mp3` and uploads it via multipart `sendAudio`). If the
  audio leg fails, the text has already gone.
- **SMS (Twilio, to `USER_CELL_E164`) only as the fallback** — when Telegram is not configured, or as a
  last resort when a configured Telegram send fails. US long-code SMS additionally needs Twilio A2P
  10DLC registration, which is why Telegram is the primary channel.

`wrangler tail` logs which channel carried each send (`notifyOwner: telegram` / `sms`).

**Setup (host-side, after merge — these are secrets, never in the repo):**

```bash
export CLOUDFLARE_API_TOKEN="$CLOUDFLARE_KMOSF_DNS_AND_WORKER_API_TOKEN"
wrangler secret put TELEGRAM_BOT_TOKEN    # the bot token from @BotFather (same as telegram.env)
wrangler secret put TELEGRAM_CHAT_ID      # Ceryce's chat id with the bot (same as telegram.env)
wrangler secret put TELEGRAM_THREAD_ID    # optional: a private-chat topic id; omit for the main chat
```

**Or let the repo do it — this is the sanctioned path (ruling `2026-09-12-screener-deploy-after-merge`):**

```bash
python margo/scripts/deploy_screener.py            # secrets from margo/scripts/telegram.env → wrangler (stdin), then wrangler deploy
python margo/scripts/deploy_screener.py --dry-run  # names only, runs nothing
```

It reads the daemon's own `telegram.env`, pipes each value into `wrangler secret put` on **stdin** (never
argv, never printed, never in a transcript), refuses on a missing required key rather than setting an
empty secret, and deploys only after every secret landed. Ceryce's standing authorization: once she has
approved and merged a `phone/` PR, Margo runs this herself — the merge approval is the deploy approval.

Both `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` must be set for Telegram to count as configured;
either one alone leaves the Worker on SMS. For `wrangler dev`, put the same keys in `.dev.vars`
(`.dev.vars.example` lists them).

### Live transfer: three rings, then a message

When the screener connects a caller (or an allowlisted contact dials straight through), the
`<Dial>` to the owner's cell rings for `LIVE_TRANSFER_RING_SEC` (**12 s** — kept below the cell's
no-answer-forward timer, 15 s on Ceryce's T-Mobile line, so a missed bridge can't loop back
through the screener) and reports to `/after-bridge?attempt=N`.
Unanswered ⇒ dial again, up to `LIVE_TRANSFER_ATTEMPTS` (**3**) in total; only then does the caller
hear the voicemail prompt. Both constants live in `src/twilio/transfer.ts`. This is **not** the
reminder escalation's cap (`CallEscalation`, default 15 tries) — a caller shouldn't be held for
minutes, but Margo can keep calling *her* about a reminder for half an hour.

## Layout

- `src/screener/funnel.ts` — the tiered routing decision (pure, tested).
- `src/twiml.ts` — Dial / Reject / Gather / ConversationRelay builders.
- `src/index.ts` — Worker router. Fourteen routes: `/voice`, `/gate`, `/ws`, `/status`,
  `/blocklist`, `/sync-contacts`, `/push-call`, `/push-call/ack`, `/after-bridge` (the live-transfer
  attempt loop), `/voicemail`, and Margo's Telegram intake: `/telegram-webhook`, `/telegram-stream`,
  `/telegram-ack`, `/telegram-intake-status`.
- `src/telegram/intake.ts` — the `TelegramIntake` Durable Object: buffers each webhook update until
  Margo's daemon acks it, over an SSE stream the daemon holds open
  (`../margo/docs/telegram-push-intake-spec.md`).
- `src/relay/` — ConversationRelay protocol + the `RelaySession` Durable Object.
- `src/escalation/` — the `CallEscalation` Durable Object (call-until-answered, storage alarm).
- `src/notify/` — owner notifications: `owner.ts` (the Telegram-else-SMS switch), `telegram.ts`,
  `sms.ts`, `format.ts`; `call.ts` places outbound reminder calls.
- `src/twilio/` — `calls.ts` (re-point a live call via REST), `transfer.ts` (the three-attempt live
  transfer + its constants).
- `src/screener/` — the turn engine, the Claude client, the system prompt, the decision types.
- `src/filter/heuristics.ts`, `src/budget.ts`, `src/config.ts` — free pre-LLM filtering, the daily
  spend cap, and env/config parsing.
- `src/persona.ts` — **Margo**. A *mirror* of `persona/margo.md` in the margo-chief-warrior repo,
  which is canonical; keep the demeanor/greeting/voice in sync with it.
- `src/data/` — D1 access + `schema.sql`.
- `android/` — **Margo Call Shield**, the committed on-device blocker/feed app (Kotlin + Compose).
  See `android/README.md`.
- `test/` — vitest unit tests for the pure logic.
- `.github/workflows/ci.yml` — **inert inside margo-chief-warrior** (GitHub Actions only reads
  workflows at the *repository* root, and there this directory is a `git subtree` prefix). It is
  kept because it is the real CI for the standalone `ai-call-screener` repo this is pushed back to.
