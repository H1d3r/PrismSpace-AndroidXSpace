package com.yzddmr6.prismspace.setup

import com.yzddmr6.prismspace.mobile.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SetupManagedDeviceBoundaryTest {

    @Test
    fun `known device owner label produces an OK-only error`() {
        val error = SetupViewModel.buildManagedDeviceError("Dhizuku", "Unknown")

        assertEquals(R.string.setup_error_managed_device, error.message)
        assertArrayEquals(arrayOf("Dhizuku"), error.message_params)
        assertEquals(0, error.action_extra)
    }

    @Test
    fun `missing or blank device owner label uses the honest fallback`() {
        listOf<CharSequence?>(null, "", "   ").forEach { label ->
            val error = SetupViewModel.buildManagedDeviceError(label, "Unknown device-management app")
            assertArrayEquals(arrayOf("Unknown device-management app"), error.message_params)
            assertEquals(0, error.action_extra)
        }
    }

    @Test
    fun `device owner precondition is checked before any root fallback is offered`() {
        val source = File("src/main/java/com/yzddmr6/prismspace/setup/compose/SetupController.kt").readText()
        val precondition = source.indexOf("checkManagedProvisioningPrerequisites")
        val rootFallback = source.indexOf("R.string.button_setup_space_privileged")

        assertTrue(precondition >= 0)
        assertTrue(rootFallback >= 0)
        assertTrue(precondition < rootFallback)
    }

    @Test
    fun `launch-failure fallback reuses the probe-driven error with identical actions`() {
        // The ActivityNotFoundException catch must not hand-build a weaker dialog without the
        // privileged fallback — it delegates to the same builder the pre-check path uses.
        val source = File("src/main/java/com/yzddmr6/prismspace/setup/compose/SetupController.kt").readText()
        val catch = source.indexOf("catch (e: ActivityNotFoundException)")
        val sharedBuilder = source.indexOf("missingProvisioningErrorPublic")

        assertTrue(catch >= 0)
        assertTrue(sharedBuilder > catch)
    }
}
