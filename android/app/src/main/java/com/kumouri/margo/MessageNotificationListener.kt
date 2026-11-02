package com.kumouri.margo

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * The text feed's Phase 2 (`margo/docs/sms-ingest-spec.md` §6): reads incoming messages off the
 * notifications messaging apps post, so RCS — which never reaches `content://sms` — lands in the same
 * store as the SMS Phase 1 reads.
 *
 * **Why a notification listener.** Google Messages keeps RCS chats in its own store, unreadable to any
 * other app; the notification it posts is the only door RCS opens. The same door reaches Signal and
 * WhatsApp, whose messages were never in the SMS provider at all. Bound by the system once Ceryce
 * grants **Notification access** (a Settings page, like "Allow all the time" — not an in-app dialog;
 * the Access tab links to it).
 *
 * **What it reads.** Every app's notifications arrive here; everything outside
 * [SettingsStore.notifPackages] (default: Google Messages only) is returned from on the first line,
 * unread. An allowlisted notification is copied into a plain [MessageNotificationParser.Snapshot] and
 * parsed by the pure [MessageNotificationParser], which is where every decision lives and is tested.
 * Never dismisses, snoozes, replies to or otherwise touches a notification — it only reads.
 *
 * **Gated on the same consent as Phase 1.** The **Text messages** switch (default OFF) must be on
 * and the presence server set, exactly like [SmsReceiver]; a granted Notification access alone ships
 * nothing. Lines go into the same [SmsStore] buffer and out through the same [SmsSyncWorker] POST.
 * The ledger gets counts only — no sender, no body.
 *
 * **Reliability (§6.4).** The system can unbind a listener (an update, battery optimisation); on
 * [onListenerDisconnected] it records the time and asks to be rebound, and the Access tab shows the
 * grant red when it is gone — the way the export card shows a lost folder grant. Opening the app
 * also requests a rebind ([requestRebindIfGranted]).
 */
class MessageNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        SmsLedger(this).recordListenerConnected()
        EventLog.add(this, "notification listener connected")
        // Whatever is on screen right now is the only history a notification has (§6.4) — read it
        // once; the desktop's exact dedupe makes a re-read on every reconnect free.
        val active = try {
            activeNotifications
        } catch (e: Exception) {
            Log.w(TAG, "could not read active notifications: ${e.message}")
            null
        }
        active?.forEach { handle(it) }
    }

    override fun onListenerDisconnected() {
        SmsLedger(this).recordListenerDisconnected()
        EventLog.add(this, "notification listener disconnected — rebind requested")
        try {
            requestRebind(component(this))
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind failed: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    private fun handle(sbn: StatusBarNotification) {
        val settings = SettingsStore(this)
        val app = sbn.packageName ?: return
        if (app !in settings.notifPackages) return
        if (!settings.smsConfigured()) return

        val messages = try {
            MessageNotificationParser.parse(snapshot(sbn))
        } catch (e: Exception) {
            // A malformed notification from one app must never take the listener down.
            Log.w(TAG, "could not parse a notification from $app: ${e.message}")
            return
        }
        val fresh = messages.filter { recent.firstTime(app, it) }
        if (fresh.isEmpty()) return

        val store = SmsStore(this)
        fresh.forEach { store.append(SmsStore.notifLine(app, it, sbn.key)) }
        SmsLedger(this).recordNotification(fresh.size)
        EventLog.add(this, "notification: ${fresh.size} message${if (fresh.size == 1) "" else "s"} " +
            "from ${SettingsStore.notifAppLabel(app)} — queued")
        SmsSyncWorker.syncNow(this)
    }

    companion object {
        private const val TAG = "MsgNotifListener"

        /** Survives across notifications for as long as the process lives; see [MessageNotificationParser.RecentKeys]. */
        private val recent = MessageNotificationParser.RecentKeys()

        fun component(context: Context) = ComponentName(context, MessageNotificationListener::class.java)

        /** Whether Ceryce has granted Notification access to this listener right now. */
        fun accessGranted(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(component(context)) == true

        /** Ask the system to (re)bind the listener — a no-op without the grant. */
        fun requestRebindIfGranted(context: Context) {
            if (!accessGranted(context)) return
            try {
                NotificationListenerService.requestRebind(component(context))
            } catch (e: Exception) {
                Log.w(TAG, "requestRebind failed: ${e.message}")
            }
        }

        /**
         * Copy what [MessageNotificationParser] reads out of a live notification. `MessagingStyle`
         * comes through `NotificationCompat`, which understands both the platform's and AndroidX's
         * bundle shapes; `person == null` is the platform's own marker for the device user's line.
         */
        fun snapshot(sbn: StatusBarNotification): MessageNotificationParser.Snapshot {
            val n = sbn.notification
            val extras = n.extras
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            val user = style?.user
            val entries = style?.messages?.map { m ->
                val person = m.person
                val isSelf = person == null ||
                    (user?.key != null && person.key == user.key)
                MessageNotificationParser.Entry(
                    text = m.text?.toString(),
                    timeMs = m.timestamp,
                    sender = if (isSelf) null else person?.name?.toString(),
                )
            }
            return MessageNotificationParser.Snapshot(
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                conversationTitle = (style?.conversationTitle
                    ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString(),
                isGroup = style?.isGroupConversation
                    ?: extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
                messages = entries,
                selfName = user?.name?.toString(),
                category = n.category,
                whenMs = n.`when`,
                postTimeMs = sbn.postTime,
                isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                isOngoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            )
        }
    }
}
