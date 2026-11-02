package com.kumouri.margo.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kumouri.margo.HealthSyncWorker
import com.kumouri.margo.MessageNotificationListener
import com.kumouri.margo.SmsLedger
import com.kumouri.margo.SyncStatus
import com.kumouri.margo.callScreeningHeld

/**
 * Every permission, whether it is granted, and — the part that matters — WHAT BREAKS WITHOUT IT.
 *
 * `READ_HEALTH_DATA_IN_BACKGROUND` is why this screen exists. It was declared in the manifest, never
 * requested, therefore never granted, and its absence does not fail: Health Connect simply returns
 * nothing to a backgrounded reader. A feed silently delivering zero rows for weeks looked exactly
 * like a person who had not moved. A permission whose absence is invisible has to be made visible
 * somewhere, and this is that somewhere.
 */
@Composable
fun PermissionsScreen(host: AppHost, refreshKey: Int, refresh: () -> Unit) {
    val ctx = host.context
    val missingHealth = remember(refreshKey) { SyncStatus(ctx).missingPermissions() }
    val backgroundHealthMissing = missingHealth.any { it.endsWith("READ_HEALTH_DATA_IN_BACKGROUND") }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {

        SectionCard("Health Connect") {
            val total = HealthSyncWorker.REQUESTED_PERMISSIONS.size
            val granted = total - missingHealth.size
            StatusRow(
                if (missingHealth.isEmpty()) StatusOk else StatusBad,
                "Granted",
                "$granted of $total",
                emphasis = true,
            )
            if (backgroundHealthMissing) {
                StatusRow(StatusBad, "Background read", "MISSING", emphasis = true)
                Hint(
                    "This is the one that fails silently. Without it Health Connect lets this app " +
                        "read only data it wrote itself — so a background sync returns nothing at " +
                        "all, and looks exactly like a quiet day."
                )
            }
            if (missingHealth.isNotEmpty()) {
                Hint("Ungranted: " + missingHealth.joinToString(", ") { it.substringAfterLast('.') })
            }
            Button(
                onClick = { host.requestHealthPermissions(); refresh() },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text(if (missingHealth.isEmpty()) "Review health permissions" else "Grant health permissions") }
            OutlinedButton(
                onClick = { host.openHealthConnect() },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("Open Health Connect") }
            Hint(
                "Granting here lets Margo READ. What your health app WRITES into Health Connect is a " +
                    "separate setting inside that app — if a type reads fine but is always empty, " +
                    "that is where to look."
            )
        }

        SectionCard("Location — presence feed") {
            val fine = ctx.granted(Manifest.permission.ACCESS_FINE_LOCATION)
            val background = ctx.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            StatusRow(if (fine) StatusOk else StatusBad, "Precise location", yn(fine), emphasis = !fine)
            StatusRow(
                if (background) StatusOk else StatusWarn,
                "All the time",
                yn(background),
                emphasis = !background,
            )
            if (!background) {
                Hint(
                    "Geofences only fire while the app is open without this. Android will not let an " +
                        "app ask for it directly — it has to be chosen on the system settings page."
                )
            }
            if (!fine) {
                Button(
                    onClick = { host.requestLocationPermission(); refresh() },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("Grant location") }
            }
            OutlinedButton(
                onClick = { host.openAppSettings() },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("Open system settings") }
        }

        SectionCard("Activity + sleep") {
            val ar = ctx.granted(Manifest.permission.ACTIVITY_RECOGNITION)
            StatusRow(if (ar) StatusOk else StatusWarn, "Activity recognition", yn(ar), emphasis = !ar)
            Hint(
                "Still / walking / driving transitions, and the Sleep API. The driving signal is what " +
                    "defers a nudge while you are at the wheel."
            )
            if (!ar) {
                Button(
                    onClick = { host.requestActivityRecognition(); refresh() },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("Grant activity recognition") }
            }
        }

        SectionCard("Text messages") {
            val receive = ctx.granted(Manifest.permission.RECEIVE_SMS)
            val read = ctx.granted(Manifest.permission.READ_SMS)
            StatusRow(if (receive) StatusOk else StatusWarn, "Receive SMS", yn(receive), emphasis = !receive)
            StatusRow(if (read) StatusOk else StatusWarn, "Read SMS", yn(read), emphasis = !read)
            Hint(
                "Receive is the live feed (each text as it lands); Read is the backfill over the " +
                    "phone's own message store, inbox and sent. Neither makes this the messaging app " +
                    "— it never sends, never writes, never becomes the default. RCS chats are not " +
                    "in that store — the card below covers them. Granting here ships NOTHING by " +
                    "itself: the feed also has to be switched on in Settings."
            )
            if (!receive || !read) {
                Button(
                    onClick = { host.requestSmsPermissions(); refresh() },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("Grant SMS permissions") }
            }
        }

        SectionCard("Message notifications — RCS & chat apps") {
            val granted = remember(refreshKey) { MessageNotificationListener.accessGranted(ctx) }
            val ledger = remember(refreshKey) { SmsLedger(ctx) }
            // Red only for a grant this phone HAD and lost — an update or battery optimisation can take
            // it — the way the export card shows a lost folder grant; never granted is only yellow.
            val lost = !granted && ledger.listenerConnectedMs != 0L
            val unbound = granted && ledger.listenerDisconnectedMs > ledger.listenerConnectedMs
            StatusRow(
                when {
                    lost -> StatusBad
                    !granted || unbound -> StatusWarn
                    else -> StatusOk
                },
                "Notification access",
                when {
                    lost -> "LOST — was granted"
                    !granted -> "not granted"
                    unbound -> "granted, unbound — rebind requested"
                    else -> "granted"
                },
                emphasis = !granted || unbound,
            )
            Hint(
                "RCS chats never reach the SMS store; the notification Google Messages posts is the " +
                    "only way to see them (Signal and WhatsApp too, if you switch them on in " +
                    "Settings). Margo reads only what the notification shows — no history, nothing " +
                    "from a muted chat, only messages sent to you. Like the SMS feed, nothing ships " +
                    "until Text messages is on in Settings. If the toggle is greyed out as a " +
                    "\"restricted setting\", open this app's App info, ⋮ menu, Allow restricted " +
                    "settings, then come back."
            )
            Button(
                onClick = { host.openNotificationAccessSettings(); refresh() },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text(if (granted) "Review notification access" else "Grant notification access") }
        }

        SectionCard("Call screening") {
            val held = callScreeningHeld(ctx)
            StatusRow(if (held) StatusOk else StatusWarn, "Screening role", yn(held), emphasis = !held)
            Hint("Without the role, calls are not screened at all — the blocklist is just a list.")
            if (!held) {
                Button(
                    onClick = { host.requestScreeningRole(); refresh() },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("Request screening role") }
            }
        }
    }
}

private fun Context.granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun yn(b: Boolean) = if (b) "granted" else "not granted"
