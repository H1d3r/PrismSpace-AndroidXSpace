package com.yzddmr6.prismspace.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.getSystemService
import com.yzddmr6.prismspace.util.Apps
import com.yzddmr6.prismspace.util.*
import com.yzddmr6.prismspace.util.Users.Companion.toId

/**
 * Utilities of "managed profile" related functionality
 *
 * Created by Oasis on 2017/2/20.
 */
object PrismManager {

    @JvmStatic fun ensureLegacyInstallNonMarketAppAllowed(context: Context, policies: DevicePolicies): Boolean {
        if (isInstallFromUnknownSourcesAllowed(context)) return true
        if (policies.isProfileOwner) {
            policies.clearUserRestrictionsIfNeeded(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            if (SDK_INT < O) @Suppress("DEPRECATION")
                policies.execute(DPM::setSecureSetting, Settings.Secure.INSTALL_NON_MARKET_APPS, "1")
        }
        return isInstallFromUnknownSourcesAllowed(context)
    }

    @Suppress("DEPRECATION") private fun isInstallFromUnknownSourcesAllowed(context: Context) =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) > 0

    @JvmStatic @OwnerUser @ProfileUser fun ensureAppHiddenState(context: Context, pkg: String, state: Boolean): Boolean {
        val policies = DevicePolicies(context)
        if (policies.setApplicationHidden(pkg, state)) return true
        // Since setApplicationHidden() return false if already in that state, also check the current state.
        val hidden = policies(DPM::isApplicationHidden, pkg)
        return state == hidden
    }

    /** @return error information, or empty string for success. */
    @JvmStatic @OwnerUser @ProfileUser fun ensureAppFreeToLaunch(context: Context, pkg: String): String {
        val policies = DevicePolicies(context)
        return try {
            if (policies(DPM::isApplicationHidden, pkg)) policies.setApplicationHidden(pkg, false)

            val pm = context.packageManager
            val before = pm.getApplicationInfo(pkg, MATCH_UNINSTALLED_PACKAGES)
            val suspendedByThisOwner = policies.isPackageSuspended(pkg)
            val suspendedForCaller = pm.isPackageSuspended(pkg)
            val suspendedFlag = before.flags and ApplicationInfo.FLAG_SUSPENDED != 0
            if (suspendedByThisOwner || suspendedForCaller || suspendedFlag)
                policies(DPM::setPackagesSuspended, arrayOf(pkg), false)

            val after = pm.getApplicationInfo(pkg, MATCH_UNINSTALLED_PACKAGES)
            val finalSuspendedForCaller = pm.isPackageSuspended(pkg)
            val finalSuspendedFlag = after.flags and ApplicationInfo.FLAG_SUSPENDED != 0
            launchReadinessReason(
                installed = Apps.isInstalledInCurrentUser(after),
                hidden = policies(DPM::isApplicationHidden, pkg),
                // PackageManager.isPackageSuspended() reports suspension by any owner. The
                // ApplicationInfo flag is not reliable on every ROM for another suspender (for
                // example Android's profile policy controller on HyperOS), so keep it only as a
                // conservative fallback.
                suspended = finalSuspendedForCaller || finalSuspendedFlag,
            ).also { reason ->
                Log.i(
                        TAG,
                        "ensure launch pkg=$pkg ownerSuspended=$suspendedByThisOwner " +
                        "callerSuspended=$suspendedForCaller flagSuspended=$suspendedFlag " +
                        "finalCallerSuspended=$finalSuspendedForCaller " +
                        "finalFlagSuspended=$finalSuspendedFlag " +
                        "reason=${reason.ifEmpty { "ready" }}",
                )
            }
        } catch (_: PackageManager.NameNotFoundException) {
            "not_found"
        }
    }

    @JvmStatic @OwnerUser fun launchApp(context: Context, pkg: String, profile: UserHandle): LaunchResult {
        val launcherApps = context.getSystemService<LauncherApps>()!!
        try {
            val activities = launcherApps.getActivityList(pkg, profile)
            if (activities.isNullOrEmpty())
                return LaunchResult.AppMissing.also { Log.w(TAG, "Unable to launch $pkg in profile ${profile.toId()}") }
            Log.i(TAG, "Launching $pkg in profile ${profile.toId()}...")
            launcherApps.startMainActivity(activities[0].componentName, profile, null, null)
            return LaunchResult.Ok }
        catch (e: SecurityException) {    // SecurityException: Cannot retrieve activities for unrelated profile 10
            Log.e(TAG, "Error launching app: $pkg @ user $profile", e)
            return LaunchResult.Denied }
        catch (e: RuntimeException) {
            Log.e(TAG, "Error launching app: $pkg @ user $profile", e)
            return LaunchResult.Unknown(e.message) }
    }

    @JvmStatic fun getProfileIdsIncludingDisabled(context: Context): IntArray =
        context.getSystemService<UserManager>()!!.getProfileIds(Users.currentId(), false)
            ?: context.getSystemService<UserManager>()!!.userProfiles.map { it.toId() }.toIntArray() // Fallback to profiles without disabled.

    fun isReady(context: Context, profile: UserHandle) = context.getSystemService<UserManager>()!!.isUserRunning(profile)
}

private const val TAG = "Prism.Manager"

internal fun launchReadinessReason(installed: Boolean, hidden: Boolean, suspended: Boolean): String = when {
    !installed -> "not_installed"
    hidden -> "still_hidden"
    suspended -> "still_suspended"
    else -> ""
}
