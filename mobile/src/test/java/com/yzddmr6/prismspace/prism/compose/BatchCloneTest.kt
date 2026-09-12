package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.vm.BatchCloneCounts
import com.yzddmr6.prismspace.prism.compose.vm.BatchClonePort
import com.yzddmr6.prismspace.prism.compose.vm.BatchCloneResult
import com.yzddmr6.prismspace.prism.compose.vm.batchCloneFeedback
import com.yzddmr6.prismspace.prism.compose.vm.runBatchClone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchCloneTest {

    @Test fun normalModeStagingCountsAsPreparedNeverCloned() = runBlocking {
        // 4 个应用全部暂存成功 → 4 prepared、0 installed；汇总必须说「还需在双开空间确认安装」。
        val port = BatchClonePort { BatchCloneResult.Prepared }

        val counts = runBatchClone(listOf("a", "b", "c", "d"), port)

        assertEquals(BatchCloneCounts(prepared = 4, installed = 0, failed = 0), counts)
        val fb = batchCloneFeedback(counts)
        assertEquals("已为 4 个应用准备安装包，还需在双开空间确认安装", fb.message)
        assertFalse(fb.isError)
    }

    @Test fun partialStagingFailureIsCountedSeparately() = runBlocking {
        val port = BatchClonePort { pkg -> if (pkg == "bad") BatchCloneResult.Failed() else BatchCloneResult.Prepared }

        val counts = runBatchClone(listOf("a", "bad", "c", "d"), port)

        assertEquals(BatchCloneCounts(prepared = 3, installed = 0, failed = 1), counts)
        val fb = batchCloneFeedback(counts)
        assertEquals("已为 3 个应用准备安装包，1 个失败；还需在双开空间确认安装", fb.message)
        assertTrue(fb.isError)
    }

    @Test fun enhancedRouteVerifiedInstallCountsAsInstalled() = runBlocking {
        val port = BatchClonePort { BatchCloneResult.Installed }

        val counts = runBatchClone(listOf("a", "b"), port)

        assertEquals(BatchCloneCounts(prepared = 0, installed = 2, failed = 0), counts)
        assertEquals("已克隆 2 个应用到双开空间", batchCloneFeedback(counts).message)
    }

    @Test fun mixedOutcomesKeepAllThreeCountersHonest() = runBlocking {
        val outcomes = mapOf(
            "rooted" to BatchCloneResult.Installed,
            "staged" to BatchCloneResult.Prepared,
            "broken" to BatchCloneResult.Failed(),
        )
        val port = BatchClonePort { pkg -> outcomes.getValue(pkg) }

        val counts = runBatchClone(listOf("rooted", "staged", "broken"), port)

        assertEquals(BatchCloneCounts(prepared = 1, installed = 1, failed = 1), counts)
        val fb = batchCloneFeedback(counts)
        assertEquals("已克隆 1 个，已准备 1 个（需在双开空间确认安装），失败 1 个", fb.message)
        assertTrue(fb.isError)
    }

    @Test fun installedPartialFailureCopy() = runBlocking {
        val counts = BatchCloneCounts(prepared = 0, installed = 2, failed = 1)
        val fb = batchCloneFeedback(counts)
        assertEquals("已克隆 2 个应用，1 个失败", fb.message)
        assertTrue(fb.isError)
    }

    @Test fun allFailedCopyClaimsNoSuccess() = runBlocking {
        val counts = BatchCloneCounts(prepared = 0, installed = 0, failed = 2)
        val fb = batchCloneFeedback(counts)
        assertEquals("未能克隆 2 个应用", fb.message)
        assertTrue(fb.isError)
    }

    @Test fun throwingPackageCountsAsFailedNotSuccess() = runBlocking {
        val port = BatchClonePort { pkg ->
            if (pkg == "boom") error("simulated staging crash") else BatchCloneResult.Prepared
        }

        val counts = runBatchClone(listOf("a", "boom", "c"), port)

        assertEquals(BatchCloneCounts(prepared = 2, installed = 0, failed = 1), counts)
    }

    @Test fun progressFiresAfterEveryRealResult() = runBlocking {
        val seen = mutableListOf<Pair<Int, Int>>()
        val port = BatchClonePort { BatchCloneResult.Prepared }

        runBatchClone(listOf("a", "b", "c"), port) { done, total -> seen += done to total }

        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
    }

    @Test fun refusedActivationIsAskedOnceAndRemainingPackagesFailFast() = runBlocking {
        // 安静模式 + 用户拒绝激活：整批只弹一次系统框；剩余包直接计失败，不再逐包弹框。
        var activateCalls = 0
        var portCalls = 0
        val port = BatchClonePort {
            portCalls++
            BatchCloneResult.Failed(needsActivation = true)
        }

        val counts = runBatchClone(listOf("a", "b", "c", "d"), port, activate = {
            activateCalls++
            false   // user refuses / dialog times out
        })

        assertEquals(1, activateCalls)
        assertEquals(4, portCalls)   // no retry after refusal, one call per package
        assertEquals(BatchCloneCounts(prepared = 0, installed = 0, failed = 4), counts)
    }

    @Test fun grantedActivationRetriesTheFirstPackageOnce() = runBlocking {
        var activateCalls = 0
        val attempts = mutableMapOf<String, Int>()
        val port = BatchClonePort { pkg ->
            val attempt = (attempts[pkg] ?: 0) + 1
            attempts[pkg] = attempt
            if (pkg == "a" && attempt == 1) BatchCloneResult.Failed(needsActivation = true)
            else BatchCloneResult.Prepared
        }

        val counts = runBatchClone(listOf("a", "b", "c"), port, activate = {
            activateCalls++
            true    // user disables quiet mode
        })

        assertEquals(1, activateCalls)
        assertEquals(2, attempts.getValue("a"))   // retried exactly once after real activation
        assertEquals(BatchCloneCounts(prepared = 3, installed = 0, failed = 0), counts)
    }

    @Test fun secondActivationNeedInOneBatchDoesNotReprompt() = runBlocking {
        // 激活成功后空间又被暂停（边界）：activation 预算已花掉，第二个需要激活的包直接失败。
        var activateCalls = 0
        val attempts = mutableMapOf<String, Int>()
        val port = BatchClonePort { pkg ->
            val attempt = (attempts[pkg] ?: 0) + 1
            attempts[pkg] = attempt
            when {
                pkg == "a" && attempt == 1 -> BatchCloneResult.Failed(needsActivation = true)
                pkg == "b" -> BatchCloneResult.Failed(needsActivation = true)
                else -> BatchCloneResult.Prepared
            }
        }

        val counts = runBatchClone(listOf("a", "b", "c"), port, activate = {
            activateCalls++
            true
        })

        assertEquals(1, activateCalls)
        assertEquals(BatchCloneCounts(prepared = 2, installed = 0, failed = 1), counts)
    }

    @Test fun nonActivationFailuresNeverTriggerThePrompt() = runBlocking {
        var activateCalls = 0
        val port = BatchClonePort { BatchCloneResult.Failed(needsActivation = false) }

        val counts = runBatchClone(listOf("a", "b"), port, activate = {
            activateCalls++
            true
        })

        assertEquals(0, activateCalls)
        assertEquals(BatchCloneCounts(prepared = 0, installed = 0, failed = 2), counts)
    }
}
