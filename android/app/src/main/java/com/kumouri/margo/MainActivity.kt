package com.kumouri.margo

import android.app.role.RoleManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

/**
 * One-screen control panel: grant the Call-Screening role, see how many numbers
 * are blocked + when we last synced, and force a sync. The actual blocking runs
 * in CallScreeningServiceImpl; syncing in BlocklistSyncWorker.
 */
class MainActivity : AppCompatActivity() {

    private val roleRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.grantRole).setOnClickListener { requestScreeningRole() }
        findViewById<Button>(R.id.syncNow).setOnClickListener {
            BlocklistSyncWorker.syncNow(this)
            findViewById<TextView>(R.id.status).text = getString(R.string.syncing)
        }

        // Keep the blocklist fresh in the background.
        BlocklistSyncWorker.enqueuePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestScreeningRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        ) {
            roleRequest.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
        }
    }

    private fun isRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun refresh() {
        val store = BlocklistStore(this)
        val held = isRoleHeld()
        val syncedAt = store.lastSyncedAt()
        val syncedText =
            if (syncedAt == 0L) "never" else DateFormat.getDateTimeInstance().format(Date(syncedAt))

        findViewById<TextView>(R.id.status).text = buildString {
            appendLine(if (held) "✅ Screening active" else "⚠️ Tap below to make Margo the screening app")
            appendLine("Blocked numbers: ${store.count()}")
            append("Last synced: $syncedText")
        }
        findViewById<Button>(R.id.grantRole).isEnabled = !held
    }
}
