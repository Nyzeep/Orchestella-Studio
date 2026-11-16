package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.runtime.Immutable

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
)

/**
 * Oe3 motion scale. [reduced] is the global "reduce motion" result: every
 * duration becomes 0 so state changes land instantly, while the token shape
 * stays identical for call sites.
 */
object Oe3Motion {

    private val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private val decelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    private val easeInOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    private val easeOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

    /** The bottom-bar pill: spring, 0.85 damping, ~400ms perceived settle. */
    val navSelectionSpring: SpringSpec<Float> =
        SpringSpec(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    fun standard(): Oe3MotionTokens = Oe3MotionTokens(
        navSelection = Oe3MotionSpec(400, standard, dampingRatio = 0.85f),
        press = Oe3MotionSpec(250, decelerate),
        pageFade = Oe3MotionSpec(220, easeInOut),
        pageSlide = Oe3MotionSpec(300, decelerate),
        cardEnter = Oe3MotionSpec(300, decelerate),
        dialog = Oe3MotionSpec(250, easeOutCubic),
        themeTween = Oe3MotionSpec(300, standard),
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
        )
    }
}
