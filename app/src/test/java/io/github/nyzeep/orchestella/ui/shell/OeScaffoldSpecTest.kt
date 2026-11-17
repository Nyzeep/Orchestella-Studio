package io.github.nyzeep.orchestella.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UI-02 纯函数切片：顶栏动作槽折叠规则与滚动联动的背景透明度。
 *
 * 这些规则原样被 [OeScaffold] 消费，所以不需要 Robolectric 就能把
 * 「最多 3 个动作」「12dp 位移内 alpha 0→1」两件事钉住。
 */
class OeScaffoldSpecTest {

    @Test
    fun atMostThreeActionsStayDirectlyInTheBar() {
        assertEquals(0, OeScaffoldSpec.directCount(0))
        assertEquals(2, OeScaffoldSpec.directCount(2))
        assertEquals(3, OeScaffoldSpec.directCount(3))
        assertEquals(0, OeScaffoldSpec.overflowCount(3))
    }

    @Test
    fun extraActionsFoldIntoTheOverflowMenuAndKeepTheMoreButtonVisible() {
        // 4 个动作：2 个直连 + 溢出菜单（含 More 仍是 3 个可见槽位）。
        assertEquals(2, OeScaffoldSpec.directCount(4))
        assertEquals(2, OeScaffoldSpec.overflowCount(4))
        // 10 个动作也不撑破顶栏。
        assertEquals(2, OeScaffoldSpec.directCount(10))
        assertEquals(8, OeScaffoldSpec.overflowCount(10))
        assertEquals(
            OeScaffoldSpec.MAX_VISIBLE_ACTIONS,
            OeScaffoldSpec.directCount(10) + 1,
        )
    }

    @Test
    fun barAlphaRampsFromTransparentToSurfaceWithinTheCollapseDistance() {
        assertEquals(0f, OeScaffoldSpec.barAlpha(offsetPx = 0f, collapsePx = 12f), 0.0001f)
        assertEquals(0.5f, OeScaffoldSpec.barAlpha(offsetPx = 6f, collapsePx = 12f), 0.0001f)
        assertEquals(1f, OeScaffoldSpec.barAlpha(offsetPx = 12f, collapsePx = 12f), 0.0001f)
        // 越界夹紧：滚动再远也只到不透明，回弹到负数也不该变负。
        assertEquals(1f, OeScaffoldSpec.barAlpha(offsetPx = 99f, collapsePx = 12f), 0.0001f)
        assertEquals(0f, OeScaffoldSpec.barAlpha(offsetPx = -5f, collapsePx = 12f), 0.0001f)
        // 塌缩距离为 0（或未测量）时直接给不透明，避免除零。
        assertEquals(1f, OeScaffoldSpec.barAlpha(offsetPx = 3f, collapsePx = 0f), 0.0001f)
    }

    @Test
    fun scrollDeltaAdvancesTheOffsetAndStopsAtBothEnds() {
        assertEquals(3f, OeScaffoldSpec.advance(offsetPx = 0f, availablePx = -3f, collapsePx = 12f), 0.0001f)
        // 上滑超过阈值：停在 12dp，不继续累积。
        assertEquals(12f, OeScaffoldSpec.advance(offsetPx = 10f, availablePx = -5f, collapsePx = 12f), 0.0001f)
        // 下滑回到顶部：清零并且不越过 0。
        assertEquals(0f, OeScaffoldSpec.advance(offsetPx = 3f, availablePx = 4f, collapsePx = 12f), 0.0001f)
        assertEquals(0f, OeScaffoldSpec.advance(offsetPx = 0f, availablePx = 30f, collapsePx = 12f), 0.0001f)
        assertEquals(0f, OeScaffoldSpec.advance(offsetPx = 0f, availablePx = -30f, collapsePx = 0f), 0.0001f)
    }
}
