package io.github.nyzeep.orchestella.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W2-05 顶栏折叠滞回（纯 JVM）：56dp 进入折叠、24dp 之内展开，中间 32dp 是
 * 双向都不翻转的滞回带。连续阈值在临界滚动位置会被 1px 抖动反复打翻（修饰符
 * 树反复重建，R-02 抖动源），滞回把两次翻转的最小间隔钉死为整个带宽。
 */
class OeScaffoldCollapseHysteresisTest {

    /** 与 OeScaffoldSpec 语义常量对应的测试像素值（56dp / 24dp 等效 px）。 */
    private val enterPx = 56f
    private val exitPx = 24f

    @Test
    fun enterThresholdCollapsesAtOrPast56dp() {
        // 展开态：位移不足进入阈值不折叠。
        assertFalse(OeScaffoldSpec.nextCollapsed(false, 0f, enterPx, exitPx))
        assertFalse(OeScaffoldSpec.nextCollapsed(false, 25f, enterPx, exitPx))
        assertFalse(OeScaffoldSpec.nextCollapsed(false, 55.9f, enterPx, exitPx))
        // >= enterPx 即折叠（恰好落在阈值上也折叠）。
        assertTrue(OeScaffoldSpec.nextCollapsed(false, 56f, enterPx, exitPx))
        assertTrue(OeScaffoldSpec.nextCollapsed(false, 120f, enterPx, exitPx))
    }

    @Test
    fun exitThresholdExpandsBackWithin24dp() {
        // 折叠态：必须回到退出阈值之内（offsetPx <= exitPx）才展开。
        assertTrue(OeScaffoldSpec.nextCollapsed(true, 24.1f, enterPx, exitPx))
        assertTrue(OeScaffoldSpec.nextCollapsed(true, 40f, enterPx, exitPx))
        // 恰好回到阈值上即展开。
        assertFalse(OeScaffoldSpec.nextCollapsed(true, 24f, enterPx, exitPx))
        assertFalse(OeScaffoldSpec.nextCollapsed(true, 0f, enterPx, exitPx))
    }

    @Test
    fun hysteresisBandIsBidirectionallyStable() {
        // 25..55dp 滞回带内双向都不翻转：展开态不被拖进折叠，折叠态不被拖回展开。
        for (v in 25..55) {
            assertFalse(
                "展开态在滞回带内 v=$v 不得折叠",
                OeScaffoldSpec.nextCollapsed(false, v.toFloat(), enterPx, exitPx),
            )
            assertTrue(
                "折叠态在滞回带内 v=$v 不得展开",
                OeScaffoldSpec.nextCollapsed(true, v.toFloat(), enterPx, exitPx),
            )
        }
    }

    @Test
    fun borderJitterDoesNotOscillate() {
        // 进入沿抖动负例：55/56 反复注入，只在首个 56 处折叠一次，之后不再翻转。
        var collapsed = false
        var flips = 0
        for (v in listOf(55f, 56f, 55f, 56f, 55f, 56f, 56f)) {
            val next = OeScaffoldSpec.nextCollapsed(collapsed, v, enterPx, exitPx)
            if (next != collapsed) flips++
            collapsed = next
        }
        assertEquals("进入沿临界抖动必须只翻转一次", 1, flips)
        assertTrue(collapsed)

        // 退出沿抖动负例：折叠态下 24/25 反复注入，只在首个 24 处展开一次。
        collapsed = true
        flips = 0
        for (v in listOf(25f, 24f, 25f, 24f, 25f, 24f)) {
            val next = OeScaffoldSpec.nextCollapsed(collapsed, v, enterPx, exitPx)
            if (next != collapsed) flips++
            collapsed = next
        }
        assertEquals("退出沿临界抖动必须只翻转一次", 1, flips)
        assertFalse(collapsed)
    }

    @Test
    fun flippingBackRequiresCrossingTheWholeBand() {
        // 全程往返 0 → 56 → 40 → 24 → 0：状态只在两个阈值处各翻转一次，两次
        // 翻转位置相距整个滞回带（56dp 折叠、回到 24dp 才展开）。
        var collapsed = false
        val flipOffsets = mutableListOf<Float>()
        for (v in listOf(0f, 40f, 55f, 56f, 56f, 40f, 25f, 24f, 0f)) {
            val next = OeScaffoldSpec.nextCollapsed(collapsed, v, enterPx, exitPx)
            if (next != collapsed) {
                flipOffsets += v
                collapsed = next
            }
        }
        assertEquals(listOf(56f, 24f), flipOffsets)
        assertFalse("回到顶部必须回到展开态", collapsed)
    }

    @Test
    fun scrolledPastFirstItemCollapsesImmediately() {
        // `oeScrollOffsetPx()` 滚过首项返回 Int.MAX_VALUE：Float 化仍大于任何
        // enterPx，展开态自然折叠、折叠态保持折叠，无需特判。
        val maxOffset = Int.MAX_VALUE.toFloat()
        assertTrue(OeScaffoldSpec.nextCollapsed(false, maxOffset, enterPx, exitPx))
        assertTrue(OeScaffoldSpec.nextCollapsed(true, maxOffset, enterPx, exitPx))
    }

    @Test
    fun exitClampedToEnterDegradesToSingleThresholdWithoutOscillation() {
        // 调用侧 coerceIn(0f, enterPx) 的退化语义：exitPx > enterPx 时被钳到
        // enterPx，滞回带宽度归零、退化为单阈值开关——单侧规则仍然成立，
        // 不会出现同一位置双向矛盾。
        val enter = 10f
        val clampedExit = 20f.coerceIn(0f, enter)
        assertEquals(10f, clampedExit, 0f)
        // 折叠态：阈值之内（<= exitPx）展开。
        assertFalse(OeScaffoldSpec.nextCollapsed(true, 10f, enter, clampedExit))
        assertFalse(OeScaffoldSpec.nextCollapsed(true, 9.5f, enter, clampedExit))
        // 折叠态：阈值之上保持折叠。
        assertTrue(OeScaffoldSpec.nextCollapsed(true, 10.5f, enter, clampedExit))
        // 展开态：阈值之上折叠。
        assertTrue(OeScaffoldSpec.nextCollapsed(false, 10f, enter, clampedExit))
        assertFalse(OeScaffoldSpec.nextCollapsed(false, 9.5f, enter, clampedExit))
    }
}
