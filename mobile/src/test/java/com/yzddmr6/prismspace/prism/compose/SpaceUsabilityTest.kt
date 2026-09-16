package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.space.spaceUsability
import com.yzddmr6.prismspace.prism.compose.space.spaceUsabilityFromState
import com.yzddmr6.prismspace.space.SpaceBridgeCause
import com.yzddmr6.prismspace.space.SpaceState
import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceUsabilityTest {
    @Test fun `not provisioned wins over running and unlocked inputs`() {
        assertEquals(SpaceUsability.NotProvisioned, spaceUsability(false, false, false))
        assertEquals(SpaceUsability.NotProvisioned, spaceUsability(false, true, true))
        assertEquals(SpaceUsability.NotProvisioned, spaceUsability(false, true, true, bridgeReady = false))
    }

    @Test fun `provisioned but not running is suspended`() {
        assertEquals(SpaceUsability.Suspended, spaceUsability(true, false, false))
        assertEquals(SpaceUsability.Suspended, spaceUsability(true, false, true))
        assertEquals(SpaceUsability.Suspended, spaceUsability(true, false, true, bridgeReady = false))
    }

    @Test fun `quiet mode is suspended even when the user is still running`() {
        assertEquals(
            SpaceUsability.Suspended,
            spaceUsability(provisioned = true, running = true, unlocked = true, quietMode = true),
        )
    }

    @Test fun `running but CE locked needs unlock`() {
        assertEquals(SpaceUsability.LockedNeedsUnlock, spaceUsability(true, true, false))
    }

    @Test fun `running and unlocked is usable`() {
        assertEquals(SpaceUsability.Usable, spaceUsability(true, true, true))
    }

    @Test fun bridgeNotReadyOnlyAppliesAfterProfileBasicsAreReady() {
        assertEquals(
            SpaceUsability.BridgeNotReady,
            spaceUsability(provisioned = true, running = true, unlocked = true, bridgeReady = false),
        )
    }

    @Test fun lockedProfileTakesPrecedenceOverBridgeState() {
        assertEquals(
            SpaceUsability.LockedNeedsUnlock,
            spaceUsability(provisioned = true, running = true, unlocked = false, bridgeReady = false),
        )
    }

    @Test fun unknownBridgeStateIsNotReportedAsNotProvisioned() {
        assertEquals(
            SpaceUsability.Unknown,
            spaceUsability(provisioned = true, running = true, unlocked = true, bridgeReady = null),
        )
    }

    @Test fun `classified state maps directly to action usability`() {
        val userId = 18
        assertEquals(SpaceUsability.Unknown, spaceUsabilityFromState(null, userId))
        assertEquals(SpaceUsability.NotProvisioned, spaceUsabilityFromState(SpaceState.NoProfile, userId))
        assertEquals(SpaceUsability.NotProvisioned, spaceUsabilityFromState(SpaceState.OrphanProfile(userId), userId))
        assertEquals(SpaceUsability.NotProvisioned, spaceUsabilityFromState(SpaceState.ForeignProfile(999), userId))
        assertEquals(SpaceUsability.Unknown, spaceUsabilityFromState(SpaceState.Provisioning(userId), userId))
        assertEquals(SpaceUsability.BridgeNotReady, spaceUsabilityFromState(SpaceState.HalfProvisioned(userId, true), userId))
        assertEquals(SpaceUsability.LockedNeedsUnlock, spaceUsabilityFromState(SpaceState.Locked(userId), userId))
        assertEquals(SpaceUsability.Suspended, spaceUsabilityFromState(SpaceState.Inactive(userId), userId))
        assertEquals(
            SpaceUsability.BridgeNotReady,
            spaceUsabilityFromState(SpaceState.BridgeDown(userId, SpaceBridgeCause.TimedOut), userId),
        )
        assertEquals(SpaceUsability.Usable, spaceUsabilityFromState(SpaceState.Healthy(userId), userId))
    }

    @Test fun `healthy remains usable without consulting an expiring bridge cache`() {
        assertEquals(SpaceUsability.Usable, spaceUsabilityFromState(SpaceState.Healthy(18), 18))
    }

    @Test fun `snapshot for another user fails closed`() {
        assertEquals(SpaceUsability.Unknown, spaceUsabilityFromState(SpaceState.Healthy(18), 22))
    }
}
