package com.kumouri.margo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/** The four screens, in the order the bottom bar shows them. */
enum class Tab(val label: String, val icon: ImageVector) {
    Status("Status", Icons.Filled.Dashboard),
    Permissions("Access", Icons.Filled.Lock),
    Settings("Settings", Icons.Filled.Settings),
    Diagnostics("Debug", Icons.Filled.BugReport),
}

/**
 * The whole app. Four tabs, no navigation library — with this few destinations and no deep links, a
 * NavHost would be a dependency and an indirection buying nothing.
 *
 * **`refreshKey` is how every screen stays honest.** Each one reads SharedPreferences directly rather
 * than holding a cached copy, and bumping this key forces recomposition after anything that could
 * have changed state (a sync, a permission grant, a settings save). Compose cannot observe
 * SharedPreferences on its own, and a screen that silently shows a stale answer is the exact failure
 * mode this app exists to end.
 */
@OptIn(ExperimentalMaterial3Api::class)   // TopAppBar; stable everywhere else here
@Composable
fun MargoApp(host: AppHost) {
    var tab by remember { mutableStateOf(Tab.Status) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val refresh: () -> Unit = { refreshKey++ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Margo Call Shield") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t; refresh() },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.Status -> StatusScreen(host, refreshKey, refresh)
                Tab.Permissions -> PermissionsScreen(host, refreshKey, refresh)
                Tab.Settings -> SettingsScreen(host, refreshKey, refresh)
                Tab.Diagnostics -> DiagnosticsScreen(host, refreshKey, refresh)
            }
        }
    }
}

// --------------------------------------------------------------------------------------------
// Shared pieces
// --------------------------------------------------------------------------------------------

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, body: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            body()
        }
    }
}

/** A coloured dot plus a line. The dot carries the state; the text always says it too, because a
 *  colour alone is unreadable to a chunk of people and unreadable to everyone in sunlight. */
@Composable
fun StatusRow(dot: Color, label: String, value: String, emphasis: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        // THE WEIGHT BELONGS ON THE VALUE, NOT THE LABEL.
        //
        // It was on the label, so the label absorbed whatever space the value left — and a long value
        // ("body_fat, hr, sleep_session, spo2, steps, weight") left almost none, squeezing "Delivering"
        // into a column ONE CHARACTER WIDE. Six stacked letters down the side of the card.
        //
        // With the weight here the label keeps its intrinsic width and the value wraps into what
        // remains, which is the right way round: the label is short and fixed, the value is the part
        // whose length we do not control.
        Text(
            label,
            Modifier.padding(start = 10.dp, end = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            value,
            Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasis) dot else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun Hint(text: String) {
    Text(
        text,
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun Mono(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun KeyValues(pairs: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        pairs.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** "never", or a real local timestamp. Never a bare epoch and never "unknown" — both of those send
 *  someone hunting for a bug when the honest answer is "this has not happened yet". */
fun whenText(ms: Long): String =
    if (ms <= 0L) "never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        .format(Date(ms))

fun agoText(ms: Long): String {
    if (ms <= 0L) return "never"
    val mins = (System.currentTimeMillis() - ms) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}
