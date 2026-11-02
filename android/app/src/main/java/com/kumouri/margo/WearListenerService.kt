package com.kumouri.margo

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.net.HttpURLConnection
import java.net.URL

/**
 * T3 (margo/docs/training-staff-spec.md): the phone half of the watch link.
 *
 * The watch observes; this forwards. The watch holds no endpoint and no token deliberately — it may
 * have no wifi and no tailnet, so a direct POST would be a feed that works at home and silently dies
 * everywhere else. The phone already has the credentials and a working ingest path, so wrist events
 * ride exactly the same road the phone's own presence events do.
 *
 * Payloads arrive already in the NDJSON shape `presence_import.py` accepts, carrying
 * `source: "watch"` — which is what lets `presence_common.recompute_context` rank a wrist reading
 * above a phone one.
 *
 * **This service forwards and nothing else** for [PATH_PRESENCE]/[PATH_ACK]. It doesn't interpret,
 * doesn't gate a reminder, and doesn't notify. A watch tap can never send or approve a draft — the
 * same rule that binds a Telegram reaction, and for the same reason: a wrist is the easiest surface
 * in the world to press by accident.
 *
 * **[WatchBridge.PATH_HOLD_STOP] is the one deliberate exception (2026-09-19).** A watch-initiated
 * Stop isn't a feed reading to archive — it's an instruction the phone has to ACT on (stop its own
 * vibration, tell the desktop), because the watch has no endpoint or token to do either itself. See
 * [PresenceLinkService.stopFromWatchRelay].
 *
 * **[WatchBridge.PATH_SIGNAL_ACK] (2026-10-06)** is the watch confirming a presence-signal push it
 * acted on ("started vibrating", "sat it out", "hold ended after N re-issues") — forwarded to the
 * desktop's `/presence-signal-ack` verbatim, so Margo can tell "felt on the wrist" from "sent".
 */
class WearListenerService : WearableListenerService() {

    /** Read fresh per message rather than cached in a field: this is a long-lived service, and a
     *  settings change made in the app must not need a reboot to take effect on the wrist path. */
    private val settings get() = SettingsStore(applicationContext)

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_PRESENCE -> forward(
                settings.presenceUrl,
                settings.presenceToken,
                String(event.data, Charsets.UTF_8),
            )
            PATH_ACK -> {
                // A wrist ack. This forwards it and does NOTHING else — deliberately.
                //
                // The tap carries no reminder id, because the watch can't know one (nudges go out over
                // Telegram and there's no inbound path to the phone). So it means what it means in
                // plain English: "whatever you just nudged me about, I did it." The DESKTOP resolves
                // that to the most recent nudge it actually sent today, and applies it through the one
                // canonical ack path a Telegram 👍 uses. Interpreting it here would be a second ack
                // implementation, which is how that durability guarantee quietly stops being true.
                forward(
                    settings.ackUrlOrPresence(),
                    settings.presenceToken,
                    String(event.data, Charsets.UTF_8),
                )
            }
            // The watch's OWN Stop was tapped (its notification action or its in-app button) and
            // relayed here — "Stop stops everything, everywhere, always" (2026-09-19,
            // `../../margo/docs/presence-signal-spec.md` §5.3). Until this case existed, a watch Stop
            // silenced only the wrist: the phone kept buzzing and the desktop was never told, because
            // the watch holds no endpoint or token of its own — this is the one place that gap closes.
            WatchBridge.PATH_HOLD_STOP -> PresenceLinkService.stopFromWatchRelay(applicationContext)
            WatchBridge.PATH_SIGNAL_ACK -> {
                val body = runCatching { org.json.JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull()
                if (body == null) {
                    Log.w(TAG, "watch ack was not JSON; dropped")
                } else {
                    body.put("device", "watch")
                    PresenceSignalClient.postAck(settings.presenceSignalAckUrl(), settings.presenceToken, body)
                }
            }
            else -> Log.d(TAG, "ignoring message on ${event.path}")
        }
    }

    /**
     * POST one NDJSON payload to the desktop listener.
     *
     * Runs on the binder thread `WearableListenerService` already hands us (it is not the main
     * thread), so a blocking call is correct here. A failure is logged and dropped, matching the
     * phone's own presence worker: presence is a stream of edges, and the next one supersedes a lost
     * one. A queue would add durability the consumer doesn't actually want — a stale edge replayed an
     * hour late is worse than no edge.
     */
    private fun forward(url: String, token: String, body: String) {
        if (url.isBlank()) {
            Log.w(TAG, "no PRESENCE_INGEST_URL baked in; dropping wrist event")
            return
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-ndjson")
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) Log.w(TAG, "desktop returned $code for a wrist event")
        } catch (e: Exception) {
            Log.w(TAG, "wrist forward failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "MargoWear"

        /** Must match `PhoneBridge.PATH_PRESENCE` in the :wear module. */
        const val PATH_PRESENCE = "/margo/presence"

        /** Must match `PhoneBridge.PATH_ACK` in the :wear module. */
        const val PATH_ACK = "/margo/ack"
    }
}
