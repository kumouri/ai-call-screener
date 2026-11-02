package com.kumouri.margo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Keeps a hold's vibration ACTUALLY PLAYING for its whole window (2026-10-06,
 * `../../margo/docs/presence-signal-spec.md` §5.5).
 *
 * It plays one finite [HoldRhythm] chunk and re-issues the next the moment it ends, for as long as
 * the hold lasts — rather than handing the vibrator one infinite repeating waveform once and hoping
 * nothing ever cancels it. Two things re-issue:
 *
 * - **the chunk tick** — every chunk (~10 s), unconditionally, so a vibration cancelled by anything
 *   at all costs at most one chunk of silence;
 * - **`ACTION_SCREEN_OFF`** — the platform's vibrator service cancels a NON-SYSTEM app's ongoing
 *   vibration when the screen goes non-interactive (AOSP `VibrationSettings.
 *   shouldCancelVibrationOnScreenOff`; only a timeout/inattentive sleep is spared), which is the most
 *   likely reason the watch's hold stopped ~3 pulses in on 2026-10-06 — she looked, lowered her wrist,
 *   the screen went off. A vibration that STARTS after that go-to-sleep is left alone, so the keeper
 *   re-issues [SCREEN_OFF_REISSUE_DELAY_MS] later.
 *
 * **It holds a partial wake lock while a felt hold runs.** Between a cancel and the next tick the
 * vibrator no longer holds the CPU awake, and a `Handler` delay does not advance in deep sleep — so
 * without the lock, the one moment a re-issue matters is the moment it would be late. Bounded by the
 * hold's own expiry (+1 min) and released on stop; a silent hold (this surface sits it out) takes no
 * lock at all.
 *
 * Every re-issue is a logcat line (`adb logcat -s MargoPresenceSignal`, `trigger=screen-off|chunk`),
 * and [reissues]/[screenOffReissues] ride the hold-end report — so which trigger fired, and how often,
 * is checkable on the device and from the desktop's delivery ledger.
 *
 * Main-thread only. **Duplicated in `:wear` (`com.kumouri.margo.wear.PresenceHoldKeeper`) — keep the
 * two identical**, same reasoning as [PresenceHapticPatterns].
 */
class PresenceHoldKeeper(context: Context, private val tag: String) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var effect: VibrationEffect? = null
    private var chunkMs = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false

    var active = false
        private set
    var startedAtMs = 0L
        private set
    var reissues = 0
        private set
    var screenOffReissues = 0
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            play("chunk")
            handler.postDelayed(this, chunkMs)
        }
    }

    private val afterScreenOff = Runnable {
        if (!active || effect == null) return@Runnable
        screenOffReissues++
        play("screen-off")
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, chunkMs)
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF || !active || effect == null) return
            handler.removeCallbacks(afterScreenOff)
            handler.postDelayed(afterScreenOff, SCREEN_OFF_REISSUE_DELAY_MS)
        }
    }

    /** Start (or restart) a hold. [effect] `null` = a silent hold — tracked, never felt here. */
    fun start(effect: VibrationEffect?, chunkMs: Long, expiresAtMs: Long) {
        stop()
        this.effect = effect
        this.chunkMs = chunkMs.coerceAtLeast(MIN_CHUNK_MS)
        active = true
        startedAtMs = System.currentTimeMillis()
        reissues = 0
        screenOffReissues = 0
        if (effect == null) {
            Log.i(tag, "hold start silent (this surface sits it out)")
            return
        }
        val remaining = expiresAtMs - System.currentTimeMillis()
        val lockMs = (remaining + 60_000L).coerceIn(60_000L, MAX_WAKE_MS)
        wakeLock = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(lockMs)
            }
        try {
            ContextCompat.registerReceiver(
                appContext, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(tag, "screen-off receiver not registered: ${e.message}")
        }
        Log.i(tag, "hold start chunkMs=${this.chunkMs} wakeLockMs=$lockMs")
        play("start")
        handler.postDelayed(tick, this.chunkMs)
    }

    private fun play(trigger: String) {
        val e = effect ?: return
        PresenceHapticPatterns.vibrate(appContext, e)
        if (trigger != "start") reissues++
        val line = "hold reissue trigger=$trigger n=$reissues screenOff=$screenOffReissues " +
            "elapsedMs=${System.currentTimeMillis() - startedAtMs} uptime=${SystemClock.uptimeMillis()}"
        if (trigger == "chunk") Log.d(tag, line) else Log.i(tag, line)
    }

    /** End the hold here: no more re-issues, the lock released, the motor stopped. Idempotent. */
    fun stop() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(afterScreenOff)
        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(screenOffReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (active && effect != null) {
            PresenceHapticPatterns.vibrator(appContext).cancel()
            Log.i(tag, "hold end playedMs=${playedMs()} reissues=$reissues screenOff=$screenOffReissues")
        }
        active = false
        effect = null
    }

    fun playedMs(): Long = if (startedAtMs == 0L) 0L else System.currentTimeMillis() - startedAtMs

    companion object {
        /** Long enough for the platform's own screen-off cancel to land first. */
        const val SCREEN_OFF_REISSUE_DELAY_MS = 400L
        private const val MIN_CHUNK_MS = 1_000L
        /** The 8 h hold ceiling (`presence_signal.MAX_HOLD_MINUTES`) plus a minute. */
        private const val MAX_WAKE_MS = 8L * 60 * 60 * 1000 + 60_000L
        private const val WAKE_LOCK_TAG = "margo:presence-hold"
    }
}
