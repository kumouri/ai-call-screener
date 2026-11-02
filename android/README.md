# Margo Call Shield (Android on-device blocker)

A tiny Kotlin app that **silences calls on Margo's blocklist before your phone rings.**
Everything else (contacts, unknowns) rings normally — so the cloud screener still handles the
unknowns you miss, and when Margo flags a new spammer it syncs here and that number never rings again.

```
incoming call → this app checks the synced blocklist
   ├─ on blocklist → silently rejected (no ring, no notification, still logged)
   └─ otherwise    → rings normally  → (unanswered) → forwards to Margo → she screens/learns → syncs back
```

The app pulls Margo's blocklist hourly from `GET /blocklist` on your Worker. Block action is **silent reject**.

This folder is a **committed, buildable Gradle project** — no manual project setup. Open it in Android
Studio, or build from the command line with the Gradle wrapper.

## Prereqs
- **JDK 17** (the wrapper targets Gradle 8.14.x / AGP 8.11). Android Studio's bundled JBR works too.
- The **Android SDK** (Android Studio installs it). The build uses `compileSdk 36` / `build-tools 36.1.0`.
- Your `BLOCKLIST_SYNC_SECRET` — it's in the repo's `.dev.vars` (gitignored).

## Build & install

**1. Create `phone/android/local.properties`** (gitignored — SDK path, and optionally endpoints):
```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
BLOCKLIST_URL=https://ai-call-screener.will-c-armstrong.workers.dev/blocklist
BLOCKLIST_SECRET=<paste BLOCKLIST_SYNC_SECRET from the repo's .dev.vars>
```
Opening the project in Android Studio writes `sdk.dir` for you.

> ### Endpoints are configured IN THE APP now (2026-08-12)
>
> Every endpoint and token lives in **Settings**, on the phone. `local.properties` is only a **seed**:
> a value never set in-app falls back to the compiled-in one, so an existing install keeps working with
> nothing to re-enter, and a fresh build with no properties file simply starts empty and asks.
>
> **You do not need to put anything but `sdk.dir` here.** Build, install, open Settings, type the
> server address and token. That is the whole setup.
>
> This is the change that makes the APK installable by someone who did not build it — previously
> "install this app" meant "clone a repo, write a properties file, run Gradle", which is fine for one
> person and impossible for anyone else. It is also the prerequisite for this code reaching
> [Seneschal](https://github.com/kumouri/seneschal), the de-personalized build.
>
> Because the tokens now live in `SharedPreferences` rather than inside the binary, the app sets
> `allowBackup="false"` and excludes everything from device transfer — a live ingest credential should
> not ride to a cloud backup.

**2. Build + install** on the S23 (USB or wireless debugging on):
```bash
cd phone/android
./gradlew installDebug        # builds, signs with the debug keystore, installs over adb
# or: open the folder in Android Studio and hit Run ▶
```

**3. Grant the role.** Open the app → **"Make Margo the screening app"** → approve. (This takes over the
"Caller ID & spam app" role from Samsung Smart Call — only one app can hold it.)

**4. Keep it alive.** Settings → Apps → Margo → Battery → **Unrestricted** (so Samsung doesn't pause the
hourly sync). A manual **Sync now** is only an *enqueue* — WorkManager decides when it runs, and a held
app can sit on it for hours (on 2026-09-12 four taps waited up to ~2.5 h; Samsung's app-freezing is the
suspect, unproven). Two things make that survivable: each manual sync is **unique work with KEEP**, so
tapping again while one is pending is a no-op rather than a second identical upload (three stacked taps
once shipped the same 2.25 MB three times), and the Debug tab's **Last sync** card shows a held request
as `requested <time> ENQUEUED` with a "waiting for WorkManager" line — a deferred sync reads as
*waiting*, never as silence. The same unique-work shape covers the blocklist and export-scan taps and the
edge-driven presence flush (that one appends rather than keeps, so an edge landing mid-flush still ships).
**The export ship shows a notification while it runs — leave it alone.** A Samsung export is ~97 MB
and thousands of small files; zipping and uploading it is minutes, and on 2026-09-12 Android killed the
app mid-zip twice the moment the screen was left (*"excessive binder traffic during cached"*). So the
export scan now runs as a **foreground worker** whenever it has something to zip or ship: a silent,
low-priority **"Shipping Samsung Health export"** notification (`<newest folder> — zipping N files →
uploading M MB` → a dismissible verdict) is what keeps the process alive, and you can put the phone
down. It only ever ships the **newest** export in the folder — Samsung's exports are cumulative, so
the older ones are superseded and deleted, never zipped (2026-09-12 23:42). Allow notifications when the app asks (Android 13+; the ship runs either way, you just won't see
it). A run that still gets killed leaves nothing behind: the next scan says **interrupted — will retry**
and starts the export over.

## Test it
The blocklist starts empty, so seed one number you can call from (ask me to run it, or):
```bash
wrangler d1 execute call_screener --remote --command \
  "INSERT INTO blocklist (number_e164, reason, confidence, first_seen, last_seen, hit_count)
   VALUES ('+1XXXXXXXXXX','manual test',1.0,datetime('now'),datetime('now'),1)"
```
Then in the app tap **Sync blocklist now** (count should show 1) → call your cell from that number →
it should be **silently rejected** (no ring), and appear in your call log as a blocked/declined call.

## Notes
- Only one app can hold the call-screening role — granting it to Margo replaces Samsung Smart Call's spam role.
- `onScreenCall` runs for non-contact calls; contacts ring through regardless (which is what we want).
- The secret in `BuildConfig` is extractable from the APK — fine for a personal sideloaded app (it only
  grants read access to your blocklist). Don't publish the APK with it baked in.
- CI builds this app on every change under `phone/android/**` (`.github/workflows/android.yml`,
  `./gradlew :app:assembleDebug`) — with no secrets, since `BuildConfig` fields default to `""`.

---

## Health feed (Health Connect → desktop dashboard)

The same app also carries `HealthSyncWorker`, which reads Samsung Health data out of **Health Connect** and
POSTs it as NDJSON to the desktop listener (`margo/scripts/health_listener.py`) over your **tailnet** —
the "genuinely automatic" sleep feed, no manual export tap. It reuses the existing background-sync shape
(`BlocklistSyncWorker`), runs every ~3 hours, and lands in the same `health.db` the dashboard reads. Full
picture: `margo/scripts/HEALTH_SETUP.md`.

**v4 (2026-07-17): workouts + nutrition.** The worker also reads `ExerciseSessionRecord` (workouts) and
`NutritionRecord` (meals) and ships them over the same NDJSON pipe as `t: "workout"` / `t: "nutrition"`
lines — see `import_ndjson`'s docstring in `health_import.py` for the exact wire shapes. A workout's
calories/distance aren't fields on the session itself in Health Connect — they're separate
`TotalCaloriesBurnedRecord` / `DistanceRecord` entries over the same interval — so the worker also reads
those and folds in whatever overlaps a given session's start/end before posting.

Everything the health feed needs is already in this committed project — the Health Connect dependency, the
`android.permission.health.READ_*` permissions (sleep, heart rate, oxygen saturation, steps, exercise,
total calories burned, distance, nutrition), the `<queries>` block, and the permissions-rationale
`activity-alias`. You only add the endpoint + token to `local.properties`.

**1. Desktop side first.** On the machine that holds `health.db`, run the listener (it binds your Tailscale
IP by default):
```bash
python margo/scripts/health_listener.py --token <pick-a-token>
# prints e.g.  health listener on http://100.x.y.z:8765  (tailnet, token required)
```
Note the `http://100.x.y.z:8765` address — that's your `HEALTH_INGEST_URL` below. (For a first smoke test
without Tailscale, `--host 127.0.0.1` and point the phone at the PC's LAN IP.)

**2. Add the endpoint + token to `local.properties`** (the same file as above, gitignored):
```properties
HEALTH_INGEST_URL=http://100.x.y.z:8765/health-ingest
HEALTH_INGEST_TOKEN=<the same token you passed to health_listener.py>
```

**3. Build + install** (`./gradlew installDebug`, or Run ▶). On launch the app requests Health Connect read
access through Health Connect's own system dialog — **approve** sleep, heart rate, blood oxygen, steps,
exercise, total calories burned, distance, nutrition, and (for hands-off background syncing)
allow-all-the-time. Then **Sync now** fires the first pull (one at a time — a second tap while it's pending
is a no-op, and the Debug tab shows it as waiting until WorkManager runs it); after that it's automatic.

**4. Verify.** The listener prints a line per ingest (`[time] ingest NB -> {...}`). Then rebuild the
dashboard on the desktop: `python margo/scripts/health_dashboard.py`.

### Notes
- **Health Connect is the source.** Make sure Samsung Health is syncing into Health Connect on the phone
  (Samsung Health → Settings → Health Connect). The watch data flows Samsung Health → Health Connect →
  this worker → your desktop.
- **Background reads** need the "all the time" grant *and* the app kept off battery-optimization (same
  Unrestricted setting as the blocklist sync). Without it, the sync still runs on app-open and while
  charging — just not on its own overnight.
- **Idempotent + self-healing.** Every record carries Health Connect's stable id; the desktop upserts on
  it, and the worker re-reads the last 2 days each run so same-day step totals and late sleep edits stay
  correct. The steps read starts at local midnight of that first day, so every date it sends is a whole
  day's total (`HealthSyncWorker.stepsWindowStart`). Re-syncing never duplicates.
- The token in `BuildConfig` is extractable from the APK — fine for a personal sideloaded app on a private
  tailnet. The listener also refuses to bind a public interface without a token.

### Samsung Health export folder (the export ships itself — 2026-09-11, folders 2026-09-12)

Health Connect carries what Samsung Health chooses to share with it, which is not everything (sleep
score, stress, per-minute movement never cross). The full dataset only leaves the phone as the
"Download personal data" export, and that tap is the one thing nobody can automate — but the **copy**
to the desktop can be, and the app does it. **On the S23 that export is a FOLDER, not a zip**
(`samsunghealth_<account>_<14 digits>/` holding the `com.samsung.*.csv` tables and a `jsons/` tree);
the app zips it on the phone and ships the zip. Design record:
`margo/docs/samsung-export-watcher-spec.md`; the desktop route: `margo/scripts/HEALTH_SETUP.md` →
"Tier 0.5".

**Setup — once:**

1. **Settings tab → "Samsung Health export folder" → Choose folder.** The system folder picker opens
   (at `Download/Samsung Health` where the Files provider honours the hint; otherwise navigate there:
   *Internal storage → Download → Samsung Health*). Tap **Use this folder** → **Allow**. That is a
   one-time **read+write** grant on that one folder — not "all files" access — and it persists across
   reboots. Write is what lets the app delete an export once the desktop confirms it (below). The card
   flips to a green **Folder: Download/Samsung Health** row. **Granted before 2026-09-12?** That grant
   was read-only; the card shows a red **Cleanup: re-choose the folder to allow cleanup** row until you
   tap **Change folder** and confirm the same folder again — shipping keeps working meanwhile, only
   the delete does not.
2. Nothing else. The health feed's **Server URL** and **Bearer token** above are reused: the export
   goes to the same listener at `/health-export-ingest` (derived from the health URL — there is no
   second URL field, deliberately). If the health URL is blank the card says so. The one option on the
   card, **Delete zips after confirmed ship**, is on by default (below).

**Then, whenever you feel like it:** Samsung Health → ⋮ → Settings → **Download personal data**. The
export folder lands in that folder; within the hour (or immediately via **Scan now**) the app **zips
it into its own cache** (streamed file by file, `<folder name>.zip`, members under `<folder name>/`
exactly as Samsung's own zips nest them), streams the zip to the desktop, the listener imports it, and
the card's **Last shipped** row shows the name, the age and the listener's own verdict
(`export 20260905225220: 110891 rows`, `duplicate (already on the desktop)`, …). **The whole response
body is read before it's parsed** (bounded at 256 KB, not the actual cap on any real reply) — a
`.take(600)` applied after the read used to cut off the listener's `ok:true` body (which lists every
table's row count and easily runs past 600 chars) before `JSONObject` ever saw it, so a **successful**
98 MB import on 2026-09-13 read as a failure and was re-zipped and re-shipped every hour forever
(`ExportWatchWorker.kt`'s `parseShipResponse`). **Last zipped** shows
the file count and the zipped size. A folder Samsung is still writing (touched within the last
3 minutes) is skipped with "still being written" and picked up on the next scan. A
`samsunghealth_*.zip` already in the folder (the older layout) still ships as it is. **Newest export
seen** is what the folder holds; **Last scan** is when the app last looked.

**Only the newest export ships — the rest are inside it (2026-09-12 23:42).** Samsung's exports are
cumulative: the newest one alone rebuilds everything, so an older export carries nothing the newer one
lacks. If the folder holds several exports the app has not delivered (the first real run found three:
July, 09-05 and 09-11), a scan zips and uploads **only the newest** by its 14-digit stamp, and once the
desktop confirms it the older ones are **superseded** — recorded as such, deleted on the same terms as
a confirmed export (folder always, zip behind the switch), never zipped, never uploaded. Ceryce's
words: *"They're cumulative, so why ever run any but the newest one? You don't need to say 'export 1
of 3' because export 1 contains exports 2 and 3."* So nothing here says "1 of 3": the card's **Last
shipped** reads `<name> · <age> · 2 older superseded`, the scan line `3 export(s): 1 shipped, 2
superseded — <verdict>`, and the Debug tab one `superseded by <stamp> — deleted, not shipped` line per
older export. A newest folder Samsung is still writing makes the scan wait — it does not ship the
previous one meanwhile — and if the newest is not confirmed, nothing older is touched either.

**While it ships, a notification shows (2026-09-12 22:47).** The zip + upload of a real export is
minutes, and as ordinary background work Android's app freezer killed the process mid-zip as soon as
the app left the screen — twice, on the first real run. The scan therefore promotes itself to a
foreground service the moment it finds an export to zip or ship: a silent **"Shipping Samsung Health
export — `<folder>` — zipping N files / uploading M MB"** notification for the duration, then a
dismissible one-line verdict. A scan that finds nothing new shows nothing. **Scan now** is expedited,
so a tap starts within seconds rather than at WorkManager's leisure. The hourly tick cannot always be
promoted (Android 12+ refuses a foreground service started from the background) and does not need to
be — it runs under a JobScheduler binding that already keeps the process out of the cached state; the
refusal is logged and the scan carries on. If a run is killed or cancelled anyway, nothing is marked
shipped, the cache zip is swept at the next start, and the card's **Last scan** reads
`<folder>: interrupted — will retry` until that next scan finishes it.

**One scan at a time, and only a zip that checks out is sent (2026-09-30).** On 2026-09-30 the hourly
scan and a **Scan now** ran at the same moment. Each one shipped the zip the *other* was still
writing, and the desktop got three cut-off zips (12, 82 and 5 MB; the Debug tab read `65248 files,
4 MB`), each answered `HTTP 500 landed but import failed … BadZipFile`. Now a scan that starts while
another is running stands down at once, with the Debug line `another scan already running — this one
stood down`, and the running one finishes the job. Before uploading, the app opens its own zip and
checks it holds exactly the files it wrote. If not, **Last zipped** goes red (`zip did not verify —
…`), nothing is sent, and the next scan zips again. The desktop checks the zip as well: a cut-off or
incomplete one is answered `HTTP 422 desktop says the upload was not a whole zip — will re-ship`,
never imported and never confirmed, so nothing is deleted from the phone because of it. Design
record: `margo/docs/samsung-export-watcher-spec.md` §12.

**Where the status shows:** the same card, always — grant held / lost / not granted, last scan (green
or red, with the wire's last line verbatim), newest seen, last zipped, last shipped (and how many older
exports it superseded), last deleted. A
revoked grant (a reinstall, a cleared app) is a red **grant lost — choose again** row, never silence;
**Forget folder** releases the grant and clears the shipped ledger without touching a file. The Debug
tab's event log carries one line per step — zipped, shipped (or refused), superseded, deleted (or
refused) — per export.

**What it deletes, and when (rulings 2026-09-12):** an export is removed from the folder **only after
the desktop confirmed it holds it** — the listener answered `ok:true` for it (imported,
`already imported`, or `duplicate (already on the desktop)`). A timeout, a refusal, a failed or
unverified zip, a 422 re-ship, or a failed import deletes nothing; the export stays and the next scan
tries again. **The export FOLDER is
always deleted** after confirmation — all of it, CSVs, `jsons/` tree and the directory; that is not
optional. **A zip that was sitting in the folder is deleted on the same terms only while the card's
"Delete zips after confirmed ship" switch is on** (default on). The app's own cache zip never
outlives the attempt. An export confirmed on an earlier pass and still in the folder is cleaned up on
the next scan. "Shipped" is still a record on this phone (a zip by name + size + modified time, every
export by its 14-digit stamp), written before the delete, so a crash between the two costs one cheap
duplicate re-send. **An older export a confirmed newer one supersedes is deleted on the same terms**
— it was never shipped, and its Debug line says so (`deleted folder: <name> — superseded by <stamp> —
deleted, not shipped`). The card's **Last deleted** row and a Debug-tab line
(`deleted folder after confirmed ship: <name>` / `deleted zip …`) record each one; a delete the
provider refuses shows as red **Last deleted: FAILED**, never silently. **What it never touches:**
anything not named `samsunghealth_*_<14 digits>` or `samsunghealth_*_<14 digits>.zip` — nothing else
in the folder is listed, let alone deleted.

**Not verified on a device** as of this writing — the app has no device tests; `:app:assembleDebug`
compiling is all CI can say. First real pass: install, grant, tap Samsung's export, watch the card and
`margo/state/health-listener/<today>.log`.

---

## Presence feed (geofences, activity transitions, sleep/wake)

The app also carries the **presence feed**: it registers on-device **geofences** for your named places
and POSTs enter/exit **events** to the desktop listener's `/presence-ingest` over the tailnet, so Margo can
time things off "arrived home" / "left." **Coordinates never leave the phone** — the wire carries only the
place name + `enter`/`exit`.

**It sends three signal families, not just geofences** (`Geofencing.kt` / `GeofenceBroadcastReceiver.kt`,
`ActivityRecognitionUpdates.kt` / `ActivityTransitionReceiver.kt`, `SleepUpdates.kt` / `SleepReceiver.kt`,
all queued through `PresenceStore` and shipped by `PresenceSyncWorker`): **place** enter/exit, **activity**
transitions (`in_vehicle` / `still` / `walking`, via `ACTIVITY_RECOGNITION`), and **sleep/wake** (the Play
Services Sleep API).

> ### The sleep signal still ships. Only the *desktop gate* on it was retired (2026-07-13).
>
> Nothing in this app changed and nothing here needs to. `asleep` is still sensed, still POSTed, and
> still lands in the desktop's rolled-up presence context — it is **informational only** there. What was
> retired is the daemon-side reminder rule that used to *defer nudges* while `asleep` was true
> (`margo/scripts/presence_rules.py`, which now deliberately ignores `context['asleep']` and says so in
> its module docstring). **Why:** the Sleep API is phone-only, so it reads an idle handset as a sleeping
> owner — it declared Ceryce asleep at confidence 73–92 straight through a Monday afternoon and held her
> daytime nudges. A wearable-grade signal (wrist motion + HR) can tell "she's asleep" from "her phone is
> bored"; a phone alone cannot — which is what the `:wear` module below is for. **Do not "fix" this by
> ripping the sender out**: the retirement is a gate decision on the desktop, not a sensor decision here.

**1. Endpoint in `local.properties`** (same listener as the health feed, different path + the same token):
```properties
PRESENCE_INGEST_URL=http://100.x.y.z:8765/presence-ingest
PRESENCE_INGEST_TOKEN=<the same token you passed to health_listener.py>
```

**2. On device** (`./gradlew installDebug`, then in the app):
- Tap **"Save this spot as a place"** → grant **location** when asked, then name it (default `home`).
- Android then sends you to Settings to choose **"Allow all the time"** for background location — do it, or
  geofences only fire while the app is open.
- Battery → **Unrestricted** (same as the other feeds).

Verify on the desktop: the listener prints `… /presence-ingest …` on each enter/exit;
`python presence_import.py --status` / `--context` show the event store + the current `{at_place, …}`.

---

## Text messages (SMS feed — 2026-09-15, Phase 1; RCS via notifications — Phase 2)

The app also ships **her texts** to the same listener (`/sms-ingest`, same token, derived from the
presence URL — nothing extra to type). **It is not, and never becomes, the default SMS app**: it holds
`RECEIVE_SMS` (the live `SMS_RECEIVED` broadcast → `SmsReceiver.kt`, multipart reassembled on the
phone) and `READ_SMS` (a daily + on-demand backfill over `content://sms`, inbox AND sent, behind a
watermark → `SmsBackfillWorker.kt`), buffers through `SmsStore`, flushes through `SmsSyncWorker`, and
records what happened in `SmsLedger` (counts and timestamps only — never a number, never a body).
Both senders build the one wire shape in `SmsStore.line`. Design + the open questions:
`margo/docs/sms-ingest-spec.md`; setup + how to test without a real text: `margo/scripts/SMS_SETUP.md`.

**Two consents.** The permissions are granted on the **Access** tab ("Text messages" card); the feed is
switched on in **Settings** ("Text messages" card) — **default OFF**, the only feed switch that is,
because this one carries message bodies. Either alone ships nothing. **The switch commits on the tap**
(every Settings switch does, since the 2026-09-16 fix — the first live run lost it as an unsaved draft on
a tab change; only the typed fields wait for Save changes). Turning it on with the
permission held runs the first backfill immediately; **Backfill now** and **Re-read all** (clears the
watermark; the desktop dedupes) are on the same card, with the last receive / flush / backfill rows.

**RCS — Phase 2, the notification listener.** Google Messages keeps RCS chats in its own store —
nothing in `content://sms` and no broadcast carries them — so `MessageNotificationListener.kt` (a
`NotificationListenerService`) reads the notification Messages posts, the only door RCS opens to
another app. The pure parsing is `MessageNotificationParser.kt` (`MessagingStyle` entries first, her
own lines dropped; a plain notification only when the app categorised it as a message), pinned by
`MessageNotificationParserTest` over the extras-shaped fixtures in `app/src/test/resources/
notifications/`. Each message becomes a `kind:"notif-msg"` line (`SmsStore.notifLine`) through the
same buffer, flush and route. Its permission is **Notification access** (a system Settings page,
linked from the Access tab's *Message notifications* card, red when a grant was lost); its consent is
the same **Text messages** switch. Which apps it reads is one switch per app on the Settings card —
Google Messages on by default, Signal / WhatsApp / Samsung Messages off. Incoming only, only what the
notification shows; the desktop folds a Messages notification into the SMS row it duplicates. On
Android 13+ a sideloaded install may need App info → ⋮ → *Allow restricted settings* before the
access switch can be turned on.

**Play policy vs platform.** Google Play refuses these two permissions to a non-default SMS app; the
platform does not. This app is sideloaded and always has been — do not "fix" the manifest for a store
submission that is not happening.

---

## Presence signal — "I'm here" (2026-09-18)

The app also carries Margo's **"I'm here" ping/hold** — a discreet on-body signal, not a
notification: a **ping** is one gentle tap, a **hold** is a slow repeating haptic she can lean on
(falling asleep, walking up to someone at a bar, alone on work travel). Margo starts and stops it
from chat ("stay with me while I fall asleep") by running `margo/scripts/presence_signal.py`; the
desktop PUSHES it to the phone, which plays it and relays it to the watch. Full design, the two
verbs, the transport choice and the intensity ceiling: `margo/docs/presence-signal-spec.md`.

**Push, not polling (2026-10-06 — Ceryce's decision: the fix is a push from the desktop to her phone,
and no more polling).** The app no longer asks for the signal on a timer.
`PresenceLinkService` is ONE always-on foreground service (a silent, minimised **"Margo link"**
notification) that holds a connection open to the desktop listener — `GET /presence-signal-stream`,
Server-Sent Events over the tailnet — and the desktop writes down it the instant Margo pings, starts,
or stops a hold (and when a hold expires). The phone applies it on the spot, relays it to the watch,
and **acks it back** (`POST /presence-signal-ack`) with what it did and whether the watch got it — so
when Margo says "it reached your wrist" she has the watch's own word for it, and when it didn't reach
your phone she says so. The old idle poller (a ~60 s WorkManager chain) died silently on 2026-10-06 and
a 30-minute hold went nowhere while this card read "idle / Last check 15m ago"; it and the 10 s
hold-time poll are gone (an updated install cancels the old chain itself).

**No setup beyond the presence feed above** — same listener, same token, every route derived from the
Presence feed's Server URL. **One phone setting matters:** the card shows **Battery: optimized** until
you tap **"Let Margo stay connected (unrestricted battery)"** and allow it — that is what lets the link
come back on its own after Android stops it, and keeps Doze from deferring it. (Step 4 of *Build &
install* above, the same Unrestricted setting, covers it if you already did that.)

**What the link costs, stated plainly.** An idle held connection is close to free: nothing moves on it
except a one-line heartbeat from the desktop once a minute, which is also how the phone notices a dead
link (nothing heard for 150 s → reconnect). A drop reconnects with a short backoff (2 s → 2 min), on a
network change at once, and through an inexact alarm while the phone dozes. A ping that arrives more
than 2 minutes late (the phone was out of reach and just reconnected) is dropped, never played late.
While a hold is playing, the app holds a partial wake lock — the vibrator is running the whole time
anyway — so the rhythm can be re-issued on time (next paragraph but one).

**Works under Mute/DND, and survives a denied foreground-service start (2026-09-18 fix; the service
named here is gone since 2026-10-06 — the link service is already in the foreground when a hold
arrives, so that denial can no longer happen on this path).** Her very
first real hold went completely silent: it was started while the app was in the background (the
normal case — the idle poller is the one that notices), and Android 12+'s background-foreground-
service-start restriction DENIED `PresenceSignalService`'s promotion to foreground
(`startForegroundCount=0`, confirmed via `dumpsys activity services`), so the vibrate call gated
behind that promotion never ran. Two independent fixes: **(1)** the haptic now plays from
`PresenceHapticPatterns.vibrate()` with **alarm-class attributes** (`VibrationAttributes.
USAGE_ALARM` on API 33+, the `AudioAttributes` equivalent below it) instead of the default
`USAGE_UNKNOWN` a bare `vibrate()` carries — a default-attribute vibration is exactly what the system
suppresses under Mute/DND/Bedtime, and this feature's whole reason to exist ("stay with me while I
fall asleep") happens under one of those by definition; **(2)** `PresenceSignalApplier.apply()` now
fires the vibration itself, immediately, the moment it recognizes a new hold — a plain
`Vibrator.vibrate()` needs no foreground promotion and is driven by the system vibrator service
independently of the calling process, so it plays whether or not the foreground service is ever
allowed to start. `PresenceSignalService` is now a pure enhancement (the persistent notification,
the Stop button, the tighter 10 s poll) rather than a prerequisite — a denied start just means no
chrome, never no sensation; `PresenceSignalPollWorker`'s idle cadence carries an expiry-check
fallback for that no-chrome case, so a hold still stops on time even without the service's own local
timer. Neither change touches `PresenceHapticPatterns.MAX_AMPLITUDE` — this only changes WHEN a
vibration is allowed to play, never how hard. A **"Test tap"** button on the Settings card plays a
ping locally (no server round trip) so this is checkable on-device without asking Margo for a real
one.

**A hold now plays for its WHOLE window (2026-10-06).** On her wrist a hold stopped ~3 pulses in
(2026-10-06 21:49 CT) and, on 2026-09-22, "dies at ~60 s" — when a keepalive job that re-started the
hold every 50 s was the only thing keeping it going. Both apps used to hand the vibrator ONE infinite
repeating waveform at hold-start and never touch it again; anything that cancels an ongoing vibration
— most likely the platform's own cancel of a non-system app's vibration when the screen goes off (a
glance, then the wrist lowered), or a higher-importance vibration — ended the hold silently while the
notification still said "Margo is here". Now `PresenceHoldKeeper` (one copy per module) plays the
rhythm in ~10 s chunks of whole cycles (`HoldRhythm`) and re-issues each the moment it ends, re-issues
immediately after the screen goes off, and holds a partial wake lock so neither is late. **Check it
on a device:** `adb logcat -s MargoWear` (watch) / `-s MargoPresenceSignal` (phone) prints
`hold reissue trigger=screen-off|chunk …`, and the watch's hold-end ack lands `played_ms`,
`reissues` and `screen_off_reissues` in the desktop's `state/presence-signal-delivery.jsonl`.

**Settings → "Presence signal — 'I'm here'"** shows whether a hold is currently running, the **Link**
(connected since / connecting / down — with the reason, verbatim — plus a **Reconnect now** button),
**Last heard** from the desktop, the battery state, a **pattern editor**, two **intensity sliders**
(phone / watch), a Test tap, a Test pattern, and a Stop button. **There is no amplitude above the ceiling** — `PresenceHapticPatterns.
MAX_AMPLITUDE` (130 of 255), the device's own gentle-notification-level haptic — either slider only
picks how close to it a pulse lands.

**The default rhythm is her own (2026-09-18 19:18 CT).** After her first real hold reached her wrist,
she asked for it softer and slower: *"0.4 on, 0.1 off, 0.4 on 0.1 off, repeat"* — 400 ms on, 100 ms
off, named `pulse` and now the default pattern (`slow-breath`/`heartbeat` remain as presets). The two
sliders replace the old `soft`/`normal` toggle, default BELOW its old typical output on purpose
(45/130 phone, 35/130 watch — "softer than today's soft"), and the Presence card's **pattern editor**
lets her type a custom on/off millisecond sequence (`"400,100,400,100"`) that overrides whichever
named pattern a hold carries, on both the phone and (relayed) the watch — a malformed entry is
rejected outright, never silently repaired. **Test pattern** plays the current selection for ~3 s;
**Test tap** still plays a single ping. Full rationale and the exact numbers:
`margo/docs/presence-signal-spec.md` §5.2.

**The watch is the primary surface**, if one is paired (see "The watch" below): the phone relays
every ping/hold event over the same Data Layer link T3 already uses, now carrying the watch's own
amplitude and any custom pattern override too. **A hold now runs the watch's own foreground service**
(`PresenceHoldService`, 2026-09-18) rather than a bare `WearableListenerService` callback — her report
that a hold "doesn't seem to last long on my watch" traced to the vibration and its stop timer having
no protection from Wear OS's freezer once the app left the foreground; a genuine promoted foreground
service does (backed by the OngoingActivity API, so an active hold also shows on the watch face
complication / app chip). It arms its own independently-armed expiry timer, so losing the phone
connection mid-hold doesn't leave the watch buzzing forever. A "Stop presence" button on the watch's
one screen (or the ongoing notification's own Stop action) stops locally and relays the stop back
through the phone. **That relay never actually left the watch until 2026-10-06:** it called the
blocking Data Layer send on the main thread, which Play services refuses — the exception was caught
and logged, and the phone never heard. It now sends from a background thread. **Belt-and-suspenders:** her own fix attempt — turning on "allow run in
background" for the watch app — likely helped for the same reason (keeping the process alive) and is
still worth leaving on, but the foreground promotion above is the actual fix and doesn't depend on it.
**The hold-start relay was dead code until the 2026-09-18 fix** — `WatchBridge.sendHoldStart` existed
but nothing ever called it, so the watch never learned a hold had begun regardless of pairing state;
it's now called from the same spot the phone's own new-hold vibration fires.

**Stop stops everything, everywhere, always (2026-09-19 fix).** A watch Stop tap always stopped the
wrist and relayed a message to the phone — but the phone's `WearListenerService` only ever listened
for the presence/ack paths, so that relay landed nowhere: the phone kept buzzing, its notification
kept running, and the desktop was never told. It now acts on that relay (`WatchBridge.PATH_HOLD_STOP`
→ `PresenceLinkService.stopFromWatchRelay`), which both stops the phone locally AND POSTs
`/presence-signal-stop?stopped_by=watch` to the desktop — the one thing only the phone can do, since
the watch has no endpoint or token of its own. The watch's own stop handling was split into two
paths to avoid a ping-pong once the phone started acting on its relay: a genuinely local stop (the
notification action, the in-app button) still relays outward; a stop relayed FROM the phone now
stops locally only. Full account, including her two verbatim quotes:
`margo/docs/presence-signal-spec.md` §5.3.

**Per-device "sit this out," and a per-hold `--surface` flag (2026-09-19).** Both amplitude sliders
now run `0..130`, and `0` is a real setting — that device plays no ping or hold vibration, but still
tracks the hold and its own Stop control still works (and still stops the signal everywhere).
Independently, `presence_signal.py hold start --surface {both,wrist,phone}` (default `both`) picks
which device(s) a given hold should even try to buzz, from chat — both apps read the relayed
`surface` field and gate their own vibrate call on it, on top of whatever their amplitude slider
already says. Neither knob ever narrows who can Stop a hold. Design and rationale:
`margo/docs/presence-signal-spec.md` §5.3.

**`ping` gained the identical `--surface` flag (2026-09-19).** She asked for a wrist-only ping and it
went to both devices — `ping` had carried no such knob at all while `hold start` already did.
`presence_signal.py ping --surface {both,wrist,phone}` (default `both`) works exactly like the hold
flag above: `PresenceSignalClient.Signal.lastPingSurface` carries it to the phone, which gates its
own ping vibrate on it (`!= "wrist"`) and relays it to the watch (`WatchBridge.sendPing`'s new
`surface` parameter); `PresenceSignalListenerService`'s `PATH_PING` handler on the watch gates its own
vibrate the mirror way (`!= "phone"`). Design and rationale: `margo/docs/presence-signal-spec.md` §5.4.

## Project layout

```
phone/android/
  settings.gradle.kts            module list — :app AND :wear (the T3 companion, below)
  build.gradle.kts               plugin versions (AGP 8.11.1, Kotlin 2.1.20, and the Compose
                                 compiler plugin — which ships WITH Kotlin since 2.0, so its
                                 version must track the Kotlin one EXACTLY or the build fails
                                 with a compatibility error that never names Compose)
  gradle.properties              AndroidX + JVM args
  gradle/wrapper/                the Gradle wrapper (8.14.5) — committed
  gradlew, gradlew.bat           wrapper launchers
  local.properties              (gitignored) SDK path + BuildConfig endpoint/secret SEEDS
  app/
    build.gradle.kts             module config (compileSdk 36 / minSdk 29 / targetSdk 36),
                                 deps, BuildConfig wiring, `buildFeatures { compose = true }`
    proguard-rules.pro
    src/main/
      AndroidManifest.xml
      java/com/kumouri/margo/    Call screening: CallScreeningServiceImpl, BlocklistStore,
                                 BlocklistSyncWorker, Roles. Health: HealthSyncWorker;
                                 ExportWatchWorker + ExportLedger (the Samsung export folder
                                 watcher). Presence: Geofencing, GeofenceBroadcastReceiver, Places,
                                 PresenceStore, PresenceSyncWorker, SleepUpdates, SleepReceiver,
                                 ActivityRecognitionUpdates, ActivityTransitionReceiver.
                                 Text messages: SmsReceiver, SmsStore, SmsSyncWorker,
                                 SmsBackfillWorker, SmsLedger; MessageNotificationListener +
                                 MessageNotificationParser (Phase 2, RCS via notifications).
                                 Presence signal: PresenceLinkService (the always-on push
                                 link + hold chrome), PresenceSignalStream (SSE parsing),
                                 PresenceSignalApplier (the pure apply decision),
                                 PresenceHoldKeeper + HoldRhythm (a hold played in re-issued
                                 chunks), PresenceSignalStore, PresenceSignalClient,
                                 PresenceHapticPatterns, WatchBridge (the phone -> watch relay).
                                 Plumbing: SettingsStore, SyncStatus, EventLog, BootReceiver,
                                 WearListenerService, MainActivity.
        ui/                      **Jetpack Compose — there are no XML layouts.** MargoApp +
                                 AppHost host FOUR screens: StatusScreen, PermissionsScreen,
                                 SettingsScreen, DiagnosticsScreen (+ Theme.kt).
      res/                       drawable, mipmap (launcher icons), values (strings/themes),
                                 xml — **no `layout/` directory**; the UI is Compose.
  wear/                          the Wear OS companion (`:wear`) — see "The watch" below.
```

> Historically this folder shipped **source-only** — you generated an Android Studio project and pasted the
> files in. That step is gone: the Gradle project (wrapper included) is committed, so building is just
> `local.properties` + `./gradlew installDebug`.

### T2 (2026-07-29): the widened feed + the sync manifest

`HealthSyncWorker` now also reads **weight, body fat, HRV (RMSSD), resting HR, active calories, and
hydration**, and runs **hourly** instead of every 3 h (with `ExistingPeriodicWorkPolicy.UPDATE`, so an
already-installed app actually adopts the new cadence rather than honouring its old enqueue).

**More importantly it now reports on itself.** Every run appends a `sync_report` line carrying the
per-type counts it read — *including the zeroes* — plus the read window, app version and granted-
permission count. This exists because the feed had been delivering **one of six record types** (steps
only) for weeks and nothing could tell: the worker posted what it found and recorded nothing about what
it didn't, so a zero and an unwired reader looked identical. See `margo/scripts/HEALTH_SETUP.md` →
"The `sync_report` manifest" and the cockpit's **Health feed** panel.

**⚠️ After installing this build you MUST re-grant Health Connect permissions.** `doWork` requires the
whole set before it ships anything, so until you open the app and approve the new reads, **no sync
happens at all** — not even steps. That's deliberate: a partial grant that silently ships a subset is
the failure this change exists to end.

**Skin temperature is not included** — `SkinTemperatureRecord` doesn't exist in the pinned
`connect-client:1.1.0-alpha07` (verified by compiling). The desktop side is already ready for it, so
it's one reader away whenever that dependency moves.

### Two things the `:wear` build learned the hard way (2026-08-11)

Both shipped green and both were broken on the device, which is the whole lesson: for Android, "it
compiles" is not evidence that it runs.

1. **`AppCompatActivity` + `Theme.DeviceDefault` crashes on launch.** AppCompat throws
   `IllegalStateException: You need to use a Theme.AppCompat theme` from `setContentView`. From the
   wrist it looked like the app opening and instantly closing. Fixed by dropping AppCompat entirely —
   it's a phone-oriented compat layer and a Wear app on `DeviceDefault` shouldn't carry it.
2. **`BODY_SENSORS` is not grantable on Android 16.** For `targetSdk 36` the platform replaced it with
   `android.permission.health.READ_HEART_RATE` ([behaviour
   changes](https://developer.android.com/about/versions/16/behavior-changes-16)), so a
   `checkSelfPermission(BODY_SENSORS)` gate can never pass — "Start sensing" refused forever even after
   approving everything. Fixed by asking for **less**: the module only ever needed
   `ACTIVITY_RECOGNITION` for `UserActivityInfo`, and the heart-rate subscription that dragged
   `BODY_SENSORS` in was data we deliberately discard anyway.

> **Debugging note:** a Galaxy Watch is **locked while charging and off the wrist**, and a locked watch
> silently ignores `adb shell input` and `uiautomator dump`. Taps appear to succeed and nothing
> happens — which reads exactly like a broken app. Put it on, or unlock it, before concluding anything
> from UI automation.

---

## The watch (`:wear`) — T3, 2026-07-30

A Wear OS companion for the Galaxy Watch 8. Build + install it the same way:

```bash
cd phone/android
./gradlew :wear:installDebug     # with the watch paired for debugging
```

Open **Margo** on the watch, grant the sensor permissions, tap **Start sensing**.

**What it does.** Health Services passive monitoring delivers `UserActivityState` — asleep / passive /
active-exercise — derived from **wrist motion and heart rate**. That is categorically better evidence
than the phone's Sleep API, which infers sleep from a handset lying idle and therefore declares you
asleep in the middle of the afternoon.

**How it gets home.** The watch holds **no endpoint and no token**. It sends over the Wearable Data
Layer to the phone (`WearListenerService`), which forwards to the desktop using credentials it already
has. A Galaxy Watch off wifi is the normal case, not the exception — a direct POST would be a feed that
works at home and silently dies everywhere else.

**Why the desktop change matters more than this app.** Both devices write into the same presence event
log, and the rollup used to take whichever event was *newest* — so a watch saying "awake" still lost to
a phone saying "asleep" five minutes later. `presence_common.recompute_context` now ranks a **wrist**
reading above a phone one (falling back when the watch goes stale, so a charging watch never freezes
the snapshot). See `margo/docs/training-staff-spec.md` → T3.

**The presence signal runs the OTHER direction (2026-09-18)** — phone → watch, not watch → phone.
`WatchBridge` (in `:app`) sends every ping/hold event the phone applies to `PresenceSignalListenerService`
(in `:wear`, the module's first `WearableListenerService`), carrying the watch's own amplitude
(`SettingsStore.presenceWatchAmplitude`, relayed from the phone) and any phone-local custom pattern
override. **A hold is handed off to `PresenceHoldService`** (also 2026-09-18, same-day follow-up) — a
genuine foreground service, OngoingActivity-backed, rather than driven inline from the listener
callback; it plays the same haptic waveforms on the wrist and arms its own independent expiry timer —
a lost Data Layer connection mid-hold stops the watch on time regardless of what the phone is doing,
and the foreground promotion means it survives the app leaving the foreground too (the fix for her
report that a hold used to fade out on its own). A "Stop presence" button on the watch's one screen
(or the ongoing notification's own Stop action) relays back through `PhoneBridge` the same way the
existing "Done ✓" ack button does. See `margo/docs/presence-signal-spec.md` §5.2. **Since 2026-10-06
the watch also acks** every ping/hold it acts on (`PhoneBridge.PATH_SIGNAL_ACK` → the phone → the
desktop's `/presence-signal-ack`), and plays a hold through `PresenceHoldKeeper` so it lasts its whole
window — §5.5.

**Not built yet, deliberately:** wrist reminder-acks (the `/margo/ack` path is received but does
nothing — acks must ride the *same* outbox-backed path a Telegram 👍 takes), and quick meal/workout
logging (waits on the health feed actually delivering; see T2). (A watch-side intensity toggle,
formerly listed here, shipped 2026-09-18.)

**Note on `applicationId`:** the watch module deliberately shares `com.kumouri.margo` with the phone
app. The Data Layer pairs a watch app to its phone app by application id **and** signature — they must
match or nothing is delivered.

## App icon + notification icon (2026-09-18)

The launcher icon (`:app` and `:wear`) is Margo's bird avatar, redrawn **perched and watchful**
rather than flying — Ceryce's ask: *"maybe your avatar nesting instead, or just perched, like you're
watching over me or something."* Both modules only ever run on API 26+ (`minSdk 29`), so the adaptive
icon (`res/mipmap-anydpi-v26/ic_launcher*.xml`) is the only launcher-icon shape needed — no legacy
per-density mipmap set.

- **Background** (`res/drawable/ic_launcher_background.xml`, vector): a near-black `#0D0612` field,
  not a flat Kumouri Purple one — the bird's own body is Kumouri Purple, and a purple bird on a purple
  field disappears. The dark field also matches the neon-glow-on-night look the avatar art already
  uses.
- **Foreground** (`res/drawable-xxxhdpi/ic_launcher_foreground.png`, raster, 432×432 = 108dp): the
  perched bird, generated with the Comfy Cloud MCP (`vertexai/nano-banana-pro`, the avatar's
  `margo-avatar-2026-08-24-bird-v2.png` as the style/reference image, background removed with the
  `utility_birefnet_remove_background` template) rather than hand-vectorized — image generation was
  available this session. A single xxxhdpi-bucket asset is deliberate: Android scales it for other
  densities, and a personal sideloaded app doesn't need a full per-density export.
- **Notification small icon** (`res/drawable-xxxhdpi/ic_stat_margo.png`, 96×96 = 24dp): Android
  requires this to be pure white-on-transparent alpha (any colour is discarded and would render as a
  grey blob) — derived from the same generated bird by binarizing its alpha channel to drop the
  reference art's soft glow halo, which would otherwise read as mud at 24dp, leaving a crisp
  perched-bird-on-a-branch silhouette. Wired into `PresenceLinkService`'s "Margo link" / "Margo is
  here" foreground notification (`setSmallIcon`).
- **Notification large icon** (`res/drawable-xxxhdpi/ic_notification_large.png`, 256×256 = 64dp):
  the colour bird composited onto the same `#0D0612` field as the launcher icon, for
  `NotificationCompat.Builder.setLargeIcon` on the same notification.

Only the rendered PNG/vector assets are committed — the avatar source images they were generated from
live outside this repo (`margo/state/avatar/`, gitignored) and are never copied in.
