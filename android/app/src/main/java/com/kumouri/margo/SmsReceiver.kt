package com.kumouri.margo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

/**
 * Turns an incoming text into one `kind:"sms"` line and queues it for [SmsSyncWorker].
 *
 * **This app is NOT the default SMS app and never will be** (Ceryce, 2026-09-15: *"I don't want to
 * build a full text-messaging app"*). It listens for `SMS_RECEIVED` — the broadcast every app holding
 * `RECEIVE_SMS` gets, *after* the default app has already written the message to the provider — and
 * reads it. `SMS_DELIVER` (the default app's exclusive, pre-write broadcast) is deliberately not
 * declared: an app that filters it is offering to be the messaging app, which is the thing not being
 * built. Sending, and writing to `content://sms`, are default-app-gated on the platform; receiving
 * and reading are not. `margo/docs/sms-ingest-spec.md` §2.
 *
 * **Multipart reassembly happens here, not on the desktop.** A long text arrives as one intent
 * carrying every PDU of the concatenated message, in order; `getMessagesFromIntent` decodes them and
 * the bodies are joined. The desktop never sees a fragment. A `null` PDU set (a malformed or class-0
 * message) is skipped, never crashed on.
 *
 * Gated on the Settings switch, which defaults OFF — a permission grant alone ships nothing. Off the
 * wire this writes counts only ([SmsLedger]); the body goes into [SmsStore] and nowhere else.
 *
 * **Must work from a cold process.** This is manifest-registered, so the system starts the app
 * process for the broadcast when nothing else is running — no Activity, no Compose state. Everything
 * it reads is therefore persisted: the switch is [SettingsStore.smsEnabled] in SharedPreferences
 * (the Settings card's switch commits there on the tap, since 2026-09-16 — before that it was a
 * Compose draft that only reached the store via "Save changes", and this receiver correctly saw
 * "off" while the card showed "on"), the endpoint is derived from the persisted presence URL, and the
 * hand-off is a WorkManager enqueue, which needs only the [Context] it is given.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val settings = SettingsStore(context)
        if (!settings.smsConfigured()) return

        val parts: Array<SmsMessage> = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        } catch (e: Exception) {
            Log.w(TAG, "could not decode SMS_RECEIVED PDUs: ${e.message}")
            return
        }
        if (parts.isEmpty()) return

        // One intent = one (possibly concatenated) message from one sender; join the parts in the
        // order the framework delivered them. The SMSC stamp of the first part is the message's time.
        val first = parts.first()
        val address = first.displayOriginatingAddress ?: first.originatingAddress ?: return
        val body = parts.joinToString(separator = "") { it.displayMessageBody ?: it.messageBody ?: "" }
        // Some OEM builds put the SIM slot on the intent; absent means "unknown", not slot 0.
        val slot = if (intent.hasExtra(EXTRA_SLOT)) intent.getIntExtra(EXTRA_SLOT, -1) else null

        val line = SmsStore.line(
            direction = "in",
            address = address,
            body = body,
            tsMs = System.currentTimeMillis(),
            origin = "receiver",
            simSlot = slot,
            tsSentMs = first.timestampMillis,
        )
        SmsStore(context).append(line)
        SmsLedger(context).recordReceived()
        EventLog.add(context, "sms received (${parts.size} part${if (parts.size == 1) "" else "s"}) — queued")
        SmsSyncWorker.syncNow(context)
    }

    private companion object {
        const val TAG = "SmsReceiver"
        const val EXTRA_SLOT = "slot"
    }
}
