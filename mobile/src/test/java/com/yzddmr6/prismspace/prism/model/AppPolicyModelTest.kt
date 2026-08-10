package com.yzddmr6.prismspace.prism.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPolicyModelTest {

    @Test fun defaultPolicyKeepsFilesIsolatedAndSystemDefaults() {
        val policy = AppPolicy()

        assertEquals(FileAccessMode.Isolated, policy.fileAccess)
        assertEquals(BackgroundPolicy.SystemDefault, policy.background)
        assertEquals(NetworkPolicy.SystemDefault, policy.network)
        assertEquals(NotificationPolicy.SystemDefault, policy.notifications)
    }

    @Test fun profileOwnerActionsAreAvailableWithoutShizukuOrRoot() {
        val state = CapabilityState(
            normal = CapabilityAvailability.Available,
            shizuku = CapabilityAvailability.NeedsSetup,
            root = CapabilityAvailability.Unsupported,
            profileOwner = CapabilityAvailability.Available,
        )

        assertEquals(CapabilityAvailability.Available, AppPolicyPlanner.availability(PolicyAction.Freeze, state))
        assertEquals(CapabilityAvailability.Available, AppPolicyPlanner.availability(PolicyAction.Suspend, state))
        assertEquals(CapabilityAvailability.Available, AppPolicyPlanner.availability(PolicyAction.ImportExportFiles, state))
    }

    @Test fun enhancedIsolationRequiresShizukuAdbBeforeRootFallback() {
        val state = CapabilityState(
            normal = CapabilityAvailability.Available,
            shizuku = CapabilityAvailability.NeedsSetup,
            root = CapabilityAvailability.AvailableButDisabled,
            profileOwner = CapabilityAvailability.Available,
        )

        val network = AppPolicyPlanner.availability(PolicyAction.RestrictNetwork, state)
        val background = AppPolicyPlanner.availability(PolicyAction.RestrictBackground, state)
        val sharedMedia = AppPolicyPlanner.availability(PolicyAction.ConfigureSharedMedia, state)

        assertTrue(network is CapabilityAvailability.NeedsSetup)
        assertTrue(background is CapabilityAvailability.NeedsSetup)
        assertTrue(sharedMedia is CapabilityAvailability.NeedsSetup)
    }

    @Test fun shizukuAdbUnlocksEnhancedIsolationWithoutRoot() {
        val state = CapabilityState(
            normal = CapabilityAvailability.Available,
            shizuku = CapabilityAvailability.Available,
            root = CapabilityAvailability.Unsupported,
            profileOwner = CapabilityAvailability.Available,
        )

        assertEquals(CapabilityAvailability.Available, AppPolicyPlanner.availability(PolicyAction.RestrictNetwork, state))
        assertEquals(CapabilityAvailability.Available, AppPolicyPlanner.availability(PolicyAction.RestrictBackground, state))
        assertEquals(CapabilityAvailability.Available, AppPolicyPlanner.availability(PolicyAction.ConfigureSharedMedia, state))
    }
}
