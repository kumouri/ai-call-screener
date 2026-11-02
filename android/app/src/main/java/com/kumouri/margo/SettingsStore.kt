package com.kumouri.margo

import android.content.Context

/**
 * Every endpoint and token the app talks to, as a RUNTIME value rather than a compiled-in one.
 *
 * **This is the de-personalization.** Until now the seven endpoints/tokens came from `BuildConfig`,
 * injected at build time from a gitignored `local.properties`. That is exactly right for one person
 * building her own APK and exactly wrong for anyone else: installing it meant cloning the repo,
 * writing a properties file and running Gradle. The same code is destined for
 * [Seneschal](https://github.com/kumouri/seneschal) — the de-personalized build that runs on other
 * people's machines — so the config has to be something a person can type into a phone.
 *
 * **BuildConfig becomes the SEED, not the source.** A value that has never been set in-app falls back
 * to the compiled-in one, so Ceryce's existing install keeps working with nothing to re-enter, while a
 * stranger's build (which compiles those to `""`) starts empty and asks. One mechanism serves both,
 * and neither has a special case.
 *
 * **Blank means unset, everywhere.** `getString` returning `""` and the key being absent must behave
 * identically, because a user who clears a field means "no value" just as much as one who never typed
 * one — and a `""` URL that reads as configured is a worker that tries to POST to nowhere every hour.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- endpoints + tokens ---------------------------------------------------------------------

    var healthUrl: String
        get() = read(KEY_HEALTH_URL, BuildConfig.HEALTH_INGEST_URL)
        set(v) = write(KEY_HEALTH_URL, v)

    var healthToken: String
        get() = read(KEY_HEALTH_TOKEN, BuildConfig.HEALTH_INGEST_TOKEN)
        set(v) = write(KEY_HEALTH_TOKEN, v)

    var presenceUrl: String
        get() = read(KEY_PRESENCE_URL, BuildConfig.PRESENCE_INGEST_URL)
        set(v) = write(KEY_PRESENCE_URL, v)

    var presenceToken: String
        get() = read(KEY_PRESENCE_TOKEN, BuildConfig.PRESENCE_INGEST_TOKEN)
        set(v) = write(KEY_PRESENCE_TOKEN, v)

    /** Blank falls back to [presenceUrl] — the ack route lives on the same listener by default. */
    var ackUrl: String
        get() = read(KEY_ACK_URL, BuildConfig.ACK_INGEST_URL)
        set(v) = write(KEY_ACK_URL, v)

    /** What `WearListenerService` should actually POST wrist acks to. */
    fun ackUrlOrPresence(): String = ackUrl.ifBlank { presenceUrl }

    /** Where the "I'm here" signal is PUSHED from (the held `GET /presence-signal-stream`), acked
     *  (`POST /presence-signal-ack`) and stopped (`POST /presence-signal-stop`): the SAME listener as
     *  the presence feed, same token, same reason `exportIngestUrl`/`smsIngestUrl` give one origin —
     *  `../../margo/docs/presence-signal-spec.md`. */
    fun presenceSignalStreamUrl(): String = presenceSignalStreamUrlFor(presenceUrl)
    fun presenceSignalAckUrl(): String = presenceSignalAckUrlFor(presenceUrl)
    fun presenceSignalStopUrl(): String = presenceSignalStopUrlFor(presenceUrl)

    /**
     * `0..MAX_AMPLITUDE` (`PresenceHapticPatterns.maxAmplitude()`) — the phone's own haptic ceiling,
     * a slider rather than the old `soft`/`normal` pair (2026-09-18, Ceryce: *"being able to set the
     * pattern and intensities in the app would be sick"*). **There is nothing above the ceiling**:
     * the device's own gentle-notification-level output is it, by design (`presence-signal-
     * spec.md`'s hard line) — this only picks how close a pulse lands, never past it. Independent of
     * [presenceWatchAmplitude] — the wrist and the pocket are different surfaces with different felt
     * intensities, per Ceryce's own rhythm request.
     *
     * **`0` means "this phone sits the signal out"** (2026-09-19, `presence-signal-spec.md` §5.3) —
     * no vibration for a ping or a hold, but the hold is still tracked here and Stop still works;
     * see [PresenceHapticPatterns.shouldVibrate]. `1..MAX_AMPLITUDE` remain real intensities.
     */
    var presencePhoneAmplitude: Int
        get() = prefs.getInt(KEY_PRESENCE_PHONE_AMPLITUDE, DEFAULT_PRESENCE_PHONE_AMPLITUDE)
            .coerceIn(0, PresenceHapticPatterns.maxAmplitude())
        set(v) = prefs.edit()
            .putInt(KEY_PRESENCE_PHONE_AMPLITUDE, v.coerceIn(0, PresenceHapticPatterns.maxAmplitude()))
            .apply()

    /** The watch's own ceiling — previously hardcoded (the watch "always plays at its gentlest
     *  tier"); now a second slider, relayed to the wrist over the Data Layer on every ping/hold
     *  (`WatchBridge`). Softer by default than [presencePhoneAmplitude] — on the wrist, not in a
     *  pocket. Same `0` = "sits out" meaning as [presencePhoneAmplitude], since 2026-09-19. */
    var presenceWatchAmplitude: Int
        get() = prefs.getInt(KEY_PRESENCE_WATCH_AMPLITUDE, DEFAULT_PRESENCE_WATCH_AMPLITUDE)
            .coerceIn(0, PresenceHapticPatterns.maxAmplitude())
        set(v) = prefs.edit()
            .putInt(KEY_PRESENCE_WATCH_AMPLITUDE, v.coerceIn(0, PresenceHapticPatterns.maxAmplitude()))
            .apply()

    /** Raw `"on,off,on,off,…"` milliseconds, or blank for "no override" — [PresenceHapticPatterns.
     *  parseCustomPattern] is the one parser/validator, called by [presenceCustomPatternSegments].
     *  When it parses, it overrides whichever named pattern a hold carries, on BOTH the phone and
     *  (relayed) the watch. */
    var presenceCustomPatternMs: String
        get() = prefs.getString(KEY_PRESENCE_CUSTOM_PATTERN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PRESENCE_CUSTOM_PATTERN, v.trim()).apply()

    /** [presenceCustomPatternMs], parsed — `null` when blank or invalid (never partially applied). */
    fun presenceCustomPatternSegments(): LongArray? =
        PresenceHapticPatterns.parseCustomPattern(presenceCustomPatternMs)

    /** Which named preset the Settings card's "Test pattern" button previews when no custom pattern
     *  overrides it. LOCAL ONLY — a real hold's pattern is always chosen server-side, from chat
     *  (`presence_signal.py hold start --pattern`); this never reaches the daemon. */
    var presencePreviewPattern: String
        get() = prefs.getString(KEY_PRESENCE_PREVIEW_PATTERN, PresenceHapticPatterns.DEFAULT_PATTERN)
            ?: PresenceHapticPatterns.DEFAULT_PATTERN
        set(v) = prefs.edit().putString(KEY_PRESENCE_PREVIEW_PATTERN, v).apply()

    var blocklistUrl: String
        get() = read(KEY_BLOCKLIST_URL, BuildConfig.BLOCKLIST_URL)
        set(v) = write(KEY_BLOCKLIST_URL, v)

    var blocklistSecret: String
        get() = read(KEY_BLOCKLIST_SECRET, BuildConfig.BLOCKLIST_SECRET)
        set(v) = write(KEY_BLOCKLIST_SECRET, v)

    /**
     * The `content://` tree URI of the folder Samsung Health drops its "Download personal data"
     * export into (a folder on the S23, a zip on older builds) — granted ONCE through the system
     * folder picker (`ACTION_OPEN_DOCUMENT_TREE`) and held as a persistable read+write permission.
     * Blank = no grant, and the export watcher does nothing at all.
     *
     * No `BuildConfig` seed, deliberately: a folder grant is a per-device permission the OS hands out,
     * not a value anyone can type into a properties file.
     */
    var exportTreeUri: String
        get() = read(KEY_EXPORT_TREE, "")
        set(v) = write(KEY_EXPORT_TREE, v)

    /**
     * Where the export zip is POSTed: the SAME listener as the health feed, route
     * `/health-export-ingest`. Derived from [healthUrl] rather than a second field, because the
     * listener's routes all hang off one origin and a second URL is a second thing to mistype — the
     * 2026-08-11 outage was four independent misconfigurations, and every field is one more.
     * Scheme + authority are kept, the path is replaced, so `http://100.x.y.z:8765/health-ingest`,
     * `http://100.x.y.z:8765/` and `http://100.x.y.z:8765` all resolve to the same route.
     */
    fun exportIngestUrl(): String = exportIngestUrlFor(healthUrl)

    /**
     * Where text messages are POSTed: the SAME listener as the presence feed, route `/sms-ingest`,
     * derived from [presenceUrl] for the reason [exportIngestUrl] gives — one origin, one token, no
     * second field to mistype. The presence token is the bearer.
     */
    fun smsIngestUrl(): String = smsIngestUrlFor(presenceUrl)

    /**
     * Why the text feed is NOT configured right now, as one sentence naming the ONE thing to fix, or
     * `null` when it is. The card, the worker and the "Backfill now" toast all say the same words.
     *
     * The first live run (2026-09-15 23:52) showed a red "text feed off or no presence server
     * address" under a Presence card with the URL plainly filled in: a two-cause message of which
     * only one was true, sending Ceryce to check the address when the switch was the problem.
     */
    fun smsUnconfiguredReason(): String? = smsUnconfiguredReason(smsEnabled, presenceUrl)

    // --- feature switches -----------------------------------------------------------------------
    //
    // Default ON so an upgrade changes nothing for an existing install. A switch that defaults off
    // would silently stop three feeds on the first launch after update, which is the same class of
    // silent-stop this whole subsystem spent an evening being bitten by.

    var healthEnabled: Boolean
        get() = prefs.getBoolean(KEY_HEALTH_ON, true)
        set(v) = prefs.edit().putBoolean(KEY_HEALTH_ON, v).apply()

    var presenceEnabled: Boolean
        get() = prefs.getBoolean(KEY_PRESENCE_ON, true)
        set(v) = prefs.edit().putBoolean(KEY_PRESENCE_ON, v).apply()

    var blocklistEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCKLIST_ON, true)
        set(v) = prefs.edit().putBoolean(KEY_BLOCKLIST_ON, v).apply()

    /**
     * The text-message feed. **Default OFF** — the one switch here that is, deliberately: the other
     * three default on so an upgrade changes nothing, but this feed did not exist before 2026-09-15
     * and it ships message bodies. Granting `READ_SMS` is a permission; this is the consent. Both
     * are needed before a single text leaves the phone (`margo/docs/sms-ingest-spec.md` §4).
     */
    var smsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMS_ON, false)
        set(v) = prefs.edit().putBoolean(KEY_SMS_ON, v).apply()

    /**
     * Which apps' notifications the Phase 2 listener reads (`margo/docs/sms-ingest-spec.md` §6.1).
     * Default: Google Messages only — RCS is the stated hole; Signal, WhatsApp and Samsung Messages
     * are one tap each on the Settings card. Never set = the default; an empty set is a real answer
     * ("read none") and is kept as one. The [smsEnabled] switch still gates all of it.
     */
    var notifPackages: Set<String>
        get() = prefs.getStringSet(KEY_NOTIF_PACKAGES, null)?.toSet() ?: DEFAULT_NOTIF_PACKAGES
        set(v) = prefs.edit().putStringSet(KEY_NOTIF_PACKAGES, v.toSet()).apply()

    /** Add or remove one app from [notifPackages] — the Settings card's per-app switch. */
    fun setNotifPackage(pkg: String, on: Boolean) {
        notifPackages = withNotifPackage(notifPackages, pkg, on)
    }

    /**
     * Delete a `samsunghealth_*.zip` from the export folder once the desktop has confirmed it.
     * Default ON — Ceryce's ruling 2026-09-12 20:42 (*"deleted the old ones after it confirmed a
     * successful send"*); made a switch by her 21:05 line (*"deleting the zip should be an option in
     * the options"*). The export FOLDER Samsung actually writes is always deleted after confirmation —
     * that half is not optional — and the app's own cache zip is always cleaned up. The switch only
     * governs a zip that was already sitting in the folder.
     */
    var deleteShippedZips: Boolean
        get() = prefs.getBoolean(KEY_DELETE_SHIPPED_ZIPS, true)
        set(v) = prefs.edit().putBoolean(KEY_DELETE_SHIPPED_ZIPS, v).apply()

    /**
     * Hours between health syncs. Clamped rather than validated-and-rejected: a settings field is a
     * place people type `0`, and an unclamped 0 becomes WorkManager's minimum period anyway while
     * *looking* like it means "never". 1..24 is the honest range.
     */
    var healthIntervalHours: Int
        get() = prefs.getInt(KEY_HEALTH_HOURS, DEFAULT_HEALTH_HOURS).coerceIn(1, 24)
        set(v) = prefs.edit().putInt(KEY_HEALTH_HOURS, v.coerceIn(1, 24)).apply()

    // --- derived --------------------------------------------------------------------------------

    /** True when the health feed has somewhere to POST. The workers check this, not `isBlank()`. */
    fun healthConfigured(): Boolean = healthEnabled && healthUrl.isNotBlank()

    /** The export watcher needs both halves: a listener to ship to and a folder to watch. */
    fun exportWatchConfigured(): Boolean = healthConfigured() && exportTreeUri.isNotBlank()

    fun presenceConfigured(): Boolean = presenceEnabled && presenceUrl.isNotBlank()

    /** The text feed needs the switch AND somewhere to POST (it rides the presence server). */
    fun smsConfigured(): Boolean = smsEnabled && smsIngestUrl().isNotBlank()

    fun blocklistConfigured(): Boolean = blocklistEnabled && blocklistUrl.isNotBlank()

    /** Nothing set up at all — the first-run state, which the UI greets differently from a fault. */
    fun isFirstRun(): Boolean =
        healthUrl.isBlank() && presenceUrl.isBlank() && blocklistUrl.isBlank()

    private fun read(key: String, seed: String): String =
        prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: seed

    private fun write(key: String, value: String) {
        prefs.edit().putString(key, value.trim()).apply()
    }

    companion object {
        private const val PREFS = "margo_settings"
        private const val KEY_HEALTH_URL = "health_url"
        private const val KEY_HEALTH_TOKEN = "health_token"
        private const val KEY_PRESENCE_URL = "presence_url"
        private const val KEY_PRESENCE_TOKEN = "presence_token"
        private const val KEY_ACK_URL = "ack_url"
        private const val KEY_BLOCKLIST_URL = "blocklist_url"
        private const val KEY_BLOCKLIST_SECRET = "blocklist_secret"
        private const val KEY_HEALTH_ON = "health_enabled"
        private const val KEY_PRESENCE_ON = "presence_enabled"
        private const val KEY_BLOCKLIST_ON = "blocklist_enabled"
        private const val KEY_SMS_ON = "sms_enabled"
        private const val KEY_NOTIF_PACKAGES = "notif_packages"
        private const val KEY_HEALTH_HOURS = "health_interval_hours"
        private const val KEY_EXPORT_TREE = "export_tree_uri"
        private const val KEY_DELETE_SHIPPED_ZIPS = "export_delete_shipped_zips"
        private const val KEY_PRESENCE_PHONE_AMPLITUDE = "presence_phone_amplitude"
        private const val KEY_PRESENCE_WATCH_AMPLITUDE = "presence_watch_amplitude"
        private const val KEY_PRESENCE_CUSTOM_PATTERN = "presence_custom_pattern_ms"
        private const val KEY_PRESENCE_PREVIEW_PATTERN = "presence_preview_pattern"
        const val DEFAULT_HEALTH_HOURS = 1
        const val EXPORT_INGEST_PATH = "/health-export-ingest"
        const val SMS_INGEST_PATH = "/sms-ingest"
        const val PRESENCE_SIGNAL_STREAM_PATH = "/presence-signal-stream"
        const val PRESENCE_SIGNAL_ACK_PATH = "/presence-signal-ack"
        const val PRESENCE_SIGNAL_STOP_PATH = "/presence-signal-stop"

        /** Softer than the old `"soft"` toggle's typical output (its 0.55 scale against a ~90-100
         *  base landed ~50-55) — her 2026-09-18 ask, applied as the new numeric default rather than
         *  a third named tier. */
        const val DEFAULT_PRESENCE_PHONE_AMPLITUDE = 45

        /** Softer still — the wrist, not a pocket. */
        const val DEFAULT_PRESENCE_WATCH_AMPLITUDE = 35

        /** Pure function of the health URL, kept off the instance so it can be reasoned about without
         *  a `Context`. A URL that will not parse yields `""`, which the worker reads as unconfigured. */
        fun exportIngestUrlFor(healthUrl: String): String = routeOn(healthUrl, EXPORT_INGEST_PATH)

        /** The same, for the text feed: the presence URL's origin + `/sms-ingest`. */
        fun smsIngestUrlFor(presenceUrl: String): String = routeOn(presenceUrl, SMS_INGEST_PATH)

        /** The same, for the presence signal: the presence URL's origin + the push stream (labelled
         *  `device=phone` — the hub closes an older stream with the same label, so a reconnect
         *  replaces a half-open one), the ack route, and the Stop route. */
        fun presenceSignalStreamUrlFor(presenceUrl: String): String =
            routeOn(presenceUrl, PRESENCE_SIGNAL_STREAM_PATH).let { if (it.isBlank()) it else "$it?device=phone" }
        fun presenceSignalAckUrlFor(presenceUrl: String): String = routeOn(presenceUrl, PRESENCE_SIGNAL_ACK_PATH)
        fun presenceSignalStopUrlFor(presenceUrl: String): String =
            routeOn(presenceUrl, PRESENCE_SIGNAL_STOP_PATH)

        const val PKG_GOOGLE_MESSAGES = "com.google.android.apps.messaging"

        /** The apps the listener can be pointed at, in the order the Settings card lists them
         *  (`margo/docs/sms-ingest-spec.md` §6.1). The desktop folds a notification from the two
         *  SMS-capable ones into the matching SMS row (`sms_common.SMS_APP_PACKAGES`). */
        val NOTIF_APPS: List<Pair<String, String>> = listOf(
            PKG_GOOGLE_MESSAGES to "Google Messages",
            "org.thoughtcrime.securesms" to "Signal",
            "com.whatsapp" to "WhatsApp",
            "com.samsung.android.messaging" to "Samsung Messages",
        )

        val DEFAULT_NOTIF_PACKAGES: Set<String> = setOf(PKG_GOOGLE_MESSAGES)

        /** The label the card and the event log use; an unknown package prints as itself. */
        fun notifAppLabel(pkg: String): String = NOTIF_APPS.firstOrNull { it.first == pkg }?.second ?: pkg

        /** Pure form of [SettingsStore.setNotifPackage]. */
        fun withNotifPackage(current: Set<String>, pkg: String, on: Boolean): Set<String> =
            if (on) current + pkg else current - pkg

        const val SMS_OFF_REASON = "text feed is off — the Text messages switch in Settings"
        const val SMS_NO_SERVER_REASON =
            "no presence server address — set the Presence feed's Server URL in Settings"

        /** Pure form of [SettingsStore.smsUnconfiguredReason]: the switch first, then the address. */
        fun smsUnconfiguredReason(enabled: Boolean, presenceUrl: String): String? = when {
            !enabled -> SMS_OFF_REASON
            smsIngestUrlFor(presenceUrl).isBlank() -> SMS_NO_SERVER_REASON
            else -> null
        }

        /** `<scheme>://<authority><path>` from any URL on the same listener, `""` if it won't parse. */
        fun routeOn(anyUrl: String, path: String): String {
            val trimmed = anyUrl.trim()
            if (trimmed.isBlank()) return ""
            return try {
                val u = java.net.URI(trimmed)
                if (u.scheme.isNullOrBlank() || u.authority.isNullOrBlank()) ""
                else "${u.scheme}://${u.authority}$path"
            } catch (_: Exception) {
                ""
            }
        }
    }
}
