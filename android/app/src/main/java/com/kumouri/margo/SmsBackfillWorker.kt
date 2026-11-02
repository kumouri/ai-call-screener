package com.kumouri.margo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Reads `content://sms` — inbox AND sent, `type` 1 and 2 — newer than the stored watermark and POSTs
 * the rows in batches to `/sms-ingest`, advancing the watermark only after each batch is confirmed.
 *
 * **Why a backfill exists beside the receiver.** The `SMS_RECEIVED` broadcast is the live edge and
 * carries only INCOMING texts; the provider is the history and carries both sides. A conversation
 * needs both, so the desktop's store is fed from both: the receiver for immediacy, this pass for
 * completeness (outgoing texts, anything that arrived while the switch was off or the phone was
 * without network, and the first-run history). The two copies of one incoming text collapse on the
 * desktop (`sms_common.insert_message`), so re-reading a row is never a duplicate — which is also
 * why the watermark is cheap to reset from Settings.
 *
 * **Read-only, and not the default app.** `READ_SMS` is enough to query the provider; nothing here
 * writes to it or marks anything read. `content://sms` is the platform's public provider, not the
 * raw `mmssms.db` (root-only, and unnecessary). It holds SMS only — RCS lives in Google Messages'
 * own store and is Phase 2's problem (`margo/docs/sms-ingest-spec.md` §6). MMS (`content://mms`)
 * is out of scope in Phase 1 pending Ceryce's answer on attachments (spec §7).
 *
 * Runs once when the switch is turned on with permission held, once daily as a sweep, and on demand
 * from Settings. Batches of [BATCH] rows, oldest first, so a partial pass leaves a coherent prefix
 * behind the watermark rather than holes.
 */
class SmsBackfillWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val settings get() = SettingsStore(applicationContext)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val ledger = SmsLedger(ctx)
        // The switch OFF is not a failed pass, it is no pass: the daily tick fires once on enqueue
        // (app start, every boot) and used to stamp a red "FAILED" into the ledger before the feed
        // had ever been asked for anything — the "Last backfill: FAILED · 26m ago" of the first live
        // run, from a tick at install time. Only the switch ON with nowhere to POST is a fault worth
        // a row, and the row names that one cause.
        if (!settings.smsEnabled) return Result.success()
        settings.smsUnconfiguredReason()?.let { reason ->
            ledger.recordBackfill(false, 0, 0, reason)
            EventLog.add(ctx, "sms backfill skipped: $reason")
            return Result.failure()
        }
        if (!hasReadSms(ctx)) {
            ledger.recordBackfill(false, 0, 0, "READ_SMS not granted")
            EventLog.add(ctx, "sms backfill skipped: READ_SMS not granted")
            return Result.failure()
        }

        val url = settings.smsIngestUrl()
        val token = settings.presenceToken
        var read = 0
        var confirmed = 0
        var lastDetail = "nothing newer than the watermark"
        try {
            while (true) {
                val batch = readBatch(ctx, ledger.watermarkMs, BATCH)
                if (batch.isEmpty()) break
                read += batch.size
                val (ok, detail) = SmsSyncWorker.post(url, token, batch.map { it.line })
                lastDetail = detail
                if (!ok) {
                    ledger.recordBackfill(false, read, confirmed, detail)
                    EventLog.add(ctx, "sms backfill FAILED after $confirmed rows: $detail")
                    return Result.retry()
                }
                confirmed += batch.size
                // Advance only past what the desktop confirmed. `date` is the provider's own ordering
                // column, so the next query is strictly newer than everything in this batch.
                ledger.watermarkMs = batch.last().dateMs
                if (batch.size < BATCH) break
            }
        } catch (e: SecurityException) {
            ledger.recordBackfill(false, read, confirmed, "provider refused: ${e.message}")
            return Result.failure()
        } catch (e: Exception) {
            ledger.recordBackfill(false, read, confirmed, e.message ?: e.javaClass.simpleName)
            return Result.retry()
        }
        ledger.recordBackfill(true, read, confirmed, lastDetail)
        EventLog.add(ctx, "sms backfill: $confirmed rows confirmed" +
            if (confirmed == 0) " (up to date)" else "")
        return Result.success()
    }

    private class Row(val line: String, val dateMs: Long)

    /**
     * Up to [limit] provider rows with `date > sinceMs`, ascending by `date` then `_id` (the tie-break
     * keeps a same-millisecond pair stable across passes). Only `type` 1 (inbox) and 2 (sent) — drafts,
     * outbox, failed and queued rows are not messages that happened.
     */
    private fun readBatch(ctx: Context, sinceMs: Long, limit: Int): List<Row> {
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.SUBSCRIPTION_ID,
        )
        val selection = "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.TYPE} IN (?, ?)"
        val args = arrayOf(
            sinceMs.toString(),
            Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
            Telephony.Sms.MESSAGE_TYPE_SENT.toString(),
        )
        val order = "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC LIMIT $limit"
        val out = ArrayList<Row>(limit)
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, selection, args, order)?.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iThread = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iSent = c.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
            val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val iSub = c.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)
            while (c.moveToNext()) {
                val address = c.getString(iAddr) ?: continue      // a row with no peer is not a message
                val dateMs = c.getLong(iDate)
                val type = c.getInt(iType)
                val line = SmsStore.line(
                    direction = if (type == Telephony.Sms.MESSAGE_TYPE_SENT) "out" else "in",
                    address = address,
                    body = c.getString(iBody) ?: "",
                    tsMs = dateMs,
                    origin = "backfill",
                    providerId = c.getLong(iId),
                    threadId = if (c.isNull(iThread)) null else c.getLong(iThread),
                    simSlot = if (c.isNull(iSub)) null else c.getInt(iSub),
                    tsSentMs = if (c.isNull(iSent)) null else c.getLong(iSent),
                )
                out.add(Row(line, dateMs))
            }
        }
        return out
    }

    companion object {
        private const val UNIQUE_DAILY = "sms-backfill"
        private const val UNIQUE_NOW = "sms-backfill-now"
        const val BATCH = 200

        fun hasReadSms(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED

        private fun connectedConstraints() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** A daily sweep for outgoing texts and anything the receiver missed. KEEP, like the others. */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmsBackfillWorker>(24, TimeUnit.HOURS)
                .setConstraints(connectedConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** The one-shot (switch just turned on) and the on-demand tap. KEEP: a pass already running
         *  will read everything a second one would, and two concurrent passes would both advance the
         *  watermark over each other's batches. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmsBackfillWorker>()
                .setConstraints(connectedConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
