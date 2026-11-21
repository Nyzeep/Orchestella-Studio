package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
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
    /**
     * v4 (UI-09): edit-mode idle wiggle cycle. [Oe3MotionSpec.durationMillis] is
     * the cycle length; pages drive sin/cos from the progress themselves. Zero
     * under reduced motion, so cards stand still while editing.
     */
    val editWiggle: Oe3MotionSpec,
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
        editWiggle = Oe3MotionSpec(OeMotion.EDIT_WIGGLE_PERIOD_MS, LinearEasing),
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
            editWiggle = base.editWiggle.instant(),
            navSelectionSpring = null,
            pagePushSpring = null,
        )
    }
}

/**
 * Motion v2 spring vocabulary (W1-02 / A4-M1). Spatial springs (press, pill,
 * expressive) keep the 0.85 damping's slight controlled rebound; Effects
 * springs settle at damping 1.0 with no overshoot. Every number comes from
 * [OeMotion]; reduced motion never reads these directly — consumers gate on
 * `motion.*.durationMillis == 0` and fall back to `snap()`, exactly as the
 * pre-token springs did.
 */
object OeSpring {

    /** Press feedback for chips, buttons and the nav pill (≈250ms nominal). */
    val Press: SpringSpec<Float> = SpringSpec(
        dampingRatio = OeMotion.SPRING_DAMPING,
        stiffness = OeMotion.PRESS_STIFFNESS,
    )

    /** Nav pill selection travel (≈400ms nominal; reduced motion withdraws it). */
    val Pill: SpringSpec<Float> = SpringSpec(
        dampingRatio = OeMotion.SPRING_DAMPING,
        stiffness = OeMotion.NAV_SELECTION_STIFFNESS,
    )

    /** Effects-style land-in-place settle: pill stiffness, zero rebound. */
    val Settle: SpringSpec<Float> = SpringSpec(
        dampingRatio = OeMotion.SETTLE_DAMPING,
        stiffness = OeMotion.SETTLE_STIFFNESS,
    )

    /** Expressive spatial spring; Wave 2 back-stack candidate anchor (M2 A/B). */
    val Expressive: SpringSpec<Float> = SpringSpec(
        dampingRatio = OeMotion.EXPRESSIVE_DAMPING,
        stiffness = OeMotion.EXPRESSIVE_STIFFNESS,
    )

    /**
     * Page-push spring pair: the fixed [OePushSpring.spec] plus the gesture
     * release [OePushSpring.initialVelocity] expressed in progress units per
     * second — the caller hands both to `Animatable.animateTo(target, spec,
     * initialVelocity)` (the `OeMotion.PAGE_PUSH_VELOCITY_FACTOR` convention
     * OeBackStack already uses). Wave 2 (Oe3Pager / OeBackStack) consumes it.
     */
    fun push(velocity: Float = OeMotion.PAGE_PUSH_VELOCITY_FACTOR): OePushSpring =
        OePushSpring(
            spec = SpringSpec(
                dampingRatio = OeMotion.PAGE_PUSH_DAMPING,
                stiffness = OeMotion.PAGE_PUSH_STIFFNESS,
            ),
            initialVelocity = velocity,
        )
}

/**
 * Page-push spring pair (W1-02 / A4-M1): spec + gesture-release velocity in
 * progress units per second; produced by [OeSpring.push].
 */
data class OePushSpring(
    val spec: SpringSpec<Float>,
    val initialVelocity: Float,
)
