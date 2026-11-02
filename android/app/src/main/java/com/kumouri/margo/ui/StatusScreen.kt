package com.kumouri.margo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kumouri.margo.BlocklistStore
import com.kumouri.margo.BuildConfig
import com.kumouri.margo.PlacesStore
import com.kumouri.margo.SettingsStore
import com.kumouri.margo.SyncStatus

/**
 * "Is it working, and if not, what exactly is wrong."
 *
 * The screen that did not exist on 2026-08-11, when the health feed was broken four ways and the
 * phone's entire report was a blank screen. Every row here answers a question that evening had to be
 * answered with a USB cable.
 */
@Composable
fun StatusScreen(host: AppHost, refreshKey: Int, refresh: () -> Unit) {
    val ctx = host.context
    // refreshKey is read so recomposition re-runs these reads; without it Compose would happily show
    // a snapshot from whenever the screen was first composed.
    val settings = remember(refreshKey) { SettingsStore(ctx) }
    val status = remember(refreshKey) { SyncStatus(ctx) }
    val blocklist = remember(refreshKey) { BlocklistStore(ctx) }
    val places = remember(refreshKey) { PlacesStore(ctx).all() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {

        if (settings.isFirstRun()) {
            SectionCard("Not set up yet") {
                Hint(
                    "No server is configured, so nothing is being sent anywhere. Open Settings and " +
                        "enter the address of your Margo listener — everything else follows from that."
                )
            }
        }

        // --- health feed ------------------------------------------------------------------------
        SectionCard("Health feed") {
            val counts = status.counts()
            val delivering = counts.filterValues { it > 0 }
            val errored = counts.filterValues { it == -1 }.keys
            val missing = status.missingPermissions()

            when {
                !settings.healthEnabled ->
                    StatusRow(StatusIdle, "Feed", "turned off", emphasis = true)
                settings.healthUrl.isBlank() ->
                    StatusRow(StatusWarn, "Feed", "no server set", emphasis = true)
                status.lastAttemptMs == 0L ->
                    StatusRow(StatusIdle, "Feed", "not run yet", emphasis = true)
                status.ok ->
                    StatusRow(StatusOk, "Last sync", agoText(status.lastOkMs), emphasis = true)
                else ->
                    StatusRow(StatusBad, "Last sync", "FAILED", emphasis = true)
            }

            if (status.detail.isNotBlank()) Hint(status.detail)

            if (status.lastAttemptMs > 0L && status.lastOkMs != status.lastAttemptMs) {
                // Attempted-but-not-delivered is its own state and gets said out loud: a feed that
                // tried 40 times and never got through must not read like one that is merely quiet.
                StatusRow(StatusWarn, "Last successful delivery", agoText(status.lastOkMs))
            }

            if (missing.isNotEmpty()) {
                StatusRow(StatusBad, "Permissions missing", "${missing.size}", emphasis = true)
                Hint(missing.joinToString(", ") { it.substringAfterLast('.') })
            }

            if (delivering.isNotEmpty()) {
                StatusRow(StatusOk, "Delivering", delivering.keys.sorted().joinToString(", "))
            }
            if (errored.isNotEmpty()) {
                StatusRow(StatusBad, "Read errored", errored.sorted().joinToString(", "))
                Hint(
                    "Several types erroring at once is a permission, not a missing record type — " +
                        "check \"read data in background\" under Access."
                )
            }

            val quiet = status.probe().filterValues { it == 0 }.keys
            if (quiet.isNotEmpty() && errored.isEmpty()) {
                StatusRow(StatusWarn, "Health Connect has none", "${quiet.size} types")
                Hint(
                    "These read fine and hold nothing, so the gap is upstream of this app: open " +
                        "Health Connect and check what your health app is allowed to write."
                )
            }

            Button(
                onClick = { host.syncHealthNow(); refresh() },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                enabled = settings.healthConfigured(),
            ) { Text("Sync health now") }
            if (!settings.healthConfigured()) {
                Hint("Enable the health feed and set a server address in Settings first.")
            }
        }

        // --- presence feed ----------------------------------------------------------------------
        SectionCard("Presence feed") {
            when {
                !settings.presenceEnabled ->
                    StatusRow(StatusIdle, "Feed", "turned off", emphasis = true)
                settings.presenceUrl.isBlank() ->
                    StatusRow(StatusWarn, "Feed", "no server set", emphasis = true)
                else -> StatusRow(StatusOk, "Feed", "on", emphasis = true)
            }
            StatusRow(
                if (places.isEmpty()) StatusWarn else StatusOk,
                "Places",
                if (places.isEmpty()) "none yet" else places.joinToString(", ") { it.name },
            )
            if (places.isEmpty()) {
                Hint("Without a place, geofence enter/exit can never fire — activity and sleep still will.")
            }
            OutlinedButton(
                onClick = { host.addPlaceHere() },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Save this spot as a place") }
        }

        // --- call screening ---------------------------------------------------------------------
        SectionCard("Call screening") {
            val held = com.kumouri.margo.callScreeningHeld(ctx)
            StatusRow(
                if (held) StatusOk else StatusWarn,
                "Screening role",
                if (held) "active" else "not granted",
                emphasis = true,
            )
            StatusRow(StatusIdle, "Blocked numbers", "${blocklist.count()}")
            StatusRow(StatusIdle, "Blocklist synced", agoText(blocklist.lastSyncedAt()))
            if (!held) {
                Button(
                    onClick = { host.requestScreeningRole(); refresh() },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("Make Margo the screening app") }
            } else {
                OutlinedButton(
                    onClick = { host.syncBlocklistNow(); refresh() },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    enabled = settings.blocklistConfigured(),
                ) { Text("Sync blocklist now") }
            }
        }

        Text(
            "Health syncs every ${settings.healthIntervalHours}h in the background. " +
                "v${BuildConfig.VERSION_NAME}",
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
