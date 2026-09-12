package com.yzddmr6.prismspace.prism.service

import android.content.Context
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.RequestAppUninstall
import com.yzddmr6.prismspace.bridge.UninstallLaunchKind
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.shuttle.DEFAULT_SYNC_TIMEOUT_MS
import com.yzddmr6.prismspace.util.PrismLocale

/**
 * Asks the managed profile to present the system uninstaller for a clone.
 *
 * The uninstall confirmation is launched inside the profile process (caller user == target user),
 * so no ROM uninstaller behavior can redirect it at the main-space copy (issue #6). There is
 * deliberately no user-0 fallback: any failure is reported, never rerouted.
 */
internal class ProfileUninstallLauncher {

    sealed interface Result {
        data object Launched : Result

        /** @param message user-facing guidance; @param reason classified cause for diagnostics. */
        data class Failed(val message: String, val reason: String? = null) : Result
    }

    fun requestUninstall(context: Context, packageName: String, targetUserId: Int): Result {
        val target = BridgeTargets.profile(targetUserId)
            ?: return Result.Failed(str(context, R.string.fb_need_create_space), "space_missing")
        return when (val bridge = runProfileBridgeOperation(
            context,
            TAG,
            "request uninstall pkg=$packageName",
            target = target,
            timeoutMs = DEFAULT_SYNC_TIMEOUT_MS,
            command = RequestAppUninstall(packageName),
        )) {
            is ProfileBridgeResult.Value -> when (val outcome = bridge.value) {
                null -> Result.Failed(str(context, R.string.prompt_space_not_ready), "empty_result")
                else -> when (outcome.kind) {
                    UninstallLaunchKind.Launched -> Result.Launched
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

    private fun str(context: Context, id: Int, vararg args: Any): String =
        PrismLocale.wrap(context).getString(id, *args)

    private companion object {
        private const val TAG = "Prism.ProfileUninstall"
    }
}
