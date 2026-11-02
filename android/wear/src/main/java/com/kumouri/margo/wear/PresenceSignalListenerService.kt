package com.kumouri.margo.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/**
 * The wrist's half of the presence-signal relay — the phone's `WatchBridge` sends here. The watch is
 * the PRIMARY surface for this signal (it's on her body, unlike a phone in a pocket or across the
 * room), so this drives the haptic directly rather than just mirroring a phone notification.
 *
 * No polling, no endpoint, no token — same posture as every other wrist feature in this module: the
 * phone owns the wire (since 2026-10-06 a held push stream from the desktop, `PresenceLinkService`)
 * and relays over the Data Layer, because the watch may have no wifi at all.
 *
 * **Every message it acts on is acked back** (`PhoneBridge.signalAck`, echoing the desktop's
 * `push_id`/`seq`): a ping's `played`/`silenced` right here; a hold's from [PresenceHoldService] once
 * the hold is actually playing (or `failed`, if the watch refused to start it). That ack is what lets
 * Margo tell "felt on your wrist" from "sent".
 *
 * **A hold is handed off to [PresenceHoldService] (2026-09-18), not driven from this callback.** A
 * `WearableListenerService` is a transient system-bound callback the platform is free to stop the
 * moment [onMessageReceived] returns — fine for a momentary ping, not for a repeating vibration that
 * has to outlive the app leaving the foreground.
 */
class PresenceSignalListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val json = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull()
        val pushId = json?.optString("push_id", "") ?: ""
        val seq = json?.optLong("seq", 0L) ?: 0L
        when (event.path) {
            PATH_PING -> {
                val amplitude = json?.optInt("amplitude", DEFAULT_AMPLITUDE) ?: DEFAULT_AMPLITUDE
                // `surface` (2026-09-19) mirrors PATH_HOLD_START's own gate — "phone" means this
                // ping wasn't asked to be felt on the wrist.
                val surface = json?.optString("surface", "both") ?: "both"
                val felt = surface != "phone" && PresenceHapticPatterns.shouldVibrate(amplitude)
                if (felt) {
                    PresenceHapticPatterns.vibrate(
                        applicationContext,
                        PresenceHapticPatterns.pingEffect(applicationContext, amplitude),
                    )
                }
                Log.i(TAG, "ping push=$pushId seq=$seq ${if (felt) "played" else "silenced"}")
                PhoneBridge.signalAck(applicationContext, pushId, seq, "ping", if (felt) "played" else "silenced")
            }
            PATH_HOLD_START -> {
                if (json == null) {
                    Log.w(TAG, "hold-start payload was not JSON")
                    return
                }
                val started = PresenceHoldService.start(
                    applicationContext,
                    json.optString("pattern", PresenceHapticPatterns.DEFAULT_PATTERN),
                    json.optInt("amplitude", DEFAULT_AMPLITUDE),
                    json.optString("segments", ""),
                    json.optLong("expires_at_ms", 0L),
                    json.optString("surface", "both"),
                    pushId,
                    seq,
                )
                if (!started) {
                    PhoneBridge.signalAck(
                        applicationContext, pushId, seq, "hold-start", "failed",
                        JSONObject().put("detail", "the watch refused to start the hold in the background"),
                    )
                }
            }
            // The PHONE told us to stop (see `PresenceHoldService`'s docstring for why this is
            // `stopFromRelay`, not `stop` — routing it through the local-stop path would ping-pong
            // back to the phone forever, since 2026-09-19 the phone also acts on OUR relayed stop).
            PATH_HOLD_STOP -> if (PresenceHolding.holding) {
                PresenceHoldService.stopFromRelay(applicationContext, pushId, seq)
            } else {
                PhoneBridge.signalAck(
                    applicationContext, pushId, seq, "hold-stop", "stopped",
                    JSONObject().put("detail", "nothing was playing on the watch"),
                )
            }
            else -> Log.d(TAG, "ignoring ${event.path}")
        }
    }

    companion object {
        private const val TAG = "MargoWear"

        /** Must match `WatchBridge`'s paths in the `:app` module. */
        const val PATH_PING = "/margo/presence-signal/ping"
        const val PATH_HOLD_START = "/margo/presence-signal/hold-start"
        const val PATH_HOLD_STOP = "/margo/presence-signal/hold-stop"

        /** Fallback only — the phone always relays a real `SettingsStore.presenceWatchAmplitude`. */
        private const val DEFAULT_AMPLITUDE = 35

        /** The watch's own Stop tile/button: stops [PresenceHoldService], which relays the stop back
         *  to the phone (and, from there, the desktop) itself. A plain `startService` dispatch, safe
         *  to call from the main thread. */
        fun stopFromUi(context: Context) {
            PresenceHoldService.stop(context)
        }
    }
}
