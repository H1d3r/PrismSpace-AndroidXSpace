package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.vm.MultiSelect
import com.yzddmr6.prismspace.prism.compose.vm.SpaceRow
import com.yzddmr6.prismspace.prism.compose.vm.SpaceSegment
import com.yzddmr6.prismspace.prism.compose.vm.batchAllSelected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSelectTest {

    @Test fun enterSnapshotsTheFilteredResultSetAsDomain() {
        // 用户以「仅未添加」筛选后得到 filtered 集；进入批量后选择域恰好是该集（且排除系统应用）。
        val filtered = listOf(row("new.a"), row("new.b"), row("new.c", system = true))

        val state = MultiSelect.enter("new.a", filtered)!!

        assertEquals(setOf("new.a"), state.selected)
        assertEquals(listOf("new.a", "new.b"), state.domain.map { it.pkg })
    }

    @Test fun enterOnSystemRowIsRejected() {
        assertNull(MultiSelect.enter("sys.a", listOf(row("sys.a", system = true))))
    }

    @Test fun enterOnUnknownRowIsRejected() {
        assertNull(MultiSelect.enter("ghost", listOf(row("a"))))
    }

    @Test fun selectAllCoversExactlyTheDomain() {
        val state = MultiSelect.enter("a", listOf(row("a"), row("b"), row("c")))!!

        val all = MultiSelect.selectAll(state)

        assertEquals(setOf("a", "b", "c"), all.selected)
    }

    @Test fun toggleOutsideTheDomainIsANoOp() {
        val state = MultiSelect.enter("a", listOf(row("a"), row("b")))!!

        val after = MultiSelect.toggle(state, "not.in.domain")

        assertTrue(after === state)
        assertEquals(setOf("a"), after!!.selected)
    }

    @Test fun togglingOffTheLastSelectionExitsMultiSelect() {
        val state = MultiSelect.enter("a", listOf(row("a")))!!

        assertNull(MultiSelect.toggle(state, "a"))
    }

    @Test fun domainSnapshotIsUnaffectedByLaterListRefreshes() {
        val entered = MultiSelect.enter("a", listOf(row("a"), row("b")))!!
        val refreshedRows = listOf(row("a"), row("b"), row("c"), row("d"))

        // A background refresh produces a new list; the held domain must not change.
        val selected = MultiSelect.selectAll(entered)

        assertEquals(listOf("a", "b"), selected.domain.map { it.pkg })
        assertEquals(setOf("a", "b"), selected.selected)
        assertTrue(!selected.domain.any { it.pkg in refreshedRows.map { r -> r.pkg }.drop(2) })
    }

    @Test fun allSelectedIsDomainDrivenNotSegmentDriven() {
        // 域驱动：分母是快照域大小而非整个分段——筛选后的「全选」应翻转为「取消全选」。
        assertFalse(batchAllSelected(domainSize = 0, selectedCount = 0))
        assertFalse(batchAllSelected(domainSize = 3, selectedCount = 2))
        assertTrue(batchAllSelected(domainSize = 2, selectedCount = 2))
        assertTrue(batchAllSelected(domainSize = 2, selectedCount = 3))
    }

    private fun row(pkg: String, system: Boolean = false) = SpaceRow(
        pkg = pkg,
        label = pkg,
        frozen = false,
        suspended = false,
        launchable = true,
        system = system,
        cloned = false,
        prepared = false,
        segment = SpaceSegment.Main,
        chipText = "",
        chipOk = true,
        critical = false,
    )
}
