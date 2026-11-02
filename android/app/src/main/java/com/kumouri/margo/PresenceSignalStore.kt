package com.kumouri.margo

import android.content.Context

/**
 * The phone's own record of the "I'm here" signal: which events it has already ACTED on (so a
 * re-push of the same `seq` — a reconnect snapshot — is a no-op, not a repeated buzz), what it
 * believes is currently playing, and the state of the held link the desktop pushes down
 * ([PresenceLinkService]) for the Settings card.
 *
 * Deliberately separate from [SettingsStore] — that file is things Ceryce TYPES; this is things the
 * app derives from the wire and would happily rebuild from the next reconnect snapshot if it were
 * ever lost (a cleared app, a reinstall) at the cost of one duplicate haptic at most.
 */
class PresenceSignalStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The highest `last_ping_seq` already handled (played, or dropped as stale). The desktop is the
     *  one seq authority, so a re-push of this-or-lower is a ping this phone has already handled. */
    var appliedPingSeq: Long
        get() = prefs.getLong(KEY_APPLIED_PING_SEQ, 0L)
        set(v) = prefs.edit().putLong(KEY_APPLIED_PING_SEQ, v).apply()

    var holdActive: Boolean
        get() = prefs.getBoolean(KEY_HOLD_ACTIVE, false)
        set(v) = prefs.edit().putBoolean(KEY_HOLD_ACTIVE, v).apply()

    /** The server's `hold_started_at`, verbatim — the identity of "which hold this is", so a second
     *  `hold start` mid-hold (a pattern change, a re-arm) is told apart from the same hold continuing. */
    var holdStartedAt: String
        get() = prefs.getString(KEY_HOLD_STARTED_AT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HOLD_STARTED_AT, v).apply()

    var holdPattern: String
        get() = prefs.getString(KEY_HOLD_PATTERN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HOLD_PATTERN, v).apply()

    /** The server's `surface` for the current hold — `"both"`/`"wrist"`/`"phone"`, which device(s)
     *  it should be FELT on (2026-09-19). Defaults to `"both"`, matching the server's own default. */
    var holdSurface: String
        get() = prefs.getString(KEY_HOLD_SURFACE, "both") ?: "both"
        set(v) = prefs.edit().putString(KEY_HOLD_SURFACE, v).apply()

    /** Epoch ms. The phone's OWN local stop timer is armed off this at hold-start, so a link that
     *  drops near the boundary still stops on time (the desktop also pushes the expiry). */
    var holdExpiresAtMs: Long
        get() = prefs.getLong(KEY_HOLD_EXPIRES_AT_MS, 0L)
        set(v) = prefs.edit().putLong(KEY_HOLD_EXPIRES_AT_MS, v).apply()

    // --- the link, for the Settings card ---------------------------------------------------------

    /** `connected` / `connecting` / `down` / `off` / `blocked` — [PresenceLinkService]'s own word. */
    var linkState: String
        get() = prefs.getString(KEY_LINK_STATE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LINK_STATE, v).apply()

    /** Why it's down / off / blocked, verbatim — shown on the card, never paraphrased. */
    var linkDetail: String
        get() = prefs.getString(KEY_LINK_DETAIL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LINK_DETAIL, v).apply()

    /** When [linkState] last changed. */
    var linkSinceMs: Long
        get() = prefs.getLong(KEY_LINK_SINCE_MS, 0L)
        set(v) = prefs.edit().putLong(KEY_LINK_SINCE_MS, v).apply()

    /** The last time ANY bytes arrived on the link — a pushed event or a heartbeat. */
    var lastHeardMs: Long
        get() = prefs.getLong(KEY_LAST_HEARD_MS, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_HEARD_MS, v).apply()

    /** The last time a real pushed EVENT (not a heartbeat) was applied. */
    var lastPushMs: Long
        get() = prefs.getLong(KEY_LAST_PUSH_MS, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_PUSH_MS, v).apply()

    companion object {
        private const val PREFS = "margo_presence_signal"
        private const val KEY_APPLIED_PING_SEQ = "applied_ping_seq"
        private const val KEY_HOLD_ACTIVE = "hold_active"
        private const val KEY_HOLD_STARTED_AT = "hold_started_at"
        private const val KEY_HOLD_PATTERN = "hold_pattern"
        private const val KEY_HOLD_SURFACE = "hold_surface"
        private const val KEY_HOLD_EXPIRES_AT_MS = "hold_expires_at_ms"
        private const val KEY_LINK_STATE = "link_state"
        private const val KEY_LINK_DETAIL = "link_detail"
        private const val KEY_LINK_SINCE_MS = "link_since_ms"
        private const val KEY_LAST_HEARD_MS = "last_heard_ms"
        private const val KEY_LAST_PUSH_MS = "last_push_ms"
    }
}
