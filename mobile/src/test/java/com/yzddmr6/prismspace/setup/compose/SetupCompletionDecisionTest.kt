package com.yzddmr6.prismspace.setup.compose

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import com.yzddmr6.prismspace.space.SpaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCompletionDecisionTest {

    @Test fun `healthy facts finish regardless of activity result`() {
        assertEquals(
            SetupCompletionAction.Finish,
            setupCompletionAction(Activity.RESULT_OK, SpaceState.Healthy(20)),
        )
        assertEquals(
            SetupCompletionAction.Finish,
            setupCompletionAction(Activity.RESULT_CANCELED, SpaceState.Healthy(20)),
        )
    }

    @Test fun `explicit cancel with fresh no-profile facts shows cancellation`() {
        assertEquals(
            SetupCompletionAction.ShowCanceled,
            setupCompletionAction(Activity.RESULT_CANCELED, SpaceState.NoProfile),
        )
    }

    @Test fun `missing or incomplete facts keep waiting without inventing success`() {
        assertEquals(
            SetupCompletionAction.WaitForHealth,
            setupCompletionAction(Activity.RESULT_OK, SpaceState.NoProfile),
        )
        assertEquals(
            SetupCompletionAction.WaitForHealth,
            setupCompletionAction(Activity.RESULT_CANCELED, SpaceState.HalfProvisioned(20, resumable = true)),
        )
        assertEquals(
            SetupCompletionAction.WaitForHealth,
            setupCompletionAction(Activity.RESULT_OK, null),
        )
    }

    @Test fun `provisioning wait flag restores through saved state and consumes once`() {
        val originalHandle = SavedStateHandle()
        SetupStateViewModel(originalHandle).provisioningLaunched = true

        val restored = SetupStateViewModel(SavedStateHandle(mapOf(
            SetupStateViewModel.KEY_PROVISIONING_LAUNCHED to
                originalHandle.get<Boolean>(SetupStateViewModel.KEY_PROVISIONING_LAUNCHED),
        )))

        assertTrue(restored.provisioningLaunched)
        assertTrue(restored.consumeProvisioningLaunched())
        assertFalse(restored.consumeProvisioningLaunched())
    }
}
