package com.yzddmr6.prismspace.prism.compose.vm

import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.space.SpacePresentationKind
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.space.SpaceBridgeCause

internal data class SettingsSpaceAction(
    val title: String,
    val summary: String,
    val needsConfirmation: Boolean,
    val enabled: Boolean,
)

internal fun settingsSpaceAction(
    kind: SpacePresentationKind,
    bridgeCause: SpaceBridgeCause?,
    res: StringResolver,
): SettingsSpaceAction {
    fun text(id: Int) = res(id, emptyArray())
    return when (kind) {
        SpacePresentationKind.Checking,
        SpacePresentationKind.Unavailable -> SettingsSpaceAction(
            text(R.string.lz_set_state_unavailable_title), text(R.string.lz_set_state_unavailable_summary), false, false,
        )
        SpacePresentationKind.Missing -> SettingsSpaceAction(
            text(R.string.lz_set_create_title), text(R.string.lz_set_create_summary), false, true,
        )
        SpacePresentationKind.Orphan -> SettingsSpaceAction(
            text(R.string.lz_set_orphan_title), text(R.string.lz_set_orphan_summary), false, true,
        )
        SpacePresentationKind.Provisioning -> SettingsSpaceAction(
            text(R.string.lz_set_provisioning_title), text(R.string.lz_set_provisioning_summary), false, false,
        )
        SpacePresentationKind.Inactive -> SettingsSpaceAction(
            text(R.string.lz_set_resume_title), text(R.string.lz_set_resume_summary), false, true,
        )
        SpacePresentationKind.Locked -> SettingsSpaceAction(
            text(R.string.lz_set_unlock_title), text(R.string.lz_set_unlock_summary), false, true,
        )
        SpacePresentationKind.BridgeUnavailable -> SettingsSpaceAction(
            text(R.string.lz_set_reconnect_title), text(bridgeCauseSummary(bridgeCause)), false, true,
        )
        SpacePresentationKind.Incomplete -> SettingsSpaceAction(
            text(R.string.lz_set_repair_title), text(R.string.lz_set_repair_summary), true, true,
        )
        SpacePresentationKind.Ready -> SettingsSpaceAction(
            text(R.string.lz_set_ready_title), text(R.string.lz_set_ready_summary), false, false,
        )
    }
}

private fun bridgeCauseSummary(cause: SpaceBridgeCause?): Int = when (cause) {
    SpaceBridgeCause.PermissionDenied -> R.string.lz_set_reconnect_permission_denied
    SpaceBridgeCause.ProfileUnavailable -> R.string.lz_set_reconnect_profile_unavailable
    SpaceBridgeCause.ProviderUnavailable -> R.string.lz_set_reconnect_provider_unavailable
    SpaceBridgeCause.TimedOut -> R.string.lz_set_reconnect_timed_out
    SpaceBridgeCause.Failed -> R.string.lz_set_reconnect_failed
    SpaceBridgeCause.NotChecked,
    null -> R.string.lz_set_reconnect_not_checked
}

/** Presentation of the 暂停所有分身 switch: driven by the real aggregated freeze state; when the
 *  space is locked or the bridge is down the switch is disabled with the state-specific reason. */
internal data class SuspendSwitchPresentation(
    val enabled: Boolean,
    val summaryRes: Int,
)

internal fun suspendSwitchPresentation(
    freezeState: SpaceFreezeState,
    usability: SpaceUsability,
): SuspendSwitchPresentation {
    if (usability != SpaceUsability.Usable) {
        return SuspendSwitchPresentation(
            enabled = false,
            summaryRes = when (usability) {
                SpaceUsability.LockedNeedsUnlock -> R.string.lz_set_suspend_disabled_locked
                SpaceUsability.BridgeNotReady -> R.string.lz_set_suspend_disabled_bridge
                else -> R.string.lz_set_suspend_state_unknown
            },
        )
    }
    return SuspendSwitchPresentation(
        enabled = freezeState != SpaceFreezeState.Unknown,
        summaryRes = when (freezeState) {
            SpaceFreezeState.Active -> R.string.lz_set_suspend_summary
            SpaceFreezeState.Frozen -> R.string.lz_set_suspend_state_frozen
            SpaceFreezeState.Mixed -> R.string.lz_set_suspend_state_mixed
            SpaceFreezeState.Unknown -> R.string.lz_set_suspend_state_unknown
        },
    )
}
