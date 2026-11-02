package com.kumouri.margo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * [PresenceSignalApplier] — what a pushed signal MEANS for this phone (pure: `decide`) and the ISO
 * parsing under it. `PresenceLinkService` carries the decision out; that half needs a device.
 */
class PresenceSignalApplierTest {

    private val now = Instant.parse("2026-10-07T03:00:00Z").toEpochMilli()

    private fun signal(
        state: String = "idle",
        pattern: String? = null,
        startedAt: String? = null,
        pingSeq: Long = 0,
        pingAt: String? = null,
    ) = PresenceSignalClient.Signal(
        seq = 1, state = state, pattern = pattern, holdStartedAt = startedAt,
        holdExpiresAt = null, surface = null, lastPingSeq = pingSeq, lastPingAt = pingAt,
        lastPingLabel = "", lastPingSurface = null,
    )

    private val idle = PresenceSignalApplier.Local(0, false, "", "")

    @Test
    fun `a fresh ping plays once`() {
        val d = PresenceSignalApplier.decide(idle, signal(pingSeq = 5, pingAt = "2026-10-07T02:59:50+00:00"), now)
        assertEquals(PresenceSignalApplier.PingAction.PLAY, d.ping)
        val again = PresenceSignalApplier.decide(idle.copy(appliedPingSeq = 5), signal(pingSeq = 5), now)
        assertEquals(PresenceSignalApplier.PingAction.NONE, again.ping)
    }

    @Test
    fun `a ping that arrives late on a reconnect is stale, never played`() {
        val d = PresenceSignalApplier.decide(idle, signal(pingSeq = 5, pingAt = "2026-10-07T02:40:00+00:00"), now)
        assertEquals(PresenceSignalApplier.PingAction.STALE, d.ping)
    }

    @Test
    fun `a ping with no readable time still plays`() {
        val d = PresenceSignalApplier.decide(idle, signal(pingSeq = 5, pingAt = null), now)
        assertEquals(PresenceSignalApplier.PingAction.PLAY, d.ping)
    }

    @Test
    fun `a new hold starts, the same hold continues, a new pattern restarts`() {
        val s = signal(state = "hold", pattern = "pulse", startedAt = "2026-10-07T02:49:00+00:00")
        assertEquals(PresenceSignalApplier.HoldAction.START, PresenceSignalApplier.decide(idle, s, now).hold)
        val running = PresenceSignalApplier.Local(0, true, "2026-10-07T02:49:00+00:00", "pulse")
        assertEquals(PresenceSignalApplier.HoldAction.CONTINUE, PresenceSignalApplier.decide(running, s, now).hold)
        val repatterned = s.copy(pattern = "heartbeat")
        assertEquals(PresenceSignalApplier.HoldAction.START, PresenceSignalApplier.decide(running, repatterned, now).hold)
    }

    @Test
    fun `idle while holding stops, idle while idle does nothing`() {
        val running = PresenceSignalApplier.Local(0, true, "x", "pulse")
        assertEquals(PresenceSignalApplier.HoldAction.STOP, PresenceSignalApplier.decide(running, signal(), now).hold)
        assertEquals(PresenceSignalApplier.HoldAction.NONE, PresenceSignalApplier.decide(idle, signal(), now).hold)
    }

    @Test
    fun `parses the offset shape python's isoformat produces`() {
        val ms = PresenceSignalApplier.parseIsoToMs("2026-09-18T09:30:00+00:00")
        assertEquals(Instant.parse("2026-09-18T09:30:00Z").toEpochMilli(), ms)
    }

    @Test
    fun `parses a non-UTC offset correctly`() {
        val ms = PresenceSignalApplier.parseIsoToMs("2026-09-18T04:30:00-05:00")
        assertEquals(Instant.parse("2026-09-18T09:30:00Z").toEpochMilli(), ms)
    }

    @Test
    fun `null blank and unparseable all yield null, never now`() {
        assertNull(PresenceSignalApplier.parseIsoToMs(null))
        assertNull(PresenceSignalApplier.parseIsoToMs(""))
        assertNull(PresenceSignalApplier.parseIsoToMs("not a timestamp"))
    }
}
