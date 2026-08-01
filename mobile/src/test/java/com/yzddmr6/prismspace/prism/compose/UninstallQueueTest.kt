package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.vm.UNINSTALL_VERIFICATION_TIMEOUT_MS
import com.yzddmr6.prismspace.prism.compose.vm.UninstallOutcomeStatus
import com.yzddmr6.prismspace.prism.compose.vm.UninstallQueueReducer
import com.yzddmr6.prismspace.prism.compose.vm.UninstallRequest
import com.yzddmr6.prismspace.prism.compose.vm.UninstallReturnHint
import com.yzddmr6.prismspace.prism.compose.vm.UninstallStage
import com.yzddmr6.prismspace.prism.compose.vm.shouldClearCloneRegistry
import com.yzddmr6.prismspace.prism.compose.vm.uninstallQueueFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallQueueTest {

    @Test fun allConfirmedAdvanceOneAtATimeAndCountRealSuccess() {
        var state = UninstallQueueReducer.start(listOf(request("one"), request("two")))
        assertEquals("one", state.current?.request?.packageName)
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, UninstallReturnHint.Completion, 100)
        state = UninstallQueueReducer.observed(state, installedInTarget = false, mainCopyExists = true, nowMs = 100)
        assertEquals("two", state.current?.request?.packageName)
        assertEquals(UninstallStage.ReadyToLaunch, state.current?.stage)

        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, UninstallReturnHint.Foreground, 200)
        state = UninstallQueueReducer.observed(state, installedInTarget = false, mainCopyExists = true, nowMs = 200)

        assertTrue(state.complete)
        assertEquals(2, state.summary.succeeded)
        assertEquals(0, state.summary.failed)
    }

    @Test fun verifiedCancellationDoesNotCountAsSuccess() {
        var state = UninstallQueueReducer.start(listOf(request("one")))
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, UninstallReturnHint.Cancelled, 100)
        state = UninstallQueueReducer.observed(state, installedInTarget = true, mainCopyExists = true, nowMs = 100)

        assertEquals(UninstallOutcomeStatus.Cancelled, state.outcomes.single().status)
        assertEquals(0, state.summary.succeeded)
        assertEquals(1, state.summary.cancelled)
    }

    @Test fun unknownPackageStateTimesOutWithoutClaimingSuccess() {
        var state = UninstallQueueReducer.start(listOf(request("one")))
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, UninstallReturnHint.Foreground, 1_000)
        state = UninstallQueueReducer.observed(
            state,
            installedInTarget = null,
            mainCopyExists = true,
            nowMs = 1_000 + UNINSTALL_VERIFICATION_TIMEOUT_MS,
        )

        assertEquals(UninstallOutcomeStatus.TimedOut, state.outcomes.single().status)
        assertEquals(1, state.summary.timedOut)
    }

    @Test fun timeInSystemUiDoesNotStartVerificationTimeout() {
        var state = UninstallQueueReducer.start(listOf(request("one")))
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.observed(
            state,
            installedInTarget = true,
            mainCopyExists = true,
            nowMs = UNINSTALL_VERIFICATION_TIMEOUT_MS * 10,
        )
        assertFalse(state.complete)
        assertEquals(UninstallStage.AwaitingSystemUi, state.current?.stage)

        state = UninstallQueueReducer.returned(state, UninstallReturnHint.Foreground, 200_000)
        state = UninstallQueueReducer.observed(state, true, true, 200_001)
        assertEquals(UninstallStage.Verifying, state.current?.stage)
    }

    @Test fun registryCleanupSignalExistsOnlyOnVerifiedSuccess() {
        var success = UninstallQueueReducer.start(listOf(request("ok")))
        success = UninstallQueueReducer.launched(success)
        success = UninstallQueueReducer.returned(success, UninstallReturnHint.Completion, 0)
        success = UninstallQueueReducer.observed(success, false, true, 0)

        var cancelled = UninstallQueueReducer.start(listOf(request("cancel")))
        cancelled = UninstallQueueReducer.launched(cancelled)
        cancelled = UninstallQueueReducer.returned(cancelled, UninstallReturnHint.Cancelled, 0)
        cancelled = UninstallQueueReducer.observed(cancelled, true, true, 0)

        assertEquals(UninstallOutcomeStatus.Success, success.outcomes.single().status)
        assertEquals(UninstallOutcomeStatus.Cancelled, cancelled.outcomes.single().status)
        assertTrue(shouldClearCloneRegistry(success.outcomes.single().status))
        assertFalse(shouldClearCloneRegistry(cancelled.outcomes.single().status))
    }

    @Test fun summarySeparatesNotRemovedFromUnconfirmed() {
        var state = UninstallQueueReducer.start(listOf(request("cancel"), request("timeout")))
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, UninstallReturnHint.Cancelled, 0)
        state = UninstallQueueReducer.observed(state, true, true, 0)
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, UninstallReturnHint.Foreground, 0)
        state = UninstallQueueReducer.observed(state, null, true, UNINSTALL_VERIFICATION_TIMEOUT_MS)

        assertEquals("卸载完成：已卸载 0 个，未卸载 1 个，未能确认 1 个。", uninstallQueueFeedback(state.summary).message)
    }

    private fun request(pkg: String) = UninstallRequest(pkg, targetUserId = 22, mainCopyExisted = true)
}
