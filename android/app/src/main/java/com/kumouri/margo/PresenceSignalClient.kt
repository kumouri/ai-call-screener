package com.kumouri.margo

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The wire shapes for the presence signal, and the two small POSTs the phone makes back to the
 * desktop listener (`margo/scripts/health_listener.py`): a Stop tap (`/presence-signal-stop`) and an
 * ack for every pushed event it applied (`/presence-signal-ack`).
 *
 * **There is no fetch here any more.** The phone used to POLL `GET /presence-signal` (a ~60 s
 * WorkManager chain while idle, every 10 s during a hold); Ceryce ruled 2026-10-06 21:53 CT that the
 * fix is a push from the desktop to her phone, with no more polling, so state now arrives down the held
 * stream [PresenceLinkService] keeps open, parsed by [PresenceSignalStream].
 *
 * Blocking, like every other HTTP call in this app — every caller is already off the main thread.
 */
object PresenceSignalClient {
    private const val TAG = "MargoPresenceSignal"

    /** The effective, expiry-aware document the desktop pushes — `presence_signal.effective_state`.
     *  `state` is `"idle"` or `"hold"`; `pattern` / `holdStartedAt` / `holdExpiresAt` are non-null only
     *  while `state == "hold"`. */
    data class Signal(
        val seq: Long,
        val state: String,
        val pattern: String?,
        val holdStartedAt: String?,
        val holdExpiresAt: String?,
        /** Which device(s) `presence_signal.py hold start --surface` asked to FEEL this hold —
         *  `"both"`/`"wrist"`/`"phone"`, non-null only while [state] is `"hold"`. Never narrows who
         *  can Stop it. */
        val surface: String?,
        val lastPingSeq: Long,
        val lastPingAt: String?,
        val lastPingLabel: String,
        /** Which device(s) `presence_signal.py ping --surface` asked to FEEL this ping —
         *  `"both"`/`"wrist"`/`"phone"`, `null` until the first ping this store has ever recorded
         *  (2026-09-19, mirrors [surface] above). */
        val lastPingSurface: String?,
    )

    /** The pushed JSON → [Signal]. Missing fields take the idle defaults. */
    fun parseSignal(json: JSONObject): Signal = Signal(
        seq = json.optLong("seq", 0L),
        state = json.optString("state", "idle"),
        pattern = json.stringOrNull("pattern"),
        holdStartedAt = json.stringOrNull("hold_started_at"),
        holdExpiresAt = json.stringOrNull("hold_expires_at"),
        surface = json.stringOrNull("surface"),
        lastPingSeq = json.optLong("last_ping_seq", 0L),
        lastPingAt = json.stringOrNull("last_ping_at"),
        lastPingLabel = json.optString("last_ping_label", ""),
        lastPingSurface = json.stringOrNull("last_ping_surface"),
    )

    /** A Stop tap, from either surface. Body-empty by design — `health_listener.py`'s route reads
     *  nothing from the body, the trigger IS the request; [stoppedBy] rides the query string
     *  instead (`?stopped_by=phone|watch`, 2026-09-19, `presence-signal-spec.md` §5.3 — "Stop stops
     *  everything, everywhere, always"). */
    fun postStop(url: String, token: String, stoppedBy: String = "phone"): Boolean {
        if (url.isBlank()) return false
        val target = url + (if (url.contains("?")) "&" else "?") + "stopped_by=$stoppedBy"
        return post(target, token, ByteArray(0), null)
    }

    /** One ack (`presence_signal_hub.record_ack`'s shape): the phone's own, or a watch ack it is
     *  relaying verbatim. A lost ack costs the desktop its proof of delivery, never the delivery. */
    fun postAck(url: String, token: String, body: JSONObject): Boolean {
        if (url.isBlank()) return false
        return post(url, token, body.toString().toByteArray(Charsets.UTF_8), "application/json")
    }

    private fun post(url: String, token: String, body: ByteArray, contentType: String?): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                if (contentType != null) setRequestProperty("Content-Type", contentType)
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) Log.w(TAG, "POST $url -> HTTP $code")
            code == 200
        } catch (e: Exception) {
            Log.w(TAG, "POST $url failed: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}

/** `org.json`'s `optString(key, null)` returns the literal string `"null"` for a JSON `null` value
 *  (it stringifies `JSONObject.NULL` rather than treating it as absent) — this is the correct read. */
internal fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key)
}
