package com.yzddmr6.prismspace.space

import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceStateClassifierTest {
    private fun profile(
        userId: Int = 22,
        pkg: Boolean = true,
        marker: Boolean = true,
        provisioning: Boolean = false,
        running: Boolean = true,
        unlocked: Boolean = true,
        quiet: Boolean = false,
        bridge: Boolean? = true,
        owner: Boolean? = true,
    ) = SpaceProfileFacts(userId, pkg, marker, provisioning, running, unlocked, quiet, bridge,
        profileOwner = owner)

    @Test fun noProfile() = assertEquals(SpaceState.NoProfile, SpaceStateClassifier.classify(SpaceFacts(emptyList())))

    @Test fun orphanHasNoPrismPackage() = assertEquals(
        SpaceState.OrphanProfile(22), SpaceStateClassifier.classify(SpaceFacts(listOf(profile(pkg = false, marker = false)))),
    )

    @Test fun provisioningPrecedesIncompleteMarker() = assertEquals(
        SpaceState.Provisioning(22),
        SpaceStateClassifier.classify(SpaceFacts(listOf(profile(marker = false, provisioning = true)))),
    )

    @Test fun halfProvisionedIsPreciselyResumableOnlyWithBridgeAndOwnership() {
        assertEquals(SpaceState.HalfProvisioned(22, true), SpaceStateClassifier.classify(
            SpaceFacts(listOf(profile(marker = false, bridge = true, owner = true)))))
        assertEquals(SpaceState.HalfProvisioned(22, false), SpaceStateClassifier.classify(
            SpaceFacts(listOf(profile(marker = false, bridge = false, owner = true)))))
        assertEquals(SpaceState.HalfProvisioned(22, false), SpaceStateClassifier.classify(
            SpaceFacts(listOf(profile(marker = false, bridge = true, owner = false)))))
    }

    @Test fun lockedPrecedesQuietMode() = assertEquals(
        SpaceState.Locked(22), SpaceStateClassifier.classify(SpaceFacts(listOf(profile(unlocked = false, quiet = true)))),
    )

    @Test fun quietOrStoppedIsInactive() {
        assertEquals(SpaceState.Inactive(22), SpaceStateClassifier.classify(SpaceFacts(listOf(profile(quiet = true)))))
        assertEquals(SpaceState.Inactive(22), SpaceStateClassifier.classify(SpaceFacts(listOf(profile(running = false)))))
    }

    @Test fun missingAndFailedBridgeAreExplicit() {
        assertEquals(SpaceState.BridgeDown(22, SpaceBridgeCause.NotChecked), SpaceStateClassifier.classify(
            SpaceFacts(listOf(profile(bridge = null)))))
        assertEquals(SpaceState.BridgeDown(22, SpaceBridgeCause.TimedOut), SpaceStateClassifier.classify(
            SpaceFacts(listOf(profile(bridge = false).copy(bridgeCause = SpaceBridgeCause.TimedOut)))))
    }

    @Test fun healthy() = assertEquals(
        SpaceState.Healthy(22), SpaceStateClassifier.classify(SpaceFacts(listOf(profile()))),
    )

    @Test fun multipleProfilesUseEvidenceThenStableIdNotEnumerationOrder() {
        val orphan999 = profile(userId = 999, pkg = false, marker = false)
        val half100 = profile(userId = 100, marker = false)
        val managed22 = profile(userId = 22)
        assertEquals(SpaceState.Healthy(22), SpaceStateClassifier.classify(SpaceFacts(listOf(orphan999, half100, managed22))))
        assertEquals(SpaceState.HalfProvisioned(100, true), SpaceStateClassifier.classify(SpaceFacts(listOf(orphan999, half100))))
    }

    @Test fun specialUserIdsAreDataNotSentinels() {
        assertEquals(SpaceState.Healthy(999), SpaceStateClassifier.classify(SpaceFacts(listOf(profile(userId = 999)))))
        assertEquals(SpaceState.Healthy(100), SpaceStateClassifier.classify(SpaceFacts(listOf(profile(userId = 100)))))
    }
}
