package com.yzddmr6.prismspace.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClonePreparationStoreTest {

    @Test fun addRemoveAndMembershipArePackageScopedAndIdempotent() {
        val persistence = MemoryPersistence()
        val store = ClonePreparationStateStore(persistence)

        store.add("one.pkg")
        store.add("one.pkg")
        store.add("two.pkg")

        assertTrue(store.contains("one.pkg"))
        assertEquals(setOf("one.pkg", "two.pkg"), persistence.value)
        store.remove("one.pkg")
        store.remove("one.pkg")
        assertFalse(store.contains("one.pkg"))
        assertEquals(setOf("two.pkg"), persistence.value)
    }

    @Test fun newStoreInstanceReadsPersistedPendingPackages() {
        val persistence = MemoryPersistence(setOf("persisted.pkg"))

        assertTrue(ClonePreparationStateStore(persistence).contains("persisted.pkg"))
    }

    @Test fun installedPackagesAreReconciledWithoutDroppingOtherPendingWork() {
        val persistence = MemoryPersistence(setOf("installed.pkg", "pending.pkg"))
        val store = ClonePreparationStateStore(persistence)

        assertEquals(setOf("pending.pkg"), store.reconcileInstalled(setOf("installed.pkg")))
        assertEquals(setOf("pending.pkg"), persistence.value)
    }

    @Test fun clearDropsEveryPendingMarker() {
        // 删除双开空间后：待安装标记全部清除（首页待办卡与主空间行不再呈现悬空任务）。
        val persistence = MemoryPersistence(setOf("one.pkg", "two.pkg"))
        val store = ClonePreparationStateStore(persistence)

        store.clear()

        assertEquals(emptySet<String>(), persistence.value)
        assertFalse(store.contains("one.pkg"))
        assertEquals(emptySet<String>(), store.reconcileInstalled(emptySet()))
    }

    private class MemoryPersistence(initial: Set<String> = emptySet()) : ClonePreparationPersistence {
        var value = initial
        override fun read(): Set<String> = value
        override fun write(packages: Set<String>) { value = packages }
    }
}
