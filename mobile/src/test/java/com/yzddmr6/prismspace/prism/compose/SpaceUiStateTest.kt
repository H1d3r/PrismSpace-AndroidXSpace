package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.vm.SpaceAppInput4
import com.yzddmr6.prismspace.prism.compose.vm.SpaceSegment
import com.yzddmr6.prismspace.prism.compose.vm.SpaceUiState
import com.yzddmr6.prismspace.prism.compose.vm.mainAppIsCloned
import com.yzddmr6.prismspace.prism.compose.vm.mapSpaceRows
import com.yzddmr6.prismspace.prism.compose.space.PrismSpace
import com.yzddmr6.prismspace.prism.compose.space.PrismSpaceKind
import com.yzddmr6.prismspace.prism.compose.space.resolveSpaceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compat tests for the dual-profile stateText rules via mapSpaceRows().
 * The new authoritative tests live in SpaceMapTest.
 */
class SpaceUiStateTest {

    private val main = PrismSpace("main", 0, PrismSpaceKind.Main, "主空间")
    private val dual = PrismSpace("space_22", 22, PrismSpaceKind.Dual, "双开空间")

    @Test
    fun `main and dual spaces show system apps by default`() {
        val state = SpaceUiState()

        assertTrue(state.showSystem)
        assertTrue(state.showSystemDual)
    }

    @Test
    fun `dual segment falls back to main when no dual space exists`() {
        val selection = resolveSpaceSelection(SpaceSegment.Dual, "space_22", listOf(main))

        assertEquals(SpaceSegment.Main, selection.segment)
        assertEquals(null, selection.selectedDualSpaceId)
    }

    @Test
    fun `dual segment remains selected when its space exists`() {
        val selection = resolveSpaceSelection(SpaceSegment.Dual, "space_22", listOf(main, dual))

        assertEquals(SpaceSegment.Dual, selection.segment)
        assertEquals("space_22", selection.selectedDualSpaceId)
    }

    @Test
    fun `main segment preserves a valid dual selection for later`() {
        val selection = resolveSpaceSelection(SpaceSegment.Main, "space_22", listOf(main, dual))

        assertEquals(SpaceSegment.Main, selection.segment)
        assertEquals("space_22", selection.selectedDualSpaceId)
    }

    @Test
    fun `stale dual selection resolves to the first real dual space`() {
        val selection = resolveSpaceSelection(SpaceSegment.Dual, "space_99", listOf(main, dual))

        assertEquals(SpaceSegment.Dual, selection.segment)
        assertEquals("space_22", selection.selectedDualSpaceId)
    }

    @Test
    fun `system package presence alone is not an explicit clone`() {
        assertEquals(false, mainAppIsCloned(isSystem = true, installedInDual = true, systemCloneMarked = false))
    }

    @Test
    fun `explicitly marked system package is cloned`() {
        assertEquals(true, mainAppIsCloned(isSystem = true, installedInDual = true, systemCloneMarked = true))
    }

    @Test
    fun `third party clone follows dual installation fact`() {
        assertEquals(true, mainAppIsCloned(isSystem = false, installedInDual = true, systemCloneMarked = false))
        assertEquals(false, mainAppIsCloned(isSystem = false, installedInDual = false, systemCloneMarked = true))
    }

    @Test
    fun `frozen dual app shows 已冻结`() {
        val input = listOf(SpaceAppInput4(pkg = "com.example.app", label = "My App", frozen = true, launchable = false))
        val rows = mapSpaceRows(input)
        assertEquals(1, rows.size)
        assertEquals("已冻结", rows[0].chipText)
    }

    @Test
    fun `non-frozen dual app shows 运行中`() {
        val input = listOf(SpaceAppInput4(pkg = "com.example.nolauncher", label = "No Launcher", frozen = false, launchable = false))
        val rows = mapSpaceRows(input)
        assertEquals(1, rows.size)
        assertEquals("运行中", rows[0].chipText)
    }

    @Test
    fun `normal dual app shows 运行中`() {
        val input = listOf(SpaceAppInput4(pkg = "com.example.normal", label = "Normal App", frozen = false, launchable = true))
        val rows = mapSpaceRows(input)
        assertEquals(1, rows.size)
        assertEquals("运行中", rows[0].chipText)
    }

    @Test
    fun `label and pkg pass through correctly`() {
        val input = listOf(SpaceAppInput4(pkg = "com.example.passthrough", label = "Pass Through", frozen = false, launchable = true))
        val rows = mapSpaceRows(input)
        assertEquals(1, rows.size)
        assertEquals("com.example.passthrough", rows[0].pkg)
        assertEquals("Pass Through", rows[0].label)
    }

    @Test
    fun `frozen takes priority over not-launchable`() {
        val input = listOf(SpaceAppInput4(pkg = "com.example.frozennolaunch", label = "Frozen No Launch", frozen = true, launchable = false))
        val rows = mapSpaceRows(input)
        assertEquals(1, rows.size)
        assertEquals("已冻结", rows[0].chipText)
    }
}
