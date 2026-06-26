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

## Prereqs
- Android Studio (you have it).
- Your `BLOCKLIST_SYNC_SECRET` — it's in the repo's `.dev.vars` (gitignored).

## Setup (~15 min)

**1. Create the project.** Android Studio → **New Project → Empty Views Activity**:
- Name: **Margo**, Package name: **`com.kumouri.margo`**, Language: **Kotlin**, Minimum SDK: **API 29**.

**2. Drop in the source.** Copy these files from here into the matching paths of your new project
(overwriting the generated `MainActivity.kt`, `activity_main.xml`, `strings.xml`, and `AndroidManifest.xml`):
```
app/src/main/java/com/kumouri/margo/CallScreeningServiceImpl.kt
app/src/main/java/com/kumouri/margo/BlocklistStore.kt
app/src/main/java/com/kumouri/margo/BlocklistSyncWorker.kt
app/src/main/java/com/kumouri/margo/MainActivity.kt
app/src/main/res/layout/activity_main.xml
app/src/main/res/values/strings.xml
app/src/main/AndroidManifest.xml          ← or just merge the two "MERGE IN" blocks
```

**3. `app/build.gradle.kts` — add the WorkManager dep + BuildConfig wiring.** At the very top, add:
```kotlin
import java.util.Properties
```
Inside `android { ... }`, ensure these exist:
```kotlin
    defaultConfig {
        // ...keep applicationId/minSdk/etc...
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "BLOCKLIST_URL", "\"${localProps.getProperty("BLOCKLIST_URL", "")}\"")
        buildConfigField("String", "BLOCKLIST_SECRET", "\"${localProps.getProperty("BLOCKLIST_SECRET", "")}\"")
    }
    buildFeatures { buildConfig = true }
```
In `dependencies { ... }`, add:
```kotlin
    implementation("androidx.work:work-runtime-ktx:2.9.1")
```
(`androidx.appcompat:appcompat` is already there from the template — `MainActivity` uses it.)

**4. `local.properties` — add your endpoint + secret** (this file is gitignored by Android):
```properties
BLOCKLIST_URL=https://ai-call-screener.will-c-armstrong.workers.dev/blocklist
BLOCKLIST_SECRET=<paste BLOCKLIST_SYNC_SECRET from the repo's .dev.vars>
```

**5. Build + install** on the S23 (USB debugging or wireless): **Run ▶**.

**6. Grant the role.** Open the app → **"Make Margo the screening app"** → approve. (This takes over the
"Caller ID & spam app" role from Samsung Smart Call — only one app can hold it.)

**7. Keep it alive.** Settings → Apps → Margo → Battery → **Unrestricted** (so Samsung doesn't pause the
hourly sync).

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
