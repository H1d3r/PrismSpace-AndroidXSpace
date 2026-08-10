package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.space.SpacePresentationKind
import com.yzddmr6.prismspace.prism.compose.vm.settingsSpaceAction
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
}
