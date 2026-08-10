package com.yzddmr6.prismspace.prism.service

import com.yzddmr6.prismspace.prism.model.CapabilityAvailability
import com.yzddmr6.prismspace.prism.model.CapabilityState

class CapabilityService {

    fun buildState(
        profileOwner: Boolean,
        shizukuReady: Boolean,
        adbReady: Boolean = false,
        rootDetected: Boolean,
        rootEnabled: Boolean,
    ): CapabilityState = CapabilityState(
        normal = CapabilityAvailability.Available,
        shizuku = if (shizukuReady) CapabilityAvailability.Available else CapabilityAvailability.NeedsSetup,
        adb = if (adbReady) CapabilityAvailability.Available else CapabilityAvailability.NeedsSetup,
        root = when {
            rootEnabled -> CapabilityAvailability.Available
            rootDetected -> CapabilityAvailability.AvailableButDisabled
            else -> CapabilityAvailability.Unsupported
        },
        profileOwner = if (profileOwner) CapabilityAvailability.Available else CapabilityAvailability.NeedsSetup,
    )
}
