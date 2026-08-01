package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import android.os.UserManager
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.ProfileRecoveryService
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.util.Users.Companion.toId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Chooses exactly one authorized deletion route. User confirmation remains owned by the UI. */
object SpaceDeletionCoordinator {
    suspend fun delete(context: Context, space: PrismSpace, useRoot: Boolean): DeleteSpaceResult =
        withContext(Dispatchers.IO) {
            if (useRoot) return@withContext SpaceProvisioningEngine.deleteSpace(context, space)

            val profile = UserHandles.of(space.userId)
            val health = runCatching { BridgeHealthRepository(context).refreshHealth(profile) }
                .onFailure { DiagnosticLog.w(TAG, "bridge check before delete failed user=${space.userId}", it) }
                .getOrNull()
            if (health?.available != true) {
                return@withContext DeleteSpaceResult.ManualRemovalRequired("cross-profile bridge unavailable")
            }
            val request = ProfileRecoveryService.remove(context, profile)
            if (request is ProfileBridgeResult.Value && request.value != true) {
                return@withContext DeleteSpaceResult.ManualRemovalRequired("PrismSpace is not profile owner")
            }
            // wipeData() removes the profile asynchronously and may kill the remote process before the
            // bridge can return. Confirm the parent-side user list instead of trusting dispatch/IPC.
            if (awaitProfileRemoval(context, space.userId)) DeleteSpaceResult.Success
            else DeleteSpaceResult.ManualRemovalRequired("profile removal not confirmed (${request.javaClass.simpleName})")
        }

    private suspend fun awaitProfileRemoval(context: Context, userId: Int): Boolean {
        val userManager = context.getSystemService(UserManager::class.java) ?: return false
        repeat(REMOVAL_CHECKS) {
            val exists = runCatching { userManager.userProfiles.orEmpty().any { it.toId() == userId } }
                .getOrDefault(true)
            if (!exists) return true
            delay(REMOVAL_POLL_MS)
        }
        return false
    }

    private const val TAG = "Prism.SpaceDelete"
    private const val REMOVAL_CHECKS = 40
    private const val REMOVAL_POLL_MS = 250L
}
