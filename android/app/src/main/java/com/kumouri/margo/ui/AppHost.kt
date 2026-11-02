package com.kumouri.margo.ui

import android.content.Context

/**
 * The things a screen needs the Activity to do for it.
 *
 * Everything here is something Compose genuinely cannot own: launching a system permission dialog,
 * requesting a role, resolving a location fix. **Reads are deliberately NOT on this interface** — a
 * screen reads `SettingsStore` / `SyncStatus` / `BlocklistStore` itself, because routing plain
 * SharedPreferences reads through the Activity would buy indirection and nothing else.
 *
 * It exists so the screens compile and preview without an Activity, and so the permission plumbing
 * stays in one place rather than being re-derived per screen.
 */
interface AppHost {
    val context: Context

    /** Launch Health Connect's own permission UI for `HealthSyncWorker.REQUESTED_PERMISSIONS`. */
    fun requestHealthPermissions()

    /** Foreground location. Background location is a Settings trip; see [openAppSettings]. */
    fun requestLocationPermission()

    fun requestActivityRecognition()

    /** `RECEIVE_SMS` + `READ_SMS` together — the text feed's live edge and its backfill. Runtime
     *  permissions on every supported API (minSdk 29). A grant alone ships nothing: the Settings
     *  switch is the consent. */
    fun requestSmsPermissions()

    /** Run `SmsBackfillWorker` now — the on-demand pass over `content://sms`. */
    fun backfillSmsNow()

    /** Forget the backfill watermark so the next pass re-reads the whole provider (the desktop
     *  dedupes, so this costs bandwidth and nothing else). */
    fun resetSmsBackfill()

    /** The system Notification-access page for `MessageNotificationListener` (the text feed's
     *  Phase 2 — RCS and other messaging apps). A Settings trip, not a dialog: Android has no
     *  in-app prompt for this grant. */
    fun openNotificationAccessSettings()

    /** Ask to become the call-screening app. No-op if already held. */
    fun requestScreeningRole()

    /** Android's per-app settings page — the only route to "Allow all the time" and to Health Connect
     *  permissions the in-app dialog will not re-prompt for once permanently denied. */
    fun openAppSettings()

    /** Open Health Connect's own app, where its per-type sharing switches live. */
    fun openHealthConnect()

    fun syncHealthNow()

    fun syncBlocklistNow()

    /** Capture the current location and prompt to save it as a named place. */
    fun addPlaceHere()

    /**
     * POST an empty batch at the configured health endpoint and report what came back, verbatim.
     *
     * Verbatim matters: `401 bad or missing bearer token` and `unexpected end of stream` and
     * `failed to connect` are three completely different faults that a boolean would flatten into one
     * unhelpful "couldn't connect" — which is the shape that made the 2026-08-11 outage take an
     * evening instead of a minute.
     */
    fun testConnection(onResult: (String) -> Unit)

    /**
     * Open the system folder picker for the Samsung Health export folder and persist a READ grant on
     * whatever comes back. `onDone` fires after the grant is stored (or the picker was dismissed) so
     * the screen can re-read `SettingsStore.exportTreeUri` — the picker is asynchronous and Compose
     * cannot observe SharedPreferences on its own.
     */
    fun pickExportFolder(onDone: () -> Unit)

    /** Release the folder grant and forget the shipped ledger. Whatever is in the folder stays. */
    fun forgetExportFolder()

    /** Run `ExportWatchWorker` now rather than at the next hourly tick. */
    fun scanExportsNow()

    /** The Settings tab's own Stop control for an active "hold" — same path the notification's
     *  Stop action takes. A no-op (still reports back to the desktop) if nothing is active. */
    fun stopPresenceSignalNow()

    /** Whether Android lets this app run unrestricted in the background (battery optimizations
     *  ignored) — what keeps the presence push link up through Doze and restartable from the
     *  background. */
    fun isPresenceBatteryUnrestricted(): Boolean

    /** Ask for [isPresenceBatteryUnrestricted] with the system's own prompt. */
    fun requestPresenceBatteryUnrestricted()

    /** (Re)start the presence push link now — after a Settings change, or from its card. */
    fun reconnectPresenceLink()

    /** The Settings tab's "Test tap" — plays a single ping locally, at the current intensity, with
     *  no server round trip and no dependency on `PresenceLinkService`. Lets her confirm the
     *  device actually buzzes (incl. under Mute/DND) without asking Margo for a real ping. */
    fun testPresenceSignalTap()

    /** The pattern editor's "Test pattern" — plays the current custom pattern (or, absent one, the
     *  preview preset) for ~3 s then cancels, at [SettingsStore.presencePhoneAmplitude]. Local only,
     *  same no-server-round-trip posture as [testPresenceSignalTap]. */
    fun testPresenceSignalPattern()

    fun toast(message: String)
}
