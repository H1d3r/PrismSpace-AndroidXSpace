package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import android.os.UserManager
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.ProfileWipe
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.ProfileRecoveryService
import com.yzddmr6.prismspace.prism.compose.vm.CapabilityRepository
import com.yzddmr6.prismspace.prism.compose.vm.CapabilityRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.vm.PrismMode
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import com.yzddmr6.prismspace.space.SpaceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Chooses exactly one authorized deletion route. User confirmation remains owned by the UI. */
object SpaceDeletionCoordinator {
    suspend fun delete(
        context: Context,
        space: PrismSpace,
        capabilities: CapabilityRepository = CapabilityRepositoryProvider.get(context),
    ): DeleteSpaceResult =
        withContext(Dispatchers.IO) {
            val currentUserId = Users.currentId()
            if (currentUserId == space.userId) return@withContext deleteCurrentProfile(context)

            val freshState = SpaceStateRepository(context).preflightDelete()
            val facts = deletionFacts(currentUserId, space.userId, freshState, capabilities)
            when (deletionRoute(facts)) {
                SpaceDeletionRoute.Self -> return@withContext deleteCurrentProfile(context)
                SpaceDeletionRoute.Root -> {
                    val rootResult = SpaceProvisioningEngine.deleteSpace(context, space)
                    if (rootResult == DeleteSpaceResult.RootUnavailable) {
                        capabilities.markRootUnavailable()
                        if (deletionRetryRoute(rootResult, facts) == SpaceDeletionRoute.Bridge) {
                            return@withContext deleteViaBridge(context, space)
                        }
                    } else {
                        capabilities.markRootReady()
                    }
                    return@withContext rootResult
                }
                SpaceDeletionRoute.Bridge -> return@withContext deleteViaBridge(context, space)
                SpaceDeletionRoute.Refuse -> return@withContext DeleteSpaceResult.ManualRemovalRequired(
                    if (!facts.ownedByPrism) "fresh ownership could not be confirmed"
                    else "no currently ready deletion route",
                )
            }
        }

    private suspend fun deleteViaBridge(context: Context, space: PrismSpace): DeleteSpaceResult {
        val profile = UserHandles.of(space.userId)
        val health = runCatching { BridgeHealthRepository(context).refreshHealth(profile) }
            .onFailure { DiagnosticLog.w(TAG, "bridge check before delete failed user=${space.userId}", it) }
            .getOrNull()
        if (health?.available != true) {
            return DeleteSpaceResult.ManualRemovalRequired("cross-profile bridge unavailable")
        }
        val request = ProfileRecoveryService.remove(context, profile)
        if (request is ProfileBridgeResult.Value && request.value != true) {
            return DeleteSpaceResult.ManualRemovalRequired("PrismSpace is not profile owner")
        }
        // wipeData() removes the profile asynchronously and may kill the remote process before the
        // bridge can return. Confirm the parent-side user list instead of trusting dispatch/IPC.
        return if (awaitProfileRemoval(context, space.userId)) DeleteSpaceResult.Success
        else DeleteSpaceResult.ManualRemovalRequired("profile removal not confirmed (${request.javaClass.simpleName})")
    }

    /** Direct profile-side route used by the legacy confirmation Activity. */
    @JvmStatic fun deleteCurrentProfile(context: Context): DeleteSpaceResult = try {
        if (ProfileWipe.wipeSelf(context)) DeleteSpaceResult.Success
        else DeleteSpaceResult.ManualRemovalRequired("PrismSpace is not profile owner")
    } catch (e: RuntimeException) {
        DeleteSpaceResult.Failed(e.message)
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

internal enum class SpaceDeletionRoute { Self, Root, Bridge, Refuse }

internal data class SpaceDeletionFacts(
    val currentUserId: Int,
    val targetUserId: Int,
    val ownedByPrism: Boolean,
    val bridgeHealthy: Boolean,
    val preferredMode: PrismMode,
    val rootReady: Boolean,
)

private fun deletionFacts(
    currentUserId: Int,
    targetUserId: Int,
    state: SpaceState?,
    capabilities: CapabilityRepository,
): SpaceDeletionFacts {
    val targetMatches = state?.userId == targetUserId
    val owned = targetMatches && state !is SpaceState.OrphanProfile
    val runtime = capabilities.runtimeSnapshot()
    return SpaceDeletionFacts(
        currentUserId,
        targetUserId,
        ownedByPrism = owned,
        bridgeHealthy = targetMatches && state is SpaceState.Healthy,
        preferredMode = runtime.preferredMode,
        rootReady = runtime.rootReady,
    )
}

internal fun deletionRoute(facts: SpaceDeletionFacts): SpaceDeletionRoute = when {
    facts.currentUserId == facts.targetUserId -> SpaceDeletionRoute.Self
    !facts.ownedByPrism -> SpaceDeletionRoute.Refuse
    facts.preferredMode == PrismMode.Root && facts.rootReady -> SpaceDeletionRoute.Root
    facts.bridgeHealthy -> SpaceDeletionRoute.Bridge
    else -> SpaceDeletionRoute.Refuse
}

/** RootUnavailable is emitted before the removal command; no other result is safe to retry. */
internal fun deletionRetryRoute(result: DeleteSpaceResult, facts: SpaceDeletionFacts): SpaceDeletionRoute =
    if (result == DeleteSpaceResult.RootUnavailable && facts.ownedByPrism && facts.bridgeHealthy) {
        SpaceDeletionRoute.Bridge
    } else SpaceDeletionRoute.Refuse
