package com.yzddmr6.prismspace.controller

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.UserHandle
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.EnsureAppFreeToLaunch
import com.yzddmr6.prismspace.prism.compose.space.ShizukuPrivilegedShell
import com.yzddmr6.prismspace.prism.compose.space.SuPrivilegedShell
import com.yzddmr6.prismspace.prism.compose.space.PrivilegedTransport
import com.yzddmr6.prismspace.prism.compose.space.chooseTransport
import com.yzddmr6.prismspace.prism.compose.vm.ShizukuUtil
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.runProfileBridgeOperation
import com.yzddmr6.prismspace.shuttle.DEFAULT_SYNC_TIMEOUT_MS
import com.yzddmr6.prismspace.util.Users.Companion.toId
import eu.chainfire.libsuperuser.Shell

/**
 * Cross-suspender suspension recovery for clones.
 *
 * Proven on-device: a clone suspended by the system/shell (`suspendingPackage=root`, MIUI policy
 * controller, …) cannot be un-suspended by the profile owner's own `setPackagesSuspended(false)` —
 * PackageManager only honors the original suspender. The app's "restore" then fails silently.
 * The remaining lever is a privileged shell (`pm unsuspend --user <id> <pkg>`), which the system
 * accepts regardless of the suspender. This helper verifies the outcome after every step so no
 * path can claim success without the suspended flag actually clearing.
 */
object CloneSuspendRecovery {

    enum class Result { ALREADY_FREE, BRIDGE_FREED, PRIVILEGED_FREED, BRIDGE_UNAVAILABLE, NO_TRANSPORT, STILL_SUSPENDED }

    /** Parent-side cross-profile suspension check; null when the query itself failed. */
    fun isSuspended(context: Context, profile: UserHandle, pkg: String): Boolean? = runCatching {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        val info = launcherApps.getApplicationInfo(pkg, 0, profile)
        (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
    }.getOrNull()

    /**
     * Best-effort unsuspend: bridge first (profile-owner DPM, covers hide too), verify, then a
     * privileged `pm unsuspend` when the suspension was imposed by someone else. [allowSuProbe]
     * gates the su fallback — probing su may raise a root prompt, so launch/restore paths pass
     * false and only Shizuku (already user-authorized) is used there.
     */
    @JvmStatic fun ensureUnsuspended(
        context: Context,
        profile: UserHandle,
        pkg: String,
        allowSuProbe: Boolean,
    ): Result {
        if (isSuspended(context, profile, pkg) == false) return Result.ALREADY_FREE

        val bridge = runProfileBridgeOperation(
            context,
            TAG,
            "clone ensure unsuspended pkg=$pkg",
            target = BridgeTargets.profile(profile.toId()),
            timeoutMs = DEFAULT_SYNC_TIMEOUT_MS,
            command = EnsureAppFreeToLaunch(pkg),
        )
        if (bridge !is ProfileBridgeResult.Value) {
            DiagnosticLog.w(TAG, "clone unsuspend bridge unavailable pkg=$pkg user=${profile.toId()} result=$bridge")
        } else if (isSuspended(context, profile, pkg) == false) {
            DiagnosticLog.i(TAG, "clone unsuspend via bridge pkg=$pkg user=${profile.toId()}")
            return Result.BRIDGE_FREED
        }

        return if (privilegedUnsuspend(context, profile, pkg, allowSuProbe)) {
            DiagnosticLog.i(TAG, "clone unsuspend via privileged shell pkg=$pkg user=${profile.toId()}")
            Result.PRIVILEGED_FREED
        } else {
            val finalState = isSuspended(context, profile, pkg)
            DiagnosticLog.w(TAG, "clone unsuspend failed pkg=$pkg user=${profile.toId()} stillSuspended=$finalState")
            if (finalState == true) {
                if (ShizukuUtil.isAuthorized() || (allowSuProbe && suAvailable())) Result.STILL_SUSPENDED
                else Result.NO_TRANSPORT
            } else {
                // Verification query itself is unreliable here; report bridge state honestly.
                if (bridge !is ProfileBridgeResult.Value) Result.BRIDGE_UNAVAILABLE else Result.STILL_SUSPENDED
            }
        }
    }

    /** Privileged-only unsuspend. @return true when the suspended flag is cleared at the end. */
    @JvmStatic fun privilegedUnsuspend(
        context: Context,
        profile: UserHandle,
        pkg: String,
        allowSuProbe: Boolean = false,
    ): Boolean {
        if (isSuspended(context, profile, pkg) == false) return true
        val transport = chooseTransport(ShizukuUtil.isAuthorized()) { allowSuProbe && suAvailable() }
            ?: return false
        val shell = when (transport) {
            PrivilegedTransport.SHIZUKU -> ShizukuPrivilegedShell()
            PrivilegedTransport.SU -> SuPrivilegedShell()
        }
        val output = shell.run("pm unsuspend --user ${profile.toId()} $pkg")
        DiagnosticLog.i(
            TAG,
            "clone unsuspend privileged attempt pkg=$pkg user=${profile.toId()} " +
                "transport=${transport.name.lowercase()} output=${output?.joinToString("|")?.take(120)}",
        )
        return isSuspended(context, profile, pkg) == false
    }

    private fun suAvailable(): Boolean =
        Shell.SU.run("id")?.any { it.contains("uid=0") } == true

    private const val TAG = "Prism.CloneRecovery"
}
