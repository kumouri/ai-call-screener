package com.kumouri.margo

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.kumouri.margo.ui.AppHost
import com.kumouri.margo.ui.MargoApp
import com.kumouri.margo.ui.MargoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** How long the pattern editor's "Test pattern" button plays before self-cancelling. */
private const val PATTERN_TEST_DURATION_MS = 3000L

/**
 * The app: four Compose screens over the workers that do the actual work.
 *
 * **It was one XML screen with four buttons until 2026-08-11.** That night the health feed failed in
 * four independent ways and this app reported none of them — every fault looked like a blank screen,
 * and diagnosis needed a cable, a laptop and `adb logcat`. So Status, Access and Debug exist to make
 * the phone answer for itself, and Settings exists because the endpoints and tokens used to be
 * compiled in from a gitignored `local.properties`, which meant nobody but the author could install
 * it. That last one is the prerequisite for this ever reaching
 * [Seneschal](https://github.com/kumouri/seneschal).
 *
 * `ComponentActivity`, not `AppCompatActivity` — deliberately. The :wear module crashed on launch in
 * exactly this spot: `AppCompatActivity` under a non-AppCompat theme throws from `setContentView`.
 * Compose needs nothing from AppCompat, so taking it out of the inheritance chain removes the trap.
 * (AppCompat stays a dependency for `AlertDialog`, which the place-capture flow still uses.)
 */
class MainActivity : ComponentActivity(), AppHost {

    override val context get() = this

    /** Bumped after anything asynchronous lands, to re-read state the screens show. */
    private var onResumeRefresh: (() -> Unit)? = null

    private val roleRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val healthPermsRequest =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            // First pull the moment reads are granted, so the Status screen has something to show
            // instead of "not run yet" until the next hourly tick.
            syncHealthNow()
        }

    private val locationPermsRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                Geofencing.registerAll(this)
                maybePromptBackgroundLocation()
            }
        }

    private val activityPermRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ActivityRecognitionUpdates.register(this)
                SleepUpdates.register(this)
            }
        }

    private val smsPermsRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val read = grants[Manifest.permission.READ_SMS] == true
            val receive = grants[Manifest.permission.RECEIVE_SMS] == true
            EventLog.add(this, "sms permissions: read=$read receive=$receive")
            // A grant with the switch already on means the first backfill can run now rather than
            // at the daily tick — the same reason a Health Connect grant triggers a sync.
            if (read && SettingsStore(this).smsConfigured()) SmsBackfillWorker.runNow(this)
        }

    /** Android 13+ gates every notification on this. The export ship's foreground run posts one
     *  ("Shipping Samsung Health export…") and runs whether or not it is visible; asking is what
     *  makes it visible. Asked once, at the folder grant and at a Scan-now tap — never on launch. */
    private val notificationPermRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        notificationPermRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Set by [pickExportFolder] for the one picker in flight; the launcher itself is registered
     *  once, up front, as `registerForActivityResult` requires. */
    private var onExportFolderPicked: (() -> Unit)? = null

    private val exportFolderRequest =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    ExportWatchWorker.takeGrant(this, uri)
                    EventLog.add(this, "export folder granted: $uri")
                    toast("Folder granted. Scanning for exports…")
                    ensureNotificationPermission()
                    // Scan immediately, so the screen shows a result instead of "not run yet" until
                    // the hourly tick — the same reason a Health Connect grant triggers a sync.
                    ExportWatchWorker.scanNow(this)
                } catch (e: SecurityException) {
                    toast("Android refused to persist that folder grant: ${e.message}")
                }
            }
            onExportFolderPicked?.invoke()
            onExportFolderPicked = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MargoTheme { MargoApp(this) } }

        // Keep everything scheduled. Each enqueue is idempotent (UPDATE policy), so re-running them
        // on every launch reconciles rather than stacking.
        BlocklistSyncWorker.enqueuePeriodic(this)
        HealthSyncWorker.enqueuePeriodic(this)
        PresenceSyncWorker.enqueuePeriodic(this)
        SmsSyncWorker.enqueuePeriodic(this)
        SmsBackfillWorker.enqueuePeriodic(this)
        ExportWatchWorker.enqueuePeriodic(this)
        // The presence signal's push link (2026-10-06, "push, not polling"): one always-on
        // foreground service holding the stream the desktop writes to. Opening the app is also the
        // one sure way to (re)start it if Android refused a background start.
        PresenceLinkService.ensureRunning(this)
        // The text feed's notification listener: the system can unbind it (an update, battery
        // optimisation), and opening the app is the one sure moment to ask for it back.
        MessageNotificationListener.requestRebindIfGranted(this)
        Geofencing.registerAll(this)
        ActivityRecognitionUpdates.register(this)
        SleepUpdates.register(this)
    }

    override fun onResume() {
        super.onResume()
        onResumeRefresh?.invoke()
    }

    // --- AppHost --------------------------------------------------------------------------------

    override fun requestHealthPermissions() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            toast("Health Connect isn't available on this device.")
            return
        }
        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(HealthSyncWorker.REQUESTED_PERMISSIONS)) {
                toast("All health permissions already granted.")
            } else {
                healthPermsRequest.launch(HealthSyncWorker.REQUESTED_PERMISSIONS)
            }
        }
    }

    override fun requestLocationPermission() {
        locationPermsRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    override fun requestActivityRecognition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activityPermRequest.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    override fun requestSmsPermissions() {
        smsPermsRequest.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
    }

    override fun backfillSmsNow() {
        val settings = SettingsStore(this)
        settings.smsUnconfiguredReason()?.let { toast(it); return }
        if (!SmsBackfillWorker.hasReadSms(this)) {
            toast("Grant the SMS permissions on the Access tab first.")
            return
        }
        SmsBackfillWorker.runNow(this)
        EventLog.add(this, "manual sms backfill requested")
        toast("Backfilling texts…")
    }

    override fun resetSmsBackfill() {
        SmsLedger(this).resetWatermark()
        EventLog.add(this, "sms backfill watermark reset")
        toast("Watermark cleared. The next backfill re-reads everything.")
    }

    override fun openNotificationAccessSettings() {
        // Android 11+ can open this listener's own toggle directly; older builds get the list.
        val component = MessageNotificationListener.component(this).flattenToString()
        val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        } else null
        for (intent in listOfNotNull(direct, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // fall through to the next, broader page
            }
        }
        toast("This phone has no Notification access settings page.")
    }

    override fun requestScreeningRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        ) {
            roleRequest.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
        }
    }

    override fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        )
    }

    override fun openHealthConnect() {
        // The per-type sharing switches that decide whether anything EXISTS to read live inside
        // Health Connect, not here — a read permission we hold and an app that writes nothing look
        // identical from this side, which is the distinction that cost 2026-08-11 its evening.
        try {
            startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            toast("Health Connect isn't installed on this device.")
        }
    }

    override fun syncHealthNow() {
        val settings = SettingsStore(this)
        if (!settings.healthConfigured()) {
            toast("Set a health server address in Settings first.")
            return
        }
        HealthSyncWorker.syncNow(this)
        EventLog.add(this, "manual health sync requested")
        toast("Syncing… pull down or reopen Status in a few seconds.")
    }

    override fun syncBlocklistNow() {
        BlocklistSyncWorker.syncNow(this)
        EventLog.add(this, "manual blocklist sync requested")
        toast("Syncing blocklist…")
    }

    override fun testConnection(onResult: (String) -> Unit) {
        val settings = SettingsStore(this)
        val url = settings.healthUrl
        val token = settings.healthToken
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { probe(url, token) }
            EventLog.add(this@MainActivity, "test connection -> ${result.lineSequence().first()}")
            onResult(result)
        }
    }

    /**
     * One empty-batch POST, reported verbatim.
     *
     * An empty body is deliberate: `MAX_BODY` rejects a zero-length one with a 413, which still proves
     * the whole path — DNS, route, TLS, auth — reached a listener that answered. Anything that is not
     * an HTTP status at all is a transport fault, and saying which is the entire value here.
     */
    private fun probe(url: String, token: String): String {
        if (url.isBlank()) return "No health URL configured."
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-ndjson")
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                conn.outputStream.use { it.write(ByteArray(0)) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText()?.take(300).orEmpty()
                val verdict = when {
                    code == 401 -> "\n\nThe server is reachable but rejected the token. Check the " +
                        "bearer token in Settings matches the listener's."
                    code in 200..299 || code == 413 -> "\n\nReachable and authenticated."
                    else -> ""
                }
                "HTTP $code\n$body$verdict"
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}\n\nNo HTTP response at all — the address is " +
                "unreachable from this phone. If it is a tailnet address, check the VPN is connected."
        }
    }

    override fun pickExportFolder(onDone: () -> Unit) {
        onExportFolderPicked = onDone
        try {
            // The picker opens AT Download/Samsung Health where the provider honours an initial URI
            // (Samsung's and AOSP's do); elsewhere it opens at the root and she navigates. Either
            // way the grant is whatever folder she confirms — this is a hint, never a choice made for her.
            exportFolderRequest.launch(ExportWatchWorker.SUGGESTED_FOLDER)
        } catch (_: ActivityNotFoundException) {
            onExportFolderPicked = null
            toast("No folder picker on this device — a Files app is required.")
            onDone()
        }
    }

    override fun forgetExportFolder() {
        ExportWatchWorker.releaseGrant(this)
        EventLog.add(this, "export folder grant released")
        toast("Folder forgotten. Your files were not touched.")
    }

    override fun scanExportsNow() {
        val settings = SettingsStore(this)
        if (!settings.exportWatchConfigured()) {
            toast(if (settings.exportTreeUri.isBlank()) "Choose the export folder first."
                  else "Set a health server address in Settings first.")
            return
        }
        ensureNotificationPermission()
        ExportWatchWorker.scanNow(this)
        EventLog.add(this, "manual export scan requested")
        toast("Scanning… a notification shows while an export is being shipped.")
    }

    override fun addPlaceHere() {
        if (!Geofencing.hasFineLocation(this)) {
            requestLocationPermission()
            return
        }
        val cts = CancellationTokenSource()
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    if (loc == null) toast("Couldn't get a fix — try again, ideally near a window.")
                    else promptSavePlace(loc.latitude, loc.longitude)
                }
                .addOnFailureListener { toast("Location error: ${it.message}") }
        } catch (_: SecurityException) {
            requestLocationPermission()
        }
    }

    override fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun stopPresenceSignalNow() {
        PresenceLinkService.requestStop(this)
        EventLog.add(this, "presence signal stopped from Settings")
        toast("Stopped.")
    }

    override fun isPresenceBatteryUnrestricted(): Boolean =
        (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)

    /** The system's own "let this app run unrestricted" prompt. Granted, Android lets the link
     *  service (re)start from the background and never defers its network in Doze. */
    override fun requestPresenceBatteryUnrestricted() {
        try {
            @Suppress("BatteryLife")
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: ActivityNotFoundException) {
            openAppSettings()
        }
    }

    override fun reconnectPresenceLink() {
        PresenceLinkService.ensureRunning(this)
    }

    override fun testPresenceSignalTap() {
        PresenceHapticPatterns.vibrate(
            this,
            PresenceHapticPatterns.pingEffect(this, SettingsStore(this).presencePhoneAmplitude),
        )
    }

    /** Plays the pattern editor's current selection — the custom override if one parses, else the
     *  preview preset — for [PATTERN_TEST_DURATION_MS], then cancels. Same local-only posture as
     *  [testPresenceSignalTap]: no server round trip, no dependency on [PresenceLinkService]. */
    override fun testPresenceSignalPattern() {
        val settings = SettingsStore(this)
        val segments = settings.presenceCustomPatternSegments()
        PresenceHapticPatterns.vibrate(
            this,
            PresenceHapticPatterns.holdEffect(
                this, settings.presencePreviewPattern, settings.presencePhoneAmplitude, segments
            ),
        )
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { PresenceHapticPatterns.vibrator(this).cancel() },
            PATTERN_TEST_DURATION_MS,
        )
    }

    // --- places (still a dialog: a one-off capture flow, not a screen) ---------------------------

    private fun promptSavePlace(lat: Double, lon: Double) {
        val nameInput = EditText(this).apply {
            hint = "Place name (e.g. home)"
            setText("home")
        }
        val radiusInput = EditText(this).apply {
            hint = "Radius in metres"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("150")
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(nameInput)
            addView(radiusInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Save this location")
            .setMessage("Lat %.5f, Lon %.5f".format(lat, lon))
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { "home" }
                val radius = radiusInput.text.toString().toFloatOrNull()?.coerceIn(50f, 2000f) ?: 150f
                PlacesStore(this).save(Place(name, lat, lon, radius))
                Geofencing.registerAll(this)
                toast("Saved “$name” (${radius.toInt()} m) and registered its geofence.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun maybePromptBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) return
        AlertDialog.Builder(this)
            .setTitle("Allow location “all the time”")
            .setMessage(
                "For geofences to fire while the app is closed, Android needs background location. " +
                    "Tap Open, then choose “Allow all the time.”"
            )
            .setPositiveButton("Open") { _, _ -> openAppSettings() }
            .setNegativeButton("Later", null)
            .show()
    }
}
