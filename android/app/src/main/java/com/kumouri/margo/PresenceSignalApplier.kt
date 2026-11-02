package com.kumouri.margo

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * What one pushed [PresenceSignalClient.Signal] MEANS for this phone, given what it already
 * applied — pure, so "what counts as new" is defined once and unit-tested on the JVM
 * (`PresenceSignalApplierTest`). [PresenceLinkService] carries the decision out (the vibration, the
 * hold keeper, the watch relay, the ack).
 *
 * The rules:
 * - **A ping plays once.** `last_ping_seq` above the highest this phone applied is new. But a ping
 *   that arrives more than [PING_FRESH_MS] after it was sent — the phone was out of reach and the
 *   reconnect snapshot carried it — is marked applied and NOT played: a "here" tap twenty minutes
 *   late is a startle, not a comfort. (Mirrors `presence_signal.PING_FRESH_SEC` on the desktop.)
 * - **A hold's identity is its `hold_started_at` + pattern.** Same identity as the one running →
 *   [HoldAction.CONTINUE] (a reconnect snapshot, an unrelated ping); different → [HoldAction.START]
 *   (a new hold, or a re-arm with a new pattern).
 * - **Idle while a hold runs here → [HoldAction.STOP]**, whoever ended it (chat, the watch, expiry).
 */
object PresenceSignalApplier {

    /** See the class doc. Two minutes: comfortably above a reconnect after a brief network blip,
     *  well below "that was about something else entirely". */
    const val PING_FRESH_MS = 120_000L

    enum class PingAction { NONE, PLAY, STALE }
    enum class HoldAction { NONE, START, CONTINUE, STOP }

    data class Local(
        val appliedPingSeq: Long,
        val holdActive: Boolean,
        val holdStartedAt: String,
        val holdPattern: String,
    )

    data class Decision(val ping: PingAction, val hold: HoldAction)

    fun decide(local: Local, signal: PresenceSignalClient.Signal, nowMs: Long): Decision {
        val ping = when {
            signal.lastPingSeq <= 0 || signal.lastPingSeq <= local.appliedPingSeq -> PingAction.NONE
            else -> {
                val at = parseIsoToMs(signal.lastPingAt)
                if (at != null && nowMs - at > PING_FRESH_MS) PingAction.STALE else PingAction.PLAY
            }
        }
        val hold = if (signal.state == "hold") {
            val startedAt = signal.holdStartedAt ?: ""
            val pattern = signal.pattern ?: PresenceHapticPatterns.DEFAULT_PATTERN
            if (local.holdActive && local.holdStartedAt == startedAt && local.holdPattern == pattern) {
                HoldAction.CONTINUE
            } else {
                HoldAction.START
            }
        } else if (local.holdActive) {
            HoldAction.STOP
        } else {
            HoldAction.NONE
        }
        return Decision(ping, hold)
    }

    /** `2026-09-18T09:30:00+00:00` → epoch ms, or `null` on anything else — a hold with an
     *  unparseable expiry is treated as "no expiry known yet", never as "expires now". */
    fun parseIsoToMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
