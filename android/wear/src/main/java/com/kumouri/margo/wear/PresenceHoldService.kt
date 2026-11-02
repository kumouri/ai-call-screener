package com.kumouri.margo.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import org.json.JSONObject

/**
 * The watch's own foreground service for an active hold — and, since 2026-10-06, the thing that keeps
 * it ACTUALLY VIBRATING for its whole window.
 *
 * **Why the wrist stopped after ~3 pulses (2026-10-06 21:49 CT; the same symptom as 2026-09-22's
 * "pulse dies at ~60 s").** This service used to issue ONE infinite repeating waveform at hold-start
 * and never touch the motor again. Being a promoted foreground service kept the PROCESS alive (the
 * 2026-09-18 fix), but it never protected the VIBRATION: the platform's vibrator service cancels a
 * non-system app's ongoing vibration when the screen goes non-interactive (a wrist lowered after a
 * glance, a palm over the face — only a plain timeout is spared), and a higher-importance vibration
 * replaces it outright. After any of those the notification and OngoingActivity still said "Margo is
 * here" while the motor was silent, because nothing re-issued it. The evidence that re-issuing is the
 * cure: on 2026-09-22 a keepalive job that re-started the hold every 50 s was the only thing that kept
 * her wrist buzzing. Now [PresenceHoldKeeper] plays the rhythm in ~10 s chunks of whole cycles,
 * re-issues each the moment it ends, re-issues immediately after `ACTION_SCREEN_OFF`, and holds a
 * partial wake lock so those re-issues are never late. Each re-issue is a `MargoWear` logcat line
 * (`trigger=screen-off|chunk`), and the hold-end ack carries the counts to the desktop's delivery
 * ledger — so whether screen-off was the culprit is readable without a cable.
 *
 * **It acks.** Hold playing → `played` (or `silenced` if it isn't for the wrist / its slider is 0);
 * the hold ending → `hold-end` with `played_ms`, `reissues`, `screen_off_reissues` and why; a relayed
 * stop → `hold-stop`/`stopped`. Via the phone (`PhoneBridge.signalAck`), to the desktop.
 *
 * **Two distinct stop paths (2026-09-19, §5.3, "Stop stops everything, everywhere, always").** A stop
 * that ORIGINATES here (the notification's action, the in-app button) relays outward so the phone and
 * the desktop stop too — [stopFromUi]. A stop RELAYED FROM the phone must NOT relay back —
 * [stopSelfLocally] / [stopFromRelay]. **The outward relay now runs off the main thread**: it used to
 * call `PhoneBridge.send` (a blocking `Tasks.await`) straight from `onStartCommand`, and Play services
 * refuses `await` on the main thread — so the relay threw, was caught and logged, and a watch Stop
 * never reached the phone at all.
 */
class PresenceHoldService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null
    private lateinit var keeper: PresenceHoldKeeper
    private var holdPushId = ""
    private var holdSeq = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        keeper = PresenceHoldKeeper(this, TAG)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFromUi()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_RELAYED) {
            // The phone told us to stop (chat, the phone's own Stop, or the desktop's expiry push).
            // No relay back — the phone already knows (§5.3).
            val pushId = intent.getStringExtra(EXTRA_PUSH_ID) ?: ""
            val seq = intent.getLongExtra(EXTRA_SEQ, 0L)
            stopSelfLocally("stopped-by-phone", ackPushId = pushId, ackSeq = seq)
            return START_NOT_STICKY
        }
        if (intent == null) {
            // A sticky restart after the process died: the hold's details died with it. The phone
            // re-relays a hold only when it starts a new one, so say so and stand down rather than
            // pretending to hold.
            Log.w(TAG, "hold service restarted with no hold to resume; standing down")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val pattern = intent.getStringExtra(EXTRA_PATTERN) ?: PresenceHapticPatterns.DEFAULT_PATTERN
        val amplitude = intent.getIntExtra(EXTRA_AMPLITUDE, DEFAULT_AMPLITUDE)
        val segments = PresenceHapticPatterns.parseCustomPattern(intent.getStringExtra(EXTRA_SEGMENTS) ?: "")
        val expiresAtMs = intent.getLongExtra(EXTRA_EXPIRES_AT_MS, 0L)
        val surface = intent.getStringExtra(EXTRA_SURFACE) ?: "both"
        val pushId = intent.getStringExtra(EXTRA_PUSH_ID) ?: ""
        val seq = intent.getLongExtra(EXTRA_SEQ, 0L)

        try {
            startForeground(NOTIFICATION_ID, buildNotification(pattern))
        } catch (e: Exception) {
            // No chrome-free escape hatch on the watch: a denied start is a silent hold. Said to the
            // desktop as `failed` rather than only logged.
            Log.w(TAG, "startForeground denied on the watch: ${e.message}")
            ack(pushId, seq, "hold-start", "failed", JSONObject().put("detail", "watch foreground start denied"))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (keeper.active) {
            // A new hold (or a re-armed one) replaces the running one: report the old one's end.
            ack(holdPushId, holdSeq, "hold-end", "ended", endCounters("replaced"))
        }
        holdPushId = pushId
        holdSeq = seq
        PresenceHolding.holding = true
        // `surface == "phone"` (2026-09-19) means this hold wasn't asked to be felt on the wrist;
        // amplitude 0 means SHE turned the wrist slider all the way down. Either way the hold is
        // still tracked and the stop timer still arms — only the vibration is skipped.
        val felt = surface != "phone" && PresenceHapticPatterns.shouldVibrate(amplitude)
        val playback = if (felt) PresenceHapticPatterns.holdChunk(this, pattern, amplitude, segments) else null
        keeper.start(playback?.effect, playback?.durationMs ?: 0L, expiresAtMs)
        armStopTimer(expiresAtMs)
        Log.i(TAG, "hold start push=$pushId seq=$seq pattern=$pattern felt=$felt expiresInMs=${expiresAtMs - System.currentTimeMillis()}")
        ack(pushId, seq, "hold-start", if (felt) "played" else "silenced")
        return START_STICKY
    }

    override fun onDestroy() {
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        keeper.stop()
        PresenceHolding.holding = false
        super.onDestroy()
    }

    private fun armStopTimer(expiresAtMs: Long) {
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        if (expiresAtMs <= 0L) return
        val delay = (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val r = Runnable { stopSelfLocally("expired") }
        stopRunnable = r
        mainHandler.postDelayed(r, delay)
    }

    private fun endCounters(reason: String): JSONObject = JSONObject()
        .put("reason", reason)
        .put("played_ms", keeper.playedMs())
        .put("reissues", keeper.reissues)
        .put("screen_off_reissues", keeper.screenOffReissues)

    /** The hold ended elsewhere (expiry, or the phone relayed a stop) — no relay back. */
    private fun stopSelfLocally(reason: String, ackPushId: String? = null, ackSeq: Long = 0L) {
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        if (keeper.active) {
            val counters = endCounters(reason)
            if (ackPushId != null && ackPushId.isNotEmpty()) {
                ack(ackPushId, ackSeq, "hold-stop", "stopped", counters)
            } else {
                ack(holdPushId, holdSeq, "hold-end", "ended", counters)
            }
        }
        keeper.stop()
        stopForegroundCompat()
        stopSelf()
    }

    /** The notification's own Stop action / the watch's Stop button — relays to the phone, which
     *  relays to the desktop, so it stops everywhere (§5.3). */
    private fun stopFromUi() {
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        if (keeper.active) ack(holdPushId, holdSeq, "hold-end", "ended", endCounters("stopped-on-watch"))
        keeper.stop()
        val app = applicationContext
        Thread { PhoneBridge.send(app, PresenceSignalListenerService.PATH_HOLD_STOP, "{}") }.start()
        stopForegroundCompat()
        stopSelf()
    }

    /** Off the main thread — `PhoneBridge` blocks on the Data Layer. */
    private fun ack(pushId: String, seq: Long, kind: String, status: String, extra: JSONObject? = null) {
        val app = applicationContext
        Thread { PhoneBridge.signalAck(app, pushId, seq, kind, status, extra) }.start()
    }

    private fun stopForegroundCompat() {
        PresenceHolding.holding = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    /** Builds the ongoing notification AND wires the [OngoingActivity] onto the SAME builder — the
     *  API's own required shape (`OngoingActivity.apply` adds its extras onto the builder before it's
     *  built, not after). */
    private fun buildNotification(pattern: String): Notification {
        val nm = notificationManager()
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Margo presence", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while Margo's \"stay with me\" signal is active" }
            )
        }
        val stopIntent = Intent(this, PresenceHoldService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val label = if (pattern == PresenceHapticPatterns.PATTERN_HEARTBEAT) "heartbeat" else "here"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Margo is here")
            .setContentText("Staying with you — $label")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "Stop", stopPending)

        val touchIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(android.R.drawable.presence_online)
            .setTouchIntent(touchIntent)
            .setStatus(Status.Builder().addTemplate("Margo is here").build())
            .build()
            .apply(this)

        return builder.build()
    }

    companion object {
        private const val TAG = "MargoWear"
        private const val CHANNEL_ID = "presence-signal"
        private const val NOTIFICATION_ID = 0x5347   // "SG" (Signal) — matches the phone's own id
        private const val ACTION_STOP = "com.kumouri.margo.wear.PRESENCE_HOLD_STOP"
        /** A stop RELAYED from the phone, as opposed to [ACTION_STOP] (genuinely local). Split
         *  2026-09-19 to break a ping-pong (`presence-signal-spec.md` §5.3). */
        private const val ACTION_STOP_RELAYED = "com.kumouri.margo.wear.PRESENCE_HOLD_STOP_RELAYED"
        private const val EXTRA_PATTERN = "pattern"
        private const val EXTRA_AMPLITUDE = "amplitude"
        private const val EXTRA_SEGMENTS = "segments"
        private const val EXTRA_EXPIRES_AT_MS = "expires_at_ms"
        private const val EXTRA_SURFACE = "surface"
        private const val EXTRA_PUSH_ID = "push_id"
        private const val EXTRA_SEQ = "seq"

        /** Fallback only — [PresenceSignalListenerService] always sends a real amplitude relayed
         *  from `SettingsStore.presenceWatchAmplitude` on the phone. */
        private const val DEFAULT_AMPLITUDE = 35

        /** Returns `false` when the watch refused the foreground start outright (the caller acks
         *  `failed`); `true` means the service was asked to start and will ack for itself. */
        fun start(
            context: Context,
            pattern: String,
            amplitude: Int,
            segmentsCsv: String,
            expiresAtMs: Long,
            surface: String = "both",
            pushId: String = "",
            seq: Long = 0L,
        ): Boolean {
            val intent = Intent(context, PresenceHoldService::class.java)
                .putExtra(EXTRA_PATTERN, pattern)
                .putExtra(EXTRA_AMPLITUDE, amplitude)
                .putExtra(EXTRA_SEGMENTS, segmentsCsv)
                .putExtra(EXTRA_EXPIRES_AT_MS, expiresAtMs)
                .putExtra(EXTRA_SURFACE, surface)
                .putExtra(EXTRA_PUSH_ID, pushId)
                .putExtra(EXTRA_SEQ, seq)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService denied on the watch: ${e.message}")
                false
            }
        }

        /** The watch's OWN stop source — its notification's Stop action, or the in-app button.
         *  Relays outward (`onStartCommand` → [stopFromUi]). */
        fun stop(context: Context) {
            context.startService(Intent(context, PresenceHoldService::class.java).setAction(ACTION_STOP))
        }

        /** The hold ended because the PHONE said so — stop locally ONLY, no relay back (§5.3);
         *  [pushId]/[seq] are the stop push's own, echoed in the ack. */
        fun stopFromRelay(context: Context, pushId: String = "", seq: Long = 0L) {
            if (!PresenceHolding.holding) return  // nothing playing here; don't spin a service up to stop it
            context.startService(
                Intent(context, PresenceHoldService::class.java).setAction(ACTION_STOP_RELAYED)
                    .putExtra(EXTRA_PUSH_ID, pushId).putExtra(EXTRA_SEQ, seq)
            )
        }
    }
}

/** Whether a hold is actively being driven by [PresenceHoldService] right now — read by
 *  `MainActivity`'s Stop button. Split from the service class itself so it stays readable without
 *  binding to the (possibly not-yet-started) service. A process restart loses it, same as every other
 *  in-memory flag in this module — the phone remains the source of truth. */
object PresenceHolding {
    @Volatile
    var holding: Boolean = false
        internal set
}
