package com.kumouri.margo

import android.content.Context

/**
 * What the text-message feed has done, kept ON THE PHONE — [ExportLedger]'s shape, for
 * [ExportLedger]'s reason: the phone is the one device that always knows what happened, and a
 * feed that reports only to a server it could not reach reports nothing at all.
 *
 * Two things live here. **The backfill watermark** — the provider `date` (epoch ms) of the newest
 * `content://sms` row the desktop has confirmed, so each backfill pass reads only what is newer.
 * It advances ONLY after the listener answered 2xx with `ok:true` for the batch that contained the
 * row; a timeout or a 500 leaves it where it was and the next pass re-sends (the desktop's dedupe
 * makes that retry cheap). A watermark of 0 means "never backfilled" and the first pass reads the
 * whole provider, oldest first. **The status** — last receive, last flush, last backfill and what
 * the wire said, which the Settings card shows beside the switch — plus, since Phase 2, the
 * notification listener's last message and its bind/unbind times, which the Access tab reads to
 * tell a lost Notification-access grant from one never given.
 *
 * Counts and timestamps only. No address and no body is ever written here.
 */
class SmsLedger(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Provider `date` (epoch ms) of the newest confirmed backfill row; 0 = never. */
    var watermarkMs: Long
        get() = prefs.getLong(KEY_WATERMARK, 0L)
        set(v) = prefs.edit().putLong(KEY_WATERMARK, v).apply()

    /** Forget the watermark so the next backfill re-reads everything (the desktop dedupes). */
    fun resetWatermark() = prefs.edit().remove(KEY_WATERMARK).apply()

    // --- the receiver path -----------------------------------------------------------------------

    fun recordReceived(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_LAST_RECEIVED_AT, nowMs)
            .putInt(KEY_RECEIVED_COUNT, receivedCount + 1)
            .apply()
    }

    val lastReceivedMs: Long get() = prefs.getLong(KEY_LAST_RECEIVED_AT, 0L)
    val receivedCount: Int get() = prefs.getInt(KEY_RECEIVED_COUNT, 0)

    /** One flush of the receiver buffer: how many lines went, and what the wire said. */
    fun recordFlush(okNow: Boolean, sent: Int, detail: String, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_LAST_FLUSH_AT, nowMs)
            .putBoolean(KEY_FLUSH_OK, okNow)
            .putInt(KEY_LAST_FLUSH_SENT, sent)
            .putString(KEY_FLUSH_DETAIL, detail)
            .apply()
    }

    val lastFlushMs: Long get() = prefs.getLong(KEY_LAST_FLUSH_AT, 0L)
    val flushOk: Boolean get() = prefs.getBoolean(KEY_FLUSH_OK, false)
    val lastFlushSent: Int get() = prefs.getInt(KEY_LAST_FLUSH_SENT, 0)
    val flushDetail: String get() = prefs.getString(KEY_FLUSH_DETAIL, "") ?: ""

    // --- the backfill path -----------------------------------------------------------------------

    /** One backfill pass: rows read from the provider, rows confirmed, and what the wire said. */
    fun recordBackfill(okNow: Boolean, read: Int, confirmed: Int, detail: String,
                       nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_LAST_BACKFILL_AT, nowMs)
            .putBoolean(KEY_BACKFILL_OK, okNow)
            .putInt(KEY_LAST_BACKFILL_READ, read)
            .putInt(KEY_LAST_BACKFILL_CONFIRMED, confirmed)
            .putInt(KEY_BACKFILL_TOTAL, backfillTotal + confirmed)
            .putString(KEY_BACKFILL_DETAIL, detail)
            .apply()
    }

    val lastBackfillMs: Long get() = prefs.getLong(KEY_LAST_BACKFILL_AT, 0L)
    val backfillOk: Boolean get() = prefs.getBoolean(KEY_BACKFILL_OK, false)
    val lastBackfillRead: Int get() = prefs.getInt(KEY_LAST_BACKFILL_READ, 0)
    val lastBackfillConfirmed: Int get() = prefs.getInt(KEY_LAST_BACKFILL_CONFIRMED, 0)
    val backfillTotal: Int get() = prefs.getInt(KEY_BACKFILL_TOTAL, 0)
    val backfillDetail: String get() = prefs.getString(KEY_BACKFILL_DETAIL, "") ?: ""

    // --- the notification listener (Phase 2) -----------------------------------------------------

    /** One allowlisted notification that yielded [messages] new message lines. */
    fun recordNotification(messages: Int, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_LAST_NOTIF_AT, nowMs)
            .putInt(KEY_NOTIF_COUNT, notifCount + messages)
            .apply()
    }

    val lastNotifMs: Long get() = prefs.getLong(KEY_LAST_NOTIF_AT, 0L)
    val notifCount: Int get() = prefs.getInt(KEY_NOTIF_COUNT, 0)

    /** The system bound the listener. Non-zero forever after = "this phone HAD the grant", which is
     *  what lets the Access tab tell a lost grant (red) from one never given (yellow). */
    fun recordListenerConnected(nowMs: Long = System.currentTimeMillis()) =
        prefs.edit().putLong(KEY_LISTENER_CONNECTED_AT, nowMs).apply()

    fun recordListenerDisconnected(nowMs: Long = System.currentTimeMillis()) =
        prefs.edit().putLong(KEY_LISTENER_DISCONNECTED_AT, nowMs).apply()

    val listenerConnectedMs: Long get() = prefs.getLong(KEY_LISTENER_CONNECTED_AT, 0L)
    val listenerDisconnectedMs: Long get() = prefs.getLong(KEY_LISTENER_DISCONNECTED_AT, 0L)

    companion object {
        private const val PREFS = "margo_sms_ledger"
        private const val KEY_LAST_NOTIF_AT = "last_notif_ms"
        private const val KEY_NOTIF_COUNT = "notif_count"
        private const val KEY_LISTENER_CONNECTED_AT = "listener_connected_ms"
        private const val KEY_LISTENER_DISCONNECTED_AT = "listener_disconnected_ms"
        private const val KEY_WATERMARK = "backfill_watermark_ms"
        private const val KEY_LAST_RECEIVED_AT = "last_received_ms"
        private const val KEY_RECEIVED_COUNT = "received_count"
        private const val KEY_LAST_FLUSH_AT = "last_flush_ms"
        private const val KEY_FLUSH_OK = "flush_ok"
        private const val KEY_LAST_FLUSH_SENT = "last_flush_sent"
        private const val KEY_FLUSH_DETAIL = "flush_detail"
        private const val KEY_LAST_BACKFILL_AT = "last_backfill_ms"
        private const val KEY_BACKFILL_OK = "backfill_ok"
        private const val KEY_LAST_BACKFILL_READ = "last_backfill_read"
        private const val KEY_LAST_BACKFILL_CONFIRMED = "last_backfill_confirmed"
        private const val KEY_BACKFILL_TOTAL = "backfill_total"
        private const val KEY_BACKFILL_DETAIL = "backfill_detail"
    }
}
