package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.setup.DestroyProfileResult
import com.yzddmr6.prismspace.space.SpaceState

sealed class CreateSpaceResult {
    data class Success(val userId: Int) : CreateSpaceResult()
    object RootUnavailable : CreateSpaceResult()
    data class CapReached(val max: Int) : CreateSpaceResult()
    object ManagedProfileLimitReached : CreateSpaceResult()
    data class BlockedByState(val state: SpaceState) : CreateSpaceResult()
    data class Failed(val reason: String?) : CreateSpaceResult()
}

sealed class DeleteSpaceResult {
    object Success : DeleteSpaceResult()
    object RootUnavailable : DeleteSpaceResult()
    /** Current-profile delete delegated to the in-profile destroy flow. */
    data class FellBackToSelfDestroy(val inner: DestroyProfileResult) : DeleteSpaceResult()
    data class ManualRemovalRequired(val reason: String) : DeleteSpaceResult()
    data class Failed(val reason: String?) : DeleteSpaceResult()
}

sealed class SpaceCapProbe {
    /** max = device user ceiling; current = 1(main)+#managed. */
    data class Known(val max: Int, val current: Int) : SpaceCapProbe()
    object Unknown : SpaceCapProbe()
}
