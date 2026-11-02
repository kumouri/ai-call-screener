package com.kumouri.margo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Geofences are dropped on reboot; re-register them (and re-enqueue the flush worker) once the device
 * finishes booting, so presence keeps flowing without Ceryce reopening the app.
 *
 * **Also on `MY_PACKAGE_REPLACED`** (2026-10-06): installing a new build kills the running app, and
 * with it the presence signal's push link ([PresenceLinkService]). Both broadcasts are among the few
 * from which Android still allows a foreground-service start, so the link comes back the moment an
 * update lands — she does not have to open the app for Margo to reach her again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Geofencing.registerAll(context)
                ActivityRecognitionUpdates.register(context)
                SleepUpdates.register(context)
                PresenceSyncWorker.enqueuePeriodic(context)
                SmsSyncWorker.enqueuePeriodic(context)
                SmsBackfillWorker.enqueuePeriodic(context)
                PresenceLinkService.ensureRunning(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> PresenceLinkService.ensureRunning(context)
        }
    }
}
