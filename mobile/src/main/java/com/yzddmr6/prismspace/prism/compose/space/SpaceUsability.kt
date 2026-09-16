package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.space.SpaceState

/** Single source of truth for whether the dual space is usable RIGHT NOW
 *  (CE-unlock-aware — not just profile-owner/running). */
enum class SpaceUsability { NotProvisioned, Suspended, LockedNeedsUnlock, BridgeNotReady, Unknown, Usable }

/** Pure, unit-tested. Precedence: NotProvisioned > Suspended(not running) >
 *  Suspended(quiet mode) > LockedNeedsUnlock(running but CE-locked) > BridgeNotReady/Unknown > Usable. */
fun spaceUsability(
    provisioned: Boolean,
    running: Boolean,
    unlocked: Boolean,
    bridgeReady: Boolean? = true,
    quietMode: Boolean = false,
): SpaceUsability =
    when {
        !provisioned -> SpaceUsability.NotProvisioned
        !running || quietMode -> SpaceUsability.Suspended
        !unlocked    -> SpaceUsability.LockedNeedsUnlock
        bridgeReady == null -> SpaceUsability.Unknown
        !bridgeReady -> SpaceUsability.BridgeNotReady
        else         -> SpaceUsability.Usable
    }

/** Maps the process-wide classified snapshot to action availability without sampling a second,
 * independently expiring set of Android/bridge facts. */
fun spaceUsabilityFromState(state: SpaceState?, userId: Int): SpaceUsability {
    if (state == null) return SpaceUsability.Unknown
    // A foreign profile belongs to another app/system feature; our space is absent regardless of
    // which user id the caller is tracking.
    if (state is SpaceState.ForeignProfile) return SpaceUsability.NotProvisioned
    if (state.userId != null && state.userId != userId) return SpaceUsability.Unknown
    return when (state) {
        SpaceState.NoProfile, is SpaceState.OrphanProfile, is SpaceState.ForeignProfile ->
            SpaceUsability.NotProvisioned
        is SpaceState.Provisioning -> SpaceUsability.Unknown
        is SpaceState.HalfProvisioned, is SpaceState.BridgeDown -> SpaceUsability.BridgeNotReady
        is SpaceState.Locked -> SpaceUsability.LockedNeedsUnlock
        is SpaceState.Inactive -> SpaceUsability.Suspended
        is SpaceState.Healthy -> SpaceUsability.Usable
    }
}
