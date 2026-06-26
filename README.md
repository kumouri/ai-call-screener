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

**M0–M1 complete:** the cost-saving funnel (allowlist / blocklist / press-1 gate) is implemented and
unit-tested offline. Stage-4 Claude conversation, notifications, the learning blocklist, and production
deploy land in M2–M5. See [docs/architecture.md](docs/architecture.md) and
[docs/setup.md](docs/setup.md).

## Layout

- `src/screener/funnel.ts` — the tiered routing decision (pure, tested).
- `src/twiml.ts` — Dial / Reject / Gather / ConversationRelay builders.
- `src/index.ts` — Worker router (`/voice`, `/gate`, `/ws`, `/status`).
- `src/relay/` — ConversationRelay protocol + the Durable Object session.
- `src/data/` — D1 access + `schema.sql`.
- `test/` — vitest unit tests for the pure logic.
