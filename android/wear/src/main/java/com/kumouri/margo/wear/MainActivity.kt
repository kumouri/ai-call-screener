package com.kumouri.margo.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.PassiveListenerConfig
import kotlin.concurrent.thread

/**
 * A deliberately plain screen: grant sensor permissions, register passive monitoring, say whether it's
 * running. No Compose — the module ships one screen with three widgets, and pulling in the Compose
 * toolchain for that would be the largest dependency in the project by a wide margin, for a page
 * Ceryce opens roughly twice.
 *
 * **A plain framework `Activity`, NOT `AppCompatActivity`** — and this is load-bearing, not taste.
 * AppCompat demands a `Theme.AppCompat` descendant and throws `IllegalStateException` from
 * `setContentView` if it doesn't get one; this app uses `Theme.DeviceDefault`, which is the correct
 * Wear theme. The first build shipped as an AppCompatActivity and crashed on launch every single
 * time, silently enough that the watch just blinked and returned to the launcher. It compiled
 * perfectly — the incompatibility only exists at runtime, which is precisely why "it builds" was
 * never evidence that it works. AppCompat is a phone-oriented compat layer; a Wear app shouldn't
 * carry it at all.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Margo — wrist sensing"
            gravity = Gravity.CENTER
        }
        val register = Button(this).apply {
            text = "Start sensing"
            setOnClickListener { registerPassiveMonitoring() }
        }
        // The wrist ack. Deliberately a bare "Done" with no reminder picker: the watch cannot know
        // WHICH reminder is outstanding (nudges go out over Telegram, and there is no inbound path to
        // the phone), so the gesture means "whatever you just nudged me about, I did it" and the
        // desktop resolves it against the last nudge it actually sent today. If nothing qualifies it
        // is a no-op — which is the right failure: missing an ack costs a tap, acking the wrong thing
        // could mark something important done that wasn't.
        val ack = Button(this).apply {
            text = "Done ✓"
            setOnClickListener { sendAck() }
        }
        // Presence signal's on-wrist Stop control (margo/docs/presence-signal-spec.md). Always
        // shown rather than conditioned on PresenceHolding.holding: that flag is in-memory only and
        // a process restart loses it while a hold set from chat is still very much running (now a
        // foreground service, 2026-09-18, but still an in-memory flag), and a Stop tap with nothing
        // active is a harmless no-op either way.
        val stopPresence = Button(this).apply {
            text = "Stop presence"
            setOnClickListener { stopPresenceSignal() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
            addView(status)
            addView(register)
            addView(ack)
            addView(stopPresence)
        })

        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = REQUIRED.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ)
        }
    }

    /**
     * Ask Health Services to keep delivering to [WristListenerService].
     *
     * `setShouldUserActivityInfoBeRequested(true)` is the line the entire module exists for — it is
     * what produces asleep / passive / active-exercise from the WRIST rather than from a phone
     * noticing it has been set down.
     */
    private fun registerPassiveMonitoring() {
        val granted = REQUIRED.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            status.text = "Grant sensor permissions first"
            requestPermissionsIfNeeded()
            return
        }

        // NO data types requested — only UserActivityInfo.
        //
        // Heart rate was in here, and it was both useless and actively harmful. Useless because
        // `WristListenerService` deliberately discards wrist HR: Wear writes into the same Health
        // Connect the phone's HealthSyncWorker already reads, so forwarding it would duplicate every
        // beat under two ids. Harmful because asking for it dragged in BODY_SENSORS, which Android 16
        // (targetSdk 36) no longer grants — it is replaced by android.permission.health.READ_HEART_RATE
        // — so the permission check could never pass and "Start sensing" refused forever.
        //
        // UserActivityInfo needs only ACTIVITY_RECOGNITION, which is the one thing this module
        // genuinely requires. Asking for less is the fix.
        val config = PassiveListenerConfig.builder()
            .setShouldUserActivityInfoBeRequested(true)
            .build()

        // Off the main thread: this is a binder round trip to Health Services.
        thread {
            try {
                HealthServices.getClient(applicationContext)
                    .passiveMonitoringClient
                    .setPassiveListenerServiceAsync(WristListenerService::class.java, config)
                    // `.get()` IS THE POINT. `setPassiveListenerServiceAsync` returns a
                    // ListenableFuture and reports its failure THERE, so without this the try/catch
                    // below only ever sees a synchronous throw — and this screen said
                    // "Sensing — reporting to your phone" whether registration had succeeded or not.
                    //
                    // On 2026-08-12 it HAD succeeded (Health Services held the subscription, verified
                    // with `dumpsys activity service com.google.android.wearable.healthservices`), so
                    // this was never the cause of the silent feed — it is the thing that would have
                    // hidden the cause if it ever failed. A success message nobody checked is the
                    // defect this whole evening kept being, and it is worth closing before it costs
                    // something rather than after.
                    .get()
                Log.i(TAG, "passive monitoring registered (userActivityInfo)")
                runOnUiThread { status.text = "Sensing — reporting to your phone" }
            } catch (e: Exception) {
                Log.w(TAG, "passive registration failed", e)
                runOnUiThread { status.text = "Couldn't start: ${e.message}" }
            }
        }
    }

    /** Fire-and-report an ack tap at the phone. Off the main thread — it is a Data Layer round trip. */
    private fun sendAck() {
        status.text = "Sending…"
        thread {
            val payload = org.json.JSONObject()
                .put("t", "reminder_ack")
                .put("source", "watch")
                .put("tapped_ms", System.currentTimeMillis())
                .toString()
            val ok = PhoneBridge.send(applicationContext, PhoneBridge.PATH_ACK, payload)
            runOnUiThread {
                // Honest wording. The watch knows the tap reached the PHONE; it does not know whether
                // the desktop found a nudge to apply it to. Saying "Done!" would claim the second.
                status.text = if (ok) "Sent to your phone" else "No phone in range — try again"
            }
        }
    }

    /** `stopFromUi` itself is a non-blocking `startService` dispatch (2026-09-18: the hold now runs
     *  in `PresenceHoldService`, which relays the stop back to the phone on its own), but this still
     *  runs off the main thread — matches [sendAck]'s posture, cheap insurance if that ever changes. */
    private fun stopPresenceSignal() {
        thread {
            PresenceSignalListenerService.stopFromUi(applicationContext)
            runOnUiThread { status.text = "Presence stopped" }
        }
    }

    companion object {
        private const val TAG = "MargoWear"
        private const val REQ = 1001
        // ACTIVITY_RECOGNITION alone. BODY_SENSORS is gone on purpose: on Android 16 (targetSdk 36)
        // it is no longer a grantable runtime permission — the platform replaced it with
        // android.permission.health.READ_HEART_RATE — so leaving it in `REQUIRED` meant
        // `all { granted }` was permanently false no matter what Ceryce approved. Verified on the
        // device: ACTIVITY_RECOGNITION showed granted=true while BODY_SENSORS had no runtime grant
        // entry at all.
        private val REQUIRED = listOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
    }
}
