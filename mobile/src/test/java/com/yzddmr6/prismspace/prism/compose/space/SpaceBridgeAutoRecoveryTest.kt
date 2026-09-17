package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.prism.compose.space.SpaceBridgeAutoRecovery.Action
import com.yzddmr6.prismspace.prism.compose.space.SpaceBridgeAutoRecovery.Gate
import com.yzddmr6.prismspace.space.SpaceBridgeCause
import com.yzddmr6.prismspace.space.SpaceState
import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceBridgeAutoRecoveryTest {

    @Test fun `provider-unavailable triggers on first sight`() {
        val state = SpaceState.BridgeDown(11, SpaceBridgeCause.ProviderUnavailable)
        assertEquals(Action.Trigger, SpaceBridgeAutoRecovery.decide(state, gate = null, nowMs = 100_000L))
    }

    @Test fun `permission-denied triggers on first sight`() {
        val state = SpaceState.BridgeDown(11, SpaceBridgeCause.PermissionDenied)
        assertEquals(Action.Trigger, SpaceBridgeAutoRecovery.decide(state, gate = null, nowMs = 0L))
    }

    @Test fun `environmental and unknown causes never trigger`() {
        for (cause in listOf(
            SpaceBridgeCause.NotChecked,
            SpaceBridgeCause.ProfileUnavailable,
            SpaceBridgeCause.TimedOut,
            SpaceBridgeCause.Failed,
        )) {
            assertEquals(
                "cause=$cause",
                Action.Irrecoverable,
                SpaceBridgeAutoRecovery.decide(SpaceState.BridgeDown(11, cause), gate = null, nowMs = 0L),
            )
        }
    }

    @Test fun `non-bridge-down states never trigger`() {
        for (state in listOf(
            SpaceState.Healthy(11),
            SpaceState.Inactive(11),
            SpaceState.Locked(11),
            SpaceState.HalfProvisioned(11, resumable = true),
            SpaceState.NoProfile,
        )) {
            assertEquals(
                "state=$state",
                Action.NotBridgeDown,
                SpaceBridgeAutoRecovery.decide(state, gate = null, nowMs = 0L),
            )
        }
    }

    @Test fun `second attempt inside the interval is throttled`() {
        val state = SpaceState.BridgeDown(11, SpaceBridgeCause.ProviderUnavailable)
        val gate = Gate(lastAttemptMs = 90_000L, consecutiveAttempts = 1)
        assertEquals(Action.Throttled, SpaceBridgeAutoRecovery.decide(state, gate, nowMs = 95_000L))
    }

    @Test fun `attempt after the interval triggers again`() {
        val state = SpaceState.BridgeDown(11, SpaceBridgeCause.ProviderUnavailable)
        val gate = Gate(lastAttemptMs = 90_000L, consecutiveAttempts = 1)
        assertEquals(Action.Trigger, SpaceBridgeAutoRecovery.decide(state, gate, nowMs = 111_000L))
    }

    @Test fun `fuse blows after the third consecutive attempt`() {
        val state = SpaceState.BridgeDown(11, SpaceBridgeCause.ProviderUnavailable)
        val gate = Gate(lastAttemptMs = 0L, consecutiveAttempts = 3)
        assertEquals(Action.Exhausted, SpaceBridgeAutoRecovery.decide(state, gate, nowMs = 1_000_000L))
    }
}
