package com.kumouri.margo

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-device ring of what the workers did, so Diagnostics has something to show.
 *
 * **Why not just read logcat:** an app cannot read its own logcat on modern Android without
 * `READ_LOGS`, which is a privileged permission a normal install will never hold. Every `Log.w` this
 * app writes is therefore reachable only with a cable, a laptop and `adb` — which is precisely how
 * 2026-08-11 went, and precisely what makes a fault invisible to the person holding the phone.
 *
 * So the lines that matter are written twice: to logcat for a developer, and here for the owner. This
 * is the copy that is readable at 2am without a laptop.
 *
 * Deliberately tiny and lossy — [CAPACITY] lines, oldest dropped. It is a window, not a history; the
 * desktop's `state/health-listener/` shards are the history. Fail-open like every other writer here:
 * a logging call may never cost the thing it was logging about.
 */
object EventLog {

    const val CAPACITY = 60

    fun add(context: Context, line: String) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            val kept = (prefs.getString(KEY, "") ?: "")
                .split('\n')
                .filter { it.isNotBlank() }
                .takeLast(CAPACITY - 1)
            prefs.edit().putString(KEY, (kept + "[$stamp] $line").joinToString("\n")).apply()
        } catch (_: Exception) {
            // A log line may never cost the sync it describes.
        }
    }

    /** Newest first — the screen showing this wants the most recent event at the top. */
    fun recent(context: Context): List<String> = try {
        (context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: "")
            .split('\n').filter { it.isNotBlank() }.reversed()
    } catch (_: Exception) {
        emptyList()
    }

    fun clear(context: Context) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        } catch (_: Exception) {
        }
    }

    private const val PREFS = "margo_event_log"
    private const val KEY = "lines"
}
