package com.kumouri.margo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the text feed's Phase 2 (`margo/docs/sms-ingest-spec.md` §6.2-§6.4): one
 * notification's extras → the incoming messages the listener ships. Run host-side over the JSON
 * fixtures in `src/test/resources/notifications/` (synthetic, extras-shaped — see that folder's
 * README), so the parsing is pinned without a device. What the device alone can show — that a real
 * Google Messages notification carries these extras at all — is §8's first-run check, not this file's.
 */
class MessageNotificationParserTest {

    private fun fixture(name: String): MessageNotificationParser.Snapshot {
        val stream = javaClass.classLoader!!.getResourceAsStream("notifications/$name")
            ?: error("missing fixture $name")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return MessageNotificationParser.Snapshot.fromJson(JSONObject(text))
    }

    private fun parse(name: String) = MessageNotificationParser.parse(fixture(name))

    @Test
    fun `messaging style one-to-one yields only the other person's lines`() {
        val msgs = parse("messages_rcs_one_to_one.json")
        assertEquals(listOf("are we still on for tonight?", "running 10 late, save me a seat"), msgs.map { it.body })
        assertTrue(msgs.all { it.sender == "Alex Rivera" })
        assertEquals(listOf(1789516000000L, 1789518000000L), msgs.map { it.tsMs })
        assertTrue(msgs.all { it.senderSource == MessageNotificationParser.SOURCE_MESSAGING_STYLE })
        assertTrue(msgs.all { it.isGroup == false && it.conversation == null })
    }

    @Test
    fun `messaging style group keeps each sender and drops her own line by name`() {
        val msgs = parse("messages_rcs_group.json")
        assertEquals(listOf("Sam", "Jo", "Jo"), msgs.map { it.sender })
        assertFalse(msgs.any { it.body.startsWith("not me") })
        assertTrue(msgs.all { it.conversation == "Climbing crew" && it.isGroup == true })
        // Media arrives as whatever placeholder the app wrote; nothing is fetched (§6.4).
        assertEquals("📷 Photo", msgs.last().body)
    }

    @Test
    fun `plain group text marks a parsed sender as parsed`() {
        val (m) = parse("plain_group_sender_in_text.json")
        assertEquals("Mom", m.sender)
        assertEquals("call me when you land, no rush", m.body)       // bigText wins over text
        assertEquals("Family", m.conversation)
        assertEquals(MessageNotificationParser.SOURCE_PARSED, m.senderSource)
        assertEquals(true, m.isGroup)
    }

    @Test
    fun `plain group text with no speaker falls back to the group, marked as such`() {
        val s = fixture("plain_group_sender_in_text.json").copy(bigText = null, text = "see everyone sunday")
        val (m) = MessageNotificationParser.parse(s)
        assertEquals("Family", m.sender)
        assertEquals(MessageNotificationParser.SOURCE_CONVERSATION, m.senderSource)
    }

    @Test
    fun `plain one-to-one uses the title as sender and post time when when is zero`() {
        val (m) = parse("plain_one_to_one_no_when.json")
        assertEquals("Sam", m.sender)
        assertEquals(MessageNotificationParser.SOURCE_TITLE, m.senderSource)
        assertEquals(1789521000000L, m.tsMs)
        assertNull(m.conversation)
    }

    @Test
    fun `a group summary is never a message`() {
        assertTrue(parse("group_summary.json").isEmpty())
    }

    @Test
    fun `a non-message notification from a messaging app is never shipped`() {
        assertTrue(parse("not_a_message.json").isEmpty())
    }

    @Test
    fun `an ongoing notification is never a message`() {
        val s = fixture("plain_one_to_one_no_when.json").copy(isOngoing = true)
        assertTrue(MessageNotificationParser.parse(s).isEmpty())
    }

    @Test
    fun `messaging style entries without text or with blank senders are skipped`() {
        val s = fixture("messages_rcs_one_to_one.json").copy(
            messages = listOf(
                MessageNotificationParser.Entry(text = "  ", timeMs = 1L, sender = "Alex"),
                MessageNotificationParser.Entry(text = "hi", timeMs = 2L, sender = "  "),
                MessageNotificationParser.Entry(text = "kept", timeMs = 0L, sender = "Alex"),
            ),
        )
        val msgs = MessageNotificationParser.parse(s)
        assertEquals(listOf("kept"), msgs.map { it.body })
        assertEquals(s.whenMs, msgs.single().tsMs)                    // no entry time → when
    }

    @Test
    fun `a messaging style with no entries yields nothing rather than the plain path`() {
        val s = fixture("messages_rcs_one_to_one.json").copy(messages = emptyList())
        assertTrue(MessageNotificationParser.parse(s).isEmpty())
    }

    @Test
    fun `recent keys pass a message once and a repost never`() {
        val recent = MessageNotificationParser.RecentKeys(capacity = 2)
        val (a, b) = parse("messages_rcs_one_to_one.json")
        assertTrue(recent.firstTime("app", a))
        assertFalse(recent.firstTime("app", a))
        assertTrue(recent.firstTime("other.app", a))                  // the app is part of the key
        assertTrue(recent.firstTime("app", b))                        // evicts the oldest
        assertTrue(recent.firstTime("app", a.copy(tsMs = a.tsMs + 1)))
    }

    @Test
    fun `the wire line carries the desktop's notif-msg shape`() {
        val m = parse("messages_rcs_group.json").first()
        val o = JSONObject(SmsStore.notifLine("com.google.android.apps.messaging", m, "0|key"))
        assertEquals("notif-msg", o.getString("kind"))
        assertEquals("com.google.android.apps.messaging", o.getString("app"))
        assertEquals("Sam", o.getString("sender"))
        assertEquals("who's driving saturday", o.getString("body"))
        assertEquals(1789516000000L, o.getLong("ts_ms"))
        assertEquals("Climbing crew", o.getString("conversation"))
        assertTrue(o.getBoolean("is_group"))
        assertEquals("messaging_style", o.getString("sender_source"))
        assertEquals("0|key", o.getString("notif_key"))
        assertTrue(o.has("offset_min"))
    }

    @Test
    fun `the wire line omits what the notification did not say`() {
        val m = parse("plain_one_to_one_no_when.json").single()
        val o = JSONObject(SmsStore.notifLine("org.thoughtcrime.securesms", m))
        assertFalse(o.has("conversation"))
        assertFalse(o.has("notif_key"))
        assertFalse(o.getBoolean("is_group"))
    }
}
