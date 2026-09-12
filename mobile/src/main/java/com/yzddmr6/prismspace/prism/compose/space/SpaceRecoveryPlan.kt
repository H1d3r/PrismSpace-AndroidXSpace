package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.space.SpaceState

sealed interface SpaceRecoveryPlan {
    data object StartSetup : SpaceRecoveryPlan
    data class OpenSystemProfileSettings(val userId: Int) : SpaceRecoveryPlan
    data class WaitForProvisioning(val userId: Int) : SpaceRecoveryPlan
    data class RepairIncrementally(val userId: Int) : SpaceRecoveryPlan
    data class ActivateThenOpenEntry(val userId: Int) : SpaceRecoveryPlan
    data class Activate(val userId: Int) : SpaceRecoveryPlan
    data class OpenProfileUnlock(val userId: Int) : SpaceRecoveryPlan
    data class ReconnectBridge(val userId: Int) : SpaceRecoveryPlan
    data class AlreadyReady(val userId: Int) : SpaceRecoveryPlan
}

fun recoveryPlan(state: SpaceState): SpaceRecoveryPlan = requireNotNull(presentSpace(state).recovery)
