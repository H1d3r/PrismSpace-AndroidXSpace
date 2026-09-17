package com.yzddmr6.prismspace.setup

import android.content.pm.PackageManager
import com.yzddmr6.prismspace.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningProbeTest {

    @Test
    fun `absent package classifies as ABSENT regardless of handler counts`() {
        assertEquals(ProvisioningProbe.MissingState.ABSENT,
            ProvisioningProbe.classify(false, null, 0, 0))
        assertEquals(ProvisioningProbe.MissingState.ABSENT,
            ProvisioningProbe.classify(false, null, -1, -1))
    }

    @Test
    fun `package disabled wins over component-level evidence`() {
        assertEquals(ProvisioningProbe.MissingState.PACKAGE_DISABLED,
            ProvisioningProbe.classify(true, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0, 3))
        assertEquals(ProvisioningProbe.MissingState.PACKAGE_DISABLED,
            ProvisioningProbe.classify(true, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0, 0))
    }

    @Test
    fun `disabled-only handlers classify as COMPONENT_DISABLED`() {
        assertEquals(ProvisioningProbe.MissingState.COMPONENT_DISABLED,
            ProvisioningProbe.classify(true, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0, 2))
    }

    @Test
    fun `enabled package with no handler at all classifies as NO_HANDLER`() {
        assertEquals(ProvisioningProbe.MissingState.NO_HANDLER,
            ProvisioningProbe.classify(true, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0, 0))
        assertEquals(ProvisioningProbe.MissingState.NO_HANDLER,
            ProvisioningProbe.classify(true, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0, 0))
    }

    @Test
    fun `enabled handlers contradicting resolveActivity classify as UNKNOWN`() {
        assertEquals(ProvisioningProbe.MissingState.UNKNOWN,
            ProvisioningProbe.classify(true, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 1, 1))
        assertEquals(ProvisioningProbe.MissingState.UNKNOWN,
            ProvisioningProbe.classify(true, null, -1, -1))
    }

    @Test
    fun `blocked-by-profile copy only shows when a handler exists to be occupied`() {
        assertEquals(R.string.setup_error_provisioning_blocked_by_profile,
            ProvisioningProbe.errorMessageFor(ProvisioningProbe.MissingState.COMPONENT_DISABLED, 1, true))
        assertEquals(R.string.setup_error_provisioning_blocked_by_profile,
            ProvisioningProbe.errorMessageFor(ProvisioningProbe.MissingState.PACKAGE_DISABLED, 1, false))
        assertEquals(R.string.setup_error_provisioning_blocked_by_profile,
            ProvisioningProbe.errorMessageFor(ProvisioningProbe.MissingState.UNKNOWN, 1, true))
    }

    @Test
    fun `absent or stripped handler on a capable device gets the privileged-fallback copy`() {
        // The HyperOS 1 reality: entry package removed but FEATURE_MANAGED_USERS present.
        // The copy must point at Shizuku/ROOT instead of claiming the device is unsupported.
        assertEquals(R.string.setup_error_provisioning_entry_stripped,
            ProvisioningProbe.errorMessageFor(ProvisioningProbe.MissingState.ABSENT, 1, true))
        assertEquals(R.string.setup_error_provisioning_entry_stripped,
            ProvisioningProbe.errorMessageFor(ProvisioningProbe.MissingState.NO_HANDLER, 1, true))
        assertEquals(R.string.setup_error_provisioning_entry_stripped,
            ProvisioningProbe.errorMessageFor(ProvisioningProbe.MissingState.ABSENT, 0, true))
    }

    @Test
    fun `genuinely incapable device keeps the not-supported copy`() {
        ProvisioningProbe.MissingState.values().forEach { state ->
            assertEquals(R.string.setup_error_missing_managed_provisioning,
                ProvisioningProbe.errorMessageFor(state, 0, false))
            if (state == ProvisioningProbe.MissingState.ABSENT || state == ProvisioningProbe.MissingState.NO_HANDLER)
                assertEquals(R.string.setup_error_missing_managed_provisioning,
                    ProvisioningProbe.errorMessageFor(state, 1, false))
        }
    }

    @Test
    fun `privileged fallback is offered exactly when the platform can run managed users`() {
        assertTrue(ProvisioningProbe.shouldOfferPrivilegedFallback(true))
        assertFalse(ProvisioningProbe.shouldOfferPrivilegedFallback(false))
    }
}
