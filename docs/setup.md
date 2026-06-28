# Setup

> Status: M0–M1 (local logic). The credentialed/outward steps below are the M2–M5 runbook; nothing here
> has been provisioned yet.

## 1. Accounts & secrets

You'll need:
- An **Anthropic API key**.
- A **Twilio** account + a voice-capable phone number (a trial number works to start).
- A **Cloudflare** account (Workers + D1).
- Your **cell number** in E.164 (where screened calls get bridged).

Copy `.dev.vars.example` to `.dev.vars` and fill it in for local dev. For production set each as a secret:

```bash
wrangler secret put ANTHROPIC_API_KEY
wrangler secret put TWILIO_ACCOUNT_SID
wrangler secret put TWILIO_AUTH_TOKEN
wrangler secret put TWILIO_NUMBER_E164
wrangler secret put USER_CELL_E164
wrangler secret put PUBLIC_BASE_URL
```

Optional: `wrangler secret put PUSH_SMS_SECRET` enables `POST /push-sms`, a bearer-authed endpoint that
texts the owner a short digest (Margo's brief highlights). Without it the endpoint returns 401. Margo's
scheduler calls it as `POST {PUBLIC_BASE_URL}/push-sms` with header `Authorization: Bearer <secret>` and
body `{"text":"…"}` (optional `"to"` E.164; defaults to `USER_CELL_E164`).

## 2. Provision D1

```bash
wrangler d1 create call_screener        # paste the returned database_id into wrangler.toml
npm run db:apply:local                  # or db:apply:remote for production
```

## 3. Twilio number → webhook

Point the number's **A Call Comes In** webhook at `POST {PUBLIC_BASE_URL}/voice`. Locally, expose
`wrangler dev` with a tunnel (cloudflared/ngrok) and use that https URL as `PUBLIC_BASE_URL`.

## 4. Google Voice front door

1. **Enable GV's native spam filter** (Settings → Calls → *Filter spam calls*) — a free first line that
   keeps a lot of spam out of the paid pipeline entirely.
2. **Forward GV → the Twilio number.** Recommended default: keep your GV number and forward to Twilio.
   - Caveat: GV→Twilio forwarding is finicky — GV's verification OTP to a Twilio number can be blocked, and
     VoIP forwarding sometimes dumps to voicemail. Mitigations: a TwiML-Bin relay to capture the GV
     verification step; a short answer delay.
   - Robust upgrade (later, your call): **port the GV number into Twilio** so there's no forwarding hop.
     Semi-irreversible and takes a few days.

## 5. Seed the allowlist

Export your contacts and import them so known callers never get screened. `scripts/seed-allowlist.ts`
normalizes numbers to E.164; the D1 import wiring lands in M2/M3.

## 6. Deploy

```bash
npm run typecheck && npm test
wrangler deploy
```
Set the Twilio webhook + `PUBLIC_BASE_URL` to the deployed Worker URL.
