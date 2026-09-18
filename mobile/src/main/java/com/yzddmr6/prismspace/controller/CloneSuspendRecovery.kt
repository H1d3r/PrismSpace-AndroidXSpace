package com.yzddmr6.prismspace.controller

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserHandle
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.EnsureAppFreeToLaunch
import com.yzddmr6.prismspace.prism.compose.space.ShizukuPrivilegedShell
import com.yzddmr6.prismspace.prism.compose.space.SuPrivilegedShell
import com.yzddmr6.prismspace.prism.compose.space.PrivilegedShell
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

    /** Who imposed the suspension, read via privileged dumpsys; both fields null-safe by design. */
    data class SuspensionDiagnosis(val suspendingPackage: String?, val transport: String)

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
            if (finalState == true) {
                // The suspender identity is the single most valuable clue for cross-suspender
                // cases — surface it in diagnostics so field logs stop saying just "still suspended".
                val diagnosis = diagnoseSuspension(context, profile, pkg, allowSuProbe)
                DiagnosticLog.w(
                    TAG,
                    "clone unsuspend failed pkg=$pkg user=${profile.toId()} " +
                        "suspender=${diagnosis?.suspendingPackage ?: "unknown"}",
                )
            } else {
                DiagnosticLog.w(TAG, "clone unsuspend failed pkg=$pkg user=${profile.toId()} stillSuspended=$finalState")
            }
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

    /**
     * Reads the suspender identity from privileged `dumpsys package` output. Purely diagnostic:
     * null when the app is not suspended, no transport exists, or the output does not parse.
     * [allowSuProbe] gates the su fallback (probing may raise a root prompt).
     */
    @JvmStatic fun diagnoseSuspension(
        context: Context,
        profile: UserHandle,
        pkg: String,
        allowSuProbe: Boolean = false,
    ): SuspensionDiagnosis? {
        if (isSuspended(context, profile, pkg) != true) return null
        val transport = chooseTransport(ShizukuUtil.isAuthorized()) { allowSuProbe && suAvailable() }
            ?: return null
        val shell = when (transport) {
            PrivilegedTransport.SHIZUKU -> ShizukuPrivilegedShell()
            PrivilegedTransport.SU -> SuPrivilegedShell()
        }
        val output = shell.run("dumpsys package $pkg") ?: return null
        val suspender = parseSuspendingPackage(output, profile.toId())
        DiagnosticLog.i(
            TAG,
            "clone suspension diagnosis pkg=$pkg user=${profile.toId()} " +
                "suspender=${suspender ?: "unknown"} transport=${transport.name.lowercase()}",
        )
        return SuspensionDiagnosis(suspender, transport.name.lowercase())
    }

    /**
     * Last-resort recovery for a suspension nobody else can lift. Levers in damage order:
     * 1. Privileged unsuspend across EVERY available transport — Shizuku first, then su when
     *    allowed (a root-imposed suspension only yields to su; proven on-device). If any lifts
     *    the flag, no reinstall happens and no data is lost.
     * 2. `pm uninstall --user` + `pm install-existing --user` (source package must still exist
     *    in the main user). WARNING, verified on HyperOS: package-restrictions entries survive
     *    a per-user uninstall, so a package uninstalled WHILE SUSPENDED comes back suspended —
     *    the reinstall alone is not a clean slate.
     * 3. Therefore the unsuspend sweep runs AGAIN after the reinstall, then the flag is verified.
     * Every step is logged; any failure is reported as false, never papered over.
     */
    @JvmStatic fun forceRecoverViaPrivileged(
        context: Context,
        profile: UserHandle,
        pkg: String,
        allowSuProbe: Boolean = false,
    ): Boolean {
        val userId = profile.toId()
        val before = diagnoseSuspension(context, profile, pkg, allowSuProbe)
        DiagnosticLog.i(
            TAG,
            "clone force-recover start pkg=$pkg user=$userId suspender=${before?.suspendingPackage ?: "unknown"}",
        )
        if (unsuspendViaAnyTransport(context, profile, pkg, allowSuProbe)) {
            if (before != null) {
                DiagnosticLog.i(TAG, "clone force-recover done by unsuspend alone pkg=$pkg user=$userId")
            }
            return true
        }
        val transport = chooseTransport(ShizukuUtil.isAuthorized()) { allowSuProbe && suAvailable() }
        if (transport == null) {
            DiagnosticLog.w(TAG, "clone force-recover aborted: no privileged transport pkg=$pkg user=$userId")
            return false
        }
        val shell = shellFor(transport)
        // Verify by state, not by stdout: HyperOS prints "Package X installed for user: 22"
        // (no "Success") on install-existing, so output text is unreliable across ROMs.
        shell.run("pm uninstall --user $userId $pkg")
        if (isPackageInstalled(context, profile, pkg) != false) {
            DiagnosticLog.w(TAG, "clone force-recover uninstall failed pkg=$pkg user=$userId transport=${transport.name.lowercase()}")
            return false
        }
        shell.run("pm install-existing --user $userId $pkg")
        if (isPackageInstalled(context, profile, pkg) != true) {
            DiagnosticLog.w(TAG, "clone force-recover install-existing failed pkg=$pkg user=$userId transport=${transport.name.lowercase()}")
            return false
        }
        // Post-reinstall sweep: the resurrected suspension (HyperOS restrictions carry-over)
        // still belongs to the original suspender, so this needs the same transport coverage.
        val freed = unsuspendViaAnyTransport(context, profile, pkg, allowSuProbe)
        if (freed) {
            DiagnosticLog.i(TAG, "clone force-recover success pkg=$pkg user=$userId transport=${transport.name.lowercase()}")
        } else {
            DiagnosticLog.w(TAG, "clone force-recover left pkg=$pkg user=$userId suspended (unexpected)")
        }
        return freed
    }

    /** Tries `pm unsuspend` on every available transport (Shizuku first, su when allowed and
     *  available) until the flag actually clears. true when the clone ends up unsuspended. */
    private fun unsuspendViaAnyTransport(
        context: Context,
        profile: UserHandle,
        pkg: String,
        allowSuProbe: Boolean,
    ): Boolean {
        if (isSuspended(context, profile, pkg) != true) return true
        val transports = buildList {
            if (ShizukuUtil.isAuthorized()) add(PrivilegedTransport.SHIZUKU)
            if (allowSuProbe && suAvailable()) add(PrivilegedTransport.SU)
        }
        for (transport in transports) {
            val output = shellFor(transport).run("pm unsuspend --user ${profile.toId()} $pkg")
            DiagnosticLog.i(
                TAG,
                "clone unsuspend attempt pkg=$pkg user=${profile.toId()} " +
                    "transport=${transport.name.lowercase()} output=${output?.joinToString("|")?.take(120)}",
            )
            if (isSuspended(context, profile, pkg) == false) return true
        }
        return false
    }

    /** Parent-side cross-profile install check; null when the query itself failed. */
    private fun isPackageInstalled(context: Context, profile: UserHandle, pkg: String): Boolean? {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        return try {
            launcherApps.getApplicationInfo(pkg, 0, profile)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun shellFor(transport: PrivilegedTransport): PrivilegedShell = when (transport) {
        PrivilegedTransport.SHIZUKU -> ShizukuPrivilegedShell()
        PrivilegedTransport.SU -> SuPrivilegedShell()
    }

    /**
     * Parses `suspendingPackage=<uid>name` from the target user's section of `dumpsys package`.
     * Verified on-device (Android 14/16): the per-user block opens with
     * `User <id>: ceDataInode=... installed=...` and may contain a `Suspend params:` subsection.
     */
    internal fun parseSuspendingPackage(dumpsysLines: List<String>, userId: Int): String? {
        val start = dumpsysLines.indexOfFirst { line ->
            line.trimStart().startsWith("User $userId:") && line.contains("installed=")
        }
        if (start < 0) return null
        val nextUserOffset = dumpsysLines.drop(start + 1).indexOfFirst { line ->
            line.trimStart().let { it.startsWith("User ") && it.drop(5).trimStart().firstOrNull()?.isDigit() == true && it.contains(':') }
        }
        val end = if (nextUserOffset < 0) dumpsysLines.size else start + 1 + nextUserOffset
        val pattern = Regex("""suspendingPackage=<\d+>(\S+)""")
        return dumpsysLines.subList(start, end)
            .firstNotNullOfOrNull { pattern.find(it)?.groupValues?.get(1) }
    }

    private fun suAvailable(): Boolean =
        Shell.SU.run("id")?.any { it.contains("uid=0") } == true

    private const val TAG = "Prism.CloneRecovery"
}
