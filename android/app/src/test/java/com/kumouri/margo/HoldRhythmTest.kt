package com.kumouri.margo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HoldRhythm] — how a hold is unrolled into the finite chunks [PresenceHoldKeeper] re-issues
 * (`presence-signal-spec.md` §5.5: a hold is no longer one infinite waveform issued once, which any
 * external cancel silently ended).
 */
class HoldRhythmTest {

    @Test
    fun `her pulse unrolls to whole cycles filling the ten second chunk`() {
        val (t, a) = PresenceHapticPatterns.waveform(PresenceHapticPatterns.PATTERN_PULSE)
        val chunk = HoldRhythm.chunk(t, a)
        assertEquals(10_000L, chunk.durationMs)
        assertEquals(40, chunk.timings.size)            // 20 cycles of (400 on, 100 off)
        assertEquals(400L, chunk.timings[38])
        assertEquals(100L, chunk.timings[39])
        assertEquals(0, chunk.amplitudes[39])           // every chunk ENDS on the off segment…
        assertTrue(chunk.amplitudes[0] > 0)             // …and starts on the on one: seams fall on a cycle edge
    }

    @Test
    fun `chunk duration is the sum of its timings`() {
        for (name in PresenceHapticPatterns.NAMED_PATTERNS) {
            val (t, a) = PresenceHapticPatterns.waveform(name)
            val chunk = HoldRhythm.chunk(t, a)
            assertEquals(name, chunk.timings.sum(), chunk.durationMs)
            assertTrue(name, chunk.durationMs >= HoldRhythm.CHUNK_TARGET_MS)
        }
    }

    @Test
    fun `slow breath rounds up to two whole six second breaths`() {
        val (t, a) = PresenceHapticPatterns.waveform(PresenceHapticPatterns.PATTERN_SLOW_BREATH)
        assertEquals(12_000L, HoldRhythm.chunk(t, a).durationMs)
    }

    @Test
    fun `a cycle longer than the target is played once, never cut`() {
        val long = longArrayOf(8_000L, 9_000L)
        val chunk = HoldRhythm.chunk(long, intArrayOf(100, 0))
        assertArrayEquals(long, chunk.timings)
        assertEquals(17_000L, chunk.durationMs)
    }

    @Test
    fun `a tiny custom pattern is capped in entries, not unrolled into hundreds`() {
        val tiny = PresenceHapticPatterns.parseCustomPattern("20,20")!!
        val (t, a) = PresenceHapticPatterns.customWaveform(tiny)
        val chunk = HoldRhythm.chunk(t, a)
        assertTrue(chunk.timings.size <= HoldRhythm.MAX_CHUNK_ENTRIES)
        assertEquals(chunk.timings.sum(), chunk.durationMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mismatched arrays are refused`() {
        HoldRhythm.chunk(longArrayOf(1, 2), intArrayOf(1))
    }

    @Test
    fun `chunk amplitudes keep silence silent and scale under the ceiling`() {
        val out = PresenceHapticPatterns.chunkAmplitudes(intArrayOf(100, 0, 50), 60, hasAmplitudeControl = true)
        assertEquals(60, out[0])
        assertEquals(0, out[1])
        assertEquals(30, out[2])
    }

    @Test
    fun `without amplitude control a pulse plays at the device default, silence stays zero`() {
        // The old fallback handed bare timings to createWaveform(timings, repeat), which reads them
        // OFF/ON/OFF… and so inverted every pattern on such a motor.
        val out = PresenceHapticPatterns.chunkAmplitudes(intArrayOf(100, 0), 60, hasAmplitudeControl = false)
        assertEquals(android.os.VibrationEffect.DEFAULT_AMPLITUDE, out[0])
        assertEquals(0, out[1])
    }
}
