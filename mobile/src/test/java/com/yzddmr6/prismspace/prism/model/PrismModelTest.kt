package com.yzddmr6.prismspace.prism.model

import com.yzddmr6.prismspace.prism.service.CapabilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrismModelTest {

    @Test fun capabilitySummaryPrefersLeastPrivilege() {
        val state = CapabilityState(
            normal = CapabilityAvailability.Available,
            shizuku = CapabilityAvailability.NeedsSetup,
            root = CapabilityAvailability.AvailableButDisabled,
            profileOwner = CapabilityAvailability.Available,
        )

        assertTrue(state.canUseNormal)
        assertFalse(state.shouldPreferRoot)
    }

    @Test fun capabilityServiceMarksMissingProfileOwner() {
        val state = CapabilityService().buildState(
            profileOwner = false,
            shizukuReady = false,
            rootDetected = true,
            rootEnabled = false,
        )

        assertTrue(state.profileOwner is CapabilityAvailability.NeedsSetup)
    }
}
