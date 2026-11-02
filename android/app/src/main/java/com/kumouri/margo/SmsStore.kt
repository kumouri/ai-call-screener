package com.kumouri.margo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * A small on-device buffer of pending text-message NDJSON lines — [PresenceStore]'s shape, for the
 * same reason: [SmsReceiver] appends the moment a text lands, [SmsSyncWorker] drains and POSTs, and
 * only what was successfully sent is cleared, so a failed POST keeps the message for the next run.
 *
 * Its own preferences file rather than a second key in `presence`, because these lines hold message
 * BODIES and the presence buffer holds place names — a Diagnostics dump of one must never include the
 * other. Private app storage, excluded from backup like every other preference here
 * (`allowBackup=false`), and the buffer is expected to be empty almost always: a text is normally on
 * the wire within seconds of arriving.
 */
class SmsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun append(line: String) {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        arr.put(line)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun drain(): List<String> {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /** Drop the first [count] buffered lines — the ones a successful POST just sent. */
    @Synchronized
    fun clearFirst(count: Int) {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val remaining = JSONArray()
        for (i in count until arr.length()) remaining.put(arr.getString(i))
        prefs.edit().putString(KEY, remaining.toString()).apply()
    }

    @Synchronized
    fun pendingCount(): Int = JSONArray(prefs.getString(KEY, "[]")).length()

    companion object {
        private const val PREFS = "sms"
        private const val KEY = "pending_messages"

        /**
         * The ONE wire shape (`margo/scripts/sms_import.py`'s module docstring is the desktop copy):
         *
         *   {"kind":"sms","direction":"in"|"out","address":…,"body":…,"ts_ms":…,"offset_min":…,
         *    "origin":"receiver"|"backfill","provider_id"?:…,"thread_id"?:…,"sim_slot"?:…,
         *    "ts_sent_ms"?:…}
         *
         * Both senders build their line here so the receiver path and the backfill path cannot
         * drift apart — the desktop dedupes the two copies of one message on this exact shape.
         */
        fun line(
            direction: String,
            address: String,
            body: String,
            tsMs: Long,
            origin: String,
            providerId: Long? = null,
            threadId: Long? = null,
            simSlot: Int? = null,
            tsSentMs: Long? = null,
        ): String {
            val o = JSONObject()
                .put("kind", "sms")
                .put("direction", direction)
                .put("address", address)
                .put("body", body)
                .put("ts_ms", tsMs)
                .put("offset_min", TimeZone.getDefault().getOffset(tsMs) / 60_000)
                .put("origin", origin)
            if (providerId != null) o.put("provider_id", providerId.toString())
            if (threadId != null) o.put("thread_id", threadId.toString())
            if (simSlot != null && simSlot >= 0) o.put("sim_slot", simSlot)
            if (tsSentMs != null && tsSentMs > 0) o.put("ts_sent_ms", tsSentMs)
            return o.toString()
        }

        /**
         * The Phase 2 wire shape — one message read off a messaging app's notification
         * ([MessageNotificationListener]); `sms_import.py`'s docstring is the desktop copy:
         *
         *   {"kind":"notif-msg","app":<package>,"sender":…,"body":…,"ts_ms":…,"offset_min":…,
         *    "conversation"?:…,"is_group"?:…,"sender_source":…,"notif_key"?:…}
         *
         * Always incoming — a notification is for something received. `notif_key` is the
         * conversation's notification key, carried for debugging only: it is reused across messages,
         * so the desktop never treats it as an identity. Rides the same buffer and the same POST as
         * the SMS lines.
         */
        fun notifLine(
            app: String,
            message: MessageNotificationParser.Message,
            notifKey: String? = null,
        ): String {
            val o = JSONObject()
                .put("kind", "notif-msg")
                .put("app", app)
                .put("sender", message.sender)
                .put("body", message.body)
                .put("ts_ms", message.tsMs)
                .put("offset_min", TimeZone.getDefault().getOffset(message.tsMs) / 60_000)
                .put("sender_source", message.senderSource)
            if (message.conversation != null) o.put("conversation", message.conversation)
            if (message.isGroup != null) o.put("is_group", message.isGroup)
            if (!notifKey.isNullOrEmpty()) o.put("notif_key", notifKey)
            return o.toString()
        }
    }
}
