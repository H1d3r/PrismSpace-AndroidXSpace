package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.vm.testZhResolver
import com.yzddmr6.prismspace.prism.compose.vm.uninstallGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallGateTest {

    @Test fun usableSpaceEnablesUninstallWithoutGuidance() {
        val gate = uninstallGate(SpaceUsability.Usable, testZhResolver)

        assertTrue(gate.enabled)
        assertNull(gate.guidance)
    }

    @Test fun everyUnusableStateDisablesUninstallWithGuidance() {
        val unusable = listOf(
            SpaceUsability.Suspended,
            SpaceUsability.LockedNeedsUnlock,
            SpaceUsability.BridgeNotReady,
            SpaceUsability.NotProvisioned,
            SpaceUsability.Unknown,
        )

        unusable.forEach { state ->
            val gate = uninstallGate(state, testZhResolver)
            assertFalse("$state must disable uninstall", gate.enabled)
            assertTrue("$state must carry guidance", !gate.guidance.isNullOrBlank())
        }
    }

    @Test fun guidanceCopyIsStateSpecific() {
        assertEquals(
            "请先在设置中恢复双开空间，然后再卸载分身",
            uninstallGate(SpaceUsability.Suspended, testZhResolver).guidance,
        )
        assertEquals(
            "请先解锁双开空间（锁屏密码），然后再卸载分身",
            uninstallGate(SpaceUsability.LockedNeedsUnlock, testZhResolver).guidance,
        )
        assertEquals(
            "请先在设置中修复双开空间连接，然后再卸载分身",
            uninstallGate(SpaceUsability.BridgeNotReady, testZhResolver).guidance,
        )
        assertEquals(
            "请先创建双开空间，然后再卸载分身",
            uninstallGate(SpaceUsability.NotProvisioned, testZhResolver).guidance,
        )
        assertEquals(
            "请先在设置中修复双开空间，然后再卸载分身",
            uninstallGate(SpaceUsability.Unknown, testZhResolver).guidance,
        )
    }

    @Test fun gateHandlesEveryUsabilityValue() {
        // The when in uninstallGate is exhaustive; this pins the enum size so a new member
        // reminds the author to decide its gate behavior in tests too.
        assertEquals(6, SpaceUsability.values().size)
        SpaceUsability.values().forEach { uninstallGate(it, testZhResolver) }
    }
}
