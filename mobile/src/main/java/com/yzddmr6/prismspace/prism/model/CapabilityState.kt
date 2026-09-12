package com.yzddmr6.prismspace.prism.model

enum class Capability { Normal, Shizuku, Adb, Root, ProfileOwner }

sealed class CapabilityAvailability {
    object Available : CapabilityAvailability()
    object NeedsSetup : CapabilityAvailability()
    object AvailableButDisabled : CapabilityAvailability()
    object Unsupported : CapabilityAvailability()
}

data class CapabilityState(
    val normal: CapabilityAvailability,
    val shizuku: CapabilityAvailability,
    val adb: CapabilityAvailability = CapabilityAvailability.NeedsSetup,
    val root: CapabilityAvailability,
    val profileOwner: CapabilityAvailability,
) {
    val canUseNormal: Boolean get() = normal is CapabilityAvailability.Available
    val canUseCoreDualOpen: Boolean get() = normal is CapabilityAvailability.Available
            && profileOwner is CapabilityAvailability.Available
    val shouldPreferRoot: Boolean get() = !canUseNormal && root is CapabilityAvailability.Available
    val canUseShizukuOrAdb: Boolean get() = shizuku is CapabilityAvailability.Available
            || adb is CapabilityAvailability.Available
}
