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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Pulls Margo's blocklist from the Worker's GET /blocklist and caches it locally.
 * Runs hourly (and on demand). URL + bearer secret come from BuildConfig, which
 * reads them from local.properties at build time.
 */
class BlocklistSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Misconfigured (no endpoint baked in) — fail fast instead of retrying forever.
        val settings = SettingsStore(applicationContext)
        if (!settings.blocklistConfigured()) return Result.failure()
        return try {
            val conn = (URL(settings.blocklistUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${settings.blocklistSecret}")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            if (conn.responseCode != 200) return Result.retry()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val numbers = JSONObject(body).getJSONArray("numbers")
            val set = HashSet<String>(numbers.length())
            for (i in 0 until numbers.length()) set.add(numbers.getString(i))
            BlocklistStore(applicationContext).replaceAll(set)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "blocklist-sync"
        private const val UNIQUE_NOW = "blocklist-sync-now"

        private fun connectedConstraints() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<BlocklistSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(connectedConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** The manual tap: unique + KEEP, so a tap while one is pending is a no-op rather than a
         *  second identical fetch (HealthSyncWorker.syncNow has the 2026-09-12 story). */
        fun syncNow(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<BlocklistSyncWorker>()
                .setConstraints(connectedConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
            return request.id
        }
    }
}
