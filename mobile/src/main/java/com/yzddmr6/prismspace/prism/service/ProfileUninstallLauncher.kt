package com.yzddmr6.prismspace.prism.service

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.util.Users.Companion.toId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.RequestAppUninstall
import com.yzddmr6.prismspace.bridge.UninstallLaunchKind
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.shuttle.DEFAULT_SYNC_TIMEOUT_MS
import com.yzddmr6.prismspace.util.PrismLocale

/**
 * Asks the managed profile to present the system uninstaller for a clone.
 *
 * The uninstall confirmation is created inside the profile and sent by the foreground parent,
 * so its immutable creator user fixes the target to the profile (issue #6). There is
 * deliberately no user-0 fallback: any failure is reported, never rerouted.
 */
internal class ProfileUninstallLauncher {

    sealed interface Result {
        data object Launched : Result

        /** @param message user-facing guidance; @param reason classified cause for diagnostics. */
        data class Failed(val message: String, val reason: String? = null) : Result
    }

    suspend fun requestUninstall(context: Context, packageName: String, targetUserId: Int, canLaunch: () -> Boolean): Result {
        val target = BridgeTargets.profile(targetUserId)
            ?: return Result.Failed(str(context, R.string.fb_need_create_space), "space_missing")
        val bridge = withContext(Dispatchers.IO) {
            runProfileBridgeOperation(context, TAG, "prepare uninstall pkg=$packageName",
                target = target, timeoutMs = DEFAULT_SYNC_TIMEOUT_MS, command = RequestAppUninstall(packageName))
        }
        return when (bridge) {
            is ProfileBridgeResult.Value -> when (val outcome = bridge.value) {
                null -> Result.Failed(str(context, R.string.prompt_space_not_ready), "empty_result")
                else -> when (outcome.kind) {
                    UninstallLaunchKind.Prepared -> sendConfirmation(context, packageName, targetUserId, outcome.confirmation, canLaunch)
                    UninstallLaunchKind.Failed -> {
                        val reason = outcome.reason?.takeIf { it.isNotBlank() } ?: "unknown"
                        Result.Failed(
                            str(context, R.string.fb_space_internal_operation_failed, reason),
                            reason,
                        )
                    }
                }
            }
            else -> Result.Failed(
                profileBridgeFailureMessage(context, bridge, str(context, R.string.prompt_space_not_ready)),
                bridge.failureReason()?.name ?: "bridge_failure",
            )
        }
    }

    /** Only this foreground boundary may send the profile-owned confirmation. */
    private suspend fun sendConfirmation(
        context: Context,
        packageName: String,
        targetUserId: Int,
        confirmation: PendingIntent?,
        canLaunch: () -> Boolean,
    ): Result = withContext(Dispatchers.Main) {
        fun failed(reason: String) = Result.Failed(str(context, R.string.prompt_space_not_ready), reason)
        if (confirmation == null) return@withContext failed("missing_confirmation")
        if (confirmation.creatorPackage != context.packageName || confirmation.creatorUserHandle.toId() != targetUserId)
            return@withContext failed("wrong_confirmation_owner")
        if (!canLaunch()) {
            confirmation.cancel()
            return@withContext failed("host_not_resumed")
        }
        try {
            val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle() else null
            confirmation.send(context, 0, null, null, null, null, options)
            Result.Launched
        } catch (error: Exception) {
            DiagnosticLog.w(TAG, "uninstall confirmation send failed pkg=$packageName exception=${error.javaClass.name}")
            failed(error.javaClass.simpleName)
        }
    }

    private fun str(context: Context, id: Int, vararg args: Any): String =
        PrismLocale.wrap(context).getString(id, *args)

    private companion object {
        private const val TAG = "Prism.ProfileUninstall"
    }
}
