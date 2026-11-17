package io.github.nyzeep.orchestella.ui.design.tokens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring

/**
 * The one motion source for the whole app (UI-01).
 *
 * It merges the two vocabularies that used to carry their own numbers:
 *  - the Compose scale consumed by `Oe3Motion` (navigation pill, press, page
 *    fades/slides, card entrance, dialog, theme tween), and
 *  - the View-system rhythm consumed by `ui/motion/OrchestellaMotion`
 *    (legacy XML-shell hooks: tab state changes, list/dashboard cascades,
 *    section expand/collapse, press feedback).
 *
 * Both consumers now delegate here, so a duration or curve can only be changed
 * in one place. The bezier control points are plain floats so the View layer
 * can build `PathInterpolator`s from them without this object touching any
 * Android framework class (unit tests construct the Compose easings freely).
 */
object OeMotion {

    // --- Compose scale: nominal durations (springs are what settle) ---

    const val NAV_SELECTION_MS = 400
    const val PRESS_MS = 250
    const val PAGE_FADE_MS = 220
    const val PAGE_SLIDE_MS = 300
    const val CARD_ENTER_MS = 300
    const val DIALOG_MS = 250
    const val THEME_TWEEN_MS = 300

    const val SPRING_DAMPING = 0.85f
    const val NAV_SELECTION_STIFFNESS = Spring.StiffnessMediumLow

    // --- Card press timing (UI-04; reference press_surface 150/200/250/200) ---

    /** Reveal delay: the wash waits this long before the tint starts fading in. */
    const val CARD_PRESS_REVEAL_DELAY_MS = 150

    /** Tint fade-in once the reveal delay has elapsed. */
    const val CARD_PRESS_TINT_FADE_IN_MS = 200

    /** Release settle: a wash that reached full ink retreats to rest over this. */
    const val CARD_PRESS_RELEASE_MS = 250

    /** Ink fade-out: a wash interrupted before it settled fades out over this. */
    const val CARD_PRESS_INK_FADE_MS = 200

    const val CARD_ENTER_SCALE_FROM = 0.96f
    const val REDUCED_CARD_ENTER_SCALE_FROM = 1f

    // --- Shared cubic bezier control points ---

    const val STANDARD_X1 = 0.2f
    const val STANDARD_Y1 = 0f
    const val STANDARD_X2 = 0f
    const val STANDARD_Y2 = 1f

    const val DECELERATE_X1 = 0f
    const val DECELERATE_Y1 = 0f
    const val DECELERATE_X2 = 0f
    const val DECELERATE_Y2 = 1f

    const val EASE_IN_OUT_X1 = 0.4f
    const val EASE_IN_OUT_Y1 = 0f
    const val EASE_IN_OUT_X2 = 0.2f
    const val EASE_IN_OUT_Y2 = 1f

    const val EASE_OUT_CUBIC_X1 = 0.215f
    const val EASE_OUT_CUBIC_Y1 = 0.61f
    const val EASE_OUT_CUBIC_X2 = 0.355f
    const val EASE_OUT_CUBIC_Y2 = 1f

    /** Mirror of the legacy `oe_emphasized_decelerate` View interpolator. */
    const val EMPHASIZED_DECELERATE_X1 = 0.05f
    const val EMPHASIZED_DECELERATE_Y1 = 0.7f
    const val EMPHASIZED_DECELERATE_X2 = 0.1f
    const val EMPHASIZED_DECELERATE_Y2 = 1f

    /** Mirror of the legacy `oe_standard_accelerate` View interpolator. */
    const val STANDARD_ACCELERATE_X1 = 0.3f
    const val STANDARD_ACCELERATE_Y1 = 0f
    const val STANDARD_ACCELERATE_X2 = 1f
    const val STANDARD_ACCELERATE_Y2 = 1f

    val standardEasing: Easing =
        CubicBezierEasing(STANDARD_X1, STANDARD_Y1, STANDARD_X2, STANDARD_Y2)

    val decelerateEasing: Easing =
        CubicBezierEasing(DECELERATE_X1, DECELERATE_Y1, DECELERATE_X2, DECELERATE_Y2)

    val easeInOutEasing: Easing =
        CubicBezierEasing(EASE_IN_OUT_X1, EASE_IN_OUT_Y1, EASE_IN_OUT_X2, EASE_IN_OUT_Y2)

    val easeOutCubicEasing: Easing =
        CubicBezierEasing(EASE_OUT_CUBIC_X1, EASE_OUT_CUBIC_Y1, EASE_OUT_CUBIC_X2, EASE_OUT_CUBIC_Y2)

    // --- View-system vocabulary (legacy shell hooks) ---

    const val VIEW_ENTER_MS = 300
    const val VIEW_EXIT_MS = 150
    const val VIEW_ACTIVITY_ENTER_MS = 210

    const val VIEW_CONTAINER_TRANSFORM_MS = 500
    const val VIEW_DIALOG_ENTER_MS = 250
    const val VIEW_DIALOG_EXIT_MS = 150
    const val VIEW_LIST_ITEM_ENTER_MS = 300
    const val VIEW_LIST_ITEM_REORDER_MS = 250

    const val VIEW_STAGGER_LIST_ITEM_MS = 40
    const val VIEW_STAGGER_LIST_MAX_STEPS = 5
    const val VIEW_STAGGER_SECTION_MS = 45

    const val VIEW_PRESS_IN_MS = 100
    const val VIEW_PRESS_OUT_MS = 150
    const val VIEW_BREATH_CYCLE_MS = 1000

    /** Self-height rise fractions for the View entry vocabularies. */
    const val VIEW_RISE_FRACTION_STATE = 0.02f
    const val VIEW_RISE_FRACTION_LIST = 0.04f

    /** Dashboard scale-settle start point. */
    const val VIEW_SCALE_FROM_DASHBOARD = 0.975f
}
