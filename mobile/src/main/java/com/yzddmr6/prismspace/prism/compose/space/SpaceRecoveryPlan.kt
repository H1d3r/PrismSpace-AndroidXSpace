package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.space.SpaceState

sealed interface SpaceRecoveryPlan {
    data object StartSetup : SpaceRecoveryPlan
    data class OpenSystemProfileSettings(val userId: Int) : SpaceRecoveryPlan
    data class WaitForProvisioning(val userId: Int) : SpaceRecoveryPlan
    data class RepairIncrementally(val userId: Int) : SpaceRecoveryPlan
    data class ActivateThenOpenEntry(val userId: Int) : SpaceRecoveryPlan
    data class Activate(val userId: Int) : SpaceRecoveryPlan
    data class ReconnectBridge(val userId: Int) : SpaceRecoveryPlan
    data class AlreadyReady(val userId: Int) : SpaceRecoveryPlan
}

fun recoveryPlan(state: SpaceState): SpaceRecoveryPlan = when (state) {
    SpaceState.NoProfile -> SpaceRecoveryPlan.StartSetup
    is SpaceState.OrphanProfile -> SpaceRecoveryPlan.OpenSystemProfileSettings(state.userId)
    is SpaceState.Provisioning -> SpaceRecoveryPlan.WaitForProvisioning(state.userId)
    is SpaceState.HalfProvisioned -> if (state.resumable) {
        SpaceRecoveryPlan.RepairIncrementally(state.userId)
    } else {
        SpaceRecoveryPlan.ActivateThenOpenEntry(state.userId)
    }
    is SpaceState.Locked, is SpaceState.Inactive -> SpaceRecoveryPlan.Activate(requireNotNull(state.userId))
    is SpaceState.BridgeDown -> SpaceRecoveryPlan.ReconnectBridge(state.userId)
    is SpaceState.Healthy -> SpaceRecoveryPlan.AlreadyReady(state.userId)
}
