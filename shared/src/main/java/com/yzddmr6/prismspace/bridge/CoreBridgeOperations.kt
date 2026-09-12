package com.yzddmr6.prismspace.bridge

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager
import android.provider.Settings
import androidx.annotation.WorkerThread
import com.yzddmr6.prismspace.PrismNameManager
import com.yzddmr6.prismspace.appops.AppOpsHelper
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.engine.LaunchResult
import com.yzddmr6.prismspace.engine.PrismManager
import com.yzddmr6.prismspace.shuttle.ShuttleProvider
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.util.Users

const val ACTION_START_PROFILE_DEACTIVATION = "com.yzddmr6.prismspace.action.START_PROFILE_DEACTIVATION"
const val EXTRA_PROFILE_USER_ID = "com.yzddmr6.prismspace.extra.PROFILE_USER_ID"

internal object CoreBridgeOperations {
    fun queryProfileProvisioningFacts(context: Context) = ProfileProvisioningFactsDto(
        profileOwner = DevicePolicies(context).isProfileOwner,
        provisionComplete = isProfileProvisioningComplete(context),
    )

    fun triggerIncrementalProvisioning(context: Context): Boolean {
        if (!DevicePolicies(context).isProfileOwner) return false
        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            .setComponent(ComponentName(context.packageName, PROVISIONING_SERVICE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
        return true
    }

    fun wipeProfile(context: Context): Boolean = ProfileWipe.wipeSelf(context)

    fun queryIsProfileOwner(context: Context) = DevicePolicies(context).isProfileOwner

    @WorkerThread
    fun saveProfileName(context: Context, profileUserId: Int, name: String): Boolean {
        requireNotNull(BridgeTargets.profileFresh(context, profileUserId)) { "Unmanaged profile $profileUserId" }
        PrismNameManager.saveProfileNameFromBridge(context, profileUserId, name)
        return true
    }

    fun establishBackwardGrant(context: Context) = ShuttleProvider.establishBackwardGrant(context)

    fun setAppOpMode(context: Context, packageName: String, op: Int, mode: Int, uid: Int) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { "App-op delegation requires Android P" }
        require(UserHandles.getUserId(uid) == Users.currentId()) { "UID does not belong to command user" }
        AppOpsHelper(context).setMode(packageName, op, mode, uid)
    }

    fun startProfileDeactivation(context: Context, profileUserId: Int) {
        requireNotNull(BridgeTargets.profile(profileUserId)) { "Unmanaged profile $profileUserId" }
        val implicit = Intent(ACTION_START_PROFILE_DEACTIVATION)
            .setPackage(context.packageName)
            .putExtra(EXTRA_PROFILE_USER_ID, profileUserId)
        val component = requireNotNull(context.packageManager.resolveService(implicit, 0)?.serviceInfo) {
            "Profile deactivation service is unavailable"
        }.let { ComponentName(it.packageName, it.name) }
        context.startService(implicit.setComponent(component))
    }

    fun unfreezeAndLaunch(context: Context, packageName: String): LaunchOutcomeDto {
        val reason = PrismManager.ensureAppFreeToLaunch(context, packageName)
        if (reason.isNotEmpty()) return LaunchOutcomeDto(LaunchOutcomeKind.Unknown, reason)
        return PrismManager.launchApp(context, packageName, Users.current()).toDto()
    }

    fun openAppDetails(context: Context, packageName: String) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Presents the system uninstaller from INSIDE the profile: the intent is built from
     *  [uninstallIntentSpec], which deliberately offers no way to target another user — caller
     *  user == target user, so no ROM uninstaller behavior can redirect the request at another
     *  user's copy of the package (issue #6). */
    fun requestAppUninstall(context: Context, packageName: String): UninstallLaunchDto =
        when (val spec = uninstallIntentSpec(packageName, context.packageName)) {
            is UninstallIntentSpec.Refused -> UninstallLaunchDto(UninstallLaunchKind.Failed, spec.reason)
            is UninstallIntentSpec.Launch -> try {
                context.startActivity(
                    Intent(spec.action, Uri.parse(spec.packageUri))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                UninstallLaunchDto(UninstallLaunchKind.Launched)
            } catch (error: RuntimeException) {
                DiagnosticLog.w(TAG, "profile uninstall launch failed pkg=$packageName exception=${error.javaClass.name}")
                UninstallLaunchDto(UninstallLaunchKind.Failed, error.javaClass.simpleName)
            }
        }

    fun openDiagnosticsSnapshot(context: Context): DiagnosticsSnapshotSessionDto =
        DiagnosticLog.openChunkedSnapshot(context).let { session ->
            DiagnosticsSnapshotSessionDto(session.token, session.totalLength)
        }

    fun readDiagnosticsChunk(context: Context, token: String, offset: Long): DiagnosticsChunkResultDto =
        DiagnosticLog.readChunkedSnapshot(context, token, offset, DIAGNOSTICS_CHUNK_BYTES)
            ?.let { DiagnosticsChunkDto(it.bytes, it.eof) }
            ?: DiagnosticsSnapshotInvalid

    fun closeDiagnosticsSnapshot(context: Context, token: String) =
        DiagnosticLog.closeChunkedSnapshot(context, token)

    private fun LaunchResult.toDto() = when (this) {
        LaunchResult.Ok -> LaunchOutcomeDto(LaunchOutcomeKind.Ok)
        LaunchResult.AppMissing -> LaunchOutcomeDto(LaunchOutcomeKind.AppMissing)
        LaunchResult.Denied -> LaunchOutcomeDto(LaunchOutcomeKind.Denied)
        LaunchResult.SpaceNotReady -> LaunchOutcomeDto(LaunchOutcomeKind.Unknown, "space_not_ready")
        is LaunchResult.Unknown -> LaunchOutcomeDto(LaunchOutcomeKind.Unknown, reason)
    }
}

fun isProfileProvisioningComplete(context: Context): Boolean =
    PreferenceManager.getDefaultSharedPreferences(context)
        .getInt(PROVISION_STATE_KEY, 0) > PROVISION_STATE_STARTED

/** The only irreversible profile-local data erasure primitive. */
object ProfileWipe {
    fun wipeSelf(context: Context): Boolean {
        val policies = DevicePolicies(context)
        if (!policies.isProfileOwner) return false
        policies.manager.wipeData(0)
        return true
    }
}

/**
 * Pure, JVM-testable spec for the profile-side system-uninstall intent. [Launch] carries exactly an
 * action and a package `Uri` — there is structurally no field through which a caller could name a
 * target user, so the uninstall confirmation can only ever run against the caller's own user.
 */
internal sealed interface UninstallIntentSpec {
    data class Launch(val action: String, val packageUri: String) : UninstallIntentSpec
    data class Refused(val reason: String) : UninstallIntentSpec
}

internal fun uninstallIntentSpec(packageName: String, selfPackageName: String): UninstallIntentSpec = when {
    packageName.isBlank() -> UninstallIntentSpec.Refused("blank_package")
    packageName == selfPackageName -> UninstallIntentSpec.Refused("self_uninstall")
    else -> UninstallIntentSpec.Launch(Intent.ACTION_UNINSTALL_PACKAGE, "package:$packageName")
}

private const val PROVISION_STATE_KEY = "provision.state"
private const val PROVISION_STATE_STARTED = 1
private const val PROVISIONING_SERVICE = "com.yzddmr6.prismspace.provisioning.PrismProvisioning"
private const val TAG = "Prism.CoreOps"
