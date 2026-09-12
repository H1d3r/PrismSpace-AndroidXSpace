package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.vm.fileTransferGate
import com.yzddmr6.prismspace.prism.compose.vm.testZhResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferGateTest {

    @Test fun usableSpaceEnablesSending() {
        val gate = fileTransferGate(SpaceUsability.Usable, testZhResolver)

        assertTrue(gate.enabled)
        assertNull(gate.guidance)
    }

    @Test fun everyUnusableStateDisablesSendingWithGuidance() {
        listOf(
            SpaceUsability.Suspended,
            SpaceUsability.LockedNeedsUnlock,
            SpaceUsability.BridgeNotReady,
            SpaceUsability.NotProvisioned,
            SpaceUsability.Unknown,
        ).forEach { state ->
            val gate = fileTransferGate(state, testZhResolver)
            assertFalse("$state must disable sending", gate.enabled)
            assertTrue("$state must carry guidance", !gate.guidance.isNullOrBlank())
        }
    }

    @Test fun guidanceCopyIsStateSpecificAndActionSpecific() {
        assertEquals(
            "请先在设置中恢复双开空间，然后再发送文件",
            fileTransferGate(SpaceUsability.Suspended, testZhResolver).guidance,
        )
        assertEquals(
            "请先在设置中修复双开空间连接，然后再发送文件",
            fileTransferGate(SpaceUsability.BridgeNotReady, testZhResolver).guidance,
        )
    }
}
