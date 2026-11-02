package com.kumouri.margo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * [HealthSyncWorker.stepsWindowStart] — the steps read starts at local midnight, not at the sync
 * window's clock time, so the oldest date in a batch is a whole day and never a shrinking partial
 * (2026-10-27: 10-25 was sent as 2,277, then 1,388, then 597).
 */
class StepsWindowStartTest {
    private val chicago = ZoneId.of("America/Chicago")

    @Test
    fun `a mid-afternoon window start floors to that local midnight`() {
        // 2026-10-25T16:52:10Z is 11:52 CDT; local midnight is 05:00Z.
        assertEquals(Instant.parse("2026-10-25T05:00:00Z"),
            HealthSyncWorker.stepsWindowStart(Instant.parse("2026-10-25T16:52:10Z"), chicago))
    }

    @Test
    fun `a UTC time already past midnight UTC but still yesterday locally floors to yesterday`() {
        // 03:30Z on 10-26 is 22:30 CDT on 10-25.
        assertEquals(Instant.parse("2026-10-25T05:00:00Z"),
            HealthSyncWorker.stepsWindowStart(Instant.parse("2026-10-26T03:30:00Z"), chicago))
    }

    @Test
    fun `the DST change day floors to its own midnight`() {
        // 2026-11-01 is the fall-back day; its midnight is still CDT (05:00Z).
        assertEquals(Instant.parse("2026-11-01T05:00:00Z"),
            HealthSyncWorker.stepsWindowStart(Instant.parse("2026-11-01T20:00:00Z"), chicago))
    }

    @Test
    fun `local midnight itself is unchanged`() {
        val midnight = Instant.parse("2026-10-25T05:00:00Z")
        assertEquals(midnight, HealthSyncWorker.stepsWindowStart(midnight, chicago))
    }
}
