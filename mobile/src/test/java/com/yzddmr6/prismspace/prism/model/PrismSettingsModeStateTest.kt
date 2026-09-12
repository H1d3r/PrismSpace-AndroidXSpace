package com.yzddmr6.prismspace.prism.model

import com.yzddmr6.prismspace.prism.compose.vm.testZhResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class PrismSettingsModeStateTest {

    private fun state(shizuku: PrismShizukuAdbStatus, root: PrismRootStatus) =
        PrismSettingsModeState.from(shizuku, root, testZhResolver)

    @Test fun normalModeIsAlwaysAvailableForCoreDualOpen() {
        val state = PrismSettingsModeState.from(
            shizuku = PrismShizukuAdbStatus.NotInstalled,
            root = PrismRootStatus.NotDetected,
            res = testZhResolver,
        )

        assertEquals("普通模式", state.normal.title)
        assertEquals("可用", state.normal.status)
        assertEquals("双开空间、应用打开、冻结、暂停和基础文件导入导出不需要 Root。", state.normal.summary)
    }

    @Test fun shizukuAdbDistinguishesSetupStates() {
        assertEquals(
            "未安装",
            state(PrismShizukuAdbStatus.NotInstalled, PrismRootStatus.NotDetected).shizukuAdb.status,
        )
        assertEquals(
            "未运行",
            state(PrismShizukuAdbStatus.NotRunning, PrismRootStatus.NotDetected).shizukuAdb.status,
        )
        assertEquals(
            "等待授权",
            state(PrismShizukuAdbStatus.WaitingAuthorization, PrismRootStatus.NotDetected).shizukuAdb.status,
        )
        assertEquals(
            "可用",
            state(PrismShizukuAdbStatus.Ready, PrismRootStatus.NotDetected).shizukuAdb.status,
        )
    }

    @Test fun rootModeCopySaysItIsFallbackOnly() {
        val state = PrismSettingsModeState.from(
            shizuku = PrismShizukuAdbStatus.Ready,
            root = PrismRootStatus.AvailableButDisabled,
            res = testZhResolver,
        )

        assertEquals("Root 模式", state.root.title)
        assertEquals("可用但关闭", state.root.status)
        assertEquals("Root 仅用于高级诊断和兜底维护。Root 开通会临时调整系统用户上限，并在结束后还原。", state.root.summary)
    }
}
