package com.kumouri.margo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** The verdict of one ship attempt. `internal` (not `private`) so [parseShipResponse] can be
 *  exercised directly from a JVM unit test without an Android runtime. */
internal class Outcome(val ok: Boolean, val retryable: Boolean, val detail: String)

/** How much of the listener's response body [readBounded] will hold in memory. Generous — the
 *  listener's real `ok:true` body lists every table's row count and can run past 600 chars, which
 *  is what silently broke this on 2026-09-13 — but bounded against a runaway or malicious server. */
internal const val MAX_RESPONSE_CHARS = 256 * 1024

/** The listener's "those bytes were not a whole export — re-ship" answer (`health_listener.py`
 *  `EXPORT_RESHIP_STATUS`). The one 4xx that is retryable: the request was fine, the zip was not. */
internal const val HTTP_RESHIP = 422

/**
 * Is [file] a whole zip of exactly [expectedEntries] files, every one under [rootPrefix]? `null` if
 * so, else the reason it is not. Opening a `ZipFile` reads the central directory, the last thing a
 * zip writer writes, so a zip that was never finished, or was cut off, or was replaced by another
 * writer's half-done file, fails here instead of on the desktop. The 2026-09-30 incident shipped
 * three of those (`margo/docs/samsung-export-watcher-spec.md` §12); this is the phone's own check
 * that the file it is about to POST is the one it just finished writing.
 */
internal fun verifyZip(file: File, expectedEntries: Int, rootPrefix: String): String? {
    if (!file.isFile) return "cache zip is gone"
    return try {
        ZipFile(file).use { zf ->
            val n = zf.size()
            if (n != expectedEntries) return "zip holds $n entries but $expectedEntries files were written"
            val stray = zf.entries().asSequence().firstOrNull { !it.name.startsWith(rootPrefix) }
            if (stray != null) "entry ${stray.name} is outside $rootPrefix" else null
        }
    } catch (e: IOException) {
        "not a whole zip (${e.javaClass.simpleName}: ${e.message})"
    }
}

/**
 * Read up to [maxChars] chars of [stream] as text — never the whole thing if the server sends more.
 * The 2026-09-13 bug was a `.take(600)` applied AFTER `readText()` had already read the full body:
 * the cap did nothing for memory and everything for correctness, since it truncated a valid
 * `ok:true` payload into invalid JSON. This caps the READ itself, and the cap is generous enough
 * (256 KB) that no real listener response should ever hit it.
 */
internal fun readBounded(stream: InputStream, maxChars: Int): String {
    val reader = stream.bufferedReader()
    val buf = CharArray(8 * 1024)
    val sb = StringBuilder()
    while (sb.length < maxChars) {
        val n = reader.read(buf, 0, minOf(buf.size, maxChars - sb.length))
        if (n < 0) break
        sb.append(buf, 0, n)
    }
    return sb.toString()
}

/**
 * Turn one ship POST's response into an [Outcome]. [body] must be the FULL body (or as much of it
 * as [readBounded] captured) — never pre-truncated, or a real `ok:true` payload longer than the cut
 * point parses as invalid JSON and a delivered export reads as a failure (2026-09-13: a 98 MB export
 * imported cleanly server-side while the phone re-shipped it hourly forever because of exactly this).
 * `ok` only on a 2xx whose JSON says `ok:true` — the listener's 200 carries either `export_id`/`rows`
 * (imported), `already_imported:true` (its importer had the stamp), or `duplicate:true` (the file was
 * already on disk from an earlier upload whose answer this phone never got). All three mean
 * "delivered"; nothing else does. A body that fails to parse says so honestly — never "did not say
 * ok:true", which is only true of a body that DID parse.
 */
internal fun parseShipResponse(code: Int, body: String): Outcome {
    if (code == HTTP_RESHIP) {
        // The listener received the bytes and they were not a whole export (short, no central
        // directory, wrong entry count). Nothing landed there; zip again and re-ship.
        return Outcome(false, retryable = true,
            "HTTP $code desktop says the upload was not a whole zip — will re-ship: ${body.take(200)}".trim())
    }
    if (code !in 200..299) {
        // Any other 4xx is this phone's mistake (token, name, size) and retrying changes nothing
        // until Settings does; 5xx is the listener's and is worth another pass.
        return Outcome(false, retryable = code >= 500, "HTTP $code ${body.take(200)}".trim())
    }
    val json = try { JSONObject(body) } catch (_: Exception) { null }
        ?: return Outcome(false, true, "HTTP $code body was not valid JSON (len ${body.length}): ${body.take(120)}")
    if (!json.optBoolean("ok", false)) {
        return Outcome(false, true, "HTTP $code but body did not say ok:true: ${body.take(200)}")
    }
    val detail = when {
        json.optBoolean("duplicate", false) -> "HTTP $code duplicate (already on the desktop)"
        json.optBoolean("already_imported", false) ->
            "HTTP $code export ${json.optString("export_id")} already imported"
        else -> "HTTP $code export ${json.optString("export_id")}: ${json.optInt("rows")} rows"
    }
    return Outcome(true, false, detail)
}

/**
 * Ships Samsung Health's "Download personal data" export from the phone to the desktop listener
 * (`health_listener.py`, `POST /health-export-ingest`) the moment it appears — no share sheet, no
 * cable, no second tap.
 *
 * **What this automates and what it cannot.** Samsung has no API and no scheduled export; the export
 * exists only because Ceryce tapped "Download personal data" inside Samsung Health. That tap stays
 * hers. Everything AFTER it is this worker: it lists the folder she granted once (the system picker,
 * `ACTION_OPEN_DOCUMENT_TREE`, persisted read+write), finds the NEWEST export it has not delivered,
 * and streams it to the listener, which lands it and runs the importer. Ceryce chose this over a
 * share-target (2026-09-11): zero extra taps beyond Samsung's own, at the cost of a one-time folder
 * grant. `margo/docs/samsung-export-watcher-spec.md` is the design record.
 *
 * **Samsung's export is a FOLDER, not a zip (ruling 2026-09-12 21:05).** On the S23, "Download
 * personal data" writes `Download/Samsung Health/samsunghealth_<account>_<14-digit stamp>/` holding
 * the `com.samsung.*.csv` tables and a `jsons/<type>/…` tree of per-minute records — it does not zip
 * them. Ceryce: *"detect a new FOLDER in there with a newer timestamp on it than the last uploaded
 * one, zip the folder, send it, and delete the non-zip (deleting the zip should be an option in the
 * options)."* So each scan lists BOTH shapes — `samsunghealth_*_<stamp>.zip` files (the older
 * layout, still shipped as-is) and `samsunghealth_*_<stamp>` directories. A folder is "new" when its
 * stamp is not in the ledger; the 14 digits are the durable key (a directory has no useful size and
 * its mtime moves whenever Samsung touches a child), and `lastModified` is what tells a finished
 * folder from one Samsung is still writing (the settle window, [SETTLE_MS]). A new folder is
 * **zipped on this phone** — streamed file-by-file through `java.util.zip` into the app's own cache
 * (`cacheDir/export-zips/<folder name>.zip`, DEFLATED, members prefixed `<folder name>/…` exactly as
 * Samsung's own zips nest them, so the desktop's `as_export`/`open_table`/`_json_names` read it
 * unchanged) — then shipped by the same POST as a zip, with `X-Export-Filename: <folder name>.zip`.
 * A child that cannot be read fails the WHOLE zip: nothing partial is ever shipped, the failure is
 * named in the ledger and the Debug tab, and the next scan tries again. A zip that does not open
 * whole with exactly the files written is never shipped either ([verifyZip]). The cache zip is
 * deleted after every attempt, shipped or not.
 *
 * **Only the NEWEST pending export is ever shipped — the rest are inside it (ruling 2026-09-12 23:42).**
 * Samsung's exports are cumulative (`health_listener.py`: *"the newest alone rebuilds everything"*), so
 * on the first real run, which found three pending folders and shipped them oldest-first — ~15 min
 * zipping July's 55,565 files before touching the one that mattered — Ceryce ruled: *"They're
 * cumulative, so why ever run any but the newest one? You don't need to say 'export 1 of 3' because
 * export 1 contains exports 2 and 3."* So each scan sorts every export newest-first by the 14-digit
 * stamp (`lastModified` as the tiebreak), zips and POSTs **only the newest pending one**, and never
 * touches an older pending export until that one is CONFIRMED. On confirmation every older pending
 * export is **superseded, not shipped**: the ledger records `superseded:<stamp> → <newest stamp>` (so
 * it stays settled on every later scan, whatever happens to its bytes), the Debug tab gets
 * *"superseded by <stamp> — deleted, not shipped"*, and it is deleted on the same terms as a
 * confirmed one — folder always, zip only behind the switch. If the newest is NOT confirmed, nothing
 * older is touched either. A folder Samsung is still writing (the settle window) is not "newest" yet:
 * the scan waits for it and does not ship the previous one meanwhile. The notification and the
 * scan's result line never say "N of M" — there is no queue, only *"Shipping Samsung Health export
 * <newest folder> — zipping N files → uploading M MB"*, then *"(K older exports superseded)"* after
 * the confirm.
 *
 * **It deletes an export ONLY after the desktop has confirmed it holds it — and nothing else, ever.**
 * Ceryce's ruling, 2026-09-12 20:42: *"It'd be great if it deleted the old ones after it confirmed a
 * successful send of the data."* So the grant is read+write (`FLAG_GRANT_READ_URI_PERMISSION` |
 * `FLAG_GRANT_WRITE_URI_PERMISSION`), and an export is removed from the folder in the same pass that
 * the listener answered 2xx with `ok:true` for it — imported, `already_imported` or `duplicate`
 * (already on the desktop's disk), all three being the desktop saying "I have it". A timeout, a 4xx,
 * a 500 (including "landed but import failed") deletes nothing and leaves the export unmarked so the
 * next scan tries again; the listener's own duplicate check makes that retry cost nothing but the
 * upload. **The export FOLDER is always deleted** after confirmation (recursively — the CSVs, the
 * `jsons/` tree, the directory itself): that is the "delete the non-zip" half of her line. **A zip
 * that was sitting in the folder is deleted only while the Settings switch "Delete zips after
 * confirmed ship" is on** (default on, per the 20:42 line). An export the ledger already holds as
 * shipped — confirmed on an earlier pass, before this build or after a lost answer — is cleaned up
 * on the next scan too: "the old ones" means everything the desktop has confirmed. Anything whose
 * name is not `samsunghealth_*_<14 digits>` or `samsunghealth_*_<14 digits>.zip` is never listed,
 * never shipped, never touched.
 *
 * **A grant taken before 2026-09-12 is read-only and cannot delete.** The worker checks the persisted
 * permission before every pass rather than letting `delete()` fail: shipping proceeds exactly as
 * before, nothing is removed, and the scan result + the Settings card say "re-choose the folder to
 * allow cleanup" in red until she does. A delete the provider refuses is written to the ledger and
 * the event log, never swallowed.
 *
 * What is "done" is recorded in [ExportLedger] on this phone — a zip by name + size + lastModified,
 * every export by its stamp — and ONLY after the listener's `ok:true`. The delete happens after the
 * mark, so a crash between the two re-sends (cheap) rather than re-imports.
 *
 * **Streams; never a whole file in memory.** The September 2026 export is 97 MB as a zip and ~707 MB
 * as a folder of many small JSONs. Zipping copies each child through a 64 KiB buffer straight into
 * the `ZipOutputStream`; shipping uses `setFixedLengthStreamingMode` + the same copy loop, capped at
 * exactly the declared size, and the listener's whole-zip check turns "the file changed while I was
 * reading it" into a retryable 422 rather than a corrupt import. **Every directory — the granted folder included — is listed with ONE
 * `DocumentsContract` cursor** ([listChildren]: id, name, MIME, size, mtime in one projection) and a
 * child is opened by the document id that cursor gave; nothing in the hot loop calls
 * `DocumentFile.length()` / `lastModified()` / `isDirectory`, each of which is its own binder query.
 * A `DocumentFile` is built only for the one call that needs it, the delete.
 *
 * **It runs as a FOREGROUND worker while it has something to zip or ship (2026-09-12 22:47, the
 * freezer kill).** On the S23 the first real run started fine (`scan: 0 zip(s) + 3 folder(s), 3 not
 * yet shipped`), Ceryce left the app, and Android killed the process mid-zip — twice, pids 11512 and
 * 17227: *"Kill app due to repeated failure to freeze binder"*, *"excessive binder traffic during
 * cached"*, then `WM-WorkerWrapper: Work … ExportWatchWorker … was cancelled`. A "Scan now" tap is
 * one-time work that WorkManager's in-process `GreedyScheduler` runs directly while the app is on
 * screen — no `JobService` binds the process — so the moment she leaves, the process is CACHED, the
 * Android 14/15 freezer tries to freeze it, the zip loop's one-open-per-file binder traffic (thousands
 * of small `jsons/<type>/…` records; ~97 MB is minutes) makes that impossible, and the kernel kills it. A
 * ~97 MB export takes minutes and nobody keeps the app on screen that long. So a scan that finds
 * pending work calls `setForeground` with a low-priority persistent notification ("Shipping Samsung
 * Health export … <folder> — zipping N files → uploading → done/failed"), typed
 * `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (manifest: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`
 * and WorkManager's `SystemForegroundService` merged with `foregroundServiceType="dataSync"`, as
 * Android 14+ requires). A foreground service is never cached, so the freezer never sees it. The
 * manual scan is also `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` so a tap runs promptly. A scan
 * that finds nothing pending stays a plain background run — the notification exists for the minutes,
 * not the milliseconds. If Android refuses the promotion (Android 12+ refuses a foreground service
 * started from the background, which is the HOURLY tick's case) the scan logs it and carries on as
 * before: that path runs under a `JobService` binding, which holds the process above cached on its
 * own, so the refusal costs nothing. **A run killed or cancelled mid-zip leaves no partial state:**
 * nothing is marked shipped until the desktop's `ok:true`; the next start sweeps `cacheDir/export-zips/`
 * whole (a kill skips `finally`); and the in-flight marker the ledger holds for the export being
 * worked is read on the next start, which writes *"interrupted — will retry"* as the scan's result
 * and to the Debug tab, then retries it. An in-process stop (`isStopped`) aborts the walk and the
 * upload at the next file rather than finishing a zip nobody will ship.
 *
 * **One run at a time, and it ships only the zip it wrote and verified (2026-09-30, the
 * cut-off-zip incident).** The hourly tick and "Scan now" are different unique-work names, so
 * WorkManager ran two of these side by side. Each swept the cache on start and deleted
 * `<folder>.zip` in `finally`, both zipped to that one path, and each measured and POSTed whatever
 * sat there when it finished: the other run's half-written zip. Three cut-off zips (12 / 82 / 5 MB,
 * no central directory) reached the desktop that night; one run logged "65248 files, 4 MB". Now
 * a process-wide [RUN_LOCK] is taken before anything is touched (a second run stands down and
 * returns success), each attempt zips to its own temp file, and [verifyZip] must find exactly the
 * files written before a byte is POSTed. Those files' count rides as `X-Export-Entries`. The
 * listener checks the zip again and answers a cut-off one with a retryable 422, never `ok`
 * (`margo/docs/samsung-export-watcher-spec.md` §12).
 *
 * Endpoint + token are the health feed's own (`SettingsStore.exportIngestUrl()` derives the route
 * from the health URL — same listener, same bearer token). Runs hourly (network required) and on
 * demand from the Settings screen's "Scan now".
 */
class ExportWatchWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // ONE run at a time, before anything else is touched (2026-09-30). The hourly tick
        // (`export-watch`) and "Scan now" (`export-watch-now`) are different unique-work names, so
        // WorkManager will happily run both at once, and before this lock that is exactly what
        // happened: each run swept `cacheDir/export-zips/` on start and deleted its zip in `finally`,
        // both zipped to the SAME path, and each then measured and POSTed whatever file sat at that
        // path — the other run's half-written zip. Three cut-off zips went to the desktop that
        // night. A run that finds another in progress stands down without touching the ledger, the
        // cache, or the in-flight marker: the one already running owns all three, and its own
        // result line is the one the card should show.
        if (!RUN_LOCK.tryLock()) {
            Log.i(TAG, "another export scan is already running in this process — standing down")
            EventLog.add(applicationContext, "export watch: another scan already running — this one stood down")
            return Result.success()
        }
        return try {
            scanAndShip()
        } finally {
            RUN_LOCK.unlock()
        }
    }

    /** The whole scan, under [RUN_LOCK]. Never call it without holding the lock. */
    private suspend fun scanAndShip(): Result {
        val settings = SettingsStore(applicationContext)
        val ledger = ExportLedger(applicationContext)

        // Not configured is not a fault (SyncStatus's distinction, kept): no listener or no folder
        // grant means there is nothing to do and nothing to retry. Say which, then stop.
        if (!settings.healthConfigured()) {
            ledger.recordScan(false, "health feed is off or has no server — nothing to ship to")
            return Result.failure()
        }
        val treeUri = settings.exportTreeUri
        if (treeUri.isBlank()) {
            ledger.recordScan(false, "no export folder granted yet")
            return Result.failure()
        }
        val url = settings.exportIngestUrl()
        if (url.isBlank()) {
            ledger.recordScan(false, "health server URL does not parse — cannot derive the export route")
            return Result.failure()
        }

        // A previous run that died mid-zip (the freezer kill, a crash, a cancel) skipped its
        // `finally`: say so where the card and the Debug tab can see it, and clear the marker so
        // this run's own record is the one that stands. Nothing was ever marked shipped for it.
        // Under RUN_LOCK a marker here really is a dead run's — never a live sibling's, which is
        // what it was mistaken for on 2026-09-30.
        ledger.inFlight.takeIf { it.isNotBlank() }?.let { name ->
            ledger.clearInFlight()
            ledger.recordScan(false, "$name: interrupted — will retry")
            EventLog.add(applicationContext, "export watch: previous run interrupted mid-zip/ship: $name — will retry")
            Log.w(TAG, "previous run interrupted while working $name — retrying")
        }
        // The cache zip of a killed run outlives its `finally`; a fresh run starts from nothing.
        // Safe only because RUN_LOCK guarantees no other run is writing or uploading one.
        sweepCacheZips()

        return try {
            val uri = Uri.parse(treeUri)
            if (!grantStillHeld(applicationContext, uri)) {
                // The OS revoked or never persisted it (a reinstall, a cleared app, a Files-app
                // change). Nothing to do but say so loudly; MainActivity re-asks.
                ledger.recordScan(false, "folder grant lost — choose the folder again in Settings")
                EventLog.add(applicationContext, "export watch: folder grant lost")
                return Result.failure()
            }
            val tree = DocumentFile.fromTreeUri(applicationContext, uri)
            if (tree == null || !tree.isDirectory || !tree.canRead()) {
                ledger.recordScan(false, "granted folder is unreadable or gone")
                return Result.failure()
            }

            // Only `samsunghealth_*_<stamp>.zip` files and `samsunghealth_*_<stamp>` directories are
            // ever listed — so only those can ever be shipped or deleted. One cursor for the whole
            // folder; size and mtime come from its columns, never from a per-child query. NEWEST
            // FIRST, by the 14-digit stamp across both shapes (a folder and a zip of the same export
            // carry the same digits), mtime as the tiebreak: exports are cumulative, so the head of
            // this list is the only one that carries information (ruling 2026-09-12 23:42).
            val children = listChildren(tree.uri)
            val exports = children
                .filter { if (it.isDir) EXPORT_DIR_NAME.matches(it.name) else EXPORT_NAME.matches(it.name) }
                .sortedWith(
                    compareByDescending<Child> { ExportLedger.stampOf(it.name) ?: "" }
                        .thenByDescending { it.lastModified }
                )
            val total = exports.size
            val newest = exports.firstOrNull()

            // Settled = the desktop holds it (confirmed on this or an earlier pass) OR a newer export
            // the desktop holds contains it (superseded). Only the rest is pending, and of the pending
            // only the newest is ever shipped.
            val (settled, pending) = exports.partition { settled(it, ledger) }
            // A pre-2026-09-12 grant is read-only: it can list and ship but `delete()` would be
            // refused. Decide once per pass, say so once, and never let it stop a ship.
            val canDelete = grantCanWrite(applicationContext, uri)
            val deleteZips = settings.deleteShippedZips
            ledger.setCleanupBlocked(!canDelete)
            Log.i(TAG, "scan: ${exports.count { !it.isDir }} zip(s) + ${exports.count { it.isDir }} folder(s), " +
                "${pending.size} pending (newest ${pending.firstOrNull()?.name ?: "none"}), " +
                "${settled.size} settled earlier, " +
                "newest shipped stamp ${ledger.newestShippedStamp().ifBlank { "none" }}, " +
                "cleanup ${if (!canDelete) "BLOCKED" else if (deleteZips) "on" else "folders only"}")

            // "The old ones" — everything the desktop has confirmed or that a confirmed export
            // supersedes, including one left behind by an earlier pass (this build, a lost answer, a
            // refused delete, the zip switch having been off). Folders always; zips only while the
            // switch is on.
            var deleted = 0
            var deleteFailed = 0
            if (canDelete) {
                for (c in settled) {
                    if (!c.isDir && !deleteZips) continue
                    val by = ledger.supersededBy(ExportLedger.stampOf(c.name)!!)
                    if (deleteConfirmed(c, ledger, supersededBy = by)) deleted++ else deleteFailed++
                }
            }

            if (pending.isEmpty()) {
                val base = if (total == 0) "folder readable; no samsunghealth_* export in it yet"
                    else "$total export(s), nothing pending"
                ledger.recordScan(
                    deleteFailed == 0,
                    base + cleanupNote(canDelete, settled.size, deleted, deleteFailed),
                    newest?.name, newest?.lastModified ?: 0L,
                )
                return if (deleteFailed == 0) Result.success() else Result.retry()
            }

            // The newest pending export is the ONLY one this pass will zip or POST. The older pending
            // ones are held untouched until it is confirmed, then superseded — never shipped.
            val target = pending.first()
            val older = pending.drop(1)
            val name = target.name
            val stamp = ExportLedger.stampOf(name)!!
            val heldNote = if (older.isEmpty()) ""
                else "; ${older.size} older export(s) held — superseded once $name is confirmed"

            // Is the newest finished? A folder Samsung is still writing (the settle window) or a
            // zero-length zip is not "newest" yet: wait for it, and do NOT ship the previous one
            // meanwhile — it would be minutes of work for bytes the newest already contains.
            val waitLine = when {
                target.isDir -> {
                    val now = System.currentTimeMillis()
                    val settledAt = newestWriteIn(target)
                    if (now - settledAt < SETTLE_MS) {
                        val waitMin = ((SETTLE_MS - (now - settledAt)) / 60_000L) + 1
                        "$name: still being written (modified <${SETTLE_MS / 60_000L} min ago), will retry in ~$waitMin min"
                    } else null
                }
                target.size <= 0L ->
                    // A zero-length or unknown-size document is Samsung still writing it, or a
                    // provider that cannot say. Skip WITHOUT marking; next scan sees the real size.
                    "$name: size unknown, will retry"
                else -> null
            }
            if (waitLine != null) {
                ledger.recordScan(
                    false,
                    "$total export(s), ${pending.size} pending — $waitLine" + heldNote +
                        cleanupNote(canDelete, settled.size, deleted, deleteFailed),
                    newest?.name, newest?.lastModified ?: 0L,
                )
                return Result.retry()
            }

            // Something to zip or ship: minutes of work, so hold the process above cached for it.
            val foreground = goForeground("$name — starting")
            val outcome = if (target.isDir) {
                shipFolder(target, stamp, url, settings.healthToken, ledger, foreground)
            } else {
                shipZip(target, url, settings.healthToken, ledger, foreground)
            }
            val lastWire = outcome.detail

            var superseded = 0
            if (outcome.ok) {
                // The desktop holds the newest export. Its own copy goes — "delete the non-zip" is
                // not optional, only the zip half is — and every older pending export is now
                // superseded: recorded as such, never shipped, deleted on the same terms.
                if (canDelete && (target.isDir || deleteZips)) {
                    if (deleteConfirmed(target, ledger)) deleted++ else deleteFailed++
                }
                for (c in older) {
                    ledger.markSuperseded(c.name, ExportLedger.stampOf(c.name)!!, stamp)
                    superseded++
                    EventLog.add(applicationContext, "export superseded: ${c.name} — superseded by $stamp — not shipped")
                    Log.i(TAG, "superseded ${c.name} by $stamp — not shipped")
                    if (canDelete && (c.isDir || deleteZips)) {
                        if (deleteConfirmed(c, ledger, supersededBy = stamp)) deleted++ else deleteFailed++
                    }
                }
            }

            val summary = if (outcome.ok) {
                "$total export(s): 1 shipped, $superseded superseded — $lastWire"
            } else {
                "$total export(s): 0 shipped ($name) — $lastWire" + heldNote
            } + cleanupNote(canDelete, settled.size + (if (outcome.ok) 1 + superseded else 0), deleted, deleteFailed)
            ledger.recordScan(outcome.ok && deleteFailed == 0, summary, newest?.name, newest?.lastModified ?: 0L)
            if (foreground) {
                val supersededNote = if (superseded > 0) " ($superseded older export(s) superseded)" else ""
                postOutcome(if (outcome.ok) "Shipped $name — $lastWire$supersededNote"
                            else "$name not shipped — $lastWire")
            }
            if (outcome.ok && deleteFailed == 0) Result.success()
            else if (outcome.retryable || (outcome.ok && deleteFailed > 0)) Result.retry()
            else Result.failure()
        } catch (e: CancellationException) {
            // WorkManager stopped us (the OS reclaimed the slot, the request was cancelled, the
            // process is going down). Nothing is marked; the marker + the sweep make the next start
            // say "interrupted — will retry". The result of a stopped worker is ignored, so rethrow.
            val name = ledger.inFlight
            ledger.clearInFlight()
            ledger.recordScan(false, "${if (name.isBlank()) "scan" else name}: interrupted — will retry")
            EventLog.add(applicationContext, "export watch: interrupted mid-run — will retry")
            throw e
        } catch (e: SecurityException) {
            ledger.clearInFlight()
            ledger.recordScan(false, "folder grant refused: ${e.message}")
            EventLog.add(applicationContext, "export watch: SecurityException ${e.message}")
            Result.failure()
        } catch (e: Exception) {
            ledger.clearInFlight()
            // Never swallow this (HealthSyncWorker's lesson): a bare retry() is what made a worker
            // that had been failing for weeks indistinguishable from one that had never run.
            Log.e(TAG, "export scan threw — retrying later", e)
            ledger.recordScan(false, "${e.javaClass.simpleName}: ${e.message}")
            EventLog.add(applicationContext, "export watch threw: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    /** What [zipFolder] produced: the zip's byte length and how many files went into it. */
    private class ZipResult(val bytes: Long, val files: Int)

    /** A folder that could not be zipped WHOLE. The message names the child (or the cache) at fault. */
    private class ZipFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** One row of a directory listing, read through a single `DocumentsContract` cursor. */
    private class Child(val uri: Uri, val name: String, val isDir: Boolean, val size: Long, val lastModified: Long)

    private fun cacheZipDir(): File = File(applicationContext.cacheDir, "export-zips").apply { mkdirs() }

    /**
     * Is this export already accounted for — confirmed by the desktop on some pass (by stamp, or for
     * a zip by its `name|size|mtime` key), or superseded by a newer confirmed export that contains it?
     * Either way it is never shipped again; the cleanup pass is all that touches it.
     */
    private fun settled(c: Child, ledger: ExportLedger): Boolean {
        val stamp = ExportLedger.stampOf(c.name) ?: return false
        return ledger.isStampShipped(stamp) || ledger.isStampSuperseded(stamp) ||
            (!c.isDir && ledger.isShipped(c.name, c.size, c.lastModified))
    }

    // --- shipping the one newest export -----------------------------------------------------------

    /** A `samsunghealth_*.zip` already sitting in the folder (the older layout): shipped as it is.
     *  Marks the ledger on `ok:true`; deletes nothing — the caller owns the cleanup. */
    private fun shipZip(file: Child, url: String, token: String, ledger: ExportLedger, foreground: Boolean): Outcome {
        val name = file.name
        val size = file.size
        ledger.recordInFlight(name)
        progress(foreground, "$name — uploading ${size / 1_000_000} MB")
        val outcome = ship(name, size, url, token) {
            applicationContext.contentResolver.openInputStream(file.uri)
        }
        ledger.clearInFlight()
        if (outcome.ok) {
            ledger.markShipped(name, size, file.lastModified)
            EventLog.add(applicationContext, "export shipped: $name (${size / 1_000_000} MB) — ${outcome.detail}")
        } else {
            EventLog.add(applicationContext, "export NOT shipped: $name — ${outcome.detail}")
        }
        return outcome
    }

    /** An export FOLDER: zipped into the app's cache, verified, shipped as `<folder>.zip`, marked on
     *  `ok:true`. A zip that fails or does not verify is reported as a retryable non-delivery and
     *  never POSTed. Deletes nothing but its own cache zip — the caller owns the folder's cleanup.
     *  The cache file is this attempt's alone (a unique temp name, not `<folder>.zip`): RUN_LOCK is
     *  the guarantee that nothing else writes there, and a path no other attempt can compute is the
     *  belt to it, so the bytes POSTed are always the bytes this attempt wrote and verified. */
    private fun shipFolder(dir: Child, stamp: String, url: String, token: String, ledger: ExportLedger, foreground: Boolean): Outcome {
        val name = dir.name
        val zipName = "$name.zip"
        val cacheZip = File.createTempFile("$name.", ".zip", cacheZipDir())
        // The marker is what turns a kill mid-zip into "interrupted — will retry" on the
        // next start instead of a stale "scanning…" and a cache zip nobody owns.
        ledger.recordInFlight(name)
        try {
            val zr = try {
                zipFolder(dir.uri, name, cacheZip) { files ->
                    progress(foreground, "$name — zipping $files files")
                }
            } catch (e: ZipFailure) {
                ledger.recordZipFailed(name, e.message ?: "unknown")
                EventLog.add(applicationContext, "export NOT zipped: $name — ${e.message}")
                Log.w(TAG, "zip $name failed: ${e.message}")
                return Outcome(false, true, "$name: zip failed — ${e.message}")
            }
            // The file about to be POSTed must be the whole zip this attempt just closed: it opens,
            // its central directory lists exactly the files written, all under `<folder>/`. On
            // 2026-09-30 a run reported "65248 files, 4 MB" — its own file count, another run's
            // half-written file's size — and shipped that; this is the check that would have
            // stopped it here instead of on the desktop.
            verifyZip(cacheZip, zr.files, "$name/")?.let { why ->
                ledger.recordZipFailed(name, "zip did not verify — $why")
                EventLog.add(applicationContext, "export NOT shipped: $name — zip did not verify: $why")
                Log.w(TAG, "zip $name did not verify: $why")
                return Outcome(false, true, "$name: zip did not verify — $why")
            }
            ledger.recordZipped(name, zr.bytes, zr.files)
            EventLog.add(applicationContext, "export zipped: $name — ${zr.files} files, ${zr.bytes / 1_000_000} MB (verified)")
            Log.i(TAG, "zipped $name: ${zr.files} files, ${zr.bytes} B, verified")

            progress(foreground, "$name — zipping ${zr.files} files → uploading ${zr.bytes / 1_000_000} MB")
            val outcome = ship(zipName, zr.bytes, url, token, entries = zr.files) { FileInputStream(cacheZip) }
            if (outcome.ok) {
                ledger.markFolderShipped(name, stamp)
                EventLog.add(applicationContext, "export shipped: $zipName (${zr.bytes / 1_000_000} MB) — ${outcome.detail}")
            } else {
                EventLog.add(applicationContext, "export NOT shipped: $zipName — ${outcome.detail}")
            }
            return outcome
        } finally {
            // The app's own copy never outlives the attempt — shipped or not, the folder (or
            // the desktop) is the durable one. (A kill skips this; the next start sweeps.)
            if (cacheZip.exists() && !cacheZip.delete()) {
                Log.w(TAG, "could not remove cache zip ${cacheZip.name}")
            }
            ledger.clearInFlight()
        }
    }

    /** Remove whatever an earlier run left in the cache — a run the OS killed never reached its
     *  `finally`. The folder in the granted tree is the durable copy; nothing here is. */
    private fun sweepCacheZips() {
        val left = cacheZipDir().listFiles().orEmpty()
        for (f in left) {
            if (f.delete()) Log.i(TAG, "swept stale cache zip ${f.name} (${f.length() / 1_000_000} MB)")
            else Log.w(TAG, "could not sweep stale cache zip ${f.name}")
        }
    }

    // --- the foreground run ------------------------------------------------------------------------

    /**
     * Promote this run to a foreground service for as long as it zips/ships. `true` when Android
     * took it. Android 12+ refuses a foreground service started from the background
     * (`ForegroundServiceStartNotAllowedException`, an `IllegalStateException`) — the hourly tick's
     * case, which runs under a `JobService` binding that already keeps the process out of the cached
     * state — so a refusal is logged and the run continues unpromoted rather than failing.
     */
    private suspend fun goForeground(status: String): Boolean = try {
        setForeground(foregroundInfo(status))
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "foreground promotion refused (${e.javaClass.simpleName}: ${e.message}); continuing in the background")
        false
    }

    /** Update the persistent notification's one line. A no-op unless the run was promoted. */
    private fun progress(foreground: Boolean, status: String) {
        if (!foreground) return
        notificationManager().notify(NOTIFICATION_ID, buildNotification(status, ongoing = true))
    }

    /** The foreground notification is gone the instant the worker returns, so the verdict is posted
     *  as a second, dismissible one — the same channel, low priority, auto-cancel. */
    private fun postOutcome(text: String) {
        notificationManager().notify(OUTCOME_NOTIFICATION_ID, buildNotification(text, ongoing = false))
    }

    private fun foregroundInfo(status: String): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID, buildNotification(status, ongoing = true),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    /** WorkManager asks for this when the request is expedited on API < 31, where expedited work
     *  IS a foreground service; the manual scan is expedited, so this must exist (minSdk is 29). */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("scanning the export folder")

    private fun notificationManager(): NotificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun buildNotification(status: String, ongoing: Boolean): Notification {
        val nm = notificationManager()
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Samsung Health export ship", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while an export is being zipped and uploaded to the desktop" }
            )
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Shipping Samsung Health export")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * The newest write this worker can see in an export folder: the directory's own mtime or any
     * top-level child's, whichever is later. A directory's mtime only moves when a DIRECT child is
     * added or removed, so a Samsung still filling `jsons/<type>/<n>/` deep down would look quiet at
     * the top — but Samsung writes the CSV tables at the top level too, and those are the last thing
     * a finished export has. Good enough for a settle window; the listener's importer is the real
     * check, and a folder shipped half-written fails the import (500, `ok:false`) and is never marked.
     * The folder's own mtime is the column the parent's listing already gave; one more cursor lists
     * its children.
     */
    private fun newestWriteIn(dir: Child): Long {
        var newest = dir.lastModified
        for (c in listChildren(dir.uri)) if (c.lastModified > newest) newest = c.lastModified
        return newest
    }

    /**
     * List one directory with ONE content-resolver query. `DocumentFile.listFiles()` issues a query
     * per child and then one more per attribute read (`name`, `isDirectory`, `length()`…); over the
     * export's ~40 `jsons/<type>/` trees of per-minute records that is minutes of IPC for a listing
     * the zipper needs in seconds. `buildChildDocumentsUriUsingTree` is the documented fast path.
     */
    private fun listChildren(dirUri: Uri): List<Child> {
        val resolver = applicationContext.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            dirUri, DocumentsContract.getDocumentId(dirUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Child>()
        val cursor = resolver.query(childrenUri, projection, null, null, null) ?: return out
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                out.add(Child(
                    DocumentsContract.buildDocumentUriUsingTree(dirUri, id), name,
                    mime == DocumentsContract.Document.MIME_TYPE_DIR, size, modified,
                ))
            }
        }
        return out
    }

    /**
     * Stream every file under [dir] into [dest] as `<folderName>/<relative path>` — the layout
     * Samsung's own zips use (one directory at the root), which is exactly what the desktop's
     * `as_export` + substring-matching readers accept. DEFLATED; the September export's JSONs
     * compress ~7:1. Each child is copied through a 64 KiB buffer straight into the zip stream —
     * no file is ever held whole. Any child the resolver cannot open or that fails mid-copy throws
     * [ZipFailure] naming it: a partial zip would import as a partial export and the desktop would
     * confirm it, so the whole attempt fails instead and the half-written cache zip is removed.
     * Binder cost is one cursor per directory plus one `openInputStream` per file — the open is the
     * floor; every attribute the entry needs came from the cursor. [onProgress] is called every
     * [PROGRESS_EVERY] files with the running count, for the notification. A stop request
     * (`isStopped`) is honoured at the next file and reported as a [ZipFailure] like any other
     * reason the zip could not be finished whole.
     */
    private fun zipFolder(dirUri: Uri, folderName: String, dest: File, onProgress: (Int) -> Unit): ZipResult {
        if (dest.exists()) dest.delete()
        var files = 0
        val buf = ByteArray(64 * 1024)
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(dest), 256 * 1024)).use { zos ->
                zos.setMethod(ZipOutputStream.DEFLATED)
                fun walk(uri: Uri, prefix: String) {
                    for (child in listChildren(uri)) {
                        if (isStopped) throw ZipFailure("interrupted — will retry")
                        val rel = prefix + child.name
                        if (child.isDir) {
                            walk(child.uri, "$rel/")
                            continue
                        }
                        val entry = ZipEntry(rel)
                        if (child.lastModified > 0L) entry.time = child.lastModified
                        zos.putNextEntry(entry)
                        val input = try {
                            applicationContext.contentResolver.openInputStream(child.uri)
                        } catch (e: Exception) {
                            throw ZipFailure("$rel: ${e.javaClass.simpleName}: ${e.message}", e)
                        } ?: throw ZipFailure("$rel: content resolver returned no stream")
                        try {
                            input.use { src ->
                                while (true) {
                                    val n = src.read(buf)
                                    if (n < 0) break
                                    zos.write(buf, 0, n)
                                }
                            }
                        } catch (e: IOException) {
                            throw ZipFailure("$rel: ${e.javaClass.simpleName}: ${e.message}", e)
                        }
                        zos.closeEntry()
                        files++
                        if (files % PROGRESS_EVERY == 0) onProgress(files)
                    }
                }
                walk(dirUri, "$folderName/")
            }
        } catch (e: ZipFailure) {
            dest.delete()
            throw e
        } catch (e: IOException) {
            // The cache side: disk full, the zip stream itself. Named as such, not as a child.
            dest.delete()
            throw ZipFailure("cache zip: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        if (files == 0) {
            dest.delete()
            throw ZipFailure("folder is empty — nothing to ship")
        }
        return ZipResult(dest.length(), files)
    }

    /**
     * Remove one export the desktop has confirmed it holds — a zip, or the whole export folder
     * (Samsung's CSVs + the `jsons/` tree + the directory). Called ONLY for a document whose ledger
     * key was written on an `ok:true` answer, and only when the persisted grant carries write. The
     * name is re-checked against [EXPORT_NAME] / [EXPORT_DIR_NAME] as a belt-and-braces: the listing
     * already filtered on it, but a delete is the one call here that cannot be taken back. A refusal
     * (provider says no, document already gone, `SecurityException`) is reported to the ledger and
     * the Debug tab, never hidden. The `DocumentFile` is built here, for this one call — a
     * `fromTreeUri` on a child document URI (API 24+) gives a tree-backed file that can delete and,
     * if the provider refuses the directory whole, list — nothing else in the run needs one.
     * [supersededBy] names the newer confirmed stamp when this export was never shipped but is
     * contained in one that was (ruling 2026-09-12 23:42); the Debug line says so.
     */
    private fun deleteConfirmed(child: Child, ledger: ExportLedger, supersededBy: String? = null): Boolean {
        val name = child.name
        val isDir = child.isDir
        val shape = if (isDir) EXPORT_DIR_NAME else EXPORT_NAME
        if (!shape.matches(name)) {
            Log.w(TAG, "refusing to delete a non-export: $name")
            return false
        }
        val doc = DocumentFile.fromTreeUri(applicationContext, child.uri)
        if (doc == null) {
            Log.w(TAG, "delete $name: no DocumentFile for ${child.uri}")
            ledger.recordDeleteFailed(name)
            return false
        }
        val gone = try { deleteTree(doc) } catch (e: SecurityException) {
            Log.w(TAG, "delete $name refused: ${e.message}")
            false
        }
        val what = if (isDir) "folder" else "zip"
        val why = if (supersededBy == null) "after confirmed ship" else "superseded by $supersededBy"
        if (gone) {
            ledger.recordDeleted(name)
            val line = if (supersededBy == null) "deleted $what after confirmed ship: $name"
                else "deleted $what: $name — superseded by $supersededBy — deleted, not shipped"
            EventLog.add(applicationContext, line)
            Log.i(TAG, line)
        } else {
            ledger.recordDeleteFailed(name)
            EventLog.add(applicationContext, "delete FAILED $why: $name — provider refused; $what still in the folder")
            Log.w(TAG, "delete FAILED $why: $name")
        }
        return gone
    }

    /**
     * `DocumentsContract.deleteDocument` on the external-storage provider removes a directory and
     * everything under it in one call; if a provider refuses the directory whole, fall back to
     * children-first so a folder with one stubborn child is not left as a husk. Success = gone.
     */
    private fun deleteTree(doc: DocumentFile): Boolean {
        if (doc.delete()) return true
        if (!doc.exists()) return true
        if (doc.isDirectory) {
            for (child in doc.listFiles()) deleteTree(child)
            if (doc.delete()) return true
        }
        return !doc.exists()
    }

    /** The cleanup half of a scan's one-line result, appended to the shipping half. */
    private fun cleanupNote(canDelete: Boolean, confirmed: Int, deleted: Int, failed: Int): String = when {
        !canDelete && confirmed > 0 ->
            "; $confirmed confirmed export(s) left in place — " + CLEANUP_BLOCKED_LINE
        !canDelete -> "; " + CLEANUP_BLOCKED_LINE
        failed > 0 -> "; $deleted deleted, $failed delete(s) FAILED (see Debug)"
        deleted > 0 -> "; $deleted deleted after confirmed ship"
        else -> ""
    }

    /**
     * One zip, one POST — the bytes come from [open] (a content-resolver stream for a zip in the
     * folder, a `FileInputStream` for one zipped into the cache). `ok` only on a 2xx whose JSON says
     * `ok:true` — the listener's 200 carries either `export_id`/`rows` (imported),
     * `already_imported:true` (its importer had the stamp), or `duplicate:true` (the file was already
     * on disk from an earlier upload whose answer this phone never got). All three mean "delivered";
     * nothing else does. Exactly [size] bytes are read and sent — never more, and a source that
     * ends sooner is an error here rather than a short body on the wire. [entries], when known (a
     * zip this phone made), rides as `X-Export-Entries` so the listener can check the count too.
     */
    private fun ship(name: String, size: Long, url: String, token: String, entries: Int? = null,
                     open: () -> InputStream?): Outcome {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(size)          // stream; never buffer the zip in memory
            setRequestProperty("Content-Type", "application/zip")
            setRequestProperty("X-Export-Filename", name)
            setRequestProperty("X-Export-Size", size.toString())
            if (entries != null) setRequestProperty("X-Export-Entries", entries.toString())
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15_000
            // The listener imports SYNCHRONOUSLY before answering, and a 97 MB export with ~40
            // per-record JSON directories is minutes. If this still expires, the file stays unmarked
            // and the next scan's re-send lands on the listener's duplicate path — so a long wait
            // here is cheaper than a short one that guarantees a second upload.
            readTimeout = IMPORT_WAIT_MS
        }
        return try {
            val input = open()
                ?: return Outcome(false, true, "$name: content resolver returned no stream")
            var sent = 0L
            input.use { src ->
                conn.outputStream.use { dst ->
                    val buf = ByteArray(64 * 1024)
                    while (sent < size) {
                        // A stop mid-upload: abandon the stream. The listener sees a short body,
                        // drops its `.part`, and nothing here is marked.
                        if (isStopped) throw IOException("interrupted — will retry")
                        val n = src.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
                        if (n < 0) break
                        dst.write(buf, 0, n)
                        sent += n
                    }
                    // The file shrank (or was swapped) under us: say so, rather than let the
                    // fixed-length stream fail with a less useful message.
                    if (sent != size) throw IOException("source ended at $sent of $size bytes — not shipped")
                }
            }
            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.let { readBounded(it, MAX_RESPONSE_CHARS) }.orEmpty()
            } catch (_: Exception) { "" }
            val outcome = parseShipResponse(code, body)
            if (outcome.ok) Log.i(TAG, "POST $name ($sent B) -> ${outcome.detail}")
            else Log.w(TAG, "POST $name -> HTTP $code ${outcome.detail}")
            outcome
        } catch (e: Exception) {
            // The line that was missing on 2026-08-11: a connect timeout, an unreachable host and a
            // DNS failure all used to vanish into retry() with nothing written down.
            Log.w(TAG, "POST $name failed: ${e.javaClass.simpleName}: ${e.message}")
            Outcome(false, true, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "MargoExport"
        private const val UNIQUE = "export-watch"
        private const val UNIQUE_NOW = "export-watch-now"
        private const val IMPORT_WAIT_MS = 10 * 60 * 1000

        /** Single-flight for the whole worker, across BOTH unique-work names (the hourly tick and
         *  "Scan now"), which WorkManager otherwise runs side by side. Process-wide is enough:
         *  WorkManager runs this worker in the app's one process, and a process that dies takes
         *  the lock with it, so nothing can be left holding it. `tryLock`, never `lock` — a second
         *  run stands down rather than queueing to re-zip what the first is already shipping. */
        private val RUN_LOCK = Mutex()

        /** The foreground run's notification channel + ids (the persistent one, and the verdict
         *  posted after it). `POST_NOTIFICATIONS` (Android 13+) decides whether she SEES it; the
         *  foreground service itself does not depend on it — the Task-Manager chip shows regardless. */
        const val CHANNEL_ID = "export-ship"
        private const val NOTIFICATION_ID = 0x5348   // "SH"
        private const val OUTCOME_NOTIFICATION_ID = NOTIFICATION_ID + 1

        /** How many files between notification updates while zipping. ~40 per-record directories of
         *  per-minute JSONs are thousands of files; a `notify` per file would be its own binder storm. */
        private const val PROGRESS_EVERY = 250

        /** How quiet an export FOLDER must be (no write to it or its top-level children) before it is
         *  taken as finished and zipped. Samsung writes the folder over a minute or two; an hourly
         *  scan lands well outside this, and a "Scan now" inside it says "still being written". */
        const val SETTLE_MS = 3 * 60 * 1000L

        /** Samsung's naming for the zip layout: `samsunghealth_<account>_<14-digit local stamp>.zip`.
         *  Mirrors `health_common.EXPORT_RE` on the desktop, tightened to a whole-name match. */
        val EXPORT_NAME = Regex("^samsunghealth_.+_\\d{14}\\.zip$", RegexOption.IGNORE_CASE)

        /** The same name without `.zip`: the export FOLDER "Download personal data" writes on the
         *  S23 (2026-09-12). `<folder>.zip` is what the listener's `_EXPORT_NAME_RE` then admits. */
        val EXPORT_DIR_NAME = Regex("^samsunghealth_.+_\\d{14}$", RegexOption.IGNORE_CASE)

        /** `Download/Samsung Health` on the primary volume — where Samsung Health writes the export.
         *  Offered to the picker as its starting location; harmless if the provider ignores it. */
        val SUGGESTED_FOLDER: Uri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2FSamsung%20Health"
        )

        /** The one sentence the ledger, the Debug tab and the Settings card all use for a grant that
         *  can list and ship but not delete — a grant taken before 2026-09-12, when it was read-only. */
        const val CLEANUP_BLOCKED_LINE = "re-choose the folder to allow cleanup"

        private const val GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /** Is the persisted read grant for [tree] still held? The OS can drop one (reinstall, storage
         *  changes), and a `SecurityException` mid-scan is a worse way to find out than asking. */
        fun grantStillHeld(context: Context, tree: Uri): Boolean =
            context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

        /** Does the persisted grant for [tree] carry WRITE — i.e. can the watcher delete a confirmed
         *  export? A grant taken before 2026-09-12 was read-only and answers false here until she
         *  re-chooses the folder; shipping still works on it, only cleanup does not. */
        fun grantCanWrite(context: Context, tree: Uri): Boolean =
            context.contentResolver.persistedUriPermissions.any {
                it.uri == tree && it.isReadPermission && it.isWritePermission
            }

        /**
         * Persist the grant the picker returned and remember the folder. Read+write since
         * 2026-09-12 — write is what lets a confirmed export be deleted. `ACTION_OPEN_DOCUMENT_TREE`
         * always offers both, but a provider that hands back read-only is taken as read-only rather
         * than refused: shipping is worth more than cleanup, and the card says which one she has.
         */
        fun takeGrant(context: Context, tree: Uri) {
            val resolver = context.contentResolver
            try {
                resolver.takePersistableUriPermission(tree, GRANT_FLAGS)
            } catch (e: SecurityException) {
                Log.w(TAG, "write grant refused for $tree (${e.message}); taking read-only")
                resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            SettingsStore(context).exportTreeUri = tree.toString()
        }

        /** Release the grant and forget the folder + the ledger. Whatever is in the folder stays. */
        fun releaseGrant(context: Context) {
            val settings = SettingsStore(context)
            val held = settings.exportTreeUri
            if (held.isNotBlank()) {
                val uri = Uri.parse(held)
                val flags = if (grantCanWrite(context, uri)) GRANT_FLAGS else Intent.FLAG_GRANT_READ_URI_PERMISSION
                try {
                    context.contentResolver.releasePersistableUriPermission(uri, flags)
                } catch (_: Exception) {
                    // Already gone is fine; forgetting it is the point.
                }
            }
            settings.exportTreeUri = ""
            ExportLedger(context).clearShipped()
        }

        private fun connectedConstraints() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ExportWatchWorker>(1, TimeUnit.HOURS)
                .setConstraints(connectedConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** The manual scan: unique + KEEP, so a tap while one is pending is a no-op rather than a
         *  second scan of the same folder (HealthSyncWorker.syncNow has the 2026-09-12 story).
         *  Expedited so a tap runs promptly rather than at the scheduler's leisure; out of quota it
         *  degrades to ordinary work rather than being refused. The foreground promotion is the
         *  worker's own (`goForeground`), taken only once it knows there is something to ship. */
        fun scanNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ExportWatchWorker>()
                .setConstraints(connectedConstraints())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
