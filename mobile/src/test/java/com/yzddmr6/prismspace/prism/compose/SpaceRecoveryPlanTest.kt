package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.space.SpaceRecoveryPlan
import com.yzddmr6.prismspace.prism.compose.space.recoveryPlan
import com.yzddmr6.prismspace.space.SpaceBridgeCause
import com.yzddmr6.prismspace.space.SpaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpaceRecoveryPlanTest {
    @Test fun everyStateHasOneNonContradictoryRecoveryPlan() {
        val states = listOf(
            SpaceState.NoProfile,
            SpaceState.OrphanProfile(22),
            SpaceState.Provisioning(22),
            SpaceState.HalfProvisioned(22, true),
            SpaceState.HalfProvisioned(22, false),
            SpaceState.Locked(22),
            SpaceState.Inactive(22),
            SpaceState.BridgeDown(22, SpaceBridgeCause.Failed),
            SpaceState.Healthy(22),
        )
        val plans = states.map(::recoveryPlan)
        assertEquals(SpaceRecoveryPlan.StartSetup, plans.first())
        assertEquals(SpaceRecoveryPlan.RepairIncrementally(22), plans[3])
        assertEquals(SpaceRecoveryPlan.ActivateThenOpenEntry(22), plans[4])
        assertEquals(SpaceRecoveryPlan.OpenProfileUnlock(22), plans[5])
        assertEquals(SpaceRecoveryPlan.Activate(22), plans[6])
        assertFalse(plans.drop(1).any { it is SpaceRecoveryPlan.StartSetup })
    }
}
