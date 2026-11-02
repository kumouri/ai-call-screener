package com.kumouri.margo

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Reads Samsung Health data out of Health Connect and POSTs it as NDJSON to the desktop listener
 * (health_listener.py) over the tailnet. This is the "genuinely automatic" feed — no manual export tap.
 *
 * Runs every ~1 hour (and on demand; T2 tightened it from 3 h, and uses UPDATE rather than KEEP
 * so an installed app actually picks the new cadence up instead of honouring the old enqueue). The
 * on-demand tap is its own unique work ([UNIQUE_NOW], KEEP): one pending manual sync at a time, so
 * a tap that WorkManager is still holding cannot be stacked into N identical uploads — see [syncNow]
 * for the 2026-09-12 triple-POST that made this a rule, and `DiagnosticsScreen` for how a held tap
 * shows itself as *waiting* rather than as silence. Endpoint + optional bearer token come from
 * BuildConfig, which reads them from local.properties at build time (see the android README).
 * Idempotent: every record carries Health Connect's stable id, and the desktop upserts on it, so
 * overlapping re-reads never duplicate. We deliberately re-read the last GRACE_DAYS on each run so
 * same-day step totals stay correct.
 *
 * Also reads ExerciseSessionRecord (workouts) and NutritionRecord (meals). A workout's calories/distance
 * aren't fields on the session record itself -- Health Connect stores them as separate
 * TotalCaloriesBurnedRecord / DistanceRecord entries covering the same interval -- so we read those too
 * and fold in whatever overlaps a given session's [start, end).
 *
 * The wire format is documented in margo/scripts/health_import.py (import_ndjson). Nothing here reads or
 * sends anything but Ceryce's own health data to her own desktop.
 */
class HealthSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Not configured yet (no tailnet endpoint baked in) — fail fast instead of retrying forever.
        val settings = SettingsStore(applicationContext)
        if (!settings.healthConfigured()) {
            // Not a fault: a fresh install has no server yet, and a disabled feed is a choice. Either
            // way there is nothing to retry, so this is `failure` (terminal) rather than `retry`.
            val why = if (!settings.healthEnabled) "health feed is turned off"
                      else "no health server configured"
            Log.w(TAG, why)
            SyncStatus(applicationContext).record(false, why)
            return Result.failure()
        }
        val sdkStatus = HealthConnectClient.getSdkStatus(applicationContext)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            Log.w(TAG, "Health Connect unavailable (sdkStatus=$sdkStatus)")
            return Result.failure()
        }
        val client = HealthConnectClient.getOrCreate(applicationContext)

        val granted = client.permissionController.getGrantedPermissions()
        val missing = REQUESTED_PERMISSIONS - granted

        // Ship WHAT WE HAVE, and report the gap — do not demand the whole set.
        //
        // This was `containsAll(READ_PERMISSIONS)` → return early, and the reasoning was that a partial
        // grant silently shipping a subset is the failure T2 exists to end. `sync_report` inverted that
        // reasoning: a partial grant is no longer silent, because the manifest names exactly which
        // permissions are missing. So all-or-nothing now buys nothing and costs everything — one
        // ungranted permission kills the entire feed INCLUDING the report that would explain why, which
        // is the very silent death it was meant to prevent. (It nearly did: T2 widened this set without
        // declaring the six new permissions in the manifest, so `containsAll` could never have been
        // true.)
        //
        // Only a completely empty grant is a genuine "she hasn't been through the permission flow yet",
        // and that still exits quietly rather than retry-spamming — MainActivity is what asks.
        if (granted.none { it in READ_PERMISSIONS }) {
            Log.w(TAG, "no health read permissions granted yet — MainActivity asks; not retrying")
            return Result.success()
        }
        if (missing.isNotEmpty()) {
            Log.i(TAG, "running with ${READ_PERMISSIONS.size - missing.size}/${READ_PERMISSIONS.size} " +
                "permissions; ungranted: " + missing.joinToString { it.substringAfterLast('.') })
        }

        return try {
            val prefs = applicationContext.getSharedPreferences("health_sync", Context.MODE_PRIVATE)
            val now = Instant.now()
            val watermark = Instant.ofEpochMilli(
                prefs.getLong("watermark_ms", now.minus(30, ChronoUnit.DAYS).toEpochMilli())
            )
            // Re-read a couple days of overlap so same-day step totals and late sleep edits stay correct.
            val since = watermark.minus(GRACE_DAYS, ChronoUnit.DAYS)
            val range = TimeRangeFilter.between(since, now)

            // T2: every reader reports how many lines it produced, so a type that yields NOTHING is
            // recorded as a zero instead of vanishing (see `syncReport` below).
            val lines = ArrayList<String>()
            val counts = LinkedHashMap<String, Int>()
            suspend fun tally(type: String, block: suspend () -> Unit) {
                val before = lines.size
                try {
                    block()
                } catch (e: Exception) {
                    // One unavailable record type must never cost the whole batch — a device or Health
                    // Connect version without SkinTemperatureRecord should still ship sleep. -1
                    // distinguishes "the read ERRORED" from "the read returned nothing"; those are
                    // different problems and collapsing them is how the last failure hid.
                    counts[type] = -1
                    return
                }
                counts[type] = lines.size - before
            }

            tally("sleep_session") { readSleep(client, range, lines) }
            tally("hr") { readHeartRate(client, range, lines) }
            tally("spo2") { readSpo2(client, range, lines) }
            // Steps go out as per-local-date totals, so their window starts at local midnight. With
            // `since` (a clock time) the oldest day only counted the steps after that time, and each
            // sync re-sent a finished day smaller than the last (2026-10-27; the desktop now keeps the
            // max per day, `health_import._live_steps`, and this stops sending the partial at all).
            val stepsRange = TimeRangeFilter.between(stepsWindowStart(since, ZoneId.systemDefault()), now)
            tally("steps") { readSteps(client, stepsRange, lines) }
            tally("workout") { readWorkouts(client, range, lines) }
            tally("nutrition") { readNutrition(client, range, lines) }
            // T2's widened set.
            tally("weight") { readWeight(client, range, lines) }
            tally("body_fat") { readBodyFat(client, range, lines) }
            tally("hrv") { readHrv(client, range, lines) }
            tally("resting_hr") { readRestingHr(client, range, lines) }
            tally("active_calories") { readActiveCalories(client, range, lines) }
            tally("hydration") { readHydration(client, range, lines) }

            // The manifest goes LAST so it describes the batch it travels with, and it is appended
            // UNCONDITIONALLY — including when every reader came back empty.
            //
            // That is the whole point. The old worker returned early on an empty batch, so a fully
            // broken feed sent nothing and looked exactly like a quiet day; the live feed delivered
            // one of six record types for three weeks and no surface anywhere could tell. A run that
            // reads nothing must now be a run that SAYS it read nothing.
            // Count the INTERSECTION, not `granted.size` — the latter includes any unrelated
            // permission and could report 16 of 14, in the one field whose whole job is to
            // be diagnostically exact.
            // The wide probe runs even when the incremental window found plenty — it's cheap, and its
            // value is precisely in the runs where something looks wrong.
            val probe = try {
                probeAll(client, now)
            } catch (e: Exception) {
                emptyMap()
            }
            // REQUESTED_PERMISSIONS on BOTH sides of the fraction. `permissions_requested` became
            // REQUESTED (15) when the background-read capability joined it, while this stayed READ
            // (14 data types) — so a fully-granted phone reported `14/15 granted, missing=[]`, and the
            // cockpit's `granted < requested` test rendered a permanent "Permissions incomplete" alarm
            // naming nothing. A numerator and a denominator counting different sets is a false alarm
            // that can never be cleared, which is worse than no alarm: it trains her to ignore the panel.
            lines.add(syncReport(counts, probe, since, now,
                                 granted.count { it in REQUESTED_PERMISSIONS }, missing))

            Log.i(TAG, "read window $since .. $now -> ${lines.size} lines; " +
                counts.entries.joinToString { "${it.key}=${it.value}" })

            if (!post(lines, settings.healthUrl, settings.healthToken)) {
                // WHY it failed is logged inside post(); this is just the verdict.
                Log.w(TAG, "POST failed — retrying later; watermark NOT advanced")
                SyncStatus(applicationContext)
                    .record(false, lastWireResult.ifBlank { "delivery failed" }, counts, probe, missing)
                EventLog.add(applicationContext, "sync FAILED: ${lastWireResult.ifBlank { "no response" }}")
                return Result.retry()
            }
            prefs.edit().putLong("watermark_ms", now.toEpochMilli())
                .putLong("last_sync_ms", System.currentTimeMillis()).apply()
            Log.i(TAG, "sync OK — ${lines.size} lines delivered, watermark advanced")
            SyncStatus(applicationContext)
                .record(true, "HTTP 200 — ${lines.size} lines delivered", counts, probe, missing)
            EventLog.add(applicationContext, "sync ok: ${lines.size} lines")
            Result.success()
        } catch (e: Exception) {
            // Never swallow this. A bare `catch (_: Exception) { retry() }` is what made a worker that
            // had been failing for weeks indistinguishable from one that had never run.
            Log.e(TAG, "sync threw — retrying later", e)
            SyncStatus(applicationContext)
                .record(false, "${e.javaClass.simpleName}: ${e.message}")
            EventLog.add(applicationContext, "sync threw: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    // --- readers: each appends NDJSON lines matching import_ndjson's record shapes ---

    private suspend fun readSleep(c: HealthConnectClient, range: TimeRangeFilter, out: MutableList<String>) {
        val records = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records
        for (r in records) {
            val off = offsetMinutes(r.startZoneOffset, r.startTime)
            val stages = JSONArray()
            for (s in r.stages) {
                val name = STAGE_NAMES[s.stage] ?: continue
                stages.put(JSONArray().put(name).put(s.startTime.toEpochMilli()).put(s.endTime.toEpochMilli()))
            }
            out.add(
                JSONObject()
                    .put("t", "sleep_session").put("uuid", r.metadata.id)
                    .put("start_ms", r.startTime.toEpochMilli()).put("end_ms", r.endTime.toEpochMilli())
                    .put("offset_min", off).put("stages", stages).toString()
            )
        }
    }

    private suspend fun readHeartRate(c: HealthConnectClient, range: TimeRangeFilter, out: MutableList<String>) {
        val records = c.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records
        for (r in records) {
            val off = offsetMinutes(r.startZoneOffset, r.startTime)
            for (s in r.samples) {
                out.add(
                    JSONObject()
                        .put("t", "hr").put("uuid", "${r.metadata.id}:${s.time.toEpochMilli()}")
                        .put("start_ms", s.time.toEpochMilli()).put("offset_min", off)
                        .put("bpm", s.beatsPerMinute).toString()
                )
            }
        }
    }

    private suspend fun readSpo2(c: HealthConnectClient, range: TimeRangeFilter, out: MutableList<String>) {
        val records = c.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range)).records
        for (r in records) {
            out.add(
                JSONObject()
                    .put("t", "spo2").put("uuid", r.metadata.id)
                    .put("start_ms", r.time.toEpochMilli()).put("offset_min", offsetMinutes(r.zoneOffset, r.time))
                    .put("pct", r.percentage.value).toString()
            )
        }
    }

    private suspend fun readSteps(c: HealthConnectClient, range: TimeRangeFilter, out: MutableList<String>) {
        val records = c.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records
        // steps_daily is keyed by local date, so sum the interval records into per-date totals.
        val byDate = HashMap<String, Long>()
        for (r in records) {
            val zone = r.startZoneOffset ?: ZoneId.systemDefault().rules.getOffset(r.startTime)
            val date = r.startTime.atZone(zone).toLocalDate().toString()
            byDate[date] = (byDate[date] ?: 0L) + r.count
        }
        for ((date, count) in byDate) {
            out.add(JSONObject().put("t", "steps").put("date", date).put("count", count).toString())
        }
    }

    private suspend fun readWorkouts(c: HealthConnectClient, range: TimeRangeFilter, out: MutableList<String>) {
        val sessions = c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records
        if (sessions.isEmpty()) return
        // Calories/distance aren't fields on the session -- they're separate records over the same
        // interval -- so pull both once for the whole range and fold in whatever overlaps each session.
        val calories = c.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, range)).records
        val distances = c.readRecords(ReadRecordsRequest(DistanceRecord::class, range)).records
        for (r in sessions) {
            val off = offsetMinutes(r.startZoneOffset, r.startTime)
            val kcal = calories.filter { overlaps(it.startTime, it.endTime, r.startTime, r.endTime) }
                .sumOf { it.energy.inKilocalories }
            val meters = distances.filter { overlaps(it.startTime, it.endTime, r.startTime, r.endTime) }
                .sumOf { it.distance.inMeters }
            val obj = JSONObject()
                .put("t", "workout").put("uuid", r.metadata.id)
                .put("start_ms", r.startTime.toEpochMilli()).put("end_ms", r.endTime.toEpochMilli())
                .put("offset_min", off)
                .put("exercise_type", ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[r.exerciseType] ?: "unknown")
            r.title?.let { obj.put("title", it) }
            r.notes?.let { obj.put("notes", it) }
            if (kcal > 0.0) obj.put("energy_kcal", kcal)
            if (meters > 0.0) obj.put("distance_m", meters)
            out.add(obj.toString())
        }
    }

    private suspend fun readNutrition(c: HealthConnectClient, range: TimeRangeFilter, out: MutableList<String>) {
        val records = c.readRecords(ReadRecordsRequest(NutritionRecord::class, range)).records
        for (r in records) {
            val off = offsetMinutes(r.startZoneOffset, r.startTime)
            val obj = JSONObject()
                .put("t", "nutrition").put("uuid", r.metadata.id)
                .put("start_ms", r.startTime.toEpochMilli()).put("end_ms", r.endTime.toEpochMilli())
                .put("offset_min", off)
                .put("meal_type", MealType.MEAL_TYPE_INT_TO_STRING_MAP[r.mealType] ?: "unknown")
            r.name?.let { obj.put("name", it) }
            r.energy?.let { obj.put("energy_kcal", it.inKilocalories) }
            r.protein?.let { obj.put("protein_g", it.inGrams) }
            r.totalCarbohydrate?.let { obj.put("carbs_g", it.inGrams) }
            r.totalFat?.let { obj.put("fat_g", it.inGrams) }
            out.add(obj.toString())
        }
    }

    // --- T2's widened readers (margo/docs/training-staff-spec.md) ---------------------------------
    // Each mirrors a table in health_common.SCHEMA and a `_LIVE` entry in health_import.py. The
    // instantaneous ones share `instantLine`; the rest are one-offs because their value accessors and
    // units differ.

    /** The shared shape for "one timestamped scalar, keyed by Health Connect's stable record id". */
    private fun instantLine(t: String, id: String, at: Instant, off: Int, key: String, value: Double) =
        JSONObject().put("t", t).put("uuid", id).put("start_ms", at.toEpochMilli())
            .put("offset_min", off).put(key, value).toString()

    private suspend fun readWeight(c: HealthConnectClient, range: TimeRangeFilter, out: MutableList<String>) {
        for (r in c.readRecords(ReadRecordsRequest(WeightRecord::class, range)).records) {
            out.add(instantLine("weight", r.metadata.id, r.time,
                offsetMinutes(r.zoneOffset, r.time), "kg", r.weight.inKilograms))
        }
    }

    /**
     * BodyFatRecord — the signal Milo's context calls truer than the scale, and the input that turns
     * the BMR estimate into a Katch-McArdle calculation rather than a coefficient argument
     * (training-staff-spec.md "The kcal question"). Percent as Health Connect reports it; never derived.
     */
    private suspend fun readBodyFat(c: HealthConnectClient, range: TimeRangeFilter, out: MutableList<String>) {
        for (r in c.readRecords(ReadRecordsRequest(BodyFatRecord::class, range)).records) {
            out.add(instantLine("body_fat", r.metadata.id, r.time,
                offsetMinutes(r.zoneOffset, r.time), "pct", r.percentage.value))
        }
    }

    private suspend fun readHrv(c: HealthConnectClient, range: TimeRangeFilter, out: MutableList<String>) {
        for (r in c.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, range)).records) {
            out.add(instantLine("hrv", r.metadata.id, r.time,
                offsetMinutes(r.zoneOffset, r.time), "rmssd_ms", r.heartRateVariabilityMillis))
        }
    }

    private suspend fun readRestingHr(c: HealthConnectClient, range: TimeRangeFilter, out: MutableList<String>) {
        for (r in c.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range)).records) {
            out.add(instantLine("resting_hr", r.metadata.id, r.time,
                offsetMinutes(r.zoneOffset, r.time), "bpm", r.beatsPerMinute.toDouble()))
        }
    }

    private suspend fun readHydration(c: HealthConnectClient, range: TimeRangeFilter, out: MutableList<String>) {
        for (r in c.readRecords(ReadRecordsRequest(HydrationRecord::class, range)).records) {
            out.add(instantLine("hydration", r.metadata.id, r.startTime,
                offsetMinutes(r.startZoneOffset, r.startTime), "liters", r.volume.inLiters))
        }
    }

    // NO skin-temperature reader, deliberately. `SkinTemperatureRecord` does not exist in the pinned
    // `androidx.health.connect:connect-client:1.1.0-alpha07` — verified by compiling, not assumed — and
    // bumping an alpha dependency for the least valuable of T2's seven fields is not a change worth
    // making as a side effect of this one. The desktop keeps its `skin_temp` table and live import path
    // (they predate T2 and cost nothing), so enabling this later is a client bump plus a reader here and
    // no schema work. Until then the cockpit's feed panel honestly shows skin temp as "not yet".

    /**
     * Whole-day active burn, summed per LOCAL date by the phone — same per-date keying as steps, so the
     * deliberate re-read of recent days upserts instead of accumulating. Deliberately not the same
     * thing as a workout's `energy_kcal`, which covers one session.
     */
    private suspend fun readActiveCalories(c: HealthConnectClient, range: TimeRangeFilter, out: MutableList<String>) {
        val records = c.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, range)).records
        val byDate = HashMap<String, Double>()
        for (r in records) {
            val zone = r.startZoneOffset ?: ZoneId.systemDefault().rules.getOffset(r.startTime)
            val date = r.startTime.atZone(zone).toLocalDate().toString()
            byDate[date] = (byDate[date] ?: 0.0) + r.energy.inKilocalories
        }
        for ((date, kcal) in byDate) {
            out.add(JSONObject().put("t", "active_calories").put("date", date).put("kcal", kcal).toString())
        }
    }

    /**
     * **The discriminating test.** For each record type, does Health Connect hold ANY record of it at
     * all, over a deliberately wide window?
     *
     * T2 established that the live feed was delivering steps and nothing else, and concluded Samsung
     * Health must not be syncing the other categories. That conclusion was *inferred*, not proven, and
     * a competing explanation was never ruled out: the incremental read window. A zero in the sync
     * window is consistent with BOTH "Health Connect has no such data" and "our window logic is wrong",
     * and those need completely different fixes — one is a toggle on the phone, the other is a bug here.
     *
     * So probe wide and cheap. `pageSize = 1` means we ask only "does one exist", not "give me a year of
     * heart rate" — a handful of tiny queries, once per run. Then:
     *
     *   window 0 + probe 0  -> Health Connect genuinely has none. Samsung isn't writing it. Phone fix.
     *   window 0 + probe >0 -> the data is RIGHT THERE and the incremental read is missing it. Our bug.
     *
     * That turns a months-old guess into a fact, which is the entire job of this pass.
     */
    private suspend fun probeAll(c: HealthConnectClient, now: Instant): Map<String, Int> {
        val wide = TimeRangeFilter.between(now.minus(PROBE_DAYS, ChronoUnit.DAYS), now)
        val out = LinkedHashMap<String, Int>()
        suspend fun probe(name: String, block: suspend () -> Boolean) {
            out[name] = try {
                if (block()) 1 else 0
            } catch (e: Exception) {
                -1  // same convention as `counts`: -1 is "the read errored", not "found nothing"
            }
        }
        suspend fun <T : androidx.health.connect.client.records.Record> any(
            kls: kotlin.reflect.KClass<T>,
        ): Boolean = c.readRecords(ReadRecordsRequest(kls, wide, pageSize = 1)).records.isNotEmpty()

        probe("sleep_session") { any(SleepSessionRecord::class) }
        probe("hr") { any(HeartRateRecord::class) }
        probe("spo2") { any(OxygenSaturationRecord::class) }
        probe("steps") { any(StepsRecord::class) }
        probe("workout") { any(ExerciseSessionRecord::class) }
        probe("nutrition") { any(NutritionRecord::class) }
        probe("weight") { any(WeightRecord::class) }
        probe("body_fat") { any(BodyFatRecord::class) }
        probe("hrv") { any(HeartRateVariabilityRmssdRecord::class) }
        probe("resting_hr") { any(RestingHeartRateRecord::class) }
        probe("active_calories") { any(ActiveCaloriesBurnedRecord::class) }
        probe("hydration") { any(HydrationRecord::class) }
        return out
    }

    /**
     * The per-run manifest — T2's actual fix. Carries the per-type counts (including zeroes and the -1
     * "read errored" marker), the read window, the app version, and how many permissions were granted.
     *
     * `report_id` is derived from the run's end instant so a retried POST of the same batch dedupes on
     * the desktop (`sync_report`'s primary key) rather than piling up phantom runs.
     */
    private fun syncReport(
        counts: Map<String, Int>,
        probe: Map<String, Int>,
        from: Instant,
        to: Instant,
        grantedCount: Int,
        missing: Set<String> = emptySet(),
    ): String {
        val c = JSONObject()
        for ((k, v) in counts) c.put(k, v)
        val p = JSONObject()
        for ((k, v) in probe) p.put(k, v)
        return JSONObject()
            .put("t", "sync_report")
            .put("report_id", "hs-${to.toEpochMilli()}")
            .put("ran_utc", to.toString())
            .put("window_from", from.toString())
            .put("window_to", to.toString())
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("permissions_granted", grantedCount)
            .put("permissions_requested", REQUESTED_PERMISSIONS.size)
            .put("counts", c)
            .put("probe_days", PROBE_DAYS)
            .put("probe", p)
            // Name the ungranted permissions, not just a count. "13 of 14" sends her hunting;
            // "READ_BODY_FAT is not granted" is something she can act on in one tap.
            .put("missing_permissions", JSONArray().apply { missing.forEach { put(it) } })
            .toString()
    }

    /** Half-open interval overlap: does [aStart, aEnd) share any instant with [bStart, bEnd)? */
    private fun overlaps(aStart: Instant, aEnd: Instant, bStart: Instant, bEnd: Instant): Boolean =
        aStart.isBefore(bEnd) && bStart.isBefore(aEnd)

    /** What the wire actually said on the last attempt, so `doWork` can record it. A field rather
     *  than a return value because `post` is a Boolean by contract and three call sites read it. */
    private var lastWireResult: String = ""

    private fun post(lines: List<String>, url: String, token: String): Boolean {
        val body = lines.joinToString("\n").toByteArray(Charsets.UTF_8)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-ndjson")
            if (token.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        return try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.readText()?.take(200)
                } catch (e: Exception) { null }
                Log.w(TAG, "POST $url -> HTTP $code ${err ?: ""}")
                lastWireResult = "HTTP $code ${err.orEmpty()}".trim()
            } else {
                Log.d(TAG, "POST $url -> 200 (${body.size} B)")
                lastWireResult = "HTTP 200"
            }
            code == 200
        } catch (e: Exception) {
            // THE line that was missing. A connect timeout, an unreachable host, a DNS failure — all of
            // it used to vanish into `Result.retry()` with nothing written down. Tailscale being off on
            // the phone looked exactly like Samsung not syncing.
            Log.w(TAG, "POST $url failed: ${e.javaClass.simpleName}: ${e.message}")
            lastWireResult = "${e.javaClass.simpleName}: ${e.message}"
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun offsetMinutes(offset: ZoneOffset?, at: Instant): Int =
        (offset ?: ZoneId.systemDefault().rules.getOffset(at)).totalSeconds / 60

    companion object {
        private const val TAG = "MargoHealth"
        /** The periodic feed's unique name. */
        const val UNIQUE = "health-sync"

        /** The manual tap's unique name — separate, so a tap never replaces or is swallowed by the
         *  periodic schedule, and so the Debug tab can ask WorkManager about the tap specifically. */
        const val UNIQUE_NOW = "health-sync-now"
        private const val GRACE_DAYS = 2L

        /** The steps read's window start: [since] floored to local midnight in [zone], so every date
         *  the steps reader emits is a whole day's total rather than the tail after [since]. */
        internal fun stepsWindowStart(since: Instant, zone: ZoneId): Instant =
            since.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

        /** How far back the "does Health Connect hold ANY of this?" probe looks. Wide on purpose: the
         *  question is existence, not recency, and `pageSize = 1` keeps it cheap regardless. */
        private const val PROBE_DAYS = 365L

        // Samsung Health -> Health Connect stage codes we care about.
        private val STAGE_NAMES = mapOf(
            SleepSessionRecord.STAGE_TYPE_DEEP to "deep",
            SleepSessionRecord.STAGE_TYPE_LIGHT to "light",
            SleepSessionRecord.STAGE_TYPE_REM to "rem",
            SleepSessionRecord.STAGE_TYPE_SLEEPING to "light",
            SleepSessionRecord.STAGE_TYPE_AWAKE to "awake",
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to "awake"
        )

        /**
         * The **data types** this worker reads. `counts` and `probe` iterate exactly this set, so it
         * holds record types only — never a capability permission (see [BACKGROUND_READ_PERMISSION]).
         *
         * An earlier comment here said `doWork` requires ALL of it before shipping anything. It no
         * longer does, and the reasoning is at the gate itself: one ungranted permission used to kill
         * the feed including the report that would have explained why.
         */
        val READ_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            // T2's widened set (margo/docs/training-staff-spec.md). MainActivity requests exactly this
            // set, and `doWork` requires ALL of it before shipping anything -- so adding a permission
            // here means Ceryce must re-approve on the phone before ANY sync resumes. That is the
            // intended trade: a partial grant that silently ships a subset is what T2 exists to end.
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(HydrationRecord::class)
        )

        /**
         * The permission that makes this worker possible at all — and the one that was declared in the
         * manifest but never *asked for*, so it was never granted.
         *
         * Health Connect lets a **foregrounded** app read anything it holds a read permission for. A
         * **backgrounded** one may read only the data it wrote itself, unless it also holds this. This
         * worker is WorkManager — it is, by construction, always backgrounded. So every read of
         * Samsung's data threw:
         *
         *     SecurityException: Caller does not have permission to read data for the following
         *     (recordType: HydrationRecord) from other applications.
         *
         * which `_live_dated_scalar`'s per-type catch turned into `-1`, a whole feed of them. The tell
         * was that the only two clean probes in 22 runs were the two that happened to fire while the
         * app was open on screen; every backgrounded run after came back -1 across the board.
         *
         * It is a **capability**, not a record type, so it stays out of [READ_PERMISSIONS] — the counts
         * and probe loops must not try to read a record type named `READ_HEALTH_DATA_IN_BACKGROUND`.
         * It belongs in [REQUESTED_PERMISSIONS], which is what gets asked for and what `missing` is
         * measured against, so an ungranted one now names itself in every `sync_report`.
         *
         * The literal string is deliberate: it is what the manifest declares and what
         * `getGrantedPermissions()` returns, so the two cannot drift apart.
         */
        const val BACKGROUND_READ_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

        /** Everything MainActivity asks Ceryce to approve: every data type, plus the background read. */
        val REQUESTED_PERMISSIONS: Set<String> = READ_PERMISSIONS + BACKGROUND_READ_PERMISSION

        private fun connectedConstraints() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(
                SettingsStore(context).healthIntervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(connectedConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * The manual "Sync now" tap. **Unique, KEEP** — a tap while one is already pending is a no-op,
         * which is what "sync now" means. [SyncStatus.recordRequested] stamps the ask so the Debug tab
         * can show a request that WorkManager has not yet honoured.
         *
         * It was a plain `enqueue` until 2026-09-12, when four taps over two hours (18:19, 18:20,
         * 18:23, 20:39) sat unrun and then fired as THREE concurrent workers at 20:45:26. Each read
         * the same window (the watermark only advances after a POST) and each shipped the same
         * 2.25 MB — three 200s on the listener within two seconds. Why they sat that long is not
         * proven; Samsung's app-freezing is the suspect (the network constraint was satisfied). This
         * fixes the stacking regardless of the cause.
         */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setConstraints(connectedConstraints())
                .build()
            SyncStatus(context).recordRequested()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
