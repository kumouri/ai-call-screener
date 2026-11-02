# Notification fixtures — `MessageNotificationParserTest`

Each `.json` file is one notification's extras, in the shape
`MessageNotificationParser.Snapshot.fromJson` reads: the platform's own extras keys
(`android.title`, `android.text`, `android.bigText`, `android.conversationTitle`,
`android.isGroupConversation`, `android.messages` — entries `{text, time, sender}`, no `sender` =
the device user's own line, `android.selfDisplayName`) plus `category`, `when`, `postTime` and the
raw `flags` int.

**These are synthetic** — shaped after the platform's documented extras, with made-up names and
text. None was captured off a real phone. When a real notification parses wrong on her device, the
fix starts by adding its extras here (names and bodies replaced) as a new file, so the failure is a
test before it is a patch (`margo/docs/sms-ingest-spec.md` §6, §8).
