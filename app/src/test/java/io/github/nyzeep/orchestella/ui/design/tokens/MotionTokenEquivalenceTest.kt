package io.github.nyzeep.orchestella.ui.design.tokens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import io.github.nyzeep.orchestella.ui.design.components.OeSliderSpec
import io.github.nyzeep.orchestella.ui.motion.OrchestellaMotion
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import io.github.nyzeep.orchestella.ui.oe3.tokens.OeSpring
import io.github.nyzeep.orchestella.ui.shell.nav.Oe3NavPillSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * UI-01: the two motion vocabularies (Compose `Oe3Motion` and the View-system
 * `OrchestellaMotion`) now share one source, [OeMotion]. Every number is
 * pinned independently here, so merging the sources can never silently retune
 * an animation — the values are identical before and after the merge.
 */
class MotionTokenEquivalenceTest {

    @Test
    fun composeScaleDurationsArePinnedInTheSingleSource() {
        assertEquals(400, OeMotion.NAV_SELECTION_MS)
        assertEquals(250, OeMotion.PRESS_MS)
        assertEquals(220, OeMotion.PAGE_FADE_MS)
        assertEquals(300, OeMotion.PAGE_SLIDE_MS)
        assertEquals(300, OeMotion.CARD_ENTER_MS)
        assertEquals(250, OeMotion.DIALOG_MS)
        assertEquals(300, OeMotion.THEME_TWEEN_MS)
    }

    @Test
    fun pagePushValuesArePinnedInTheSingleSource() {
        // v4 spec §1.5：spring(300ms, bounce 0) + 初速 9×距离/s；前一页缩至
        // 80% 并压暗 15%（UI-03 切片 2）。数值只落在 [OeMotion] 一处。
        assertEquals(300, OeMotion.PAGE_PUSH_MS)
        assertEquals(1f, OeMotion.PAGE_PUSH_DAMPING, 0.0001f)
        assertEquals(9f, OeMotion.PAGE_PUSH_VELOCITY_FACTOR, 0.0001f)
        assertEquals(0.8f, OeMotion.PAGE_PUSH_UNDER_SCALE, 0.0001f)
        assertEquals(0.15f, OeMotion.PAGE_PUSH_UNDER_DIM, 0.0001f)
        assertEquals(1f - OeMotion.PAGE_PUSH_UNDER_SCALE, OeMotion.PAGE_PUSH_SCALE_DELTA, 0.0001f)
    }

    @Test
    fun composeSpringAndEntranceValuesArePinnedInTheSingleSource() {
        assertEquals(0.85f, OeMotion.SPRING_DAMPING, 0.0001f)
        assertEquals(Spring.StiffnessMediumLow, OeMotion.NAV_SELECTION_STIFFNESS, 0.0001f)
        assertEquals(0.96f, OeMotion.CARD_ENTER_SCALE_FROM, 0.0001f)
        assertEquals(1f, OeMotion.REDUCED_CARD_ENTER_SCALE_FROM, 0.0001f)
    }

    @Test
    fun oe3MotionBuildsItsTokensFromTheSharedSource() {
        val standard = Oe3Motion.standard()
        assertEquals(OeMotion.NAV_SELECTION_MS, standard.navSelection.durationMillis)
        assertEquals(OeMotion.PRESS_MS, standard.press.durationMillis)
        assertEquals(OeMotion.PAGE_FADE_MS, standard.pageFade.durationMillis)
        assertEquals(OeMotion.PAGE_SLIDE_MS, standard.pageSlide.durationMillis)
        assertEquals(OeMotion.CARD_ENTER_MS, standard.cardEnter.durationMillis)
        assertEquals(OeMotion.DIALOG_MS, standard.dialog.durationMillis)
        assertEquals(OeMotion.THEME_TWEEN_MS, standard.themeTween.durationMillis)

        assertEquals(OeMotion.SPRING_DAMPING, standard.navSelection.dampingRatio, 0.0001f)
        assertEquals(OeMotion.SPRING_DAMPING, standard.press.dampingRatio, 0.0001f)
        assertEquals(OeMotion.CARD_ENTER_SCALE_FROM, standard.cardEnterScaleFrom, 0.0001f)
        assertEquals(OeMotion.NAV_SELECTION_STIFFNESS, standard.navSelectionSpring!!.stiffness, 0.0001f)
        assertEquals(OeMotion.SPRING_DAMPING, standard.navSelectionSpring!!.dampingRatio, 0.0001f)
    }

    @Test
    fun oe3MotionUsesTheSharedEasingInstances() {
        val standard = Oe3Motion.standard()
        assertSame(OeMotion.standardEasing, standard.navSelection.easing)
        assertSame(OeMotion.standardEasing, standard.themeTween.easing)
        assertSame(OeMotion.decelerateEasing, standard.press.easing)
        assertSame(OeMotion.decelerateEasing, standard.pageSlide.easing)
        assertSame(OeMotion.decelerateEasing, standard.cardEnter.easing)
        assertSame(OeMotion.easeInOutEasing, standard.pageFade.easing)
        assertSame(OeMotion.easeOutCubicEasing, standard.dialog.easing)
    }

    @Test
    fun easingControlPointsArePinnedInTheSingleSource() {
        assertEquals(0.2f, OeMotion.STANDARD_X1, 0.0001f)
        assertEquals(0f, OeMotion.STANDARD_Y1, 0.0001f)
        assertEquals(0f, OeMotion.STANDARD_X2, 0.0001f)
        assertEquals(1f, OeMotion.STANDARD_Y2, 0.0001f)

        assertEquals(0.4f, OeMotion.EASE_IN_OUT_X1, 0.0001f)
        assertEquals(0f, OeMotion.EASE_IN_OUT_Y1, 0.0001f)
        assertEquals(0.2f, OeMotion.EASE_IN_OUT_X2, 0.0001f)
        assertEquals(1f, OeMotion.EASE_IN_OUT_Y2, 0.0001f)

        assertEquals(0.215f, OeMotion.EASE_OUT_CUBIC_X1, 0.0001f)
        assertEquals(0.61f, OeMotion.EASE_OUT_CUBIC_Y1, 0.0001f)
        assertEquals(0.355f, OeMotion.EASE_OUT_CUBIC_X2, 0.0001f)
        assertEquals(1f, OeMotion.EASE_OUT_CUBIC_Y2, 0.0001f)

        assertEquals(0.05f, OeMotion.EMPHASIZED_DECELERATE_X1, 0.0001f)
        assertEquals(0.7f, OeMotion.EMPHASIZED_DECELERATE_Y1, 0.0001f)
        assertEquals(0.1f, OeMotion.EMPHASIZED_DECELERATE_X2, 0.0001f)
        assertEquals(1f, OeMotion.EMPHASIZED_DECELERATE_Y2, 0.0001f)

        assertEquals(0.3f, OeMotion.STANDARD_ACCELERATE_X1, 0.0001f)
        assertEquals(0f, OeMotion.STANDARD_ACCELERATE_Y1, 0.0001f)
        assertEquals(1f, OeMotion.STANDARD_ACCELERATE_X2, 0.0001f)
        assertEquals(1f, OeMotion.STANDARD_ACCELERATE_Y2, 0.0001f)
    }

    @Test
    fun viewSystemDurationsArePinnedInTheSingleSource() {
        assertEquals(300, OeMotion.VIEW_ENTER_MS)
        assertEquals(150, OeMotion.VIEW_EXIT_MS)
        assertEquals(210, OeMotion.VIEW_ACTIVITY_ENTER_MS)
        assertEquals(500, OeMotion.VIEW_CONTAINER_TRANSFORM_MS)
        assertEquals(250, OeMotion.VIEW_DIALOG_ENTER_MS)
        assertEquals(150, OeMotion.VIEW_DIALOG_EXIT_MS)
        assertEquals(300, OeMotion.VIEW_LIST_ITEM_ENTER_MS)
        assertEquals(250, OeMotion.VIEW_LIST_ITEM_REORDER_MS)
        assertEquals(100, OeMotion.VIEW_PRESS_IN_MS)
        assertEquals(150, OeMotion.VIEW_PRESS_OUT_MS)
        assertEquals(1000, OeMotion.VIEW_BREATH_CYCLE_MS)
    }

    @Test
    fun viewSystemStaggerAndRiseTokensArePinnedInTheSingleSource() {
        assertEquals(40, OeMotion.VIEW_STAGGER_LIST_ITEM_MS)
        assertEquals(5, OeMotion.VIEW_STAGGER_LIST_MAX_STEPS)
        assertEquals(45, OeMotion.VIEW_STAGGER_SECTION_MS)
        assertEquals(0.02f, OeMotion.VIEW_RISE_FRACTION_STATE, 0.0001f)
        assertEquals(0.04f, OeMotion.VIEW_RISE_FRACTION_LIST, 0.0001f)
        assertEquals(0.975f, OeMotion.VIEW_SCALE_FROM_DASHBOARD, 0.0001f)
    }

    @Test
    fun orchestellaMotionDelegatesEveryConstantToTheSingleSource() {
        assertEquals(OeMotion.VIEW_ENTER_MS, OrchestellaMotion.DURATION_ENTER_MS)
        assertEquals(OeMotion.VIEW_EXIT_MS, OrchestellaMotion.DURATION_EXIT_MS)
        assertEquals(OeMotion.VIEW_ACTIVITY_ENTER_MS, OrchestellaMotion.DURATION_ACTIVITY_ENTER_MS)
        assertEquals(
            OeMotion.VIEW_CONTAINER_TRANSFORM_MS,
            OrchestellaMotion.DURATION_CONTAINER_TRANSFORM_MS,
        )
        assertEquals(OeMotion.VIEW_DIALOG_ENTER_MS, OrchestellaMotion.DURATION_DIALOG_ENTER_MS)
        assertEquals(OeMotion.VIEW_DIALOG_EXIT_MS, OrchestellaMotion.DURATION_DIALOG_EXIT_MS)
        assertEquals(OeMotion.VIEW_LIST_ITEM_ENTER_MS, OrchestellaMotion.DURATION_LIST_ITEM_ENTER_MS)
        assertEquals(OeMotion.VIEW_LIST_ITEM_REORDER_MS, OrchestellaMotion.DURATION_LIST_ITEM_REORDER_MS)
        assertEquals(OeMotion.VIEW_PRESS_IN_MS, OrchestellaMotion.DURATION_PRESS_IN_MS)
        assertEquals(OeMotion.VIEW_PRESS_OUT_MS, OrchestellaMotion.DURATION_PRESS_OUT_MS)
        assertEquals(OeMotion.VIEW_BREATH_CYCLE_MS, OrchestellaMotion.DURATION_BREATH_CYCLE_MS)
        assertEquals(OeMotion.VIEW_STAGGER_LIST_ITEM_MS, OrchestellaMotion.STAGGER_LIST_ITEM_MS)
        assertEquals(OeMotion.VIEW_STAGGER_LIST_MAX_STEPS, OrchestellaMotion.STAGGER_LIST_MAX_STEPS)
        assertEquals(OeMotion.VIEW_STAGGER_SECTION_MS, OrchestellaMotion.STAGGER_SECTION_MS)
    }

    @Test
    fun reducedMotionStillCollapsesEveryDurationToZero() {
        val reduced = Oe3Motion.reduced()
        listOf(
            reduced.navSelection,
            reduced.press,
            reduced.pageFade,
            reduced.pageSlide,
            reduced.cardEnter,
            reduced.dialog,
            reduced.themeTween,
        ).forEach { spec -> assertEquals(0, spec.durationMillis) }
        assertEquals(OeMotion.REDUCED_CARD_ENTER_SCALE_FROM, reduced.cardEnterScaleFrom, 0.0001f)
        assertNull(reduced.navSelectionSpring)
    }

    // -----------------------------------------------------------------------
    // W1-02（A4-M1）：motion v2 分档标尺、强调曲线与弹簧词表。数字唯一落在
    // [OeMotion]；OeDuration/OeEase（design/tokens/Motion.kt）与 OeSpring
    // （oe3/tokens/Oe3Motion.kt）是命名视图，这里逐项钉死，防止再次长出
    // 第二事实源。
    // -----------------------------------------------------------------------

    @Test
    fun motionV2DurationLadderIsPinnedInTheSingleSource() {
        assertEquals(120, OeMotion.DURATION_MICRO_MS)
        assertEquals(200, OeMotion.DURATION_SHORT_MS)
        assertEquals(320, OeMotion.DURATION_MEDIUM_MS)
        assertEquals(480, OeMotion.DURATION_LONG_MS)
        assertEquals(4_000, OeMotion.GLOW_CYCLE_MS)

        assertEquals(OeMotion.DURATION_MICRO_MS, OeDuration.Micro)
        assertEquals(OeMotion.DURATION_SHORT_MS, OeDuration.Short)
        assertEquals(OeMotion.DURATION_MEDIUM_MS, OeDuration.Medium)
        assertEquals(OeMotion.DURATION_LONG_MS, OeDuration.Long)
        assertEquals(OeMotion.GLOW_CYCLE_MS, OeDuration.GlowCycle)
    }

    @Test
    fun motionV2EmphasizedAccelerateControlPointsArePinned() {
        assertEquals(0.3f, OeMotion.EMPHASIZED_ACCELERATE_X1, 0.0001f)
        assertEquals(0f, OeMotion.EMPHASIZED_ACCELERATE_Y1, 0.0001f)
        assertEquals(0.8f, OeMotion.EMPHASIZED_ACCELERATE_X2, 0.0001f)
        assertEquals(0.15f, OeMotion.EMPHASIZED_ACCELERATE_Y2, 0.0001f)
    }

    @Test
    fun oeEaseSharesTheSingleSourceInstances() {
        assertSame(OeMotion.standardEasing, OeEase.Standard)
        assertSame(LinearEasing, OeEase.Linear)
        // 强调曲线实例是独立构造的 CubicBezierEasing；控制点已在上面的钉值
        // 用例锁定，这里只验证它们是 OeMotion 控制点常量的命名视图（类型）。
        assertNotNull(OeEase.EmphasizedDecelerate)
        assertNotNull(OeEase.EmphasizedAccelerate)
    }

    @Test
    fun motionV2PressScalesArePinnedInTheSingleSource() {
        assertEquals(0.95f, OeMotion.CONTROL_PRESS_SCALE, 0.0001f)
        assertEquals(0.96f, OeMotion.NAV_PRESS_SCALE, 0.0001f)
        assertEquals(0.92f, OeMotion.SLIDER_PRESS_SCALE, 0.0001f)
    }

    @Test
    fun secondSourceConstantsDelegateToTheSingleSource() {
        // OeSliderSpec 保留实测名号，但数字必须来自 OeMotion（W1-02 归口）。
        assertEquals(OeMotion.GLOW_CYCLE_MS, OeSliderSpec.GlowPeriodMillis)
        assertEquals(OeMotion.SLIDER_PRESS_SCALE, OeSliderSpec.PressedScale, 0.0001f)
        // 光斑几何 8 点 / 90dp 红线不动。
        assertEquals(8, OeSliderSpec.DotCount)
        assertEquals(90f, OeSliderSpec.GlowBandDp, 0.0001f)
    }

    @Test
    fun motionV2SpringParametersArePinnedInTheSingleSource() {
        assertEquals(Spring.StiffnessMedium, OeMotion.PRESS_STIFFNESS, 0.0001f)
        assertEquals(1f, OeMotion.SETTLE_DAMPING, 0.0001f)
        assertEquals(Spring.StiffnessMediumLow, OeMotion.SETTLE_STIFFNESS, 0.0001f)
        assertEquals(0.9f, OeMotion.EXPRESSIVE_DAMPING, 0.0001f)
        assertEquals(700f, OeMotion.EXPRESSIVE_STIFFNESS, 0.0001f)
    }

    @Test
    fun oeSpringBuildsItsSpecsFromTheSharedSource() {
        assertEquals(OeMotion.SPRING_DAMPING, OeSpring.Press.dampingRatio, 0.0001f)
        assertEquals(OeMotion.PRESS_STIFFNESS, OeSpring.Press.stiffness, 0.0001f)

        assertEquals(OeMotion.SPRING_DAMPING, OeSpring.Pill.dampingRatio, 0.0001f)
        assertEquals(OeMotion.NAV_SELECTION_STIFFNESS, OeSpring.Pill.stiffness, 0.0001f)

        assertEquals(OeMotion.SETTLE_DAMPING, OeSpring.Settle.dampingRatio, 0.0001f)
        assertEquals(OeMotion.SETTLE_STIFFNESS, OeSpring.Settle.stiffness, 0.0001f)

        assertEquals(OeMotion.EXPRESSIVE_DAMPING, OeSpring.Expressive.dampingRatio, 0.0001f)
        assertEquals(OeMotion.EXPRESSIVE_STIFFNESS, OeSpring.Expressive.stiffness, 0.0001f)

        val push = OeSpring.push()
        assertEquals(OeMotion.PAGE_PUSH_DAMPING, push.spec.dampingRatio, 0.0001f)
        assertEquals(OeMotion.PAGE_PUSH_STIFFNESS, push.spec.stiffness, 0.0001f)
        assertEquals(OeMotion.PAGE_PUSH_VELOCITY_FACTOR, push.initialVelocity, 0.0001f)
        assertEquals(2f, OeSpring.push(velocity = 2f).initialVelocity, 0.0001f)
    }

    @Test
    fun oe3ControlsAndNavBarConsumeTheV2Tokens() {
        // 组件文件不再私藏数字：Oe3NavPillSpec.PRESS_SCALE 是 OeMotion 的
        // 编译期别名；pressSpring 必须就是 OeSpring.Press 单例。
        assertEquals(OeMotion.NAV_PRESS_SCALE, Oe3NavPillSpec.PRESS_SCALE, 0.0001f)
        assertSame(OeSpring.Press, Oe3NavPillSpec.pressSpring(Oe3Motion.standard()))
    }
}
