package io.github.nyzeep.orchestella.ui.motion

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.children
import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion

/**
 * Round 2026-11-07 Lane B: View-system motion helpers matching the XML token
 * family under res/anim + res/values/oe_motion_tokens.xml.
 *
 * Everything here animates opacity/translation/scale only, on the hardware
 * layer (`withLayer()`), so no frame triggers measure/layout and the
 * smoothness budget stays a pure raster op. All rise offsets are computed
 * after layout via a one-shot pre-draw listener — never from a stale
 * pre-layout height.
 *
 * NOTE (wired, 2026-11-08 T2 motion-hooks round): every helper now has a live
 * call site; the original device-round TODO list is retired as follows —
 *
 *  - [stateChangeEnter]/[stateChangeExit] -> WIRED at MainActivity.switchTab
 *    (five tab containers incl. the console/list state swap between
 *    container_tab_console and container_tab_instances; first paint and
 *    theme repaints stay instant) and at InstanceDetailActivity five detail
 *    tabs (container_detail_overview/hardware/storage/network/maintenance).
 *    Both sites carry a generation-counter guard so a stale exit timer never
 *    GONEs a container switched back to mid-fade — rapid taps leave no
 *    half-transparent residue.
 *  - [expandSection]/[collapseSection] -> WIRED at SettingsSubActivity
 *    setupFallbackExplanationSection (layout_settings_fallback_explanation
 *    toggle, with the same generation guard on the collapse-end action).
 *  - [playSectionStagger] -> WIRED at SettingsSubActivity (section bind)
 *    completion: the mirror speed test is the page's explicit data-refresh
 *    moment and replays the settings_root_container stagger. (The old one-shot
 *    XML layoutAnimation hook was removed with the old settings shell in the
 *    UI3 cleanup; the code replay is the only stagger path now.)
 *
 * NOTE (wired, 2026-11-10 Lane D list/dashboard round): the two entry
 * vocabularies are deliberately differentiated —
 *
 *  - [playListEntryStagger] -> WIRED at MainActivity.renderInstances: the
 *    instance-card list plays a fade + 4% rise cascade on every FULL list
 *    re-render. The 3-second dashboard refresh loop never reaches it: the
 *    cycle only calls renderInstances when DashboardSnapshot.stateKey (the
 *    id:state fingerprint) actually changed; unchanged cycles take the light
 *    updateConsoleWithInstance path with zero animation (the 2026-11-08 M5
 *    device-walk point). Residue safety needs no generation counter here —
 *    unlike the tab-exit timers there is no delayed post involved: a card
 *    mid-cascade that is removed by the next render simply dies with its
 *    view, and replacement cards start their own cascade from alpha 0.
 *  - [playDashboardEntry] -> WIRED at MainActivity's first dashboard snapshot
 *    render (console tab active): the console cards play a fade + scale-settle
 *    cascade once per activity instance. First data paint only — later tab
 *    switches are covered by [stateChangeEnter] at the container level, and
 *    theme repaints stay instant per the T2 contract.
 */
object OrchestellaMotion {

    // Values come from the single motion source (ui/design/tokens/Motion.kt,
    // UI-01). The names stay here because the legacy View hooks import them;
    // they are compile-time aliases, not a second set of numbers.
    const val DURATION_ENTER_MS = OeMotion.VIEW_ENTER_MS
    const val DURATION_EXIT_MS = OeMotion.VIEW_EXIT_MS
    const val DURATION_ACTIVITY_ENTER_MS = OeMotion.VIEW_ACTIVITY_ENTER_MS

    // Lane D (2026-11-10) semantic duration aliases.
    const val DURATION_CONTAINER_TRANSFORM_MS = OeMotion.VIEW_CONTAINER_TRANSFORM_MS
    const val DURATION_DIALOG_ENTER_MS = OeMotion.VIEW_DIALOG_ENTER_MS
    const val DURATION_DIALOG_EXIT_MS = OeMotion.VIEW_DIALOG_EXIT_MS
    const val DURATION_LIST_ITEM_ENTER_MS = OeMotion.VIEW_LIST_ITEM_ENTER_MS
    const val DURATION_LIST_ITEM_REORDER_MS = OeMotion.VIEW_LIST_ITEM_REORDER_MS

    // Lane D (2026-11-10) stagger rhythm tokens (UI3 cleanup moved them
    // code-side only). Also consumed by the pure [staggeredDelayMs].
    const val STAGGER_LIST_ITEM_MS = OeMotion.VIEW_STAGGER_LIST_ITEM_MS
    const val STAGGER_LIST_MAX_STEPS = OeMotion.VIEW_STAGGER_LIST_MAX_STEPS
    const val STAGGER_SECTION_MS = OeMotion.VIEW_STAGGER_SECTION_MS

    // Existing press-feedback / ambient-loop durations, previously bare
    // literals at their call sites (session 19 press tuning, dashboard
    // breathing); now token-named. Press-in/out land on the M3 short scale
    // (short3 survives in XML), the breathing cycle on extra_long4.
    const val DURATION_PRESS_IN_MS = OeMotion.VIEW_PRESS_IN_MS
    const val DURATION_PRESS_OUT_MS = OeMotion.VIEW_PRESS_OUT_MS
    const val DURATION_BREATH_CYCLE_MS = OeMotion.VIEW_BREATH_CYCLE_MS

    // Mirror of res/anim/oe_emphasized_decelerate.xml (0.05, 0.7, 0.1, 1.0).
    private val emphasizedDecelerate = PathInterpolator(
        OeMotion.EMPHASIZED_DECELERATE_X1,
        OeMotion.EMPHASIZED_DECELERATE_Y1,
        OeMotion.EMPHASIZED_DECELERATE_X2,
        OeMotion.EMPHASIZED_DECELERATE_Y2,
    )

    // Mirror of res/anim/oe_standard_accelerate.xml (0.3, 0, 1, 1).
    private val standardAccelerate = PathInterpolator(
        OeMotion.STANDARD_ACCELERATE_X1,
        OeMotion.STANDARD_ACCELERATE_Y1,
        OeMotion.STANDARD_ACCELERATE_X2,
        OeMotion.STANDARD_ACCELERATE_Y2,
    )

    // Mirror of res/anim/oe_standard.xml (0.2, 0, 0, 1) — the utilitarian M3
    // default for content that arrives without a directional gesture.
    private val standard = PathInterpolator(
        OeMotion.STANDARD_X1,
        OeMotion.STANDARD_Y1,
        OeMotion.STANDARD_X2,
        OeMotion.STANDARD_Y2,
    )

    /**
     * Views animate only while the platform agrees to animate. With
     * `ANIMATOR_DURATION_SCALE == 0` (Settings → Remove animations, battery
     * saver) `areAnimatorsEnabled()` is false and every helper below lands its
     * final state synchronously instead of starting from `alpha = 0f` and
     * hoping an end frame arrives. Same signal the Compose half reads through
     * `Oe3Motion.reduced()`.
     */
    private fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    /** The end state of every entry vocabulary: fully visible, no offset, no scale. */
    private fun landVisible(view: View) {
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    /** In-place content state enter: fade + short rise, hardware layer. */
    fun stateChangeEnter(view: View) {
        if (!animationsEnabled()) {
            landVisible(view)
            return
        }
        view.alpha = 0f
        OneShotPreDrawListener.add(view) {
            view.translationY = view.height * RISE_FRACTION_STATE
            view.animate()
                .withLayer()
                .translationY(0f)
                .alpha(1f)
                .setDuration(DURATION_ENTER_MS.toLong())
                .setInterpolator(emphasizedDecelerate)
                .setListener(null)
                .start()
            true
        }
    }

    /** In-place content state exit: short fade-out, no movement, hardware layer. */
    fun stateChangeExit(view: View) {
        if (!animationsEnabled()) {
            view.alpha = 0f
            return
        }
        view.animate()
            .withLayer()
            .alpha(0f)
            .setDuration(DURATION_EXIT_MS.toLong())
            .setInterpolator(standardAccelerate)
            .setListener(null)
            .start()
    }

    /**
     * Expandable settings section reveal: makes [view] visible and plays the
     * section-expand vocabulary (fade + downward settle). Pair with
     * [collapseSection]; neither hides the view by itself.
     *
     * This code helper is the single source of that vocabulary — the twin XML
     * anims (oe_section_expand_enter / oe_section_collapse_exit) were never
     * wired and were deleted in the U4 dead-resource closeout.
     */
    fun expandSection(view: View) {
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
        }
        if (!animationsEnabled()) {
            landVisible(view)
            return
        }
        view.alpha = 0f
        OneShotPreDrawListener.add(view) {
            view.translationY = -(view.height * RISE_FRACTION_STATE).coerceIn(0f, 24f)
            view.animate()
                .withLayer()
                .translationY(0f)
                .alpha(1f)
                .setDuration(DURATION_ENTER_MS.toLong())
                .setInterpolator(emphasizedDecelerate)
                .setListener(null)
                .start()
            true
        }
    }

    /** Collapse counterpart of [expandSection]: fade out, then caller may GONE it. */
    fun collapseSection(view: View, onEnd: () -> Unit = {}) {
        if (!animationsEnabled()) {
            view.alpha = 0f
            onEnd()
            return
        }
        view.animate()
            .withLayer()
            .alpha(0f)
            .setDuration(DURATION_EXIT_MS.toLong())
            .setInterpolator(standardAccelerate)
            .withEndAction(onEnd)
            .start()
    }

    /**
     * Instance-list entry (Lane D): fade + 4% self-height rise cascade over
     * the direct children of [group], hardware layer, one-shot pre-draw
     * geometry. The stagger rhythm and cap are [STAGGER_LIST_ITEM_MS] /
     * [STAGGER_LIST_MAX_STEPS] (oe_motion_stagger_list_* tokens).
     *
     * Caller contract (M5 replay-proofing): invoke ONLY on a full list
     * re-render — a state-fingerprint change or an explicit data refresh.
     * Light per-cycle updates must never call this. A re-render during an
     * in-flight cascade removes the animating children outright (no residue
     * and no orphaned timers possible, unlike a delayed-GONE exit).
     */
    fun playListEntryStagger(group: ViewGroup) {
        playChildEntry(
            group,
            rise = true,
            staggerMs = STAGGER_LIST_ITEM_MS,
            durationMs = DURATION_LIST_ITEM_ENTER_MS,
            interpolator = emphasizedDecelerate,
        )
    }

    /**
     * Dashboard console-card entry (Lane D): fade + 2.5% scale-settle cascade
     * (0.975 -> 1.0, center pivot) — a deliberately different vocabulary from
     * the list rise so a full screen reads as "surfaces arriving", not
     * "rows scrolling in". Section stagger rhythm ([STAGGER_SECTION_MS]).
     * One-shot at the first dashboard data paint; see the wired-site note in
     * the class KDoc for the tab/theme guard division of labour.
     */
    fun playDashboardEntry(group: ViewGroup) {
        playChildEntry(
            group,
            rise = false,
            staggerMs = STAGGER_SECTION_MS,
            durationMs = DURATION_ENTER_MS,
            interpolator = standard,
            scaleFrom = SCALE_FRACTION_DASHBOARD,
        )
    }

    /**
     * Re-runs the staggered section entry of a ViewGroup. The static XML hook
     * (android:layoutAnimation on settings_root_container) already plays once
     * per layout; use this to replay deliberately after a data refresh. Falls
     * back to a code cascade when no XML controller is attached.
     */
    fun playSectionStagger(group: ViewGroup) {
        if (!animationsEnabled()) {
            group.children.forEach { child -> landVisible(child) }
            return
        }
        val childAnim = group.layoutAnimation?.animation
        if (childAnim == null) {
            group.children.forEachIndexed { index, child ->
                child.alpha = 0f
                child.animate()
                    .withLayer()
                    .alpha(1f)
                    .setStartDelay(staggeredDelayMs(index, STAGGER_SECTION_MS, STAGGER_LIST_MAX_STEPS))
                    .setDuration(DURATION_ENTER_MS.toLong())
                    .setInterpolator(emphasizedDecelerate)
                    .setListener(null)
                    .start()
            }
        } else {
            group.startLayoutAnimation()
        }
    }

    private fun playChildEntry(
        group: ViewGroup,
        rise: Boolean,
        staggerMs: Int,
        durationMs: Int,
        interpolator: android.view.animation.Interpolator,
        scaleFrom: Float = 1f,
    ) {
        if (!animationsEnabled()) {
            group.children.forEach { child -> landVisible(child) }
            return
        }
        group.children.forEachIndexed { index, child ->
            child.alpha = 0f
            OneShotPreDrawListener.add(child) {
                if (rise) {
                    child.translationY = child.height * RISE_FRACTION_LIST
                }
                if (scaleFrom != 1f) {
                    child.scaleX = scaleFrom
                    child.scaleY = scaleFrom
                }
                child.animate()
                    .withLayer()
                    .translationY(0f)
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(staggeredDelayMs(index, staggerMs, STAGGER_LIST_MAX_STEPS))
                    .setDuration(durationMs.toLong())
                    .setInterpolator(interpolator)
                    .setListener(null)
                    .start()
                true
            }
        }
    }

    private const val RISE_FRACTION_STATE = OeMotion.VIEW_RISE_FRACTION_STATE

    /** List rise matches oe_list_item_enter's 4% vocabulary, item-relative. */
    private const val RISE_FRACTION_LIST = OeMotion.VIEW_RISE_FRACTION_LIST

    /** Dashboard scale-settle start point (sub-perceptual at a glance, present on arrival). */
    private const val SCALE_FRACTION_DASHBOARD = OeMotion.VIEW_SCALE_FROM_DASHBOARD
}

/**
 * Pure stagger rhythm math (Lane D): the start delay for cascade [step], with
 * steps beyond [maxSteps] clamped so long lists lose the tail delay instead of
 * accumulating an unbounded pre-roll. Top-level and side-effect free so JVM
 * tests can lock the rhythm without touching any Android view class.
 */
fun staggeredDelayMs(step: Int, staggerMs: Int, maxSteps: Int): Long =
    step.coerceIn(0, maxSteps).toLong() * staggerMs.toLong()
