package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.component.PrismLevel
import com.yzddmr6.prismspace.prism.compose.vm.HomePrimaryAction
import com.yzddmr6.prismspace.prism.compose.vm.SpaceHealth
import com.yzddmr6.prismspace.prism.compose.vm.mapHome
import com.yzddmr6.prismspace.prism.compose.vm.overviewLabelsLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMapTest {

    // Fake string resolver — maps the lz_home_ resource IDs to their Chinese
    // values so the existing (Chinese) assertions stay meaningful without a
    // real Android Context. Unknown IDs fall back to "".
    private val resolve: (Int) -> String = { id ->
        when (id) {
            R.string.lz_home_status_normal_title -> "双开空间正常"
            R.string.lz_home_status_normal_body -> "双开空间运行正常，可正常使用。"
            R.string.lz_home_tag_normal -> "正常"
            R.string.lz_home_label_add_app -> "添加应用"
            R.string.lz_home_status_notcreated_title -> "双开空间未创建"
            R.string.lz_home_status_notcreated_body -> "请前往设置创建双开空间。"
            R.string.lz_home_tag_notcreated -> "未创建"
            R.string.lz_home_label_create -> "创建双开空间"
            R.string.lz_home_status_suspended_title -> "双开空间已暂停"
            R.string.lz_home_status_suspended_body -> "双开空间已暂停，分身应用暂不可用。"
            R.string.lz_home_tag_suspended -> "已暂停"
            R.string.lz_home_label_restore -> "恢复双开空间"
            R.string.lz_home_status_locked_title -> "双开空间已锁定"
            R.string.lz_home_status_locked_body -> "双开空间需先解锁（输入锁屏密码）后才能使用分身。"
            R.string.lz_home_tag_locked -> "未解锁"
            R.string.lz_home_label_unlock -> "前往解锁"
            R.string.lz_home_status_checking_title -> "正在检查双开空间"
            R.string.lz_home_status_checking_body -> "正在确认主空间与双开空间的连接状态。"
            R.string.lz_home_tag_checking -> "检查中"
            R.string.lz_home_label_checking -> "打开设置"
            R.string.lz_home_status_needsrepair_title -> "双开空间需要修复"
            R.string.lz_home_status_needsrepair_body -> "上次检测发现问题，请前往设置修复。"
            R.string.lz_home_tag_needsrepair -> "需要修复"
            R.string.lz_home_label_repair -> "修复双开空间"
            else -> ""
        }
    }

    @Test
    fun `Normal - level Ok, tag 正常, primaryLabel 添加应用, route ADD_APP`() {
        val model = mapHome(SpaceHealth.Normal, mainCount = 10, cloneCount = 3, resolve = resolve)
        assertEquals(PrismLevel.Ok, model.level)
        assertEquals("正常", model.tag)
        assertEquals("添加应用", model.primaryLabel)
        assertEquals(HomePrimaryAction.OpenSpace, model.primaryAction)
        assertNull(model.primaryRoute)
        assertEquals(10, model.mainCount)
        assertEquals(3, model.cloneCount)
    }

    @Test
    fun `NotCreated - level Error, tag 未创建, primaryLabel 创建双开空间, route null`() {
        val model = mapHome(SpaceHealth.NotCreated, mainCount = 5, cloneCount = 0, resolve = resolve)
        assertEquals(PrismLevel.Error, model.level)
        assertEquals("未创建", model.tag)
        assertEquals("创建双开空间", model.primaryLabel)
        assertEquals(HomePrimaryAction.StartSetup, model.primaryAction)
        assertNull(model.primaryRoute)
        assertEquals(5, model.mainCount)
        assertEquals(0, model.cloneCount)
    }

    @Test
    fun `Suspended - level Warn, tag 已暂停, primaryLabel 恢复双开空间, route null`() {
        val model = mapHome(SpaceHealth.Suspended, mainCount = 7, cloneCount = 2, resolve = resolve)
        assertEquals(PrismLevel.Warn, model.level)
        assertEquals("已暂停", model.tag)
        assertEquals("恢复双开空间", model.primaryLabel)
        assertEquals(HomePrimaryAction.ActivateSpace, model.primaryAction)
        assertNull(model.primaryRoute)
        assertEquals(7, model.mainCount)
        assertEquals(2, model.cloneCount)
    }

    @Test
    fun `Locked - level Warn, status title locked, tag 未解锁`() {
        val model = mapHome(SpaceHealth.Locked, mainCount = 7, cloneCount = 2, resolve = resolve)
        assertEquals("双开空间已锁定", model.statusTitle)
        assertEquals("未解锁", model.tag)
        assertEquals(PrismLevel.Warn, model.level)
    }

    @Test
    fun `Checking - level Neutral, tag 检查中, no repair wording`() {
        val model = mapHome(SpaceHealth.Checking, mainCount = 7, cloneCount = 2, resolve = resolve)
        assertEquals(PrismLevel.Neutral, model.level)
        assertEquals("正在检查双开空间", model.statusTitle)
        assertEquals("检查中", model.tag)
        assertEquals("打开设置", model.primaryLabel)
        assertEquals(HomePrimaryAction.OpenSettings, model.primaryAction)
    }

    @Test
    fun `Provisioning - level Neutral (in-progress is not a warning)`() {
        val model = mapHome(SpaceHealth.Provisioning, mainCount = 7, cloneCount = 2, resolve = resolve)
        assertEquals(PrismLevel.Neutral, model.level)
        assertEquals(HomePrimaryAction.OpenSettings, model.primaryAction)
    }

    @Test
    fun `status calm rule - Ok and Neutral render as quiet line, Warn and Error as card`() {
        // 状态安静原则：健康（Ok）与无偏向（Neutral）为一行安静文字；Warn/Error 才上状态卡。
        assertTrue(mapHome(SpaceHealth.Normal, 1, 1, resolve).calm)
        assertTrue(mapHome(SpaceHealth.Checking, 1, 1, resolve).calm)
        assertTrue(mapHome(SpaceHealth.Provisioning, 1, 1, resolve).calm)
        assertTrue(!mapHome(SpaceHealth.Suspended, 1, 1, resolve).calm)
        assertTrue(!mapHome(SpaceHealth.NotCreated, 1, 1, resolve).calm)
        assertTrue(!mapHome(SpaceHealth.NeedsRepair, 1, 1, resolve).calm)
    }

    @Test
    fun `overview label line shares the avatar truncation and only ellipsizes beyond it`() {
        // 标签行与头像组同一截断口径：cloneCount ≤ 展示数时不出现省略号（4–5 个分身的边界）。
        assertEquals("甲、乙、丙、丁", overviewLabelsLine(listOf("甲", "乙", "丙", "丁"), 4))
        assertEquals("甲、乙、丙、丁、戊", overviewLabelsLine(listOf("甲", "乙", "丙", "丁", "戊"), 5))
        assertEquals("甲、乙、丙、丁、戊 …", overviewLabelsLine(listOf("甲", "乙", "丙", "丁", "戊"), 12))
    }

    @Test
    fun `NeedsRepair - level Error, tag 需要修复, primaryLabel 修复双开空间, route null`() {
        val model = mapHome(SpaceHealth.NeedsRepair, mainCount = 0, cloneCount = 0, resolve = resolve)
        assertEquals(PrismLevel.Error, model.level)
        assertEquals("需要修复", model.tag)
        assertEquals("修复双开空间", model.primaryLabel)
        assertEquals(HomePrimaryAction.OpenSettings, model.primaryAction)
        assertNull(model.primaryRoute)
        assertEquals(0, model.mainCount)
        assertEquals(0, model.cloneCount)
    }
    @Test fun pausedAndLockedActionsRequestActivationRatherThanOnlyNavigation() {
        for (health in listOf(SpaceHealth.Suspended, SpaceHealth.Locked)) {
            assertEquals(HomePrimaryAction.ActivateSpace, mapHome(health, 10, 1, resolve).primaryAction)
        }
    }
}
