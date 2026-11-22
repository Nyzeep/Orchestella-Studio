package io.github.nyzeep.orchestella.ui.oe3.motion

import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W2-06（A4-P4 / M5）：列表项重排与增删动效统一到共享令牌规格。
 *
 * 验证标准档 [Oe3Motion.standard] 严格对齐 [OeMotion.EDIT_APPEAR_MS] /
 * [OeMotion.EDIT_REORDER_SETTLE_MS] 与对应缓动曲线，减动效 [Oe3Motion.reduced]
 * 下三者时长全部归零。
 */
class Oe3ListItemMotionSpecTest {

    @Test
    fun standardModeUsesTokenizedDurationsAndEasings() {
        val standard = Oe3Motion.standard()
        val fadeIn = Oe3ListItemMotionSpec.fadeIn(standard)
        val placement = Oe3ListItemMotionSpec.placement(standard)
        val fadeOut = Oe3ListItemMotionSpec.fadeOut(standard)

        assertEquals(200, fadeIn.durationMillis)
        assertEquals(OeMotion.EDIT_APPEAR_MS, fadeIn.durationMillis)
        assertEquals(OeMotion.easeOutCubicEasing, fadeIn.easing)

        assertEquals(260, placement.durationMillis)
        assertEquals(OeMotion.EDIT_REORDER_SETTLE_MS, placement.durationMillis)
        assertEquals(OeMotion.standardEasing, placement.easing)

        assertEquals(200, fadeOut.durationMillis)
        assertEquals(OeMotion.EDIT_APPEAR_MS, fadeOut.durationMillis)
        assertEquals(OeMotion.easeOutCubicEasing, fadeOut.easing)
    }

    @Test
    fun reducedMotionZeroesAllListItemDurations() {
        val reduced = Oe3Motion.reduced()
        assertEquals(0, Oe3ListItemMotionSpec.fadeIn(reduced).durationMillis)
        assertEquals(0, Oe3ListItemMotionSpec.placement(reduced).durationMillis)
        assertEquals(0, Oe3ListItemMotionSpec.fadeOut(reduced).durationMillis)
    }
}
