package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.runtime.Immutable
import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion

/**
 * One motion rule. [dampingRatio] is non-zero only for spring-driven
 * transitions; [durationMillis] is then the design's nominal duration and the
 * spring is what actually settles.
 */
@Immutable
data class Oe3MotionSpec(
    val durationMillis: Int,
    val easing: Easing,
    val dampingRatio: Float = 0f,
)

@Immutable
data class Oe3MotionTokens(
    val navSelection: Oe3MotionSpec,
    val press: Oe3MotionSpec,
    val pageFade: Oe3MotionSpec,
    val pageSlide: Oe3MotionSpec,
    val cardEnter: Oe3MotionSpec,
    val dialog: Oe3MotionSpec,
    val themeTween: Oe3MotionSpec,
    /**
     * v4 (UI-03): page push, spring(300ms, bounce 0) nominally. The spring is
     * what settles; [Oe3MotionSpec.durationMillis] stays the outward number.
     */
    val pagePush: Oe3MotionSpec,
    /** Starting scale of the card entrance; 1.0 under reduced motion. */
    val cardEnterScaleFrom: Float,
    /** Null under reduced motion, so the pill lands instead of springing. */
    val navSelectionSpring: SpringSpec<Float>?,
    /**
     * Null under reduced motion, so a push lands instantly: the entering page
     * rests in place and the previous page keeps its scaled/dimmed final state.
     */
    val pagePushSpring: SpringSpec<Float>?,
)

/**
 * Legacy Compose entry point for the motion scale. Every number and curve comes
 * from [OeMotion] (`ui/design/tokens`, UI-01); this object only shapes them into
 * the tokens the shell consumes. [reduced] is the global "reduce motion"
 * result: every duration becomes 0 and the navigation spring is withdrawn, so
 * state changes land instantly while the token shape stays identical.
 */
object Oe3Motion {

    fun standard(): Oe3MotionTokens = Oe3MotionTokens(
        navSelection = Oe3MotionSpec(OeMotion.NAV_SELECTION_MS, OeMotion.standardEasing, dampingRatio = OeMotion.SPRING_DAMPING),
        press = Oe3MotionSpec(OeMotion.PRESS_MS, OeMotion.decelerateEasing, dampingRatio = OeMotion.SPRING_DAMPING),
        pageFade = Oe3MotionSpec(OeMotion.PAGE_FADE_MS, OeMotion.easeInOutEasing),
        pageSlide = Oe3MotionSpec(OeMotion.PAGE_SLIDE_MS, OeMotion.decelerateEasing),
        cardEnter = Oe3MotionSpec(OeMotion.CARD_ENTER_MS, OeMotion.decelerateEasing),
        dialog = Oe3MotionSpec(OeMotion.DIALOG_MS, OeMotion.easeOutCubicEasing),
        themeTween = Oe3MotionSpec(OeMotion.THEME_TWEEN_MS, OeMotion.standardEasing),
        pagePush = Oe3MotionSpec(
            OeMotion.PAGE_PUSH_MS,
            OeMotion.standardEasing,
            dampingRatio = OeMotion.PAGE_PUSH_DAMPING,
        ),
        cardEnterScaleFrom = OeMotion.CARD_ENTER_SCALE_FROM,
        navSelectionSpring = SpringSpec(
            dampingRatio = OeMotion.SPRING_DAMPING,
            stiffness = OeMotion.NAV_SELECTION_STIFFNESS,
        ),
        pagePushSpring = SpringSpec(
            dampingRatio = OeMotion.PAGE_PUSH_DAMPING,
            stiffness = OeMotion.PAGE_PUSH_STIFFNESS,
        ),
    )

    fun reduced(): Oe3MotionTokens {
        val base = standard()
        fun Oe3MotionSpec.instant() = copy(durationMillis = 0)
        return base.copy(
            navSelection = base.navSelection.instant(),
            press = base.press.instant(),
            pageFade = base.pageFade.instant(),
            pageSlide = base.pageSlide.instant(),
            cardEnter = base.cardEnter.instant(),
            dialog = base.dialog.instant(),
            themeTween = base.themeTween.instant(),
            pagePush = base.pagePush.instant(),
            cardEnterScaleFrom = OeMotion.REDUCED_CARD_ENTER_SCALE_FROM,
            navSelectionSpring = null,
            pagePushSpring = null,
        )
    }
}
