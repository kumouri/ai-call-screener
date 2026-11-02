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
 * POSTs buffered text-message lines ([SmsStore]) as NDJSON to the desktop listener's `/sms-ingest`
 * over the tailnet — [PresenceSyncWorker]'s shape exactly: fires on each received text, plus a
 * ~15-minute periodic flush in case a POST failed. The endpoint is derived from the presence
 * server's origin ([SettingsStore.smsIngestUrl]) and the presence token is the bearer, so there is
 * no second URL to mistype; a blank presence URL, or the switch off, fails fast rather than retrying.
 */
class SmsSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val settings get() = SettingsStore(applicationContext)

    override suspend fun doWork(): Result {
        // Off = nothing to do, not a fault (the periodic tick runs whether or not the switch is on).
        if (!settings.smsEnabled) return Result.success()
        if (!settings.smsConfigured()) return Result.failure()
        val store = SmsStore(applicationContext)
        val ledger = SmsLedger(applicationContext)
        val pending = store.drain()
        if (pending.isEmpty()) return Result.success()
        return try {
            val (ok, detail) = post(settings.smsIngestUrl(), settings.presenceToken, pending)
            ledger.recordFlush(ok, if (ok) pending.size else 0, detail)
            if (ok) {
                store.clearFirst(pending.size)
                EventLog.add(applicationContext, "sms flush: ${pending.size} sent — $detail")
                Result.success()
            } else {
                EventLog.add(applicationContext, "sms flush FAILED: $detail")
                Result.retry()
            }
        } catch (e: Exception) {
            ledger.recordFlush(false, 0, e.message ?: e.javaClass.simpleName)
            EventLog.add(applicationContext, "sms flush FAILED: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "sms-sync"
        private const val UNIQUE_NOW = "sms-sync-now"

        private fun connectedConstraints() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * One POST of NDJSON lines. Returns `(ok, detail)` where `detail` is the wire's answer
         * verbatim — the listener's `{"ok":true,"imported":{…}}` counts on success, or the HTTP
         * status + error body on failure — the same "say what came back" contract
         * [MainActivity.testConnection] follows. Shared with [SmsBackfillWorker].
         */
        fun post(url: String, token: String, lines: List<String>): Pair<Boolean, String> {
            val body = lines.joinToString("\n").toByteArray(Charsets.UTF_8)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-ndjson")
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            return try {
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val text = try {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }?.take(300) ?: ""
                } catch (_: Exception) {
                    ""
                }
                // `json.dumps` spaces after the colon; be tolerant of a listener that doesn't.
                val ok = code == 200 && (text.contains("\"ok\": true") || text.contains("\"ok\":true"))
                Pair(ok, if (ok) text else "HTTP $code $text".trim())
            } finally {
                conn.disconnect()
            }
        }

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmsSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connectedConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Flush now, on a received text — APPEND_OR_REPLACE, for the reason [PresenceSyncWorker.syncNow]
         *  gives: two concurrent flushes both drain and both clear, and the second clear eats a line. */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .setConstraints(connectedConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
