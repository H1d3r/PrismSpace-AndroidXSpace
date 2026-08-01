package com.yzddmr6.prismspace.space

/** Android-free facts for one profile in the current profile group. */
data class SpaceProfileFacts(
    val userId: Int,
    val prismPackagePresent: Boolean,
    val ownershipMarkerPresent: Boolean,
    val provisioningActive: Boolean = false,
    val running: Boolean = false,
    val unlocked: Boolean = false,
    val quietMode: Boolean = false,
    val bridgeReady: Boolean? = null,
    val bridgeCause: SpaceBridgeCause = SpaceBridgeCause.NotChecked,
    val profileOwner: Boolean? = null,
    val provisionComplete: Boolean? = null,
)

data class SpaceFacts(val profiles: List<SpaceProfileFacts>)

enum class SpaceBridgeCause { NotChecked, PermissionDenied, ProfileUnavailable, ProviderUnavailable, TimedOut, Failed }

sealed interface SpaceState {
    val userId: Int?

    data object NoProfile : SpaceState { override val userId: Int? = null }
    data class OrphanProfile(override val userId: Int) : SpaceState
    data class Provisioning(override val userId: Int) : SpaceState
    data class HalfProvisioned(override val userId: Int, val resumable: Boolean) : SpaceState
    data class Locked(override val userId: Int) : SpaceState
    data class Inactive(override val userId: Int) : SpaceState
    data class BridgeDown(override val userId: Int, val cause: SpaceBridgeCause) : SpaceState
    data class Healthy(override val userId: Int) : SpaceState
}

object SpaceStateClassifier {
    /**
     * Select one deterministic PrismSpace candidate, then classify by the first failed invariant.
     * A marker is the strongest ownership evidence, package presence is the recovery evidence, and
     * user id is only a stable tie-breaker. Android's profile enumeration order is never trusted.
     */
    fun classify(facts: SpaceFacts): SpaceState {
        val profile = facts.profiles.sortedWith(
            compareByDescending<SpaceProfileFacts> { it.ownershipMarkerPresent }
                .thenByDescending { it.prismPackagePresent }
                .thenBy { it.userId },
        ).firstOrNull() ?: return SpaceState.NoProfile

        if (!profile.prismPackagePresent) return SpaceState.OrphanProfile(profile.userId)
        if (profile.provisioningActive) return SpaceState.Provisioning(profile.userId)
        if (!profile.ownershipMarkerPresent) {
            val resumable = profile.bridgeReady == true && profile.profileOwner == true
            return SpaceState.HalfProvisioned(profile.userId, resumable)
        }
        if (!profile.unlocked) return SpaceState.Locked(profile.userId)
        if (profile.quietMode || !profile.running) return SpaceState.Inactive(profile.userId)
        if (profile.bridgeReady != true) return SpaceState.BridgeDown(profile.userId, profile.bridgeCause)
        return SpaceState.Healthy(profile.userId)
    }
}
