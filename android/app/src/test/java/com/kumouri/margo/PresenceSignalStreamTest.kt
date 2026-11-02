package com.kumouri.margo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SseParser] + [PresenceSignalStream] — reading the push stream exactly as
 * `margo/scripts/presence_signal_hub.py` writes it (`sse_event`, `HEARTBEAT_BYTES`).
 */
class PresenceSignalStreamTest {

    private fun feedAll(parser: SseParser, text: String): List<SseParser.Event> =
        text.split("\n").mapNotNull { parser.feed(it) }

    @Test
    fun `parses one hub frame into a pushed signal`() {
        val frame = "event: signal\nid: abc123\n" +
            "data: {\"push_id\":\"abc123\",\"reason\":\"hold-start\",\"seq\":19,\"state\":\"hold\"," +
            "\"pattern\":\"pulse\",\"hold_started_at\":\"2026-10-07T02:49:00+00:00\"," +
            "\"hold_expires_at\":\"2026-10-07T03:19:00+00:00\",\"surface\":\"wrist\"," +
            "\"last_ping_seq\":0,\"last_ping_at\":null,\"last_ping_label\":\"\",\"last_ping_surface\":null}\n\n"
        val events = feedAll(SseParser(), frame)
        assertEquals(1, events.size)
        val pushed = PresenceSignalStream.parse(events[0])
        assertNotNull(pushed)
        assertEquals("abc123", pushed!!.pushId)
        assertEquals("hold-start", pushed.reason)
        assertEquals(19L, pushed.signal.seq)
        assertEquals("hold", pushed.signal.state)
        assertEquals("wrist", pushed.signal.surface)
        assertNull(pushed.signal.lastPingAt)            // JSON null, not the string "null"
    }

    @Test
    fun `heartbeat comments dispatch nothing`() {
        val parser = SseParser()
        assertNull(parser.feed(": hb"))
        assertNull(parser.feed(""))
    }

    @Test
    fun `multi-line data joins with newlines and the default event type is message`() {
        val events = feedAll(SseParser(), "data: a\ndata: b\n\n")
        assertEquals("a\nb", events[0].data)
        assertEquals("message", events[0].event)
        assertNull(PresenceSignalStream.parse(events[0]))   // only `signal` events are signals
    }

    @Test
    fun `fields reset between events`() {
        val events = feedAll(SseParser(), "event: signal\nid: 1\ndata: {}\n\ndata: {}\n\n")
        assertEquals(2, events.size)
        assertEquals("message", events[1].event)
        assertEquals("1", events[1].id)                 // SSE keeps the last id; event type resets
    }

    @Test
    fun `unparseable json is dropped, not thrown`() {
        val events = feedAll(SseParser(), "event: signal\ndata: {not json\n\n")
        assertNull(PresenceSignalStream.parse(events[0]))
    }
}
