package com.kumouri.margo.wear

import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import java.time.ZoneId
import java.time.Instant

/**
 * The point of the whole module: a **wrist** judgment of what Ceryce is doing.
 *
 * Health Services derives [UserActivityState] from wrist motion and heart rate. That is categorically
 * better evidence than the phone's Sleep API, which infers sleep from a handset being idle — which is
 * why it declared her asleep in the middle of the afternoon and why the `asleep` reminder gate was
 * retired on 2026-07-13 rather than trusted.
 *
 * Health Services binds this service itself and delivers on its own schedule, including while the app
 * is not running. Everything here must therefore be cheap, non-blocking-ish, and tolerant of being
 * called at any time.
 *
 * **This service only reports.** It never decides anything, never gates a reminder, and never notifies.
 * Reviving the reminder gate on the back of this signal is a separate, ask-high proposal that has to
 * be argued from real data — the whole reason `presence-context.json` now records WHICH device a
 * reading came from.
 */
class WristListenerService : PassiveListenerService() {

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val at = info.stateChangeTime
        val offsetMin = ZoneId.systemDefault().rules.getOffset(at).totalSeconds / 60

        // SAY THAT IT ARRIVED, before anything can go wrong downstream.
        //
        // On 2026-08-12 the wrist feed had delivered zero events and the question "has this callback
        // ever fired?" could not be answered from the watch at all — only inferred, from the phone's
        // receiver never being invoked, which is the far end of a four-link chain. Everything else was
        // verifiable by then (subscription registered, permission granted, Data Layer node connected),
        // so the ONE unobservable link was the one that mattered. This line makes it observable: a
        // state change that fires and then fails to send now leaves a trace on the device that saw it.
        Log.i(TAG, "userActivity ${info.userActivityState} at $at (offset ${offsetMin}m)")

        when (info.userActivityState) {
            UserActivityState.USER_ACTIVITY_ASLEEP -> {
                emit(PhoneBridge.sleepEvent("asleep", at.toEpochMilli(), offsetMin))
            }
            UserActivityState.USER_ACTIVITY_PASSIVE,
            UserActivityState.USER_ACTIVITY_UNKNOWN -> {
                // Leaving the asleep state is the transition that actually matters — it's what
                // contradicts a phone that thinks she's still in bed. `passive` maps to "still"
                // because that is the vocabulary presence_import already understands from the
                // phone's Activity Recognition feed; inventing a new label would fragment the log.
                emit(PhoneBridge.sleepEvent("awake", at.toEpochMilli(), offsetMin))
                emit(PhoneBridge.activityEvent("still", at.toEpochMilli(), offsetMin))
            }
            UserActivityState.USER_ACTIVITY_EXERCISE -> {
                emit(PhoneBridge.sleepEvent("awake", at.toEpochMilli(), offsetMin))
                emit(PhoneBridge.activityEvent("exercise", at.toEpochMilli(), offsetMin))
            }
            else -> Log.d(TAG, "unhandled user activity state: ${info.userActivityState}")
        }
    }

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        // Nothing is subscribed: `PassiveListenerConfig` requests no data types, only UserActivityInfo.
        //
        // Heart rate used to be requested here and immediately thrown away, because the phone's
        // HealthSyncWorker already reads wrist HR out of Health Connect and forwarding it from the
        // watch would duplicate every beat under two ids. Subscribing to data we discard also dragged
        // in BODY_SENSORS, which Android 16 no longer grants — so it broke the permission gate for a
        // stream we never wanted. This override stays only to satisfy the base class.
        Log.d(TAG, "unexpected data points (${dataPoints.dataTypes.size} types) — none are subscribed")
    }

    private fun emit(line: String) {
        // PassiveListenerService callbacks run on a background thread, so a blocking bridge send is
        // fine here and gives us a real success/failure rather than a fire-and-forget.
        val ok = PhoneBridge.send(applicationContext, PhoneBridge.PATH_PRESENCE, line)
        // A DROPPED READING IS SAID OUT LOUD. `send` returning false means the phone was out of range
        // and the reading is gone — deliberately not queued (a stale wrist state replayed an hour late
        // is worse than a gap, since the whole point is outranking the phone's CURRENT guess). "Gone"
        // is an acceptable outcome; "gone and unrecorded" is how a feed dies quietly, which is the
        // failure this module's own history is made of.
        if (!ok) Log.w(TAG, "phone unreachable — DROPPED: $line")
    }

    companion object {
        private const val TAG = "MargoWear"

        /** For tests/manual checks: the instant helper used above, isolated. */
        fun offsetMinutesAt(at: Instant): Int =
            ZoneId.systemDefault().rules.getOffset(at).totalSeconds / 60
    }
}
