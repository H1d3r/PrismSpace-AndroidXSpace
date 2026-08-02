package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.space.SpaceState

sealed class CreateSpaceResult {
    data class Success(val userId: Int) : CreateSpaceResult()
    object RootUnavailable : CreateSpaceResult()
    data class CapReached(val max: Int) : CreateSpaceResult()
    object ManagedProfileLimitReached : CreateSpaceResult()
    object StateRefreshFailed : CreateSpaceResult()
    data class BlockedByState(val state: SpaceState) : CreateSpaceResult()
    data class Failed(val reason: String?, val analyticsPhase: Int = 2) : CreateSpaceResult()
}

sealed class DeleteSpaceResult {
    object Success : DeleteSpaceResult()
    object RootUnavailable : DeleteSpaceResult()
    data class ManualRemovalRequired(val reason: String) : DeleteSpaceResult()
    data class Failed(val reason: String?) : DeleteSpaceResult()
}

sealed class SpaceCapProbe {
    /** max = device user ceiling; current = 1(main)+#managed. */
    data class Known(val max: Int, val current: Int) : SpaceCapProbe()
    object Unknown : SpaceCapProbe()
}

enum class RootSetupUiOutcome { Success, ExistingProfile, Error }

data class RootSetupPresentation(val outcome: RootSetupUiOutcome, val analyticsPhase: Int?)

/** Stable mapping used by the legacy Activity shell around the single provisioning engine. */
object RootSetupResultMapping {
    @JvmStatic fun presentation(result: CreateSpaceResult): RootSetupPresentation = when (result) {
        is CreateSpaceResult.Success -> RootSetupPresentation(RootSetupUiOutcome.Success, null)
        is CreateSpaceResult.BlockedByState -> RootSetupPresentation(RootSetupUiOutcome.ExistingProfile, null)
        CreateSpaceResult.RootUnavailable,
        is CreateSpaceResult.CapReached,
        CreateSpaceResult.ManagedProfileLimitReached,
        CreateSpaceResult.StateRefreshFailed -> RootSetupPresentation(RootSetupUiOutcome.Error, 1)
        is CreateSpaceResult.Failed -> RootSetupPresentation(RootSetupUiOutcome.Error, result.analyticsPhase)
    }
}
