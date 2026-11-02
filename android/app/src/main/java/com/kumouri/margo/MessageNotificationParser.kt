package com.kumouri.margo

import org.json.JSONObject

/**
 * The pure half of the notification listener (`margo/docs/sms-ingest-spec.md` §6): one messaging
 * app's notification, already copied out of its `Notification` into a plain [Snapshot], becomes zero
 * or more incoming messages. No Android API is touched here, so the whole decision is a JVM unit test
 * over JSON fixtures shaped like the notification's extras (`MessageNotificationParserTest`).
 *
 * **Two paths, best first.**
 * - **`MessagingStyle`** (`EXTRA_MESSAGES`): each entry carries its own text, time and sender — the
 *   good path, and the one Google Messages uses for its conversations, RCS included. Entries the
 *   DEVICE USER sent (no sender, or the style's own user) are dropped: the conversation history a
 *   notification shows after an inline reply includes her own lines, and Phase 2 is incoming only.
 *   Every update re-posts the recent conversation, so the same entry is seen many times; the desktop
 *   dedupes on `(app, sender, body, time)` and [RecentKeys] spares the wire most of the repeats.
 * - **Plain** (`EXTRA_TITLE` + `EXTRA_BIG_TEXT`/`EXTRA_TEXT`): only for a notification the app itself
 *   categorised as a message (`category == "msg"`) — anything else from a messaging app ("backing
 *   up", "Messages is running", a missed-call notice) is not a message and is never shipped. In a
 *   group the title is the group's name; a `"Sender: body"` text yields a sender marked `parsed`,
 *   otherwise the group name stands in as the sender and is marked `conversation` — never asserted
 *   as a person.
 *
 * A group summary and an ongoing notification are never messages. Truncation, silenced apps and
 * media placeholders are the known limits (§6.4) — this reads what the notification shows, and only
 * that.
 */
object MessageNotificationParser {

    /** `Notification.CATEGORY_MESSAGE`, inlined so this file needs no Android class at all. */
    const val CATEGORY_MESSAGE = "msg"

    const val SOURCE_MESSAGING_STYLE = "messaging_style"
    const val SOURCE_TITLE = "title"
    const val SOURCE_PARSED = "parsed"
    const val SOURCE_CONVERSATION = "conversation"

    /** One `MessagingStyle` entry. `sender == null` is how the platform marks the device user's own. */
    data class Entry(val text: String?, val timeMs: Long, val sender: String?)

    /** The parts of a `Notification` this parser reads, copied out by the listener. */
    data class Snapshot(
        val title: String?,
        val text: String?,
        val bigText: String?,
        val conversationTitle: String?,
        val isGroup: Boolean,
        /** `null` = no `MessagingStyle` at all (the plain path); empty = a style with no entries. */
        val messages: List<Entry>?,
        /** The style's own user name, so an app that names her rather than leaving `sender` empty
         *  is still recognised as her. */
        val selfName: String?,
        val category: String?,
        val whenMs: Long,
        val postTimeMs: Long,
        val isGroupSummary: Boolean,
        val isOngoing: Boolean,
    ) {
        /** A message time when the notification gives none of its own: `when`, else when it posted. */
        fun fallbackTimeMs(): Long = if (whenMs > 0) whenMs else postTimeMs

        companion object {
            private const val FLAG_ONGOING_EVENT = 0x02
            private const val FLAG_GROUP_SUMMARY = 0x200

            /**
             * A fixture → a [Snapshot]. The keys are the platform's own extras keys
             * (`android.title`, `android.messages`, …) plus `category`, `when`, `postTime` and the
             * raw `flags` int, so a capture of a real notification's extras drops straight in.
             * Inside `android.messages`, an entry with no `sender` is the device user's own, exactly
             * as the platform's bundle has it.
             */
            fun fromJson(o: JSONObject): Snapshot {
                val messages = o.optJSONArray("android.messages")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val m = arr.getJSONObject(i)
                        Entry(
                            text = m.optStringOrNull("text"),
                            timeMs = m.optLong("time", 0L),
                            sender = m.optStringOrNull("sender"),
                        )
                    }
                }
                val flags = o.optInt("flags", 0)
                return Snapshot(
                    title = o.optStringOrNull("android.title"),
                    text = o.optStringOrNull("android.text"),
                    bigText = o.optStringOrNull("android.bigText"),
                    conversationTitle = o.optStringOrNull("android.conversationTitle"),
                    isGroup = o.optBoolean("android.isGroupConversation", false),
                    messages = messages,
                    selfName = o.optStringOrNull("android.selfDisplayName"),
                    category = o.optStringOrNull("category"),
                    whenMs = o.optLong("when", 0L),
                    postTimeMs = o.optLong("postTime", 0L),
                    isGroupSummary = flags and FLAG_GROUP_SUMMARY != 0,
                    isOngoing = flags and FLAG_ONGOING_EVENT != 0,
                )
            }

            private fun JSONObject.optStringOrNull(key: String): String? =
                if (has(key) && !isNull(key)) getString(key) else null
        }
    }

    /** One incoming message, ready for [SmsStore.notifLine]. */
    data class Message(
        val sender: String,
        val body: String,
        val tsMs: Long,
        val conversation: String?,
        /** `null` when the notification did not say. */
        val isGroup: Boolean?,
        val senderSource: String,
    )

    /** `"Sender: body"` — how a plain group notification often packs the speaker into its text. */
    private val GROUP_LINE = Regex("""^([^:\n]{1,60}):\s+(.+)$""", RegexOption.DOT_MATCHES_ALL)

    fun parse(s: Snapshot): List<Message> {
        if (s.isGroupSummary || s.isOngoing) return emptyList()
        val entries = s.messages
        if (entries != null) return parseMessagingStyle(s, entries)
        if (s.category != CATEGORY_MESSAGE) return emptyList()
        return listOfNotNull(parsePlain(s))
    }

    private fun parseMessagingStyle(s: Snapshot, entries: List<Entry>): List<Message> {
        val conversation = s.conversationTitle.clean() ?: if (s.isGroup) s.title.clean() else null
        val self = s.selfName.clean()
        return entries.mapNotNull { e ->
            val sender = e.sender.clean() ?: return@mapNotNull null     // her own line
            if (self != null && sender == self) return@mapNotNull null
            val body = e.text.clean() ?: return@mapNotNull null
            Message(
                sender = sender,
                body = body,
                tsMs = if (e.timeMs > 0) e.timeMs else s.fallbackTimeMs(),
                conversation = conversation,
                isGroup = s.isGroup,
                senderSource = SOURCE_MESSAGING_STYLE,
            )
        }
    }

    private fun parsePlain(s: Snapshot): Message? {
        val title = s.title.clean() ?: return null
        val body = (s.bigText.clean() ?: s.text.clean()) ?: return null
        val ts = s.fallbackTimeMs()
        if (!s.isGroup) {
            return Message(title, body, ts, conversation = null, isGroup = false, senderSource = SOURCE_TITLE)
        }
        val m = GROUP_LINE.matchEntire(body)
        return if (m != null) {
            Message(m.groupValues[1].trim(), m.groupValues[2].trim(), ts, title, true, SOURCE_PARSED)
        } else {
            Message(title, body, ts, title, true, SOURCE_CONVERSATION)
        }
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A bounded memory of `(app, sender, time, body)` already queued, so a `MessagingStyle` re-post of
     * a ten-line conversation does not put ten old lines back on the wire. In memory only: a restart
     * forgets it and the desktop's exact dedupe absorbs the repeat — this saves bandwidth, it is not
     * what makes the store correct.
     */
    class RecentKeys(private val capacity: Int = 512) {
        private val seen = object : LinkedHashMap<String, Unit>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
                size > capacity
        }

        /** True the first time this message is offered (and remembers it); false for a repeat. */
        @Synchronized
        fun firstTime(app: String, m: Message): Boolean {
            val key = "$app\u0000${m.sender}\u0000${m.tsMs}\u0000${m.body}"
            if (seen.containsKey(key)) {
                seen[key] = Unit   // refresh its place
                return false
            }
            seen[key] = Unit
            return true
        }
    }
}
