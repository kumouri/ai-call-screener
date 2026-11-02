package com.kumouri.margo

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * **The push channel's phone end** — Ceryce's ruling, 2026-10-06 21:53 CT: the fix is a push from
 * the desktop to her phone, and no more polling (`../../margo/docs/rulings.md` id
 * `2026-10-06-presence-signal-is-push`, `../../margo/docs/presence-signal-spec.md` §3-§5).
 *
 * One always-on foreground service that:
 * 1. **holds the push stream** — `GET /presence-signal-stream` on her own listener over the tailnet,
 *    a Server-Sent-Events connection the desktop writes to the instant `presence_signal.py` changes
 *    state (`margo/scripts/presence_signal_hub.py`). Nothing here asks for state on a timer; the link
 *    reconnects when it drops, and that is its only loop;
 * 2. **applies what arrives** ([PresenceSignalApplier.decide]) — a ping's tap, a hold played through
 *    [PresenceHoldKeeper] (re-issued chunk by chunk, never one infinite waveform issued once), a stop;
 * 3. **relays to the watch** ([WatchBridge]) and **acks every event** to the desktop
 *    (`POST /presence-signal-ack`) with what it did and whether the watch relay went out — so Margo
 *    can say "it didn't reach your phone" instead of claiming it did;
 * 4. **owns the hold's chrome** — the notification becomes "Margo is here … Stop" while a hold runs.
 *    Because THIS service is already in the foreground, a hold that starts while the phone is idle
 *    in a pocket needs no new background foreground-service start — the exact Android 12+ denial that
 *    silenced the first real hold on 2026-09-18 (§5.1) can't happen on this path at all.
 *
 * **It replaces two things that are gone:** `PresenceSignalPollWorker` (a self-rescheduling ~60 s
 * WorkManager chain that died silently on 2026-10-06 — her card read "idle / Last check 15m ago" while
 * a 30-minute hold went undelivered) and `PresenceSignalService` (the hold-time 10 s poll + chrome).
 * Their leftover WorkManager chain is cancelled by [ensureRunning], so an updated install never runs
 * both.
 *
 * **Why the link survives Doze.** A process holding a foreground service keeps its network while the
 * device idles, and an incoming packet on the held socket wakes the CPU to read it; the stream
 * itself is idle between events except for the desktop's heartbeat comment every
 * `HEARTBEAT_SEC` (60 s). If nothing at all arrives for [LINK_STALE_MS] the read times out and the
 * link reconnects — the one cheap health check the transport needs, not a poll of state. A reconnect
 * after a drop is driven by an inexact `setAndAllowWhileIdle` alarm as well as the thread's own wait,
 * because a plain sleep does not advance while the CPU is suspended; and a network coming back kicks
 * it immediately. The connection rides the tailnet IP, which does not change when the phone moves
 * between wifi and cellular.
 */
class PresenceLinkService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var keeper: PresenceHoldKeeper
    private val kicks = LinkedBlockingQueue<Unit>()
    private val ackExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var connectedWith: String = ""
    private var linkThread: Thread? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var expiryRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        keeper = PresenceHoldKeeper(this, TAG)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, currentNotification())
        } catch (e: Exception) {
            // Background foreground-service start denied (Android 12+). Said on the card, verbatim,
            // rather than silently doing nothing: the fix is opening the app once, or granting
            // "Unrestricted" battery so a background start is allowed.
            Log.w(TAG, "link service could not enter the foreground: ${e.message}")
            setLink("blocked", "Android refused to start the link in the background — open the app once")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        isRunning = true
        when (intent?.action) {
            ACTION_STOP_HOLD -> stopHoldFrom("phone")
            ACTION_STOP_FROM_WATCH -> stopHoldFrom("watch")
        }
        if (!SettingsStore(this).presenceConfigured()) {
            setLink("off", "the Presence feed is off or has no Server URL")
            if (!PresenceSignalStore(this).holdActive) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ensureLinkThread()
        registerNetworkCallback()
        // A Settings save with a new Server URL or token: drop the stream opened with the old ones,
        // so the link reconnects with what she just typed instead of whenever the old one drops.
        val settings = SettingsStore(this)
        val want = settings.presenceSignalStreamUrl() + "\n" + settings.presenceToken
        if (conn != null && connectedWith != want) conn?.disconnect()
        kicks.offer(Unit)
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        isRunning = false
        conn?.disconnect()
        linkThread?.interrupt()
        unregisterNetworkCallback()
        expiryRunnable?.let { mainHandler.removeCallbacks(it) }
        keeper.stop()
        ackExecutor.shutdown()
        super.onDestroy()
    }

    // --- the link --------------------------------------------------------------------------------

    private fun ensureLinkThread() {
        if (linkThread?.isAlive == true) return
        linkThread = Thread({ runLink() }, "margo-presence-link").apply {
            isDaemon = true
            start()
        }
    }

    private fun runLink() {
        var failures = 0
        while (!stopped) {
            val settings = SettingsStore(this)
            val url = settings.presenceSignalStreamUrl()
            if (!settings.presenceConfigured() || url.isBlank()) {
                setLink("off", "the Presence feed is off or has no Server URL")
                waitForKick(OFF_RECHECK_MS)
                continue
            }
            val heardBefore = PresenceSignalStore(this).lastHeardMs
            val why = try {
                holdStream(url, settings.presenceToken)
                "the desktop closed the link"
            } catch (_: InterruptedException) {
                break
            } catch (_: SocketTimeoutException) {
                "nothing heard for ${LINK_STALE_MS / 1000}s — reconnecting"
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                conn = null
            }
            if (stopped) break
            // A link that carried anything resets the backoff; one that never got going grows it.
            failures = if (PresenceSignalStore(this).lastHeardMs > heardBefore) 0 else failures + 1
            val delay = BACKOFF_MS[failures.coerceAtMost(BACKOFF_MS.size - 1)]
            setLink("down", why)
            Log.w(TAG, "link down ($why); retrying in ${delay}ms")
            scheduleReconnectAlarm(delay)
            waitForKick(delay)
        }
    }

    private fun waitForKick(ms: Long) {
        try {
            kicks.poll(ms, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        kicks.clear()
    }

    /** Blocks for the life of one connection. Returns when the desktop closes it; throws on error. */
    private fun holdStream(url: String, token: String) {
        setLink("connecting", "")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 15_000
            readTimeout = LINK_STALE_MS.toInt()
            useCaches = false
        }
        conn = c
        connectedWith = url + "\n" + token
        try {
            val code = c.responseCode
            if (code != 200) {
                throw IllegalStateException(
                    if (code == 401) "the listener refused the bearer token (HTTP 401)"
                    else if (code == 404) "the listener has no push route yet (HTTP 404) — it needs a restart"
                    else "the listener answered HTTP $code"
                )
            }
            setLink("connected", "")
            val parser = SseParser()
            c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (!stopped) {
                    val line = reader.readLine() ?: return
                    PresenceSignalStore(this).lastHeardMs = System.currentTimeMillis()
                    val event = parser.feed(line) ?: continue
                    val pushed = PresenceSignalStream.parse(event) ?: continue
                    handlePush(pushed)
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun setLink(state: String, detail: String) {
        val store = PresenceSignalStore(this)
        if (store.linkState != state) store.linkSinceMs = System.currentTimeMillis()
        store.linkState = state
        store.linkDetail = detail
        if (!keeper.active && isRunning) {
            mainHandler.post { notificationManager().notify(NOTIFICATION_ID, currentNotification()) }
        }
    }

    private fun scheduleReconnectAlarm(delayMs: Long) {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                this, 1, Intent(this, PresenceLinkService::class.java).setAction(ACTION_KICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
        } catch (e: Exception) {
            Log.w(TAG, "reconnect alarm not set: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                kicks.offer(Unit)
            }
        }
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "network callback not registered: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    // --- applying a push -------------------------------------------------------------------------

    /** Runs on the link thread. Holds a short wake lock: the packet woke the CPU, and the vibration,
     *  the Data Layer relay and the ack must all finish before it is allowed to sleep again. */
    private fun handlePush(pushed: PushedSignal) {
        val wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "margo:presence-push")
            .apply { acquire(PUSH_WAKE_MS) }
        try {
            applyPush(pushed)
        } catch (e: Exception) {
            Log.w(TAG, "push ${pushed.pushId} failed to apply: ${e.message}")
            postAck(pushed, listOf(effect("none", "failed", e.message ?: "")), null)
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    private fun applyPush(pushed: PushedSignal) {
        val signal = pushed.signal
        val store = PresenceSignalStore(this)
        val settings = SettingsStore(this)
        store.lastPushMs = System.currentTimeMillis()
        val decision = PresenceSignalApplier.decide(
            PresenceSignalApplier.Local(store.appliedPingSeq, store.holdActive, store.holdStartedAt, store.holdPattern),
            signal, System.currentTimeMillis(),
        )
        val effects = mutableListOf<JSONObject>()
        var relay: String? = null

        when (decision.ping) {
            PresenceSignalApplier.PingAction.PLAY -> {
                store.appliedPingSeq = signal.lastPingSeq
                val surface = signal.lastPingSurface ?: "both"
                val felt = surface != "wrist" && PresenceHapticPatterns.shouldVibrate(settings.presencePhoneAmplitude)
                if (felt) {
                    PresenceHapticPatterns.vibrate(this, PresenceHapticPatterns.pingEffect(this, settings.presencePhoneAmplitude))
                }
                effects += effect("ping", if (felt) "played" else "silenced")
                // Relayed regardless of `surface` — the watch makes its own vibrate decision.
                relay = WatchBridge.sendPing(this, signal.lastPingLabel, surface, pushed.pushId, signal.seq)
            }
            PresenceSignalApplier.PingAction.STALE -> {
                store.appliedPingSeq = signal.lastPingSeq
                effects += effect("ping", "stale", "arrived more than ${PresenceSignalApplier.PING_FRESH_MS / 1000}s late; not played")
            }
            PresenceSignalApplier.PingAction.NONE -> Unit
        }

        when (decision.hold) {
            PresenceSignalApplier.HoldAction.START -> {
                val pattern = signal.pattern ?: PresenceHapticPatterns.DEFAULT_PATTERN
                val surface = signal.surface ?: "both"
                val expiresAtMs = PresenceSignalApplier.parseIsoToMs(signal.holdExpiresAt) ?: 0L
                store.holdActive = true
                store.holdStartedAt = signal.holdStartedAt ?: ""
                store.holdPattern = pattern
                store.holdSurface = surface
                store.holdExpiresAtMs = expiresAtMs
                val felt = startHoldLocally(pattern, surface, expiresAtMs)
                effects += effect("hold-start", if (felt) "played" else "silenced")
                // Relayed regardless of `surface`/amplitude — the watch tracks the hold for Stop.
                relay = WatchBridge.sendHoldStart(this, pattern, expiresAtMs, surface, pushed.pushId, signal.seq)
            }
            PresenceSignalApplier.HoldAction.CONTINUE -> {
                store.holdExpiresAtMs = PresenceSignalApplier.parseIsoToMs(signal.holdExpiresAt) ?: store.holdExpiresAtMs
                if (!keeper.active) {
                    // This process restarted mid-hold: the store remembers the hold, the motor
                    // doesn't. Resume it here (the watch keeps its own).
                    val felt = startHoldLocally(store.holdPattern, store.holdSurface, store.holdExpiresAtMs)
                    effects += effect("hold-start", if (felt) "played" else "silenced", "resumed after a restart")
                }
            }
            PresenceSignalApplier.HoldAction.STOP -> {
                store.holdActive = false
                store.holdStartedAt = ""
                mainHandler.post { stopHoldLocally() }
                effects += effect("hold-stop", "stopped")
                relay = WatchBridge.sendHoldStop(this, pushed.pushId, signal.seq)
            }
            PresenceSignalApplier.HoldAction.NONE -> Unit
        }

        if (effects.isEmpty()) effects += effect("none", "noop")
        Log.i(TAG, "push ${pushed.pushId} reason=${pushed.reason} seq=${signal.seq} " +
            "effects=${effects.joinToString { "${it.optString("kind")}=${it.optString("status")}" }} relay=$relay")
        postAck(pushed, effects, relay)
    }

    /** Returns whether this phone will actually FEEL it (surface + its own intensity). */
    private fun startHoldLocally(pattern: String, surface: String, expiresAtMs: Long): Boolean {
        val settings = SettingsStore(this)
        val amplitude = settings.presencePhoneAmplitude
        val felt = surface != "wrist" && PresenceHapticPatterns.shouldVibrate(amplitude)
        val playback = if (felt) {
            PresenceHapticPatterns.holdChunk(this, pattern, amplitude, settings.presenceCustomPatternSegments())
        } else null
        mainHandler.post {
            keeper.start(playback?.effect, playback?.durationMs ?: 0L, expiresAtMs)
            armExpiry(expiresAtMs)
            notificationManager().notify(NOTIFICATION_ID, currentNotification())
        }
        return felt
    }

    /** Main thread. */
    private fun stopHoldLocally() {
        expiryRunnable?.let { mainHandler.removeCallbacks(it) }
        expiryRunnable = null
        keeper.stop()
        notificationManager().notify(NOTIFICATION_ID, currentNotification())
    }

    /** The phone's own stop timer — independent of the link, so a hold still ends on time if the
     *  desktop's expiry push never arrives. Main thread. */
    private fun armExpiry(expiresAtMs: Long) {
        expiryRunnable?.let { mainHandler.removeCallbacks(it) }
        if (expiresAtMs <= 0L) return
        val r = Runnable {
            val store = PresenceSignalStore(this)
            if (store.holdActive && System.currentTimeMillis() >= store.holdExpiresAtMs - 1_000L) {
                store.holdActive = false
                store.holdStartedAt = ""
                stopHoldLocally()
                Thread { WatchBridge.sendHoldStop(this) }.start()
            }
        }
        expiryRunnable = r
        mainHandler.postDelayed(r, (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    /** A Stop tap — the notification's own action or the Settings card ([who] `phone`), or the
     *  watch's, relayed here ([who] `watch`). "Stop stops everything, everywhere, always" (§5.3):
     *  this phone, the watch (unless the watch is who said so — it already stopped itself, and
     *  echoing back would ping-pong), and the desktop, which then pushes the idle state back down. */
    private fun stopHoldFrom(who: String) {
        val store = PresenceSignalStore(this)
        store.holdActive = false
        store.holdStartedAt = ""
        stopHoldLocally()
        Thread {
            if (who != "watch") WatchBridge.sendHoldStop(this)
            postStopWithRetry(this, who)
        }.start()
    }

    private fun effect(kind: String, status: String, detail: String = ""): JSONObject =
        JSONObject().put("kind", kind).put("status", status).apply { if (detail.isNotEmpty()) put("detail", detail) }

    private fun postAck(pushed: PushedSignal, effects: List<JSONObject>, relay: String?) {
        val settings = SettingsStore(this)
        val body = JSONObject()
            .put("push_id", pushed.pushId)
            .put("seq", pushed.signal.seq)
            .put("device", "phone")
            .put("effects", JSONArray(effects))
        if (relay != null) body.put("watch_relay", relay)
        val url = settings.presenceSignalAckUrl()
        val token = settings.presenceToken
        try {
            ackExecutor.execute { PresenceSignalClient.postAck(url, token, body) }
        } catch (_: Exception) {
            // Executor shut down with the service: the ack is lost, the delivery already happened.
        }
    }

    // --- notifications ---------------------------------------------------------------------------

    private fun notificationManager(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannels() {
        val nm = notificationManager()
        if (nm.getNotificationChannel(LINK_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(LINK_CHANNEL_ID, "Margo link", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "The always-on link Margo pushes \"I'm here\" signals down" }
            )
        }
        if (nm.getNotificationChannel(HOLD_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(HOLD_CHANNEL_ID, "Margo presence", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while Margo's \"stay with me\" signal is active" }
            )
        }
    }

    private fun currentNotification(): Notification {
        ensureChannels()
        val store = PresenceSignalStore(this)
        return if (store.holdActive) holdNotification(store.holdPattern) else linkNotification(store)
    }

    private fun linkNotification(store: PresenceSignalStore): Notification {
        val text = when (store.linkState) {
            "connected" -> "Connected — Margo can reach you"
            "connecting" -> "Connecting…"
            "down" -> "Reconnecting — ${store.linkDetail}"
            "off" -> "Off — ${store.linkDetail}"
            else -> "Starting…"
        }
        return NotificationCompat.Builder(this, LINK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_margo)
            .setContentTitle("Margo link")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun holdNotification(pattern: String): Notification {
        val stopPending = PendingIntent.getService(
            this, 0, Intent(this, PresenceLinkService::class.java).setAction(ACTION_STOP_HOLD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val label = when {
            SettingsStore(this).presenceCustomPatternSegments() != null -> "custom rhythm"
            pattern == PresenceHapticPatterns.PATTERN_HEARTBEAT -> "heartbeat"
            pattern == PresenceHapticPatterns.PATTERN_SLOW_BREATH -> "slow breath"
            else -> "steady pulse"
        }
        val largeIcon = BitmapFactory.decodeResource(resources, R.drawable.ic_notification_large)
        return NotificationCompat.Builder(this, HOLD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_margo)
            .setLargeIcon(largeIcon)
            .setContentTitle("Margo is here")
            .setContentText("Staying with you — $label")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "Stop", stopPending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "MargoPresenceSignal"
        private const val LINK_CHANNEL_ID = "presence-link"
        private const val HOLD_CHANNEL_ID = "presence-signal"
        private const val NOTIFICATION_ID = 0x5347   // "SG" (Signal)
        private const val ACTION_ENSURE = "com.kumouri.margo.PRESENCE_LINK_ENSURE"
        private const val ACTION_KICK = "com.kumouri.margo.PRESENCE_LINK_KICK"
        private const val ACTION_STOP_HOLD = "com.kumouri.margo.PRESENCE_SIGNAL_STOP"
        private const val ACTION_STOP_FROM_WATCH = "com.kumouri.margo.PRESENCE_SIGNAL_STOP_FROM_WATCH"

        /** No bytes at all for this long = a dead link, reconnect. ~2.5x the desktop's heartbeat
         *  (`presence_signal_hub.HEARTBEAT_SEC`, 60 s) — keep the two in step. */
        const val LINK_STALE_MS = 150_000L

        /** Reconnect backoff after a link that never carried anything: quick first, capped at 2 min. */
        private val BACKOFF_MS = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L)

        /** How often an unconfigured link re-checks Settings (a Save also kicks it at once). */
        private const val OFF_RECHECK_MS = 10 * 60_000L

        private const val PUSH_WAKE_MS = 30_000L

        /** The WorkManager unique name of the retired idle poll chain — cancelled, never enqueued. */
        private const val LEGACY_POLL_WORK = "presence-signal-poll"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Start (or keep) the link. From the app, a boot / app-update broadcast, or a Settings save.
         *  Also retires the old poll chain's leftover WorkManager entry. */
        fun ensureRunning(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(LEGACY_POLL_WORK)
            } catch (_: Exception) {
            }
            if (!SettingsStore(context).presenceConfigured()) {
                PresenceSignalStore(context).apply {
                    if (linkState != "off") linkSinceMs = System.currentTimeMillis()
                    linkState = "off"
                    linkDetail = "the Presence feed is off or has no Server URL"
                }
                if (!isRunning) return
            }
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, PresenceLinkService::class.java).setAction(ACTION_ENSURE)
                )
            } catch (e: Exception) {
                Log.w(TAG, "link service start denied: ${e.message}")
                PresenceSignalStore(context).apply {
                    linkState = "blocked"
                    linkDetail = "Android refused to start the link in the background — open the app once"
                    linkSinceMs = System.currentTimeMillis()
                }
            }
        }

        /** The Settings tab's own Stop control — same path as the notification's Stop action. */
        fun requestStop(context: Context) = stopVia(context, ACTION_STOP_HOLD, "phone")

        /** The watch's OWN Stop was tapped and relayed here by [WearListenerService] — "Stop stops
         *  everything, everywhere, always" (2026-09-19, `presence-signal-spec.md` §5.3). Never relays
         *  back to the watch (it already stopped itself). */
        fun stopFromWatchRelay(context: Context) = stopVia(context, ACTION_STOP_FROM_WATCH, "watch")

        /** Through the running service when there is one; directly when there isn't (the link is
         *  off, or Android refused to start it) — a Stop must never depend on the link being up. */
        private fun stopVia(context: Context, action: String, who: String) {
            if (isRunning) {
                try {
                    context.startService(Intent(context, PresenceLinkService::class.java).setAction(action))
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "stop via service failed, stopping directly: ${e.message}")
                }
            }
            PresenceHapticPatterns.vibrator(context).cancel()
            PresenceSignalStore(context).apply { holdActive = false; holdStartedAt = "" }
            Thread {
                if (who != "watch") WatchBridge.sendHoldStop(context)
                postStopWithRetry(context, who)
            }.start()
        }

        /** Blocking. A Stop that never reaches the desktop leaves the hold "on" there, and the next
         *  reconnect snapshot would start it again — so it is retried a few times before giving up. */
        private fun postStopWithRetry(context: Context, who: String) {
            val settings = SettingsStore(context)
            for (attempt in 0 until 4) {
                if (PresenceSignalClient.postStop(settings.presenceSignalStopUrl(), settings.presenceToken, who)) return
                try {
                    Thread.sleep(2_000L * (attempt + 1))
                } catch (_: InterruptedException) {
                    return
                }
            }
            Log.w(TAG, "Stop never reached the desktop after 4 tries")
        }
    }
}
