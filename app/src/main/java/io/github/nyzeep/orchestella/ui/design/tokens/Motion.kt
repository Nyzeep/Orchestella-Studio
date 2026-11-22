package io.github.nyzeep.orchestella.ui.design.tokens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
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

    // --- Page push (v4 spec §1.5, ticket UI-03; Wave 2 M2 retune, W2-03) ---
    // The stacked "previous page" treatment: the entering page is launched with
    // an initial velocity expressed in progress units (offset =
    // (1−progress)×pageWidth, so progress-velocity is width-independent) and
    // the page beneath scales/dims/parallaxes against the same progress. The
    // spring and the geometry were retuned to the W1-02 Expressive anchor
    // (A4 §3.1 M2): stiffness 700 / damping 0.9 / v0 6 progress-units per
    // second, under-scale 0.92, under-dim 0.10, under-parallax −12%.
    const val PAGE_PUSH_MS = 300
    const val PAGE_PUSH_DAMPING = 0.9f
    const val PAGE_PUSH_STIFFNESS = 700f
    const val PAGE_PUSH_VELOCITY_FACTOR = 6f
    const val PAGE_PUSH_UNDER_SCALE = 0.92f
    const val PAGE_PUSH_UNDER_DIM = 0.10f
    const val PAGE_PUSH_SCALE_DELTA = 0.08f

    /** 页宽比例计的底层视差：随 (1−p) 向反向平移 −12%（W2-03 M2）。 */
    const val PAGE_PUSH_UNDER_PARALLAX = -0.12f

    /** 栈分离渐变带宽度（W2-03 M2）：画在未裁剪层上，峰值不透明度见下。 */
    const val PAGE_PUSH_SEAM_BAND_DP = 24
    const val PAGE_PUSH_SEAM_BAND_ALPHA = 0.06f

    // --- Page fade-through timeline (W2-03 / A4 §3.1 M2: 90 out + 20 gap +
    //     210 in = 320ms, OeDuration.Medium) ---
    // Outgoing content leaves over [PAGE_FADE_EXIT_MS], the stage rests for
    // the remaining gap, incoming content arrives over [PAGE_FADE_ENTER_MS]
    // after [PAGE_FADE_ENTER_DELAY_MS]. The two fade windows never overlap
    // (exit 0..90, enter 110..320). Oe3Pager realizes the timeline with
    // opaque slides and the wizard with fades — see those files for why.
    const val PAGE_FADE_EXIT_MS = 90
    const val PAGE_FADE_ENTER_MS = 210
    const val PAGE_FADE_ENTER_DELAY_MS = 110

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

    /** M3 emphasized-accelerate curve (W1-02 motion v2): incoming spatial content. */
    const val EMPHASIZED_ACCELERATE_X1 = 0.3f
    const val EMPHASIZED_ACCELERATE_Y1 = 0f
    const val EMPHASIZED_ACCELERATE_X2 = 0.8f
    const val EMPHASIZED_ACCELERATE_Y2 = 0.15f

    // --- Motion v2 duration ladder (W1-02 / A4-M1) ---
    // The named semantic durations above stay the wired truth for the surfaces
    // that already consume them; this ladder is the authoritative scale for new
    // work (A4 Wave 2+) and for the ambient loops that have no semantic token.

    const val DURATION_MICRO_MS = 120
    const val DURATION_SHORT_MS = 200
    const val DURATION_MEDIUM_MS = 320
    const val DURATION_LONG_MS = 480

    /** Ambient loop cycle (OeSlider glow sweep); the one above-ladder duration. */
    const val GLOW_CYCLE_MS = 4000

    // --- Motion v2 spring parameters (W1-02 / A4-M1) ---
    // Spatial springs (press/pill) keep the 0.85 controlled rebound; Effects
    // springs settle at damping 1.0 with no overshoot (A4 §3.1 M1).

    /** Press feedback stiffness shared by Oe3Chip/Oe3Button/Oe3NavBar. */
    const val PRESS_STIFFNESS = Spring.StiffnessMedium

    /** Effects-style land-in-place spring: same pill stiffness, zero rebound. */
    const val SETTLE_DAMPING = 1f
    const val SETTLE_STIFFNESS = Spring.StiffnessMediumLow

    /** Expressive spatial spring; anchors the Wave 2 back-stack candidate (M2 A/B). */
    const val EXPRESSIVE_DAMPING = 0.9f
    const val EXPRESSIVE_STIFFNESS = 700f

    // --- Motion v2 press-scale tokens (W1-02 / A4-M1 second-source cleanup) ---
    // The per-component measured scales move here verbatim; component files
    // consume the tokens and keep no local copies.

    /** Oe3Chip / Oe3Button pressed scale. */
    const val CONTROL_PRESS_SCALE = 0.95f

    /** Oe3NavBar pill pressed scale (hit area and settle logic untouched). */
    const val NAV_PRESS_SCALE = 0.96f

    /** OeSlider thumb pressed scale (glow geometry 8-dot / 90dp untouched). */
    const val SLIDER_PRESS_SCALE = 0.92f

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

/**
 * Motion v2 duration ladder (W1-02 / A4-M1). The single scale new motion work
 * draws from: Micro for feedback ticks, Short for control transitions, Medium
 * for page-level moves, Long for showcase/emphasis. Every number lives in
 * [OeMotion]; this object is the named vocabulary, not a second table.
 */
object OeDuration {
    /** 120ms — feedback ticks (press wash, chip toggles). */
    const val Micro = OeMotion.DURATION_MICRO_MS

    /** 200ms — control transitions (sliders, small surfaces). */
    const val Short = OeMotion.DURATION_SHORT_MS

    /** 320ms — page-level transitions and moves. */
    const val Medium = OeMotion.DURATION_MEDIUM_MS

    /** 480ms — showcase/emphasis moments. */
    const val Long = OeMotion.DURATION_LONG_MS

    /** Ambient loop cycle (OeSlider glow sweep); the one above-ladder value. */
    const val GlowCycle = OeMotion.GLOW_CYCLE_MS
}

/**
 * Motion v2 easing vocabulary (W1-02 / A4-M1). The M3 emphasized pair joined
 * the existing [OeMotion] curves; instances are shared, so a curve is changed
 * only at its control-point constants.
 */
object OeEase {
    /** Spatial incoming content: M3 emphasized decelerate (legacy mirror). */
    val EmphasizedDecelerate: Easing =
        CubicBezierEasing(
            OeMotion.EMPHASIZED_DECELERATE_X1,
            OeMotion.EMPHASIZED_DECELERATE_Y1,
            OeMotion.EMPHASIZED_DECELERATE_X2,
            OeMotion.EMPHASIZED_DECELERATE_Y2,
        )

    /** Spatial outgoing content: M3 emphasized accelerate. */
    val EmphasizedAccelerate: Easing =
        CubicBezierEasing(
            OeMotion.EMPHASIZED_ACCELERATE_X1,
            OeMotion.EMPHASIZED_ACCELERATE_Y1,
            OeMotion.EMPHASIZED_ACCELERATE_X2,
            OeMotion.EMPHASIZED_ACCELERATE_Y2,
        )

    /** The utilitarian default for content without a directional gesture. */
    val Standard: Easing = OeMotion.standardEasing

    /** Linear: ambient loops and scrub-linked progress only. */
    val Linear: Easing = LinearEasing
}
