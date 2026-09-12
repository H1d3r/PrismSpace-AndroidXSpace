package com.yzddmr6.prismspace.notification

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.yzddmr6.prismspace.analytics.DiagnosticLog

/** One-shot notification permission prompt for explicit foreground recovery entry points. */
object NotificationPermissionPrompt {

    fun requestOnce(activity: Activity): Boolean {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val asked = prefs.getBoolean(KEY_ASKED, false)
        if (!shouldRequest(Build.VERSION.SDK_INT, granted, asked)) return false

        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        DiagnosticLog.i(TAG, "notification permission requested from foreground")
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        return true
    }

    internal fun shouldRequest(sdk: Int, granted: Boolean, asked: Boolean): Boolean =
        sdk >= Build.VERSION_CODES.TIRAMISU && !granted && !asked

    private const val PREFS = "notification_permission"
    private const val KEY_ASKED = "asked"
    private const val REQUEST_CODE = 7093
    private const val TAG = "Prism.Notification"
}
