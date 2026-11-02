package com.kumouri.margo.wear

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

/**
 * The wrist copy of `com.kumouri.margo.PresenceHapticPatterns` in the `:app` module — duplicated
 * rather than shared, since the two modules have no common Kotlin target. Keep the waveforms
 * IDENTICAL if either changes; they are meant to feel like the same signal on either device.
 *
 * **Since 2026-09-18 the watch has its own amplitude ceiling and, when set, honors a phone-local
 * custom pattern too** — relayed over the Data Layer (`amplitude`/`segments` on the hold-start and
 * ping messages) rather than a second Settings screen here; the phone's own Presence card is the one
 * editor for both surfaces (`presence-signal-spec.md` §5).
 *
 * **Plays with alarm-class attributes**, same reasoning and same fix as the phone's copy: a default-
 * attribute vibration is suppressed under Mute/DND/Bedtime, and a watch's own Bedtime/Theater mode is
 * exactly when a wrist "stay with me" signal matters most.
 */
object PresenceHapticPatterns {

    private const val MAX_AMPLITUDE = 130
    private const val REFERENCE_PEAK_BASE = 100

    const val PATTERN_PULSE = "pulse"
    const val PATTERN_HEARTBEAT = "heartbeat"
    const val DEFAULT_PATTERN = PATTERN_PULSE

    const val MAX_CUSTOM_SEGMENTS = 20
    const val MIN_SEGMENT_MS = 20L
    const val MAX_SEGMENT_MS = 10_000L

    fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /** See the `:app` module's identical helper for the full rationale. */
    fun vibrate(context: Context, effect: VibrationEffect) {
        val vibrator = vibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    fun pingEffect(context: Context, ceiling: Int): VibrationEffect {
        return if (vibrator(context).hasAmplitudeControl()) {
            VibrationEffect.createOneShot(220, amplitude(70, ceiling))
        } else {
            VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    /** A hold as [PresenceHoldKeeper] plays it — one finite chunk of whole cycles, re-issued
     *  back-to-back. See the `:app` module's identical helper (and [HoldRhythm]) for why a hold is no
     *  longer one infinite repeating waveform issued once. */
    class HoldPlayback(val effect: VibrationEffect, val durationMs: Long)

    fun holdChunk(
        context: Context,
        pattern: String,
        ceiling: Int,
        customSegments: LongArray? = null,
    ): HoldPlayback {
        val (timings, rawAmplitudes) = if (customSegments != null) {
            customWaveform(customSegments)
        } else {
            waveform(pattern)
        }
        val chunk = HoldRhythm.chunk(timings, rawAmplitudes)
        val amplitudes = chunkAmplitudes(chunk.amplitudes, ceiling, vibrator(context).hasAmplitudeControl())
        return HoldPlayback(VibrationEffect.createWaveform(chunk.timings, amplitudes, -1), chunk.durationMs)
    }

    /** See the `:app` module's identical helper. */
    internal fun chunkAmplitudes(raw: IntArray, ceiling: Int, hasAmplitudeControl: Boolean): IntArray =
        IntArray(raw.size) { i ->
            when {
                raw[i] == 0 -> 0
                hasAmplitudeControl -> amplitude(raw[i], ceiling)
                else -> VibrationEffect.DEFAULT_AMPLITUDE
            }
        }

    internal fun amplitude(base: Int, ceiling: Int): Int {
        val clampedCeiling = ceiling.coerceIn(1, MAX_AMPLITUDE)
        return ((base.toDouble() / REFERENCE_PEAK_BASE) * clampedCeiling).roundToInt().coerceIn(1, MAX_AMPLITUDE)
    }

    internal fun maxAmplitude(): Int = MAX_AMPLITUDE

    /** See the `:app` module's identical helper — `ceiling == 0` means this wrist sits the signal
     *  out (no vibration), but the hold is still tracked and Stop still works. */
    fun shouldVibrate(ceiling: Int): Boolean = ceiling > 0

    /** Parses `"400,100,400,100"` the same way the phone's copy does — see its docstring. */
    fun parseCustomPattern(raw: String): LongArray? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(",").map { it.trim() }
        if (parts.size < 2 || parts.size % 2 != 0 || parts.size > MAX_CUSTOM_SEGMENTS) return null
        val values = LongArray(parts.size)
        for (i in parts.indices) {
            val v = parts[i].toLongOrNull() ?: return null
            if (v < MIN_SEGMENT_MS || v > MAX_SEGMENT_MS) return null
            values[i] = v
        }
        return values
    }

    internal fun waveform(pattern: String): Pair<LongArray, IntArray> = when (pattern) {
        PATTERN_PULSE -> longArrayOf(400, 100) to intArrayOf(REFERENCE_PEAK_BASE, 0)
        PATTERN_HEARTBEAT -> longArrayOf(0, 55, 110, 55, 780) to intArrayOf(0, 90, 0, 90, 0)
        else -> longArrayOf(300, 300, 300, 300, 300, 300, 300, 300, 300, 300, 3000) to
            intArrayOf(20, 40, 60, 80, 100, 80, 60, 40, 20, 0, 0)
    }

    internal fun customWaveform(segments: LongArray): Pair<LongArray, IntArray> {
        val amplitudes = IntArray(segments.size) { i -> if (i % 2 == 0) REFERENCE_PEAK_BASE else 0 }
        return segments to amplitudes
    }
}
