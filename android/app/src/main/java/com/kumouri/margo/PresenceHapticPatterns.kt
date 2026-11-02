package com.kumouri.margo

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

/**
 * The named "hold" patterns, an optional custom one, and the one-shot "ping" tap. **The hard line
 * this whole feature answers to: the device's own gentle-notification-level haptic IS the ceiling** —
 * there is no tier above [MAX_AMPLITUDE], by design (`../../margo/docs/presence-signal-spec.md`).
 * [MAX_AMPLITUDE] is that ceiling in code, not a tunable — every amplitude this object ever hands to
 * [VibrationEffect] is clamped into `1..MAX_AMPLITUDE`, whatever ceiling a caller asks for.
 *
 * **Every vibration here plays with alarm-class attributes** (`USAGE_ALARM`), not the default
 * (`USAGE_UNKNOWN`) a bare `vibrate(VibrationEffect)` carries. A default-attribute vibration is
 * exactly the one the system suppresses when the ringer is on Mute or DND/Bedtime is on — and the
 * hold feature's entire reason to exist is "stay with me while I fall asleep," which by definition
 * happens under one of those. Alarm-class is the one usage the platform lets through regardless,
 * the same way an alarm clock still fires on a muted phone. This changes WHEN a vibration is
 * allowed to play, never HOW HARD — [MAX_AMPLITUDE] is untouched.
 */
object PresenceHapticPatterns {

    /** Out of 255. Android's own default "click" amplitude is roughly in this range — this is
     *  deliberately never pushed toward the top of the scale, on ANY ceiling a caller picks. */
    private const val MAX_AMPLITUDE = 130

    /** The base amplitude a pattern is AUTHORED against (e.g. `heartbeat`'s peak beat), scaled down
     *  to whatever `1..MAX_AMPLITUDE` ceiling a surface's own slider picks — [amplitude] does the
     *  scaling. A pattern's own relative dynamics (a ramp, a double-beat) survive the scale; only
     *  the peak moves. */
    private const val REFERENCE_PEAK_BASE = 100

    const val PATTERN_PULSE = "pulse"
    const val PATTERN_SLOW_BREATH = "slow-breath"
    const val PATTERN_HEARTBEAT = "heartbeat"

    /** Ceryce, 2026-09-18 19:18 CT: *"0.4 on, 0.1 off, 0.4 on 0.1 off, repeat"* — her own rhythm,
     *  named plainly, not "corrected" to the 15 Hz she also said (that describes the SAME 0.5 s
     *  period; 15 Hz would be an indistinct buzz, not a felt rhythm). The default hold pattern. */
    const val DEFAULT_PATTERN = PATTERN_PULSE

    /** The presets a picker (the Settings card, the CLI) can choose between — a custom pattern
     *  (§ below) is never one of these; it overrides whichever preset a hold names, when set. */
    val NAMED_PATTERNS = listOf(PATTERN_PULSE, PATTERN_SLOW_BREATH, PATTERN_HEARTBEAT)

    // --- custom pattern parsing/validation ------------------------------------------------------

    /** A custom pattern is a short list of on/off millisecond pairs — "a short list", never a
     *  free-form waveform editor. */
    const val MAX_CUSTOM_SEGMENTS = 20
    const val MIN_SEGMENT_MS = 20L
    const val MAX_SEGMENT_MS = 10_000L

    /**
     * Parse `"400,100,400,100"` (on,off,on,off,…) into on/off millisecond durations, or `null` if
     * [raw] is blank (no override) OR malformed — an even, non-zero count of positive integers each
     * within `[MIN_SEGMENT_MS, MAX_SEGMENT_MS]`, no more than [MAX_CUSTOM_SEGMENTS] of them. Rejects
     * outright rather than clamping: a 0 ms segment isn't "the shortest legal pulse", it's a typo,
     * and an absurd one (an hour-long "off") is more likely a stray extra zero than an intended
     * pattern.
     */
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

    fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /** Play [effect] with alarm-class attributes, so it survives Mute/DND/Bedtime the way a bare
     *  `vibrator.vibrate(effect)` (default `USAGE_UNKNOWN`) does not. `VibrationAttributes` exists
     *  from API 33; below that, the equivalent is the `AudioAttributes` overload of `vibrate()`. */
    fun vibrate(context: Context, effect: VibrationEffect) {
        val vibrator = vibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(alarmUsage(Build.VERSION.SDK_INT)))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(alarmUsage(Build.VERSION.SDK_INT)).build())
        }
    }

    /** `internal`, not `private`, so a JVM unit test can pin the "always alarm-class, on every SDK
     *  level" invariant without a real `Vibrator`. Both constants carry the same underlying platform
     *  value — this only exists so a future edit can't quietly drift to a different usage. */
    internal fun alarmUsage(sdkInt: Int): Int =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) VibrationAttributes.USAGE_ALARM else AudioAttributes.USAGE_ALARM

    /** A single gentle tap — the "ping" verb. ~250 ms, well under anything that could startle.
     *  [ceiling] is the surface's own `1..MAX_AMPLITUDE` slider value (`SettingsStore.
     *  presencePhoneAmplitude` on the phone). */
    fun pingEffect(context: Context, ceiling: Int): VibrationEffect {
        val amp = amplitude(70, ceiling)
        return if (vibrator(context).hasAmplitudeControl()) {
            VibrationEffect.createOneShot(220, amp)
        } else {
            VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    /** A hold as [PresenceHoldKeeper] PLAYS it: one finite chunk of whole cycles ([HoldRhythm]),
     *  non-repeating, plus how long it lasts — the keeper re-issues it back-to-back for the life of
     *  the hold. NOT one infinite repeating waveform: that was issued once and never again, so any
     *  external cancel (screen-off, a higher-importance vibration) silently ended the hold — see
     *  [HoldRhythm]'s docstring. [customSegments] overrides [pattern] exactly as in [holdEffect]. */
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

    /** A chunk's base amplitudes → what the vibrator gets: silence stays 0; a pulse is scaled under
     *  the ceiling, or — on a motor with no amplitude control — plays at the device default. (The
     *  old no-amplitude fallback passed bare timings to `createWaveform(timings, repeat)`, which
     *  reads them as OFF/ON/OFF…, i.e. inverted every pattern on such a device.) */
    internal fun chunkAmplitudes(raw: IntArray, ceiling: Int, hasAmplitudeControl: Boolean): IntArray =
        IntArray(raw.size) { i ->
            when {
                raw[i] == 0 -> 0
                hasAmplitudeControl -> amplitude(raw[i], ceiling)
                else -> VibrationEffect.DEFAULT_AMPLITUDE
            }
        }

    /** The repeating "hold" waveform, looped by the hardware (`repeatIndex`) — used now ONLY by the
     *  Settings card's ~3 s "Test pattern" preview; a real hold plays [holdChunk] through the keeper.
     *  [customSegments], when non-null (parsed via [parseCustomPattern]), OVERRIDES [pattern]'s own
     *  shape entirely — "a phone-local custom pattern overrides the named one when set." */
    fun holdEffect(
        context: Context,
        pattern: String,
        ceiling: Int,
        customSegments: LongArray? = null,
    ): VibrationEffect {
        val (timings, rawAmplitudes) = if (customSegments != null) {
            customWaveform(customSegments)
        } else {
            waveform(pattern)
        }
        return if (vibrator(context).hasAmplitudeControl()) {
            val amplitudes = IntArray(rawAmplitudes.size) { i ->
                if (rawAmplitudes[i] == 0) 0 else amplitude(rawAmplitudes[i], ceiling)
            }
            VibrationEffect.createWaveform(timings, amplitudes, 0)
        } else {
            VibrationEffect.createWaveform(timings, 0)
        }
    }

    /** `internal`, not `private`, so a JVM unit test can pin the ceiling without a `Context` or a
     *  real `Vibrator`. [base] is the pattern's own authored peak (see [REFERENCE_PEAK_BASE]);
     *  [ceiling] is a surface's `1..MAX_AMPLITUDE` slider value, itself clamped defensively here in
     *  case a caller forgot to. */
    internal fun amplitude(base: Int, ceiling: Int): Int {
        val clampedCeiling = ceiling.coerceIn(1, MAX_AMPLITUDE)
        return ((base.toDouble() / REFERENCE_PEAK_BASE) * clampedCeiling).roundToInt().coerceIn(1, MAX_AMPLITUDE)
    }

    internal fun maxAmplitude(): Int = MAX_AMPLITUDE

    /** `ceiling == 0` means "this device sits the signal out" (Ceryce, 2026-09-19 ~00:47 CT —
     *  the amplitude slider gets a zero meaning exactly that): no ping or hold vibration on this
     *  surface, but the hold is still tracked here and Stop still works — see [PresenceLinkService.
     *  requestStop]/`WatchBridge.sendHoldStop`, neither of which this gate touches. `1..MAX_AMPLITUDE`
     *  is a real, audible-to-the-skin intensity as before. */
    fun shouldVibrate(ceiling: Int): Boolean = ceiling > 0

    /** `(timings, amplitudes)`, parallel arrays consumed by [VibrationEffect.createWaveform] —
     *  amplitudes are the UNSCALED base values (peak [REFERENCE_PEAK_BASE]); [holdEffect] applies
     *  the per-surface ceiling above. */
    internal fun waveform(pattern: String): Pair<LongArray, IntArray> = when (pattern) {
        // Her own rhythm (2026-09-18 19:18 CT): 400 ms on, 100 ms off, repeat — a steady, gentle
        // pulse rather than a shaped breath or beat. `repeatIndex 0` loops the two-element array.
        PATTERN_PULSE -> longArrayOf(400, 100) to intArrayOf(REFERENCE_PEAK_BASE, 0)
        // A soft double "thump-thump" every ~1 s — a resting heart rate, not a metronome.
        PATTERN_HEARTBEAT -> longArrayOf(0, 55, 110, 55, 780) to intArrayOf(0, 90, 0, 90, 0)
        // Ramp up, ramp down, then a long pause — a ~6 s breath. Ten 300 ms steps (3 s) either side
        // of the mid-point, then a 3 s rest, so the whole cycle reads as inhale-exhale-pause.
        else -> longArrayOf(300, 300, 300, 300, 300, 300, 300, 300, 300, 300, 3000) to
            intArrayOf(20, 40, 60, 80, 100, 80, 60, 40, 20, 0, 0)
    }

    /** [segments] alternates on/off durations starting with "on" (her own convention) — every even
     *  index plays at [REFERENCE_PEAK_BASE], every odd index is silence. */
    internal fun customWaveform(segments: LongArray): Pair<LongArray, IntArray> {
        val amplitudes = IntArray(segments.size) { i -> if (i % 2 == 0) REFERENCE_PEAK_BASE else 0 }
        return segments to amplitudes
    }
}
