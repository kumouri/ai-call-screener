package com.kumouri.margo

import android.content.Context
import org.json.JSONObject

/**
 * What the last health sync actually did, kept ON THE PHONE so the phone can answer for itself.
 *
 * **Written because of 2026-08-11.** The feed was broken in four independent ways that evening and the
 * phone displayed nothing about any of them — the failures were visible only in `logcat` (needing a
 * cable and a laptop) or in the desktop cockpit (needing the very delivery that was broken). Every
 * fault therefore looked identical from the phone: silence. A worker that reports only to a server it
 * cannot reach reports nothing at all, and the one device that always knows what happened was the one
 * device that never said.
 *
 * So this is deliberately the LOCAL half of `sync_report`. The desktop copy is richer and is the
 * record; this one exists to survive the case where the desktop copy could not be delivered, which is
 * exactly when someone is standing there holding the phone wondering why.
 */
class SyncStatus(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Epoch millis of the last ATTEMPT, delivered or not. 0 = never run. */
    val lastAttemptMs: Long get() = prefs.getLong(KEY_AT, 0L)

    /** Epoch millis of the last SUCCESSFUL delivery. 0 = never delivered. */
    val lastOkMs: Long get() = prefs.getLong(KEY_OK_AT, 0L)

    /**
     * Epoch millis of the last manual "Sync now" tap — the ASK, as distinct from the attempt. 0 = never
     * tapped. A tap is only an enqueue; WorkManager decides when (and whether) it runs, and on
     * 2026-09-12 four taps sat for up to 2.5 h with this screen still showing the run before them.
     * Stamping the ask is what lets the Debug tab say "requested, not yet run" instead of nothing.
     */
    val lastRequestedMs: Long get() = prefs.getLong(KEY_REQUESTED_AT, 0L)

    val ok: Boolean get() = prefs.getBoolean(KEY_OK, false)

    /** Human-readable outcome: `"200 — 19 lines"`, `"HTTP 401 bad or missing bearer token"`, … */
    val detail: String get() = prefs.getString(KEY_DETAIL, "") ?: ""

    /** Per-type counts from the last read window, as `sync_report` records them. */
    fun counts(): Map<String, Int> = parse(prefs.getString(KEY_COUNTS, null))

    /** The 365-day probe: `0` read-OK-but-empty, `-1` read errored, `>0` Health Connect has it. */
    fun probe(): Map<String, Int> = parse(prefs.getString(KEY_PROBE, null))

    /** Permission names the last run found ungranted — the field that named tonight's real cause. */
    fun missingPermissions(): List<String> =
        (prefs.getString(KEY_MISSING, "") ?: "").split('\n').filter { it.isNotBlank() }

    fun record(
        okNow: Boolean,
        detail: String,
        counts: Map<String, Int> = emptyMap(),
        probe: Map<String, Int> = emptyMap(),
        missing: Collection<String> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val e = prefs.edit()
            .putLong(KEY_AT, nowMs)
            .putBoolean(KEY_OK, okNow)
            .putString(KEY_DETAIL, detail)
            .putString(KEY_COUNTS, JSONObject(counts.mapValues { it.value as Any }).toString())
            .putString(KEY_PROBE, JSONObject(probe.mapValues { it.value as Any }).toString())
            .putString(KEY_MISSING, missing.joinToString("\n"))
        // lastOkMs only moves on success, deliberately: "last attempted" and "last actually got
        // through" are different questions, and collapsing them is how a feed that has been failing
        // for a fortnight reads as fresh.
        if (okNow) e.putLong(KEY_OK_AT, nowMs)
        e.apply()
    }

    /** Stamp a manual sync request. Written by [HealthSyncWorker.syncNow], read by the Debug tab. */
    fun recordRequested(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_REQUESTED_AT, nowMs).apply()
    }

    private fun parse(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optInt(it, 0) }
        } catch (_: Exception) {
            emptyMap()   // a corrupt cache is an empty one; it must never crash the screen showing it
        }
    }

    companion object {
        private const val PREFS = "margo_sync_status"
        private const val KEY_AT = "last_attempt_ms"
        private const val KEY_OK_AT = "last_ok_ms"
        private const val KEY_REQUESTED_AT = "last_requested_ms"
        private const val KEY_OK = "ok"
        private const val KEY_DETAIL = "detail"
        private const val KEY_COUNTS = "counts"
        private const val KEY_PROBE = "probe"
        private const val KEY_MISSING = "missing"
    }
}
