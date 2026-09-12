package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.space.SpacePresentationKind
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.vm.SpaceFreezeState
import com.yzddmr6.prismspace.prism.compose.vm.settingsSpaceAction
import com.yzddmr6.prismspace.prism.compose.vm.suspendSwitchPresentation
import com.yzddmr6.prismspace.space.SpaceBridgeCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSpaceActionTest {
    private val res: (Int, Array<out Any>) -> String = { id, _ -> id.toString() }

    @Test fun unknownFactsDisableMutationInsteadOfOfferingCreation() {
        val action = settingsSpaceAction(SpacePresentationKind.Unavailable, null, res)
        assertEquals(R.string.lz_set_state_unavailable_title.toString(), action.title)
        assertFalse(action.enabled)
        assertFalse(action.needsConfirmation)
    }

    @Test fun orphanProfileOpensSystemPathAndNeverLooksAbsent() {
        val action = settingsSpaceAction(SpacePresentationKind.Orphan, null, res)
        assertEquals(R.string.lz_set_orphan_title.toString(), action.title)
        assertTrue(action.enabled)
        assertFalse(action.needsConfirmation)
    }

    @Test fun bridgeFailureCopyRetainsTheClassifiedCause() {
        val denied = settingsSpaceAction(SpacePresentationKind.BridgeUnavailable, SpaceBridgeCause.PermissionDenied, res)
        val timeout = settingsSpaceAction(SpacePresentationKind.BridgeUnavailable, SpaceBridgeCause.TimedOut, res)
        assertEquals(R.string.lz_set_reconnect_permission_denied.toString(), denied.summary)
        assertEquals(R.string.lz_set_reconnect_timed_out.toString(), timeout.summary)
    }

    // ── 暂停所有分身 switch presentation (真实聚合状态驱动；锁定/断连禁用并说明) ──

    @Test fun suspendSwitchFollowsRealAggregateState() {
        // 开关说真话：混合暂停状态呈现 Mixed 文案且可操作。
        val mixed = suspendSwitchPresentation(SpaceFreezeState.Mixed, SpaceUsability.Usable)
        assertTrue(mixed.enabled)
        assertEquals(R.string.lz_set_suspend_state_mixed, mixed.summaryRes)

        val frozen = suspendSwitchPresentation(SpaceFreezeState.Frozen, SpaceUsability.Usable)
        assertTrue(frozen.enabled)
        assertEquals(R.string.lz_set_suspend_state_frozen, frozen.summaryRes)
    }

    @Test fun suspendSwitchDisabledWithReasonWhenLockedOrBridgeDown() {
        val locked = suspendSwitchPresentation(SpaceFreezeState.Active, SpaceUsability.LockedNeedsUnlock)
        assertFalse(locked.enabled)
        assertEquals(R.string.lz_set_suspend_disabled_locked, locked.summaryRes)

        val bridge = suspendSwitchPresentation(SpaceFreezeState.Active, SpaceUsability.BridgeNotReady)
        assertFalse(bridge.enabled)
        assertEquals(R.string.lz_set_suspend_disabled_bridge, bridge.summaryRes)
    }

    @Test fun suspendSwitchUnknownFactsReadUnknownAndDisable() {
        val unknown = suspendSwitchPresentation(SpaceFreezeState.Unknown, SpaceUsability.Usable)
        assertFalse(unknown.enabled)
        assertEquals(R.string.lz_set_suspend_state_unknown, unknown.summaryRes)
    }
}
