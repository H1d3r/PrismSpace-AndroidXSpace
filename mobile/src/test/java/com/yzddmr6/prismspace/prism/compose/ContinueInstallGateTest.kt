package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.vm.continueInstallGate
import com.yzddmr6.prismspace.prism.compose.vm.testZhResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueInstallGateTest {

    @Test fun usableSpaceEnablesContinueInstall() {
        val gate = continueInstallGate(SpaceUsability.Usable, testZhResolver)

        assertTrue(gate.enabled)
        assertNull(gate.guidance)
    }

    @Test fun everyUnusableStateDisablesContinueInstallWithGuidance() {
        listOf(
            SpaceUsability.Suspended,
            SpaceUsability.LockedNeedsUnlock,
            SpaceUsability.BridgeNotReady,
            SpaceUsability.NotProvisioned,
            SpaceUsability.Unknown,
        ).forEach { state ->
            val gate = continueInstallGate(state, testZhResolver)
            assertFalse("$state must disable continue-install", gate.enabled)
            assertTrue("$state must carry guidance", !gate.guidance.isNullOrBlank())
        }
    }

    @Test fun guidanceCopyIsStateSpecific() {
        assertEquals(
            "请先在设置中恢复双开空间，然后再继续安装",
            continueInstallGate(SpaceUsability.Suspended, testZhResolver).guidance,
        )
        assertEquals(
            "请先解锁双开空间（锁屏密码），然后再继续安装",
            continueInstallGate(SpaceUsability.LockedNeedsUnlock, testZhResolver).guidance,
        )
        assertEquals(
            "请先在设置中修复双开空间连接，然后再继续安装",
            continueInstallGate(SpaceUsability.BridgeNotReady, testZhResolver).guidance,
        )
        assertEquals(
            "请先创建双开空间，然后再继续安装",
            continueInstallGate(SpaceUsability.NotProvisioned, testZhResolver).guidance,
        )
        assertEquals(
            "请先在设置中修复双开空间，然后再继续安装",
            continueInstallGate(SpaceUsability.Unknown, testZhResolver).guidance,
        )
    }
}
