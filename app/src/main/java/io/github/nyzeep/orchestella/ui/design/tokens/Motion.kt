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
    /** HomePreview dock-style crossfade duration when toggling Floating / Fixed. */
    const val PREVIEW_DOCK_SWITCH_MS = 180

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
    /** F13 spring entrance start scale and alpha for window-level `OeDialogHost` overlays. */
    const val DIALOG_SPRING_SCALE_FROM = 0.88f
    const val DIALOG_SPRING_ALPHA_FROM = 0.5f
    const val DIALOG_SPRING_STIFFNESS = Spring.StiffnessMediumLow

    // --- Page push (v4 spec §1.5, ticket UI-03) ---
    // spring(300ms, bounce 0): bounce 0 is a critically damped spring; the
    // stiffness below settles in ≈9.5/√k ≈ 300ms (same estimate that maps
    // MediumLow≈400ms for the nav pill). The entering page is launched with an
    // initial velocity of 9×distance per second, expressed once in progress
    // units (offset = (1−progress)×pageWidth, so progress-velocity is
    // width-independent). Pushing also scales the previous page to 80% and
    // dims it by 15% — the stacked "previous page" treatment.
    const val PAGE_PUSH_MS = 300
    const val PAGE_PUSH_DAMPING = 1f
    const val PAGE_PUSH_STIFFNESS = 1000f
    const val PAGE_PUSH_VELOCITY_FACTOR = 9f
    const val PAGE_PUSH_UNDER_SCALE = 0.8f
    const val PAGE_PUSH_UNDER_DIM = 0.15f
    const val PAGE_PUSH_SCALE_DELTA = 0.2f

    // --- Dashboard edit mode (UI-09; reference prism_ui edit-mode specs) ---

    /** Idle wiggle: rotation amplitude in degrees (±0.7°). */
    const val EDIT_WIGGLE_ROTATION_DEG = 0.7f

    /** Idle wiggle: translation amplitude in dp (±1.25dp). */
    const val EDIT_WIGGLE_SHIFT_DP = 1.25f

    /** Idle wiggle cycle; every card shares it, phases differ via start offsets. */
    const val EDIT_WIGGLE_PERIOD_MS = 3600

    /** Phase step between neighbouring cards so the wiggle never syncs up. */
    const val EDIT_WIGGLE_PHASE_STEP_MS = 600

    /** Displacement settle for the neighbours when a card changes slot. */
    const val EDIT_REORDER_SETTLE_MS = 260

    /** Appearance of a (re-)added card: scale 0.92→1 + fade. */
    const val EDIT_APPEAR_MS = 200
    const val EDIT_APPEAR_SCALE_FROM = 0.92f

    /** Drag lift: transient scale and settle-back duration (spec pins easeOut). */
    const val EDIT_DRAG_LIFT_SCALE_DELTA = 0.03f
    const val EDIT_DRAG_LIFT_MS = 200

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
