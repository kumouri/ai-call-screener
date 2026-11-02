package com.kumouri.margo

/**
 * How a hold's rhythm is PLAYED: as a finite chunk of whole pattern cycles that the hold keeper
 * re-issues back-to-back, never as one infinite repeating waveform handed to the vibrator once.
 *
 * **Why (2026-10-06, `../../margo/docs/presence-signal-spec.md` §5.5).** Until then a hold was ONE
 * `VibrationEffect.createWaveform(..., repeat = 0)` call, issued once at hold-start. Anything that
 * cancels an ongoing vibration — the platform's own screen-off cancel of a non-system app's
 * vibration, another vibration of higher importance, the process being torn down — ended the hold
 * for good while the notification kept saying "Margo is here", because nothing ever issued it again.
 * That is the watch's "~3 pulses and stopped" (2026-10-06 21:49 CT) and its "pulse dies at ~60 s"
 * (2026-09-22, when a 50-second keepalive job that RE-STARTED the hold was the only thing that kept
 * it going — re-issuing worked, which is the evidence for this design). A chunk caps the silence any
 * one cancel can cause at one chunk's length; the keeper also re-issues immediately on screen-off.
 *
 * Pure — no `Context`, unit-tested on the JVM (`HoldRhythmTest`). **Duplicated in `:wear`
 * (`com.kumouri.margo.wear.HoldRhythm`) — keep the two IDENTICAL**, same reasoning as
 * [PresenceHapticPatterns].
 */
object HoldRhythm {

    /** Each re-issue plays about this much of the pattern, rounded UP to whole cycles — so a cancel
     *  costs at most ~10 s of silence before the next chunk starts, and a seam always falls on a
     *  cycle boundary, where it can't be felt. */
    const val CHUNK_TARGET_MS = 10_000L

    /** A waveform this long is plenty; past it, a chunk is cut to fewer cycles (a short custom
     *  pattern of 20 ms segments would otherwise unroll into hundreds of entries). */
    const val MAX_CHUNK_ENTRIES = 128

    class Chunk(val timings: LongArray, val amplitudes: IntArray, val durationMs: Long)

    /**
     * Unroll one cycle ([cycleTimings]/[cycleAmplitudes], parallel arrays) into a chunk of whole
     * cycles lasting at least [targetMs] — or fewer cycles if [MAX_CHUNK_ENTRIES] would be crossed,
     * but never fewer than one.
     */
    fun chunk(cycleTimings: LongArray, cycleAmplitudes: IntArray, targetMs: Long = CHUNK_TARGET_MS): Chunk {
        require(cycleTimings.isNotEmpty() && cycleTimings.size == cycleAmplitudes.size) {
            "timings and amplitudes must be non-empty parallel arrays"
        }
        val cycleMs = cycleTimings.sum().coerceAtLeast(1L)
        val wanted = ((targetMs + cycleMs - 1) / cycleMs).coerceAtLeast(1L)
        val cap = (MAX_CHUNK_ENTRIES / cycleTimings.size).coerceAtLeast(1)
        val repeats = wanted.coerceAtMost(cap.toLong()).toInt()
        val n = cycleTimings.size * repeats
        val timings = LongArray(n) { cycleTimings[it % cycleTimings.size] }
        val amplitudes = IntArray(n) { cycleAmplitudes[it % cycleAmplitudes.size] }
        return Chunk(timings, amplitudes, cycleMs * repeats)
    }
}
