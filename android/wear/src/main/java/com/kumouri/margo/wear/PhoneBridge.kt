package com.kumouri.margo.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject

/**
 * The watch's only way out: hand NDJSON lines to the PHONE over the Wearable Data Layer and let it
 * forward them to the desktop.
 *
 * The watch deliberately holds no endpoint and no token. It may have no wifi and no tailnet at all —
 * a Galaxy Watch off wifi is the normal case, not the exception — so posting directly would be a feed
 * that works at home and silently stops everywhere else. The phone already owns the credentials and
 * already has a working ingest path; this reuses both.
 *
 * Wire format is the SAME NDJSON `presence_import.py` already accepts, with `source: "watch"` added.
 * That field is not cosmetic: `presence_common.recompute_context` lets a wrist reading outrank a phone
 * one, which is the actual fix for "it says I'm asleep all day".
 */
object PhoneBridge {

    private const val TAG = "MargoWear"

    /** Must match [com.kumouri.margo.WearListenerService.PATH_PRESENCE] on the phone. */
    const val PATH_PRESENCE = "/margo/presence"

    /** Must match the phone's ack path. */
    const val PATH_ACK = "/margo/ack"

    /** Must match `WatchBridge.PATH_SIGNAL_ACK` on the phone: the wrist confirming a presence-signal
     *  push it acted on (2026-10-06, "push, not polling" — the desktop tells "felt on the wrist" from
     *  "sent" by this). The phone forwards it to the desktop's `/presence-signal-ack` verbatim. */
    const val PATH_SIGNAL_ACK = "/margo/presence-signal/ack"

    /**
     * One presence-signal ack: [kind] (`ping` / `hold-start` / `hold-stop` / `hold-end`) and [status]
     * (`played` — this watch issued the vibration, the closest an app can know to "felt"; `silenced`
     * — the hold/ping wasn't for the wrist, or its slider is at 0; `stopped`; `ended`; `failed`),
     * echoing the desktop's [pushId]/[seq]. [extra] adds counters (`played_ms`, `reissues`,
     * `screen_off_reissues`) or a `detail`/`reason`. Best-effort, like every send here; blocking.
     */
    fun signalAck(
        context: Context, pushId: String, seq: Long, kind: String, status: String, extra: JSONObject? = null,
    ): Boolean {
        val effect = JSONObject().put("kind", kind).put("status", status)
        extra?.keys()?.forEach { key -> effect.put(key, extra.get(key)) }
        val body = JSONObject()
            .put("push_id", pushId)
            .put("seq", seq)
            .put("device", "watch")
            .put("effects", org.json.JSONArray().put(effect))
        return send(context, PATH_SIGNAL_ACK, body.toString())
    }

    /**
     * Send one NDJSON payload to every connected node.
     *
     * "Every node" rather than "the best node" on purpose: there is realistically one phone, and
     * picking a single node means guessing which of two paired devices is the one holding the tailnet
     * credentials. Sending to both costs a few bytes and removes the guess — the desktop dedupes on
     * the event id anyway, which is exactly what that id is for.
     *
     * Blocking, and meant to be: callers are already off the main thread (a PassiveListenerService
     * callback or a worker), and a fire-and-forget send gives no way to know it failed.
     */
    fun send(context: Context, path: String, payload: String): Boolean {
        return try {
            val nodes: List<Node> = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            if (nodes.isEmpty()) {
                // Not an error worth shouting about: a watch out of Bluetooth range is a normal
                // afternoon. The reading is dropped rather than queued — see the note below.
                Log.d(TAG, "no connected phone; dropping ${payload.length}B on $path")
                return false
            }
            val client: MessageClient = Wearable.getMessageClient(context)
            var anyOk = false
            for (node in nodes) {
                try {
                    Tasks.await(client.sendMessage(node.id, path, payload.toByteArray(Charsets.UTF_8)))
                    anyOk = true
                } catch (e: Exception) {
                    Log.w(TAG, "send to ${node.displayName} failed: ${e.message}")
                }
            }
            anyOk
        } catch (e: Exception) {
            Log.w(TAG, "bridge send failed: ${e.message}")
            false
        }
    }

    /**
     * A presence event in the shape `presence_import.py` already parses.
     *
     * `ts_ms` is the OBSERVATION time, never the send time — a reading that sat in a Bluetooth gap for
     * ten minutes must still be filed at the moment the wrist actually reported it, or the whole
     * watch-outranks-phone comparison is being made against a lie.
     */
    fun sleepEvent(state: String, tsMs: Long, offsetMin: Int): String =
        JSONObject()
            .put("t", "sleep").put("state", state)
            .put("ts_ms", tsMs).put("offset_min", offsetMin)
            .put("source", "watch")
            .toString()

    fun activityEvent(activity: String, tsMs: Long, offsetMin: Int): String =
        JSONObject()
            .put("t", "activity").put("activity", activity).put("transition", "enter")
            .put("ts_ms", tsMs).put("offset_min", offsetMin)
            .put("source", "watch")
            .toString()
}
