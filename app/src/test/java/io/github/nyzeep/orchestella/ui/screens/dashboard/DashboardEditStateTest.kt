package io.github.nyzeep.orchestella.ui.screens.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-09 红绿切片 1：编辑态模型的纯 JVM 契约。
 *
 * 卡片顺序 + 隐藏集合 + 编辑开关三条状态线，以及「隐藏 ≠ 删除」的结构保证：
 * 顺序列表永远持有全部卡片身份，隐藏只是遮罩，恢复必须回到原槽位。
 */
class DashboardEditStateTest {

    /** 无持久层的内存态：只验证模型逻辑，落盘行为在 DashboardEditPreferencesTest。 */
    private fun state(): DashboardEditState = DashboardEditState(store = null)

    @Test
    fun defaultOrderMatchesTheCatalogAndNothingIsHidden() {
        val edit = state()

        assertEquals(DashboardCardId.entries.toList(), edit.order)
        assertEquals(DashboardCardId.entries.toList(), edit.visible)
        assertTrue(edit.hidden.isEmpty())
        assertFalse(edit.isEditing)
    }

    @Test
    fun hideRemovesFromVisibleButKeepsTheCardInOrder() {
        val edit = state().apply { setEditing(true) }

        edit.hide(DashboardCardId.Storage)

        assertEquals(
            listOf(DashboardCardId.Primary, DashboardCardId.Metrics, DashboardCardId.Recent),
            edit.visible,
        )
        assertTrue("隐藏不是删除：顺序表仍持有该卡", edit.order.contains(DashboardCardId.Storage))
        assertEquals(setOf(DashboardCardId.Storage), edit.hidden.toSet())
    }

    @Test
    fun restoreReturnsTheCardToItsPreviousSlot() {
        val edit = state().apply { setEditing(true) }
        edit.hide(DashboardCardId.Storage)

        edit.restore(DashboardCardId.Storage)

        assertEquals(DashboardCardId.entries.toList(), edit.visible)
        assertTrue(edit.hidden.isEmpty())
    }

    @Test
    fun moveVisibleSwapsSlotsOfTheVisibleList() {
        val edit = state().apply { setEditing(true) }

        edit.moveVisible(fromVisibleIndex = 0, toVisibleIndex = 1)

        assertEquals(
            listOf(DashboardCardId.Storage, DashboardCardId.Primary, DashboardCardId.Metrics, DashboardCardId.Recent),
            edit.visible,
        )
    }

    @Test
    fun moveVisibleCountsOnlyVisibleSlots() {
        val edit = state().apply { setEditing(true) }
        edit.hide(DashboardCardId.Primary)
        // 可见列表 [Storage, Metrics, Recent]；把 Storage 拖到末尾。

        edit.moveVisible(fromVisibleIndex = 0, toVisibleIndex = 2)

        assertEquals(listOf(DashboardCardId.Metrics, DashboardCardId.Recent, DashboardCardId.Storage), edit.visible)
        assertTrue("被隐藏的卡不参与拖拽重排", edit.hidden.contains(DashboardCardId.Primary))
    }

    @Test
    fun mutationsAreNoOpsOutsideEditMode() {
        val edit = state()

        edit.moveVisible(0, 1)
        edit.hide(DashboardCardId.Recent)

        assertEquals(DashboardCardId.entries.toList(), edit.visible)
        assertTrue(edit.hidden.isEmpty())
    }

    @Test
    fun outOfRangeMoveIsIgnored() {
        val edit = state().apply { setEditing(true) }

        edit.moveVisible(-1, 0)
        edit.moveVisible(0, edit.visible.size)
        edit.moveVisible(2, 2)

        assertEquals(DashboardCardId.entries.toList(), edit.visible)
    }

    @Test
    fun editingFlagIsInMemoryOnlyAndFreshStatesStartClosed() {
        val store = FakeDashboardEditStore()
        DashboardEditState(store).apply { setEditing(true) }

        assertFalse("编辑开关不落盘：重进页面永远是浏览态", DashboardEditState(store).isEditing)
    }
}
