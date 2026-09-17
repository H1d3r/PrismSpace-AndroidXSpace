package com.yzddmr6.prismspace.prism.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.settings.PrismSettingsActivity
import com.yzddmr6.prismspace.util.Users.Companion.toId

internal object ProfileEntryLauncher {
    fun component(packageName: String): ComponentName =
        ComponentName(packageName, PrismSettingsActivity::class.java.name)

    /** The always-enabled convergence trampoline; the one profile-side channel that works
     *  before provisioning has run (no broadcast, no bridge, no privileged shell needed). */
    fun convergeComponent(packageName: String): ComponentName =
        ComponentName(packageName, CONVERGE_ACTIVITY_CLASS)

    /** Whether the profile-side settings entry is enabled. When false, opening it cannot work —
     *  the profile-side provisioning that enables it never ran. */
    fun isEnabled(context: Context, profile: UserHandle): Boolean = runCatching {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
        launcherApps.isActivityEnabled(component(context.packageName), profile)
    }.getOrDefault(false)

    fun start(context: Context, profile: UserHandle): Boolean =
        runCatching {
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
            val component = component(context.packageName)
            if (!launcherApps.isActivityEnabled(component, profile)) {
                DiagnosticLog.w(TAG, "profile entry disabled component=$component user=${profile.toId()}")
                return false
            }
            launcherApps.startMainActivity(component, profile, null, null)
            DiagnosticLog.i(TAG, "profile entry launched component=$component user=${profile.toId()}")
            true
        }.onFailure {
            DiagnosticLog.w(TAG, "profile entry launch failed user=${profile.toId()}", it)
        }.getOrDefault(false)

    /** Starts the convergence trampoline in the profile. Only meaningful while the profile is
     *  running and unlocked; anything else surfaces as false and the caller stays honest. */
    fun startConvergence(context: Context, profile: UserHandle): Boolean =
        runCatching {
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
            val component = convergeComponent(context.packageName)
            launcherApps.startMainActivity(component, profile, null, null)
            DiagnosticLog.i(TAG, "profile convergence triggered component=$component user=${profile.toId()}")
            true
        }.onFailure {
            DiagnosticLog.w(TAG, "profile convergence trigger failed user=${profile.toId()}", it)
        }.getOrDefault(false)

    private const val CONVERGE_ACTIVITY_CLASS = "com.yzddmr6.prismspace.provisioning.PrismProvisioning\$ConvergeActivity"
    private const val TAG = "Prism.ProfileEntry"
}
