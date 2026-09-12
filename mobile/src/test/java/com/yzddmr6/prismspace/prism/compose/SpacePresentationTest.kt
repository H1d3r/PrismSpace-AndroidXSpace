package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.space.SpacePresentationKind
import com.yzddmr6.prismspace.prism.compose.space.SpaceRecoveryPlan
import com.yzddmr6.prismspace.prism.compose.space.SpaceSnapshot
import com.yzddmr6.prismspace.prism.compose.space.SpaceSnapshotFailure
import com.yzddmr6.prismspace.prism.compose.space.presentSpace
import com.yzddmr6.prismspace.space.SpaceBridgeCause
import com.yzddmr6.prismspace.space.SpaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SpacePresentationTest {

    @Test fun everyCanonicalStateHasOneDistinctSemanticPresentation() {
        val states = listOf(
            SpaceState.NoProfile,
            SpaceState.OrphanProfile(22),
            SpaceState.Provisioning(22),
            SpaceState.HalfProvisioned(22, true),
            SpaceState.Inactive(22),
            SpaceState.Locked(22),
            SpaceState.BridgeDown(22, SpaceBridgeCause.TimedOut),
            SpaceState.Healthy(22),
        )

        assertEquals(
            listOf(
                SpacePresentationKind.Missing,
                SpacePresentationKind.Orphan,
                SpacePresentationKind.Provisioning,
                SpacePresentationKind.Incomplete,
                SpacePresentationKind.Inactive,
                SpacePresentationKind.Locked,
                SpacePresentationKind.BridgeUnavailable,
                SpacePresentationKind.Ready,
            ),
            states.map { presentSpace(it).kind },
        )
    }

    @Test fun lockedAndInactiveUseDifferentAndroidRecoveryRoutes() {
        assertEquals(SpaceRecoveryPlan.Activate(22), presentSpace(SpaceState.Inactive(22)).recovery)
        assertEquals(SpaceRecoveryPlan.OpenProfileUnlock(22), presentSpace(SpaceState.Locked(22)).recovery)
    }

    @Test fun failedSnapshotKeepsLastKnownOnlyForDisplayAndAuthorizesNothing() {
        val presentation = presentSpace(
            SpaceSnapshot.Failed(SpaceSnapshotFailure("IOException", "offline"), SpaceState.Healthy(22)),
        )

        assertEquals(SpacePresentationKind.Unavailable, presentation.kind)
        assertEquals(SpaceState.Healthy(22), presentation.lastKnown)
        assertNull(presentation.state)
        assertNull(presentation.recovery)
        assertFalse(presentation.permitsStateChange)
        assertFalse(presentation.isReady)
    }

    @Test fun bridgeCauseSurvivesPresentation() {
        assertEquals(
            SpaceBridgeCause.PermissionDenied,
            presentSpace(SpaceState.BridgeDown(22, SpaceBridgeCause.PermissionDenied)).bridgeCause,
        )
    }
}
