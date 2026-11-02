package com.kumouri.margo

import android.content.Context

/**
 * What the Samsung-export watcher has seen and shipped, kept ON THE PHONE — `SyncStatus`'s shape,
 * for `SyncStatus`'s reason: the phone is the one device that always knows what happened, and a
 * worker that reports only to a server it could not reach reports nothing at all.
 *
 * Two things live here. **The ledger** — which exports have been delivered. A zip is keyed by
 * `name|size|lastModified`, so a re-download with the same name but different bytes is a new file
 * and an unchanged one is never re-sent. Since 2026-09-12 every confirmed export ALSO records its
 * 14-digit Samsung stamp (`stamp:<14 digits>`): that is the durable key for an export FOLDER (a
 * directory has no useful size, and its mtime moves whenever Samsung touches a child), and
 * [newestShippedStamp] is what "newer than the last uploaded one" is measured against. A key is
 * written ONLY after the listener answered 2xx with `ok:true`; a timeout, a 500 or a transport fault
 * leaves the export unmarked so the next scan retries (and the listener's own duplicate path makes
 * that retry cheap). Since 2026-09-12 23:42 the ledger also holds **`superseded:<stamp>` → `<newer
 * stamp>`** — an older pending export the worker never shipped because a newer confirmed one contains
 * it (exports are cumulative); it counts as settled from then on. **The status** — last scan, the
 * newest export seen, the last one shipped (and how many older ones it superseded) and what the wire
 * said — which is what the Settings screen shows beside the folder grant.
 *
 * **The zip step** (2026-09-12). Samsung's export on the S23 is a FOLDER, not a zip, so the worker
 * zips it into the app's cache before shipping; the ledger holds the last folder zipped (size, file
 * count) and the last zip that failed, so a folder that will not zip whole is visible on the card.
 *
 * **The cleanup record** (2026-09-12). A zip the desktop has confirmed is deleted from the folder
 * on the same pass, so the ledger also holds the last file deleted and when, a running count, the
 * last delete the provider refused, and whether cleanup is blocked outright — the persisted grant
 * is read-only (taken before 2026-09-12) and the folder has to be re-chosen for delete to work.
 * **The in-flight marker** (2026-09-12 22:47). The export the worker is zipping or uploading right
 * now, `commit()`ed (not `apply()`ed) before the work starts so it survives the process being killed
 * the next instant; cleared after every attempt. A marker found at the next start is the one trace
 * of a run Android killed mid-zip, and becomes "interrupted — will retry" on the card.
 * Nothing in this class touches a file; it is the record of what the worker read, shipped and removed.
 */
class ExportLedger(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- the ledger ------------------------------------------------------------------------------

    fun key(name: String, size: Long, lastModified: Long): String = "$name|$size|$lastModified"

    fun isShipped(name: String, size: Long, lastModified: Long): Boolean =
        prefs.contains(SHIPPED_PREFIX + key(name, size, lastModified))

    fun markShipped(name: String, size: Long, lastModified: Long,
                    nowMs: Long = System.currentTimeMillis()) {
        val e = prefs.edit()
            .putLong(SHIPPED_PREFIX + key(name, size, lastModified), nowMs)
            .putString(KEY_LAST_SHIPPED_NAME, name)
            .putLong(KEY_LAST_SHIPPED_AT, nowMs)
            .putInt(KEY_LAST_SHIP_SUPERSEDED, 0)
        stampOf(name)?.let { e.putLong(STAMP_PREFIX + it, nowMs) }
        e.apply()
    }

    /**
     * An export FOLDER (zipped on this phone, then shipped) is confirmed by its Samsung stamp alone.
     * `name` is the folder's name, kept for the Settings card's "Last shipped" row; the stamp is the
     * key the next scan checks and the one the desktop's `exports` table is keyed by too.
     */
    fun markFolderShipped(name: String, stamp: String, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(STAMP_PREFIX + stamp, nowMs)
            .putString(KEY_LAST_SHIPPED_NAME, name)
            .putLong(KEY_LAST_SHIPPED_AT, nowMs)
            .putInt(KEY_LAST_SHIP_SUPERSEDED, 0)
            .apply()
    }

    /** Has an export with this 14-digit stamp been confirmed by the desktop — as a zip or a folder? */
    fun isStampShipped(stamp: String): Boolean = prefs.contains(STAMP_PREFIX + stamp)

    /**
     * An older pending export that a newer CONFIRMED export contains (ruling 2026-09-12 23:42:
     * exports are cumulative, so it was never shipped and never will be). Keyed by its own stamp,
     * valued with the stamp that superseded it — so the next scan sees it as settled whatever became
     * of its bytes (deleted, left behind by the zip switch, a refused delete), and the Debug line
     * can still say which export it is inside. Also bumps the count the card shows beside the last
     * ship, since a supersede only ever happens on the pass that shipped the newer one.
     */
    fun markSuperseded(name: String, stamp: String, byStamp: String, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(SUPERSEDED_PREFIX + stamp, byStamp)
            .putString(KEY_LAST_SUPERSEDED_NAME, name)
            .putLong(KEY_LAST_SUPERSEDED_AT, nowMs)
            .putInt(KEY_SUPERSEDED_COUNT, supersededCount + 1)
            .putInt(KEY_LAST_SHIP_SUPERSEDED, lastShipSupersededCount + 1)
            .apply()
    }

    fun isStampSuperseded(stamp: String): Boolean = prefs.contains(SUPERSEDED_PREFIX + stamp)

    /** The stamp of the confirmed export this one was superseded by, or `null` if it never was. */
    fun supersededBy(stamp: String): String? = prefs.getString(SUPERSEDED_PREFIX + stamp, null)

    val lastSupersededName: String get() = prefs.getString(KEY_LAST_SUPERSEDED_NAME, "") ?: ""
    val lastSupersededMs: Long get() = prefs.getLong(KEY_LAST_SUPERSEDED_AT, 0L)
    val supersededCount: Int get() = prefs.getInt(KEY_SUPERSEDED_COUNT, 0)

    /** How many older exports the LAST shipped export superseded — "1 shipped, 2 superseded". */
    val lastShipSupersededCount: Int get() = prefs.getInt(KEY_LAST_SHIP_SUPERSEDED, 0)

    /** The newest confirmed stamp — 14 digits sort lexically as they do chronologically — or `""`
     *  when nothing has ever been confirmed. */
    fun newestShippedStamp(): String =
        prefs.all.keys.filter { it.startsWith(STAMP_PREFIX) }
            .maxOfOrNull { it.removePrefix(STAMP_PREFIX) } ?: ""

    /** How many distinct exports have ever been delivered — a sanity figure for the screen. */
    fun shippedCount(): Int = prefs.all.keys.count { it.startsWith(STAMP_PREFIX) }

    // --- the zip step ----------------------------------------------------------------------------

    /** One export folder zipped into the app's cache, ready to ship. */
    fun recordZipped(name: String, bytes: Long, files: Int, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_LAST_ZIPPED_NAME, name)
            .putLong(KEY_LAST_ZIPPED_AT, nowMs)
            .putLong(KEY_LAST_ZIPPED_BYTES, bytes)
            .putInt(KEY_LAST_ZIPPED_FILES, files)
            .remove(KEY_LAST_ZIP_FAILED)
            .apply()
    }

    /** A folder could not be zipped WHOLE (a child unreadable, the cache full) — nothing partial is
     *  ever shipped, and the failure is shown until a zip succeeds. */
    fun recordZipFailed(name: String, why: String) {
        prefs.edit().putString(KEY_LAST_ZIP_FAILED, "$name — $why").apply()
    }

    /**
     * The export being zipped or uploaded RIGHT NOW (2026-09-12 22:47, the freezer kill). Written
     * before the work starts and cleared after the attempt, whatever its outcome — so a name still
     * here when the next run starts means the process died in between (a kill skips `finally`), and
     * that run records "<name>: interrupted — will retry" as the scan result before retrying it.
     * Never a shipped mark: those are written only on the desktop's `ok:true`.
     */
    fun recordInFlight(name: String) {
        prefs.edit().putString(KEY_IN_FLIGHT, name).commit()
    }

    fun clearInFlight() {
        prefs.edit().remove(KEY_IN_FLIGHT).commit()
    }

    val inFlight: String get() = prefs.getString(KEY_IN_FLIGHT, "") ?: ""

    val lastZippedName: String get() = prefs.getString(KEY_LAST_ZIPPED_NAME, "") ?: ""
    val lastZippedMs: Long get() = prefs.getLong(KEY_LAST_ZIPPED_AT, 0L)
    val lastZippedBytes: Long get() = prefs.getLong(KEY_LAST_ZIPPED_BYTES, 0L)
    val lastZippedFiles: Int get() = prefs.getInt(KEY_LAST_ZIPPED_FILES, 0)
    val lastZipFailed: String get() = prefs.getString(KEY_LAST_ZIP_FAILED, "") ?: ""

    // --- the cleanup record ----------------------------------------------------------------------

    /** One export — a zip, or the whole export folder — removed after the desktop confirmed it. */
    fun recordDeleted(name: String, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_LAST_DELETED_NAME, name)
            .putLong(KEY_LAST_DELETED_AT, nowMs)
            .putInt(KEY_DELETED_COUNT, deletedCount + 1)
            .remove(KEY_LAST_DELETE_FAILED)
            .apply()
    }

    /** The provider refused a delete of a confirmed zip — shown, not hidden, until one succeeds. */
    fun recordDeleteFailed(name: String) {
        prefs.edit().putString(KEY_LAST_DELETE_FAILED, name).apply()
    }

    /** Set once per scan: true when the held grant is read-only, so a confirmed zip cannot be
     *  removed until the folder is re-chosen. The Settings card shows it as a red row. */
    fun setCleanupBlocked(blocked: Boolean) {
        prefs.edit().putBoolean(KEY_CLEANUP_BLOCKED, blocked).apply()
    }

    val cleanupBlocked: Boolean get() = prefs.getBoolean(KEY_CLEANUP_BLOCKED, false)
    val lastDeletedName: String get() = prefs.getString(KEY_LAST_DELETED_NAME, "") ?: ""
    val lastDeletedMs: Long get() = prefs.getLong(KEY_LAST_DELETED_AT, 0L)
    val deletedCount: Int get() = prefs.getInt(KEY_DELETED_COUNT, 0)
    val lastDeleteFailedName: String get() = prefs.getString(KEY_LAST_DELETE_FAILED, "") ?: ""

    // --- the status ------------------------------------------------------------------------------

    /** Epoch millis of the last scan ATTEMPT (folder listed or failed to list). 0 = never. */
    val lastScanMs: Long get() = prefs.getLong(KEY_SCAN_AT, 0L)

    val ok: Boolean get() = prefs.getBoolean(KEY_OK, false)

    /** Human-readable outcome of the last scan: `"2 exports, 1 new — HTTP 200 …"`, `"folder grant
     *  lost"`, `"HTTP 401 bad or missing bearer token"`, … */
    val detail: String get() = prefs.getString(KEY_DETAIL, "") ?: ""

    /** The newest `samsunghealth_*` export (zip or folder) the last scan saw, shipped or not. */
    val lastSeenName: String get() = prefs.getString(KEY_LAST_SEEN_NAME, "") ?: ""
    val lastSeenModifiedMs: Long get() = prefs.getLong(KEY_LAST_SEEN_MODIFIED, 0L)

    val lastShippedName: String get() = prefs.getString(KEY_LAST_SHIPPED_NAME, "") ?: ""
    val lastShippedMs: Long get() = prefs.getLong(KEY_LAST_SHIPPED_AT, 0L)

    fun recordScan(
        okNow: Boolean,
        detail: String,
        newestName: String? = null,
        newestModifiedMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val e = prefs.edit()
            .putLong(KEY_SCAN_AT, nowMs)
            .putBoolean(KEY_OK, okNow)
            .putString(KEY_DETAIL, detail)
        if (newestName != null) {
            e.putString(KEY_LAST_SEEN_NAME, newestName).putLong(KEY_LAST_SEEN_MODIFIED, newestModifiedMs)
        }
        e.apply()
    }

    /** Forget every delivery — used when the folder grant is revoked, so a re-grant re-ships. The
     *  listener treats a re-sent zip as a duplicate, so this costs bandwidth and nothing else. */
    fun clearShipped() {
        val e = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(SHIPPED_PREFIX) || it.startsWith(STAMP_PREFIX) || it.startsWith(SUPERSEDED_PREFIX) }
            .forEach { e.remove(it) }
        e.remove(KEY_LAST_SHIPPED_NAME).remove(KEY_LAST_SHIPPED_AT).remove(KEY_LAST_SHIP_SUPERSEDED).apply()
    }

    companion object {
        private const val PREFS = "margo_export_ledger"
        private const val SHIPPED_PREFIX = "shipped:"
        private const val STAMP_PREFIX = "stamp:"
        private const val SUPERSEDED_PREFIX = "superseded:"
        private const val KEY_LAST_SUPERSEDED_NAME = "last_superseded_name"
        private const val KEY_LAST_SUPERSEDED_AT = "last_superseded_ms"
        private const val KEY_SUPERSEDED_COUNT = "superseded_count"
        private const val KEY_LAST_SHIP_SUPERSEDED = "last_ship_superseded"
        private const val KEY_LAST_ZIPPED_NAME = "last_zipped_name"
        private const val KEY_LAST_ZIPPED_AT = "last_zipped_ms"
        private const val KEY_LAST_ZIPPED_BYTES = "last_zipped_bytes"
        private const val KEY_LAST_ZIPPED_FILES = "last_zipped_files"
        private const val KEY_LAST_ZIP_FAILED = "last_zip_failed"
        private const val KEY_IN_FLIGHT = "in_flight_name"

        private val STAMP_RE = Regex("_(\\d{14})(?:\\.zip)?$", RegexOption.IGNORE_CASE)

        /** The 14-digit local stamp that ends every Samsung export name, zip or folder — the same
         *  group the desktop's `health_common.export_id` extracts. `null` when the name has none. */
        fun stampOf(name: String): String? = STAMP_RE.find(name)?.groupValues?.get(1)
        private const val KEY_SCAN_AT = "last_scan_ms"
        private const val KEY_OK = "ok"
        private const val KEY_DETAIL = "detail"
        private const val KEY_LAST_SEEN_NAME = "last_seen_name"
        private const val KEY_LAST_SEEN_MODIFIED = "last_seen_modified_ms"
        private const val KEY_LAST_SHIPPED_NAME = "last_shipped_name"
        private const val KEY_LAST_SHIPPED_AT = "last_shipped_ms"
        private const val KEY_LAST_DELETED_NAME = "last_deleted_name"
        private const val KEY_LAST_DELETED_AT = "last_deleted_ms"
        private const val KEY_DELETED_COUNT = "deleted_count"
        private const val KEY_LAST_DELETE_FAILED = "last_delete_failed_name"
        private const val KEY_CLEANUP_BLOCKED = "cleanup_blocked"
    }
}
