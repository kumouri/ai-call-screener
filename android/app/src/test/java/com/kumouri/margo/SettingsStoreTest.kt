package com.kumouri.margo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure half of [SettingsStore] — the URL derivations and the text feed's gate reason — which is
 * everything the 2026-09-15 first live run turned on. The instance half needs a `Context` and a real
 * SharedPreferences (Robolectric, which this module does not carry), so the round-trip of the switch
 * itself is device-verified, not unit-tested; what IS pinned here is that the presence URL Ceryce
 * had typed resolves to a live `/sms-ingest` and that the gate names the switch, not the address.
 */
class SettingsStoreTest {

    @Test
    fun `sms ingest url is the presence origin plus sms-ingest`() {
        assertEquals(
            "http://100.71.146.120:8765/sms-ingest",
            SettingsStore.smsIngestUrlFor("http://100.71.146.120:8765/presence-ingest"),
        )
    }

    @Test
    fun `sms ingest url tolerates a bare origin, a trailing slash and padding`() {
        for (u in listOf(
            "http://100.71.146.120:8765",
            "http://100.71.146.120:8765/",
            "  http://100.71.146.120:8765/presence-ingest  ",
        )) {
            assertEquals(u, "http://100.71.146.120:8765/sms-ingest", SettingsStore.smsIngestUrlFor(u))
        }
    }

    @Test
    fun `sms ingest url keeps scheme and port, drops query and fragment`() {
        assertEquals(
            "https://margo.example.net:8443/sms-ingest",
            SettingsStore.smsIngestUrlFor("https://margo.example.net:8443/presence-ingest?x=1#f"),
        )
    }

    @Test
    fun `sms ingest url is blank for a blank or unparseable presence url`() {
        for (u in listOf("", "   ", "not a url", "100.71.146.120:8765/presence-ingest", "/presence-ingest")) {
            assertEquals(u, "", SettingsStore.smsIngestUrlFor(u))
        }
    }

    @Test
    fun `export ingest url derives the same way`() {
        assertEquals(
            "http://100.71.146.120:8765/health-export-ingest",
            SettingsStore.exportIngestUrlFor("http://100.71.146.120:8765/health-ingest"),
        )
    }

    @Test
    fun `gate reason names the switch when it is off, whatever the address`() {
        assertEquals(SettingsStore.SMS_OFF_REASON,
            SettingsStore.smsUnconfiguredReason(false, "http://100.71.146.120:8765/presence-ingest"))
        assertEquals(SettingsStore.SMS_OFF_REASON, SettingsStore.smsUnconfiguredReason(false, ""))
    }

    @Test
    fun `gate reason names the address only once the switch is on`() {
        assertEquals(SettingsStore.SMS_NO_SERVER_REASON, SettingsStore.smsUnconfiguredReason(true, ""))
        assertEquals(SettingsStore.SMS_NO_SERVER_REASON, SettingsStore.smsUnconfiguredReason(true, "nope"))
    }

    @Test
    fun `gate reason is null when on with a presence url`() {
        assertNull(SettingsStore.smsUnconfiguredReason(true, "http://100.71.146.120:8765/presence-ingest"))
    }

    @Test
    fun `presence signal urls derive from the presence origin`() {
        assertEquals(
            "http://100.71.146.120:8765/presence-signal-stream?device=phone",
            SettingsStore.presenceSignalStreamUrlFor("http://100.71.146.120:8765/presence-ingest"),
        )
        assertEquals(
            "http://100.71.146.120:8765/presence-signal-ack",
            SettingsStore.presenceSignalAckUrlFor("http://100.71.146.120:8765/presence-ingest"),
        )
        assertEquals(
            "http://100.71.146.120:8765/presence-signal-stop",
            SettingsStore.presenceSignalStopUrlFor("http://100.71.146.120:8765/presence-ingest"),
        )
    }

    @Test
    fun `notification allowlist defaults to google messages only`() {
        assertEquals(setOf("com.google.android.apps.messaging"), SettingsStore.DEFAULT_NOTIF_PACKAGES)
        assertEquals(SettingsStore.NOTIF_APPS.first().first, SettingsStore.PKG_GOOGLE_MESSAGES)
    }

    @Test
    fun `notification allowlist toggles one app and can be emptied`() {
        val withSignal = SettingsStore.withNotifPackage(SettingsStore.DEFAULT_NOTIF_PACKAGES, "org.thoughtcrime.securesms", true)
        assertEquals(setOf("com.google.android.apps.messaging", "org.thoughtcrime.securesms"), withSignal)
        val none = SettingsStore.withNotifPackage(
            SettingsStore.withNotifPackage(withSignal, "org.thoughtcrime.securesms", false),
            "com.google.android.apps.messaging", false,
        )
        assertEquals(emptySet<String>(), none)
    }

    @Test
    fun `notification app labels fall back to the package`() {
        assertEquals("Signal", SettingsStore.notifAppLabel("org.thoughtcrime.securesms"))
        assertEquals("com.example.chat", SettingsStore.notifAppLabel("com.example.chat"))
    }

    @Test
    fun `presence signal urls are blank for a blank presence url`() {
        assertEquals("", SettingsStore.presenceSignalStreamUrlFor(""))
        assertEquals("", SettingsStore.presenceSignalAckUrlFor(""))
        assertEquals("", SettingsStore.presenceSignalStopUrlFor(""))
    }
}
