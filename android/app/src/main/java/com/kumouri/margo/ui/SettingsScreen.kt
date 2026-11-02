package com.kumouri.margo.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kumouri.margo.BlocklistSyncWorker
import com.kumouri.margo.ExportLedger
import com.kumouri.margo.ExportWatchWorker
import com.kumouri.margo.HealthSyncWorker
import com.kumouri.margo.MessageNotificationListener
import com.kumouri.margo.PresenceHapticPatterns
import com.kumouri.margo.PresenceSignalStore
import com.kumouri.margo.PresenceSyncWorker
import com.kumouri.margo.SettingsStore
import com.kumouri.margo.SmsBackfillWorker
import com.kumouri.margo.SmsLedger
import com.kumouri.margo.SmsStore
import com.kumouri.margo.SmsSyncWorker

/**
 * Where the app stops being Ceryce's and becomes anyone's.
 *
 * Every field here was a `BuildConfig` constant compiled in from a gitignored `local.properties`,
 * which meant installing this app required cloning a repo and running Gradle. These are the same
 * seven values, typed instead of compiled — the one change that makes the APK installable by someone
 * who is not its author, and the prerequisite for it ever going into Seneschal.
 */
@Composable
fun SettingsScreen(host: AppHost, refreshKey: Int, refresh: () -> Unit) {
    val ctx = host.context
    val settings = remember { SettingsStore(ctx) }
    // The export folder grant lands asynchronously (system picker) and its status is written by a
    // worker, so both are re-read on refreshKey rather than cached with the typed fields above.
    val exportTree = remember(refreshKey) { SettingsStore(ctx).exportTreeUri }
    val exportLedger = remember(refreshKey) { ExportLedger(ctx) }
    val grantHeld = remember(refreshKey) {
        exportTree.isNotBlank() && ExportWatchWorker.grantStillHeld(ctx, Uri.parse(exportTree))
    }
    // A grant taken before 2026-09-12 is read-only: it ships but cannot delete a confirmed zip.
    // Read straight from the persisted permissions, so the row is right before the first scan.
    val grantCanWrite = remember(refreshKey) {
        grantHeld && ExportWatchWorker.grantCanWrite(ctx, Uri.parse(exportTree))
    }

    var healthUrl by remember { mutableStateOf(settings.healthUrl) }
    var healthToken by remember { mutableStateOf(settings.healthToken) }
    var presenceUrl by remember { mutableStateOf(settings.presenceUrl) }
    var presenceToken by remember { mutableStateOf(settings.presenceToken) }
    var ackUrl by remember { mutableStateOf(settings.ackUrl) }
    var blocklistUrl by remember { mutableStateOf(settings.blocklistUrl) }
    var blocklistSecret by remember { mutableStateOf(settings.blocklistSecret) }

    var deleteZips by remember { mutableStateOf(settings.deleteShippedZips) }

    // The text feed's status is written by two workers and a receiver, so it is re-read on
    // refreshKey like the export card's, not cached with the typed fields.
    val smsLedger = remember(refreshKey) { SmsLedger(ctx) }
    val smsPending = remember(refreshKey) { SmsStore(ctx).pendingCount() }
    val smsReadHeld = remember(refreshKey) { SmsBackfillWorker.hasReadSms(ctx) }
    val notifAccess = remember(refreshKey) { MessageNotificationListener.accessGranted(ctx) }
    val notifPackages = remember(refreshKey) { settings.notifPackages }

    // EVERY SWITCH SAVES ITSELF THE MOMENT IT IS FLIPPED. The typed fields below are a draft until
    // "Save changes" — a half-typed URL must not go live keystroke by keystroke — and the switches
    // used to ride the same draft. That was invisible for three releases because the three older
    // switches default ON and got saved along with the URL beside them; the text feed is the first
    // switch that defaults OFF, and its first live run (2026-09-15 23:52) showed exactly what a
    // draft switch costs: flipped on, tab changed, off again — `SettingsScreen` is disposed with
    // the draft when the tab changes, and the Save button was four cards below. Worse, the same
    // card's status rows and buttons read the PERSISTED flag, so it showed "on" beside "text feed
    // off" and greyed buttons. A switch has nothing to validate, so it commits on the tap, the way
    // "Delete zips after confirmed ship" always has; the persisted value is the only value shown.
    val healthOn = remember(refreshKey) { settings.healthEnabled }
    val presenceOn = remember(refreshKey) { settings.presenceEnabled }
    val blocklistOn = remember(refreshKey) { settings.blocklistEnabled }
    val smsOn = remember(refreshKey) { settings.smsEnabled }
    val smsGate = remember(refreshKey) { settings.smsUnconfiguredReason() }
    var hours by remember { mutableStateOf(settings.healthIntervalHours.toString()) }

    // Presence signal: hold + push-link status is read fresh on refreshKey the way the export + sms
    // cards' status rows are, since the link service writes it, not this screen. The pattern editor
    // (preset + custom on/off ms pairs) and the two per-surface amplitude sliders save as they move,
    // same as the switches above — nothing here needs a Save button.
    val presenceSignalStore = remember(refreshKey) { PresenceSignalStore(ctx) }
    var presencePreviewPattern by remember(refreshKey) { mutableStateOf(settings.presencePreviewPattern) }
    var presenceCustomPattern by remember(refreshKey) { mutableStateOf(settings.presenceCustomPatternMs) }
    var presencePhoneAmplitude by remember(refreshKey) { mutableStateOf(settings.presencePhoneAmplitude) }
    var presenceWatchAmplitude by remember(refreshKey) { mutableStateOf(settings.presenceWatchAmplitude) }
    val presenceCustomPatternInvalid = presenceCustomPattern.isNotBlank() &&
        PresenceHapticPatterns.parseCustomPattern(presenceCustomPattern) == null

    var dirty by remember { mutableStateOf(false) }
    val touched = { dirty = true }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {

        SectionCard("Health feed") {
            SwitchRow("Enabled", healthOn) {
                settings.healthEnabled = it
                HealthSyncWorker.enqueuePeriodic(ctx)
                refresh()
            }
            Field("Server URL", healthUrl, "http://100.x.y.z:8765/health-ingest") {
                healthUrl = it; touched()
            }
            SecretField("Bearer token", healthToken) { healthToken = it; touched() }
            Field("Sync every (hours)", hours, "1", numeric = true) { hours = it; touched() }
            Hint("The token must match HEALTH_INGEST_TOKEN on the listener, or every POST gets a 401.")
        }

        // Not a typed field: a folder grant is a permission the OS hands out, so this card is a
        // status + three actions, in the Status screen's dot-and-line shape, and it saves itself.
        SectionCard("Samsung Health export folder") {
            when {
                exportTree.isBlank() ->
                    StatusRow(StatusWarn, "Folder", "not granted", emphasis = true)
                !grantHeld ->
                    StatusRow(StatusBad, "Folder", "grant lost — choose again", emphasis = true)
                else ->
                    StatusRow(StatusOk, "Folder", folderLabel(exportTree), emphasis = true)
            }
            if (exportTree.isNotBlank()) {
                StatusRow(
                    if (exportLedger.lastScanMs == 0L) StatusIdle else if (exportLedger.ok) StatusOk else StatusBad,
                    "Last scan",
                    if (exportLedger.lastScanMs == 0L) "not run yet" else agoText(exportLedger.lastScanMs),
                )
                StatusRow(
                    StatusIdle, "Newest export seen",
                    exportLedger.lastSeenName.ifBlank { "none yet" }.removePrefix("samsunghealth_"),
                )
                StatusRow(
                    if (exportLedger.lastShippedMs == 0L) StatusIdle else StatusOk,
                    "Last shipped",
                    if (exportLedger.lastShippedMs == 0L) "never"
                    else "${exportLedger.lastShippedName.removePrefix("samsunghealth_")} · ${agoText(exportLedger.lastShippedMs)}" +
                        // Exports are cumulative: only the newest ships, the older pending ones it
                        // contained were superseded on the same pass (ruling 2026-09-12 23:42).
                        (if (exportLedger.lastShipSupersededCount > 0)
                            " · ${exportLedger.lastShipSupersededCount} older superseded" else ""),
                )
                // The S23 exports a FOLDER; the worker zips it here before shipping. Red while a
                // folder will not zip whole (a child unreadable, the cache full) — nothing partial ships.
                StatusRow(
                    when {
                        exportLedger.lastZipFailed.isNotBlank() -> StatusBad
                        exportLedger.lastZippedMs == 0L -> StatusIdle
                        else -> StatusOk
                    },
                    "Last zipped",
                    when {
                        exportLedger.lastZipFailed.isNotBlank() ->
                            "FAILED: ${exportLedger.lastZipFailed.removePrefix("samsunghealth_")}"
                        exportLedger.lastZippedMs == 0L -> "none yet (folder exports only)"
                        else -> "${exportLedger.lastZippedName.removePrefix("samsunghealth_")} · " +
                            "${exportLedger.lastZippedFiles} files, ${exportLedger.lastZippedBytes / 1_000_000} MB · " +
                            agoText(exportLedger.lastZippedMs)
                    },
                )
                // Same red as "grant lost": a read-only grant is a grant that needs re-choosing.
                if (grantHeld && !grantCanWrite) {
                    StatusRow(StatusBad, "Cleanup", ExportWatchWorker.CLEANUP_BLOCKED_LINE, emphasis = true)
                }
                StatusRow(
                    when {
                        exportLedger.lastDeleteFailedName.isNotBlank() -> StatusBad
                        exportLedger.lastDeletedMs == 0L -> StatusIdle
                        else -> StatusOk
                    },
                    "Last deleted",
                    when {
                        exportLedger.lastDeleteFailedName.isNotBlank() ->
                            "FAILED: ${exportLedger.lastDeleteFailedName.removePrefix("samsunghealth_")}"
                        exportLedger.lastDeletedMs == 0L -> "none yet"
                        else -> "${exportLedger.lastDeletedName.removePrefix("samsunghealth_")} · " +
                            "${agoText(exportLedger.lastDeletedMs)} (${exportLedger.deletedCount} total)"
                    },
                )
                if (exportLedger.detail.isNotBlank()) Hint(exportLedger.detail)
            }
            // Saves itself, like the rest of this card: the switch is read by the next scan. Only the
            // zip half is optional — an export FOLDER is always deleted once the desktop confirms it.
            SwitchRow("Delete zips after confirmed ship", deleteZips) {
                deleteZips = it
                settings.deleteShippedZips = it
            }
            Hint(
                "Samsung Health → Settings → Download personal data writes a samsunghealth_… folder " +
                    "(older builds: a .zip) into Download/Samsung Health. Grant that folder once and " +
                    "every new export zips itself here and ships to the health listener within the " +
                    "hour. The export folder is deleted only after the desktop confirms it has it; a " +
                    "zip that was already in the folder is deleted on the same terms while the switch " +
                    "above is on. Nothing else in the folder is ever touched."
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { host.pickExportFolder(refresh) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (exportTree.isBlank()) "Choose folder" else "Change folder") }
                OutlinedButton(
                    onClick = { host.scanExportsNow(); refresh() },
                    modifier = Modifier.weight(1f),
                    enabled = grantHeld && settings.healthConfigured(),
                ) { Text("Scan now") }
            }
            if (exportTree.isNotBlank()) {
                TextButton(
                    onClick = { host.forgetExportFolder(); refresh() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) { Text("Forget folder") }
            }
            if (grantHeld && !settings.healthConfigured()) {
                Hint("The export ships to the health feed's server above — set that URL first.")
            }
        }

        SectionCard("Presence feed") {
            SwitchRow("Enabled", presenceOn) {
                settings.presenceEnabled = it
                PresenceSyncWorker.enqueuePeriodic(ctx)
                host.reconnectPresenceLink()
                refresh()
            }
            Field("Server URL", presenceUrl, "http://100.x.y.z:8765/presence-ingest") {
                presenceUrl = it; touched()
            }
            SecretField("Bearer token", presenceToken) { presenceToken = it; touched() }
            Field("Wrist-ack URL (optional)", ackUrl, "blank = same host, /ack-ingest") {
                ackUrl = it; touched()
            }
            Hint("Geofence, activity and sleep transitions. Coordinates never leave the phone — only the place name.")
        }

        SectionCard("Presence signal — \"I'm here\"") {
            StatusRow(
                if (presenceSignalStore.holdActive) StatusOk else StatusIdle,
                "Right now",
                if (presenceSignalStore.holdActive) "holding (${presenceSignalStore.holdPattern})" else "idle",
            )
            // The push link (2026-10-06): Margo PUSHES down a connection this phone holds open —
            // nothing here asks on a timer. "Last heard" counts the desktop's heartbeat too, so a
            // healthy idle link reads "about a minute ago" at worst.
            val linkState = presenceSignalStore.linkState
            StatusRow(
                when (linkState) {
                    "connected" -> StatusOk
                    "connecting" -> StatusWarn
                    "off" -> StatusIdle
                    else -> StatusBad
                },
                "Link",
                when (linkState) {
                    "connected" -> "connected · ${agoText(presenceSignalStore.linkSinceMs)}"
                    "connecting" -> "connecting…"
                    "" -> "not started — open the app or Save once"
                    else -> "$linkState — ${presenceSignalStore.linkDetail}"
                },
                emphasis = linkState != "connected",
            )
            if (presenceSignalStore.lastHeardMs != 0L) {
                StatusRow(StatusIdle, "Last heard", agoText(presenceSignalStore.lastHeardMs))
            }
            if (!host.isPresenceBatteryUnrestricted()) {
                StatusRow(StatusWarn, "Battery", "optimized — the link can be cut while idle", emphasis = true)
                OutlinedButton(
                    onClick = { host.requestPresenceBatteryUnrestricted() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) { Text("Let Margo stay connected (unrestricted battery)") }
            }
            if (linkState != "connected" && linkState != "connecting") {
                TextButton(
                    onClick = { host.reconnectPresenceLink(); refresh() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reconnect now") }
            }

            Text("Pattern", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresenceHapticPatterns.NAMED_PATTERNS.forEach { name ->
                    val selected = presencePreviewPattern == name
                    OutlinedButton(
                        onClick = { presencePreviewPattern = name; settings.presencePreviewPattern = name },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (selected) "✓ $name" else name, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Field(
                "Custom rhythm (ms, on/off pairs — blank = use the preset above)",
                presenceCustomPattern,
                "400,100,400,100",
            ) { presenceCustomPattern = it; settings.presenceCustomPatternMs = it }
            if (presenceCustomPatternInvalid) {
                Text(
                    "Not a valid pattern — an even count of positive on/off millisecond pairs " +
                        "(${PresenceHapticPatterns.MIN_SEGMENT_MS}-${PresenceHapticPatterns.MAX_SEGMENT_MS} " +
                        "each, at most ${PresenceHapticPatterns.MAX_CUSTOM_SEGMENTS / 2} pairs). Ignored " +
                        "until it parses — the preset above plays instead.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Text(
                if (presencePhoneAmplitude == 0) "Phone intensity — off (sits this out)"
                else "Phone intensity — $presencePhoneAmplitude of ${PresenceHapticPatterns.maxAmplitude()}",
                Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = presencePhoneAmplitude.toFloat(),
                onValueChange = { presencePhoneAmplitude = it.toInt() },
                onValueChangeFinished = { settings.presencePhoneAmplitude = presencePhoneAmplitude },
                valueRange = 0f..PresenceHapticPatterns.maxAmplitude().toFloat(),
            )
            Text(
                if (presenceWatchAmplitude == 0) "Watch intensity — off (sits this out)"
                else "Watch intensity — $presenceWatchAmplitude of ${PresenceHapticPatterns.maxAmplitude()}",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = presenceWatchAmplitude.toFloat(),
                onValueChange = { presenceWatchAmplitude = it.toInt() },
                onValueChangeFinished = { settings.presenceWatchAmplitude = presenceWatchAmplitude },
                valueRange = 0f..PresenceHapticPatterns.maxAmplitude().toFloat(),
            )

            Hint(
                "A ping is one gentle tap; a hold is a slow repeating pulse while it's active — on " +
                    "this phone and, at its own slider above, on the watch if one is paired. The " +
                    "ceiling (${PresenceHapticPatterns.maxAmplitude()}) is the device's own gentle " +
                    "notification-level haptic — hardware, not a setting; neither slider can cross " +
                    "it. All the way down (0) means this device sits the signal out — no vibration, " +
                    "but Stop still works here and still stops it everywhere. Margo starts and ends " +
                    "a hold from chat; Stop below (or the notification's own Stop) ends it " +
                    "everywhere and tells her you did."
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { host.testPresenceSignalTap() },
                    modifier = Modifier.weight(1f),
                ) { Text("Test tap") }
                OutlinedButton(
                    onClick = { host.testPresenceSignalPattern() },
                    modifier = Modifier.weight(1f),
                ) { Text("Test pattern") }
                if (presenceSignalStore.holdActive) {
                    OutlinedButton(
                        onClick = { host.stopPresenceSignalNow(); refresh() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                }
            }
        }

        // Default OFF, and the only card whose switch is: this one ships message bodies. It rides
        // the presence server (same origin, /sms-ingest, same token) so there is no URL to type.
        SectionCard("Text messages") {
            SwitchRow("Enabled", smsOn) { on ->
                settings.smsEnabled = on
                SmsSyncWorker.enqueuePeriodic(ctx)
                SmsBackfillWorker.enqueuePeriodic(ctx)
                // Just switched on with the permission held: run the first backfill now, so the card
                // shows a result instead of "not run yet" until the daily tick.
                if (on && settings.smsConfigured() && SmsBackfillWorker.hasReadSms(ctx)) {
                    SmsBackfillWorker.runNow(ctx)
                }
                refresh()
            }
            // The one thing standing between the switch and the wire, if anything is — the same
            // sentence the worker writes and the "Backfill now" toast says.
            if (smsOn && smsGate != null) StatusRow(StatusWarn, "Feed", smsGate, emphasis = true)
            StatusRow(
                when {
                    !smsOn -> StatusIdle
                    smsLedger.lastReceivedMs == 0L -> StatusIdle
                    else -> StatusOk
                },
                "Last received",
                if (smsLedger.lastReceivedMs == 0L) "none yet"
                else "${agoText(smsLedger.lastReceivedMs)} (${smsLedger.receivedCount} total)",
            )
            StatusRow(
                when {
                    smsLedger.lastFlushMs == 0L -> StatusIdle
                    smsLedger.flushOk -> StatusOk
                    else -> StatusBad
                },
                "Last flush",
                when {
                    smsLedger.lastFlushMs == 0L -> "not run yet"
                    smsLedger.flushOk -> "${smsLedger.lastFlushSent} sent · ${agoText(smsLedger.lastFlushMs)}"
                    else -> "FAILED · ${agoText(smsLedger.lastFlushMs)}"
                } + if (smsPending > 0) " · $smsPending waiting" else "",
            )
            StatusRow(
                when {
                    smsLedger.lastBackfillMs == 0L -> StatusIdle
                    smsLedger.backfillOk -> StatusOk
                    else -> StatusBad
                },
                "Last backfill",
                when {
                    smsLedger.lastBackfillMs == 0L -> "not run yet"
                    smsLedger.backfillOk ->
                        "${smsLedger.lastBackfillConfirmed} confirmed · ${agoText(smsLedger.lastBackfillMs)} " +
                            "(${smsLedger.backfillTotal} total)"
                    else -> "FAILED · ${agoText(smsLedger.lastBackfillMs)}"
                },
            )
            StatusRow(
                when {
                    !smsOn || !notifAccess -> StatusIdle
                    smsLedger.lastNotifMs == 0L -> StatusIdle
                    else -> StatusOk
                },
                "Last notification",
                when {
                    !notifAccess -> "no notification access — Access tab"
                    smsLedger.lastNotifMs == 0L -> "none yet"
                    else -> "${agoText(smsLedger.lastNotifMs)} (${smsLedger.notifCount} total)"
                },
            )
            val detail = if (!smsLedger.backfillOk && smsLedger.backfillDetail.isNotBlank()) smsLedger.backfillDetail
                         else if (!smsLedger.flushOk && smsLedger.flushDetail.isNotBlank()) smsLedger.flushDetail
                         else ""
            if (detail.isNotBlank()) Hint(detail)
            Hint(
                "Each text is sent to the presence server above (same address, /sms-ingest, same " +
                    "token) as it arrives, and a daily backfill reads the phone's own message store " +
                    "— inbox and sent — so both sides of a conversation land. Bodies stay on your " +
                    "own network; nothing here reaches Telegram. Requires the SMS permissions on " +
                    "the Access tab."
            )
            // Phase 2: which apps' notifications are read. Each switch saves on the tap, like every
            // switch on this tab. RCS arrives only through Google Messages' notification.
            Text(
                "Read messages from these apps' notifications",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            for ((pkg, label) in SettingsStore.NOTIF_APPS) {
                SwitchRow(label, pkg in notifPackages) { on ->
                    settings.setNotifPackage(pkg, on)
                    refresh()
                }
            }
            Hint(
                "RCS chats are not in the SMS store; their notification is the only way Margo can " +
                    "see them. Incoming only, and only what the notification shows — no history, " +
                    "nothing from a muted chat. Needs Notification access on the Access tab."
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { host.backfillSmsNow(); refresh() },
                    modifier = Modifier.weight(1f),
                    enabled = smsOn && smsGate == null && smsReadHeld,
                ) { Text("Backfill now") }
                TextButton(
                    onClick = { host.resetSmsBackfill(); refresh() },
                    modifier = Modifier.weight(1f),
                    enabled = smsLedger.watermarkMs != 0L,
                ) { Text("Re-read all") }
            }
            if (smsOn && !smsReadHeld) {
                Hint("The SMS permissions are not granted yet — Access tab.")
            }
        }

        SectionCard("Call screening") {
            SwitchRow("Sync blocklist", blocklistOn) {
                settings.blocklistEnabled = it
                BlocklistSyncWorker.enqueuePeriodic(ctx)
                refresh()
            }
            Field("Blocklist URL", blocklistUrl, "https://…/blocklist") { blocklistUrl = it; touched() }
            SecretField("Blocklist secret", blocklistSecret) { blocklistSecret = it; touched() }
        }

        Button(
            onClick = {
                settings.healthUrl = healthUrl
                settings.healthToken = healthToken
                settings.presenceUrl = presenceUrl
                settings.presenceToken = presenceToken
                settings.ackUrl = ackUrl
                settings.blocklistUrl = blocklistUrl
                settings.blocklistSecret = blocklistSecret
                settings.healthIntervalHours =
                    hours.toIntOrNull() ?: SettingsStore.DEFAULT_HEALTH_HOURS
                // The switches are not here: each one saved itself when it was flipped (above).

                // Re-enqueue so a changed interval or URL takes effect NOW rather than whenever the
                // old schedule next happens to fire. UPDATE keeps the existing work id, so this
                // replaces the schedule instead of stacking a second one.
                HealthSyncWorker.enqueuePeriodic(ctx)
                PresenceSyncWorker.enqueuePeriodic(ctx)
                SmsSyncWorker.enqueuePeriodic(ctx)
                SmsBackfillWorker.enqueuePeriodic(ctx)
                // A presence URL typed AFTER the text switch went on is the feed's first chance to
                // backfill — the switch handler found nowhere to POST at the time.
                if (settings.smsConfigured() && SmsBackfillWorker.hasReadSms(ctx) &&
                    !SmsLedger(ctx).backfillOk) {
                    SmsBackfillWorker.runNow(ctx)
                }
                // A changed presence URL/token: the push link reconnects with it now.
                host.reconnectPresenceLink()
                dirty = false
                host.toast("Saved.")
                refresh()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            enabled = dirty,
        ) { Text(if (dirty) "Save changes" else "Saved") }

        Text(
            "Settings live on this phone only. They are never sent anywhere, and they are excluded " +
                "from cloud backup so the tokens cannot leave the device that way.",
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `content://…/tree/primary%3ADownload%2FSamsung%20Health` → `Download/Samsung Health`. The tree
 *  URI's last segment is the provider's document id, `<volume>:<path>` for the external-storage
 *  provider; anything else is shown as-is rather than guessed at. */
private fun folderLabel(treeUri: String): String {
    val last = Uri.parse(treeUri).lastPathSegment ?: return treeUri
    return last.substringAfter(':', last).ifBlank { last }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/** Masked by default with a reveal toggle — a token you cannot see is a token you cannot check for a
 *  trailing space, and a token permanently on screen is one a shoulder reads. */
@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
        ),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
