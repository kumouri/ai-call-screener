package com.kumouri.margo

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/**
 * The phone's half of the presence-signal → wrist relay — [WearListenerService]'s mirror in the
 * other direction. The watch is the primary surface for the haptic (her wrist, not her pocket), so
 * every ping/hold event this phone applies is also relayed here.
 *
 * Blocking (every caller is already off the main thread). **Each send returns what happened** —
 * [RELAY_SENT] / [RELAY_NO_WATCH] / [RELAY_FAILED] — because since the 2026-10-06 push rebuild the
 * phone's ack to the desktop says so (`watch_relay`), and the desktop only waits for a WATCH ack when
 * the relay actually went out. Every message carries the desktop's `push_id` + `seq`, which the watch
 * echoes back in its own ack (`PATH_SIGNAL_ACK`) so the desktop can match "felt on the wrist" to the
 * push that caused it.
 */
object WatchBridge {
    private const val TAG = "MargoPresenceSignal"

    /** Must match `PresenceSignalListenerService`'s paths in the `:wear` module. */
    const val PATH_PING = "/margo/presence-signal/ping"
    const val PATH_HOLD_START = "/margo/presence-signal/hold-start"
    const val PATH_HOLD_STOP = "/margo/presence-signal/hold-stop"

    /** Watch → phone: the wrist's own ack (`PhoneBridge.PATH_SIGNAL_ACK` in `:wear`), forwarded by
     *  [WearListenerService] to the desktop verbatim. */
    const val PATH_SIGNAL_ACK = "/margo/presence-signal/ack"

    const val RELAY_SENT = "sent"
    const val RELAY_NO_WATCH = "no-watch"
    const val RELAY_FAILED = "failed"

    /** [surface] (2026-09-19) is which device(s) `presence_signal.py ping --surface` asked to FEEL
     *  this ping — relayed alongside the watch's own amplitude ceiling so the watch gates its own
     *  vibrate on it. */
    fun sendPing(context: Context, label: String, surface: String, pushId: String, seq: Long): String {
        val amplitude = SettingsStore(context).presenceWatchAmplitude
        return send(
            context, PATH_PING,
            JSONObject().put("label", label).put("amplitude", amplitude).put("surface", surface)
                .put("push_id", pushId).put("seq", seq).toString()
        )
    }

    /** Relays the watch's own amplitude ceiling, [surface] (which device(s) `presence_signal.py hold
     *  start --surface` asked to FEEL this hold), and, when set, the phone-local custom pattern that
     *  overrides [pattern]'s named shape — both surfaces read the same override. */
    fun sendHoldStart(
        context: Context, pattern: String, expiresAtMs: Long, surface: String, pushId: String, seq: Long,
    ): String {
        val settings = SettingsStore(context)
        val json = JSONObject()
            .put("pattern", pattern)
            .put("expires_at_ms", expiresAtMs)
            .put("amplitude", settings.presenceWatchAmplitude)
            .put("surface", surface)
            .put("push_id", pushId)
            .put("seq", seq)
        val customPattern = settings.presenceCustomPatternMs
        if (customPattern.isNotBlank()) json.put("segments", customPattern)
        return send(context, PATH_HOLD_START, json.toString())
    }

    /** [pushId] is blank for a stop the phone originated itself (its own Stop, its own expiry). */
    fun sendHoldStop(context: Context, pushId: String = "", seq: Long = 0L): String =
        send(context, PATH_HOLD_STOP, JSONObject().put("push_id", pushId).put("seq", seq).toString())

    private fun send(context: Context, path: String, payload: String): String {
        return try {
            val nodes: List<Node> = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            if (nodes.isEmpty()) {
                Log.d(TAG, "no connected watch; skipping $path")
                return RELAY_NO_WATCH
            }
            val client: MessageClient = Wearable.getMessageClient(context)
            var anyOk = false
            for (node in nodes) {
                try {
                    Tasks.await(client.sendMessage(node.id, path, payload.toByteArray(Charsets.UTF_8)))
                    anyOk = true
                } catch (e: Exception) {
                    Log.w(TAG, "watch send to ${node.displayName} failed: ${e.message}")
                }
            }
            if (anyOk) RELAY_SENT else RELAY_FAILED
        } catch (e: Exception) {
            Log.w(TAG, "watch bridge failed: ${e.message}")
            RELAY_FAILED
        }
    }
}
