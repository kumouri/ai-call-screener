package com.kumouri.margo

import org.json.JSONObject

/**
 * The phone's reader for `GET /presence-signal-stream` — the Server-Sent-Events channel the desktop
 * PUSHES the presence signal down (Ceryce's ruling 2026-10-06 21:53 CT, `../../margo/docs/
 * rulings.md` id `2026-10-06-presence-signal-is-push`; the desktop end is `margo/scripts/
 * presence_signal_hub.py`). [PresenceLinkService] owns the connection; this file is only the pure
 * parsing, so it is unit-tested on the JVM (`PresenceSignalStreamTest`).
 *
 * Wire format, exactly what the hub writes:
 * ```
 * event: signal
 * id: <push_id>
 * data: {"push_id":"…","reason":"hold-start","seq":19,"state":"hold",…}
 *
 * : hb
 * ```
 * A `:` line is a heartbeat comment — it carries nothing, but its arrival is what keeps the link's
 * read timeout from firing, which is the whole of the link's health check.
 */
class SseParser {
    data class Event(val event: String, val id: String, val data: String)

    private var event = ""
    private var id = ""
    private val data = StringBuilder()
    private var hasData = false

    /** Feed one line (no terminator). Returns an [Event] when a blank line completes one. */
    fun feed(line: String): Event? {
        if (line.isEmpty()) {
            if (!hasData) {
                event = ""
                return null
            }
            val out = Event(event.ifEmpty { "message" }, id, data.toString())
            event = ""
            data.setLength(0)
            hasData = false
            return out
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "id" -> id = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }
}

/** One pushed event: the hub's `push_id` and `reason`, and the signal document itself. */
data class PushedSignal(val pushId: String, val reason: String, val signal: PresenceSignalClient.Signal)

object PresenceSignalStream {
    /** A `signal` event's data → [PushedSignal], or `null` for anything else / unparseable JSON. */
    fun parse(event: SseParser.Event): PushedSignal? {
        if (event.event != "signal") return null
        return try {
            val json = JSONObject(event.data)
            PushedSignal(
                pushId = json.optString("push_id", event.id),
                reason = json.optString("reason", ""),
                signal = PresenceSignalClient.parseSignal(json),
            )
        } catch (_: Exception) {
            null
        }
    }
}
