package com.kumouri.margo

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of [PresenceHapticPatterns] — the amplitude ceiling, the waveform shapes, the custom
 * pattern parser, and the alarm-usage choice. The `VibrationEffect`-building half needs a real
 * `Vibrator` (device/Robolectric), so what's pinned here is what actually matters: **no ceiling ever
 * crosses [PresenceHapticPatterns.maxAmplitude]** (the hard line `presence-signal-spec.md` names as
 * the whole feature's ceiling, unmoved by the 2026-09-18 pattern-editor work), **every vibration is
 * alarm-class on every SDK level**, and **[PresenceHapticPatterns.parseCustomPattern] rejects rather
 * than silently repairs** a malformed custom rhythm.
 */
class PresenceHapticPatternsTest {

    @Test
    fun `alarm usage is chosen both pre- and post-Tiramisu`() {
        assertEquals(AudioAttributes.USAGE_ALARM, PresenceHapticPatterns.alarmUsage(Build.VERSION_CODES.S))
        assertEquals(VibrationAttributes.USAGE_ALARM, PresenceHapticPatterns.alarmUsage(Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun `a lower ceiling produces a gentler amplitude for the same base`() {
        val gentle = PresenceHapticPatterns.amplitude(90, 45)
        val fuller = PresenceHapticPatterns.amplitude(90, 100)
        assertTrue("ceiling 45 ($gentle) should be < ceiling 100 ($fuller)", gentle < fuller)
    }

    @Test
    fun `no ceiling, however high, ever crosses the hard cap`() {
        for (ceiling in listOf(1, 45, 100, 130, 255, Int.MAX_VALUE)) {
            val amp = PresenceHapticPatterns.amplitude(255, ceiling)
            assertTrue("ceiling $ceiling amplitude $amp exceeds the ceiling", amp <= PresenceHapticPatterns.maxAmplitude())
        }
    }

    @Test
    fun `a non-positive or absurd ceiling is clamped into range, never a crash`() {
        assertEquals(1, PresenceHapticPatterns.amplitude(100, 0))
        assertEquals(1, PresenceHapticPatterns.amplitude(100, -50))
        assertEquals(PresenceHapticPatterns.maxAmplitude(), PresenceHapticPatterns.amplitude(100, 99_999))
    }

    @Test
    fun `amplitude is never zero for a positive base — a real pulse, not silence`() {
        assertTrue(PresenceHapticPatterns.amplitude(1, 1) >= 1)
    }

    @Test
    fun `default pattern is her own rhythm, not slow-breath or heartbeat`() {
        assertEquals(PresenceHapticPatterns.PATTERN_PULSE, PresenceHapticPatterns.DEFAULT_PATTERN)
        assertEquals(
            listOf(PresenceHapticPatterns.PATTERN_PULSE, PresenceHapticPatterns.PATTERN_SLOW_BREATH, PresenceHapticPatterns.PATTERN_HEARTBEAT),
            PresenceHapticPatterns.NAMED_PATTERNS,
        )
    }

    @Test
    fun `pulse is 400ms on, 100ms off — her 2026-09-18 19-18 CT rhythm exactly`() {
        val (timings, amplitudes) = PresenceHapticPatterns.waveform(PresenceHapticPatterns.PATTERN_PULSE)
        assertEquals(listOf(400L, 100L), timings.toList())
        assertTrue("the 'on' tick should actually vibrate", amplitudes[0] > 0)
        assertEquals(0, amplitudes[1])
    }

    @Test
    fun `heartbeat waveform is a double pulse under a second`() {
        val (timings, amplitudes) = PresenceHapticPatterns.waveform("heartbeat")
        assertEquals(timings.size, amplitudes.size)
        assertTrue("total cycle should be roughly 1s", timings.sum() in 900..1100)
    }

    @Test
    fun `slow-breath is the default for an unknown pattern name`() {
        val (unknown, _) = PresenceHapticPatterns.waveform("disco")
        val (breath, _) = PresenceHapticPatterns.waveform("slow-breath")
        assertEquals(breath.toList(), unknown.toList())
    }

    @Test
    fun `slow-breath cycle is roughly 6 seconds`() {
        val (timings, _) = PresenceHapticPatterns.waveform("slow-breath")
        assertEquals(6000L, timings.sum())
    }

    // --- custom pattern parsing --------------------------------------------------------------

    @Test
    fun `a valid custom pattern parses to on-off pairs in order`() {
        val segments = PresenceHapticPatterns.parseCustomPattern("400,100,400,100")
        assertEquals(listOf(400L, 100L, 400L, 100L), segments?.toList())
    }

    @Test
    fun `blank is not an override, not an error`() {
        assertNull(PresenceHapticPatterns.parseCustomPattern(""))
        assertNull(PresenceHapticPatterns.parseCustomPattern("   "))
    }

    @Test
    fun `whitespace around values and commas is tolerated`() {
        val segments = PresenceHapticPatterns.parseCustomPattern(" 400 , 100 ,400,100 ")
        assertEquals(listOf(400L, 100L, 400L, 100L), segments?.toList())
    }

    @Test
    fun `an odd count is rejected — every on needs its off`() {
        assertNull(PresenceHapticPatterns.parseCustomPattern("400,100,400"))
    }

    @Test
    fun `a zero or negative segment is rejected, never clamped`() {
        assertNull(PresenceHapticPatterns.parseCustomPattern("0,100"))
        assertNull(PresenceHapticPatterns.parseCustomPattern("400,-100"))
    }

    @Test
    fun `an absurdly long segment is rejected`() {
        assertNull(PresenceHapticPatterns.parseCustomPattern("400,999999"))
    }

    @Test
    fun `non-numeric input is rejected`() {
        assertNull(PresenceHapticPatterns.parseCustomPattern("fast,slow"))
        assertNull(PresenceHapticPatterns.parseCustomPattern("400,100,"))
    }

    @Test
    fun `too many segments is rejected — a short list, not a waveform editor`() {
        val tooMany = (1..(PresenceHapticPatterns.MAX_CUSTOM_SEGMENTS + 2)).joinToString(",") { "50" }
        assertNull(PresenceHapticPatterns.parseCustomPattern(tooMany))
    }

    @Test
    fun `exactly the segment cap is accepted`() {
        val atCap = (1..PresenceHapticPatterns.MAX_CUSTOM_SEGMENTS).joinToString(",") { "50" }
        assertEquals(PresenceHapticPatterns.MAX_CUSTOM_SEGMENTS, PresenceHapticPatterns.parseCustomPattern(atCap)?.size)
    }

    // --- ceiling-zero "sits this out" (2026-09-19, presence-signal-spec.md §5.3) ----------------

    @Test
    fun `a zero ceiling means do not vibrate`() {
        assertEquals(false, PresenceHapticPatterns.shouldVibrate(0))
    }

    @Test
    fun `any positive ceiling means vibrate`() {
        assertTrue(PresenceHapticPatterns.shouldVibrate(1))
        assertTrue(PresenceHapticPatterns.shouldVibrate(45))
        assertTrue(PresenceHapticPatterns.shouldVibrate(PresenceHapticPatterns.maxAmplitude()))
    }

    @Test
    fun `a negative ceiling also means do not vibrate, never a crash`() {
        assertEquals(false, PresenceHapticPatterns.shouldVibrate(-1))
    }

    @Test
    fun `a custom waveform alternates on-off amplitudes starting with on`() {
        val segments = PresenceHapticPatterns.parseCustomPattern("400,100,400,100")!!
        val (timings, amplitudes) = PresenceHapticPatterns.customWaveform(segments)
        assertEquals(segments.toList(), timings.toList())
        assertTrue(amplitudes[0] > 0)
        assertEquals(0, amplitudes[1])
        assertTrue(amplitudes[2] > 0)
        assertEquals(0, amplitudes[3])
    }
}
