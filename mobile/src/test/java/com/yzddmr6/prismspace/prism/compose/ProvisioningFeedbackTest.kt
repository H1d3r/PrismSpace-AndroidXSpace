package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.space.CreateSpaceResult
import com.yzddmr6.prismspace.prism.compose.space.DeleteSpaceResult
import com.yzddmr6.prismspace.prism.compose.vm.provisioningFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningFeedbackTest {
    @Test fun `create success`() {
        val f = provisioningFeedback(CreateSpaceResult.Success(12))
        assertEquals("已创建新的双开空间", f.message); assertFalse(f.isError)
    }
    @Test fun `create root unavailable`() {
        val f = provisioningFeedback(CreateSpaceResult.RootUnavailable)
        assertEquals("需要特权通道才能创建空间：请安装并启动 Shizuku（Android 11+ 可用无线调试激活）或 Sui，或为设备启用 Root 后重试", f.message); assertTrue(f.isError)
    }
    @Test fun `create cap reached`() {
        val f = provisioningFeedback(CreateSpaceResult.CapReached(4))
        assertEquals("已达本设备空间上限（最多 4 个用户），无法再创建", f.message); assertTrue(f.isError)
    }
    @Test fun `create failed names the reason, null falls back`() {
        assertEquals("创建空间失败：boom，可重试", provisioningFeedback(CreateSpaceResult.Failed("boom")).message)
        assertEquals("创建空间失败：未知错误，可重试", provisioningFeedback(CreateSpaceResult.Failed(null)).message)
    }
    @Test fun `delete success and root-unavailable and failed`() {
        assertEquals("已删除该双开空间", provisioningFeedback(DeleteSpaceResult.Success).message)
        val rootUnavailable = provisioningFeedback(DeleteSpaceResult.RootUnavailable)
        assertEquals("普通模式无法直接删除双开空间，请在系统设置中移除工作资料；启用 Shizuku / Sui 或 Root 后可一键删除。", rootUnavailable.message)
        assertTrue(rootUnavailable.isError)
        assertTrue(rootUnavailable.routeToSystemRemoval)
        assertEquals("删除空间失败：x，空间未被破坏，可重试",
            provisioningFeedback(DeleteSpaceResult.Failed("x")).message)
    }
    @Test fun `delete failed with null or blank reason falls back`() {
        assertEquals("删除空间失败：未知错误，空间未被破坏，可重试",
            provisioningFeedback(DeleteSpaceResult.Failed(null)).message)
        assertEquals("删除空间失败：未知错误，空间未被破坏，可重试",
            provisioningFeedback(DeleteSpaceResult.Failed("   ")).message)
    }
    @Test fun `create failed with blank reason falls back`() {
        assertEquals("创建空间失败：未知错误，可重试", provisioningFeedback(CreateSpaceResult.Failed("   ")).message)
    }
    @Test fun `delete manual removal result routes to system removal`() {
        val f = provisioningFeedback(DeleteSpaceResult.ManualRemovalRequired("not owner"))
        assertTrue(f.isError)
        assertTrue(f.routeToSystemRemoval)
    }
    @Test fun `managed-profile limit feedback honest`() {
        val f = provisioningFeedback(CreateSpaceResult.ManagedProfileLimitReached)
        assertEquals("本设备系统仅允许一个双开空间（已达系统工作资料上限），无法再创建", f.message)
        assertTrue(f.isError)
    }
    @Test fun `state refresh failure stops creation with retryable feedback`() {
        val f = provisioningFeedback(CreateSpaceResult.StateRefreshFailed)
        assertEquals("无法确认双开空间的最新状态，请稍后重试。未执行任何更改。", f.message)
        assertTrue(f.isError)
        assertFalse(f.routeToSystemRemoval)
    }
}
