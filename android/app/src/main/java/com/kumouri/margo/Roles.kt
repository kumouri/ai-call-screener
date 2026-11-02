package com.kumouri.margo

import android.app.role.RoleManager
import android.content.Context
import android.os.Build

/**
 * Does this app currently hold the call-screening role?
 *
 * Top-level rather than a method on the Activity because three screens ask, and none of them should
 * need an Activity to find out. Returns false below API 29, where the role does not exist — which is
 * the honest answer for "is screening active", not an error.
 */
fun callScreeningHeld(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val rm = context.getSystemService(RoleManager::class.java) ?: return false
    return rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
}
