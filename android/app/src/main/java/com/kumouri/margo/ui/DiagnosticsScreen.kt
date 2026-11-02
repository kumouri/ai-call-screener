package com.kumouri.margo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kumouri.margo.EventLog
import com.kumouri.margo.HealthSyncWorker
import com.kumouri.margo.SettingsStore
import com.kumouri.margo.SyncStatus

/**
 * The screen that would have saved 2026-08-11.
 *
 * That evening the health feed failed for four independent reasons in sequence — a missing bearer
 * token, a schema mismatch, three stacked listeners on one port, and an ungranted background-read
 * permission. **All four produced the same symptom on the phone: nothing.** Diagnosis needed a USB
 * cable, `adb logcat`, and a laptop, because the app could not say what it had just tried and what
 * came back.
 *
 * So: the last attempt in full, the verbatim wire result of a test POST, and a local ring of recent
 * events. `logcat` is not an option — reading it needs `READ_LOGS`, which a normal install never
 * holds — so the app keeps its own copy of the lines that matter.
 */
@Composable
fun DiagnosticsScreen(host: AppHost, refreshKey: Int, refresh: () -> Unit) {
    val ctx = host.context
    val settings = remember(refreshKey) { SettingsStore(ctx) }
    val status = remember(refreshKey) { SyncStatus(ctx) }
    val events = remember(refreshKey) { EventLog.recent(ctx) }
    var probe by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {

        SectionCard("Test connection") {
            Hint(
                "POSTs an empty batch to the health endpoint and shows exactly what came back. " +
                    "A 401 means the token is wrong; a connection error usually means the tailnet " +
                    "is down or the address is unreachable from here."
            )
            Button(
                onClick = {
                    probing = true
                    probe = ""
                    host.testConnection { result -> probe = result; probing = false }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                enabled = !probing && settings.healthUrl.isNotBlank(),
            ) { Text(if (probing) "Testing…" else "Test connection") }
            if (settings.healthUrl.isBlank()) Hint("No health server address set.")
            if (probe.isNotBlank()) {
                Column(Modifier.padding(top = 10.dp)) { Mono(probe) }
            }
        }

        SectionCard("Last sync, in full") {
            // A manual tap is only an ENQUEUE. WorkManager decides when it runs, and on 2026-09-12
            // four taps sat for up to 2.5 h (Samsung app-freezing suspected, not proven) while this
            // card kept showing the run before them — the taps were invisible. So the pending
            // request is read from WorkManager itself, live, and shown as *waiting*, never silence.
            val pending by remember {
                WorkManager.getInstance(ctx)
                    .getWorkInfosForUniqueWorkFlow(HealthSyncWorker.UNIQUE_NOW)
            }.collectAsStateWithLifecycle(initialValue = emptyList())
            val held = pending.firstOrNull { !it.state.isFinished }
            KeyValues(
                listOfNotNull(
                    // Row values are monospace and unwrapped, so the row carries the time and the
                    // state word; the full sentence goes in the wrapping hint below.
                    held?.let { "requested" to "${whenText(status.lastRequestedMs)}  ${it.state}" },
                    "attempted" to whenText(status.lastAttemptMs),
                    "delivered" to whenText(status.lastOkMs),
                    "result" to (if (status.lastAttemptMs == 0L) "not run yet"
                                 else if (status.ok) "ok" else "FAILED"),
                )
            )
            if (held != null) {
                Hint(
                    "Requested, waiting for WorkManager (state: ${held.state}). " +
                        when (held.state) {
                            WorkInfo.State.RUNNING -> "Running now; this card updates when it finishes."
                            WorkInfo.State.BLOCKED -> "Blocked behind other work — nothing to do but wait."
                            else -> "Enqueued but not started: usually the network constraint, or the " +
                                "OS holding the app (Settings → Apps → Margo → Battery → Unrestricted)."
                        }
                )
            }
            if (status.detail.isNotBlank()) {
                Column(Modifier.padding(top = 8.dp)) { Mono(status.detail) }
            }

            val counts = status.counts()
            val probeMap = status.probe()
            if (counts.isNotEmpty()) {
                Hint("Per type — window count, then the 365-day probe:")
                KeyValues(
                    counts.keys.sorted().map { t ->
                        val c = counts[t] ?: 0
                        val p = probeMap[t]
                        val verdict = when {
                            c > 0 -> "delivering"
                            c == -1 || p == -1 -> "READ ERRORED"
                            p == null -> "no probe"
                            p > 0 -> "present, not syncing"
                            else -> "absent upstream"
                        }
                        t to "$c / ${p ?: "-"}   $verdict"
                    }
                )
                Hint(
                    "0 means the read worked and found nothing. -1 means the read itself failed — " +
                        "different problems, and collapsing them is how the last outage hid."
                )
            }
        }

        SectionCard("Recent events (${events.size})") {
            if (events.isEmpty()) {
                Hint("Nothing recorded yet. Run a sync and this fills in.")
            } else {
                Column(Modifier.padding(top = 6.dp)) {
                    events.take(EventLog.CAPACITY).forEach { Mono(it) }
                }
            }
            OutlinedButton(
                onClick = { EventLog.clear(ctx); refresh() },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                enabled = events.isNotEmpty(),
            ) { Text("Clear") }
        }

        SectionCard("Where it's pointed") {
            // The token is NOT shown, here or anywhere but the field you typed it into. Its presence
            // and length answer the question a diagnostics screen actually needs ("is one set, is it
            // plausibly the right one") without putting a live credential on screen.
            KeyValues(
                listOf(
                    "health" to settings.healthUrl.ifBlank { "(unset)" },
                    "health token" to tokenState(settings.healthToken),
                    "presence" to settings.presenceUrl.ifBlank { "(unset)" },
                    "presence token" to tokenState(settings.presenceToken),
                    "wrist acks" to settings.ackUrlOrPresence().ifBlank { "(unset)" },
                    "interval" to "${settings.healthIntervalHours}h",
                )
            )
        }
    }
}

private fun tokenState(token: String): String =
    if (token.isBlank()) "(unset)" else "set, ${token.length} chars"
