package com.kumouri.margo.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wrist's [HoldRhythm] — the fix for the hold that stopped ~3 pulses in (2026-10-06): the keeper
 * re-issues these finite chunks, so a cancelled vibration is replaced within one chunk. Mirrors the
 * `:app` module's `HoldRhythmTest`; the two copies must unroll identically.
 */
class HoldRhythmTest {

    @Test
    fun `her pulse unrolls to a ten second chunk of whole cycles`() {
        val (t, a) = PresenceHapticPatterns.waveform(PresenceHapticPatterns.PATTERN_PULSE)
        val chunk = HoldRhythm.chunk(t, a)
        assertEquals(10_000L, chunk.durationMs)
        assertEquals(40, chunk.timings.size)
        assertTrue(chunk.amplitudes[0] > 0)
        assertEquals(0, chunk.amplitudes[39])
    }

    @Test
    fun `every pattern's chunk is at least the target and sums to its duration`() {
        for (name in listOf(PresenceHapticPatterns.PATTERN_PULSE, PresenceHapticPatterns.PATTERN_HEARTBEAT, "slow-breath")) {
            val (t, a) = PresenceHapticPatterns.waveform(name)
            val chunk = HoldRhythm.chunk(t, a)
            assertEquals(name, chunk.timings.sum(), chunk.durationMs)
            assertTrue(name, chunk.durationMs >= HoldRhythm.CHUNK_TARGET_MS)
            assertTrue(name, chunk.timings.size <= HoldRhythm.MAX_CHUNK_ENTRIES)
        }
    }

    @Test
    fun `chunk amplitudes never cross the ceiling and keep silence silent`() {
        val out = PresenceHapticPatterns.chunkAmplitudes(intArrayOf(100, 0, 90), 35, hasAmplitudeControl = true)
        assertEquals(35, out[0])
        assertEquals(0, out[1])
        assertTrue(out[2] in 1..35)
    }
}
