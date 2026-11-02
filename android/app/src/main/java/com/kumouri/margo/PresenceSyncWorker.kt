package com.kumouri.margo

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * POSTs buffered presence events (geofence enter/exit; later activity + sleep) as NDJSON to the desktop
 * listener's `/presence-ingest` over the tailnet. Fires on each geofence event, plus a ~15-minute periodic
 * flush in case a POST failed. Endpoint + optional bearer token come from BuildConfig (local.properties);
 * a blank endpoint fails fast instead of retrying forever (same contract as the other workers).
 */
class PresenceSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    /** Re-read per use, so a settings change takes effect on the next run rather than the next boot. */
    private val settings get() = SettingsStore(applicationContext)

    override suspend fun doWork(): Result {
        if (!settings.presenceConfigured()) return Result.failure()
        val store = PresenceStore(applicationContext)
        val pending = store.drain()
        if (pending.isEmpty()) return Result.success()
        return try {
            if (post(pending)) {
                store.clearFirst(pending.size)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun post(lines: List<String>): Boolean {
        val body = lines.joinToString("\n").toByteArray(Charsets.UTF_8)
        val conn = (URL(settings.presenceUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-ndjson")
            if (settings.presenceToken.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer ${settings.presenceToken}")
            }
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        return try {
            conn.outputStream.use { it.write(body) }
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val UNIQUE = "presence-sync"
        private const val UNIQUE_NOW = "presence-sync-now"

        private fun connectedConstraints() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<PresenceSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connectedConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * Flush now, on an edge. **Unique, APPEND_OR_REPLACE** rather than the manual taps' KEEP:
         * a plain `enqueue` let a burst of edges stack ~12 concurrent instances (logcat, 2026-09-12),
         * and two concurrent flushes both [PresenceStore.drain] the same lines, both POST them, and
         * both [PresenceStore.clearFirst] — the second clear drops anything appended in between.
         * Serializing removes that. KEEP would too, but an edge that lands while a flush is RUNNING
         * would then wait for the 15-minute periodic; appending one follow-up run (which drains
         * whatever the running one did not) costs a no-op worker and ships the edge now.
         */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<PresenceSyncWorker>()
                .setConstraints(connectedConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
