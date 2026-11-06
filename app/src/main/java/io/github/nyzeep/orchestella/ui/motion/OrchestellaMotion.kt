package io.github.nyzeep.orchestella.ui.motion

import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.children

/**
 * Round 2026-11-07 Lane B: View-system motion helpers matching the XML token
 * family under res/anim + res/values/oe_motion_tokens.xml.
 *
 * Everything here animates opacity/translation only, on the hardware layer
 * (`withLayer()`), so no frame triggers measure/layout and the smoothness
 * budget stays a pure raster op. All rise offsets are computed after layout
 * via a one-shot pre-draw listener — never from a stale pre-layout height.
 *
 * NOTE (TODO, device round): every helper is currently UNWIRED. The intended
 * hook points are listed per function; wiring needs edits in existing
 * activities which are outside Lane B's file boundary and impossible to
 * verify without a device. Consume from Lane D / the next device round:
 *
 *  - [stateChangeEnter]/[stateChangeExit] -> MainActivity console/list state
 *    swaps (container_tab_console children), InstanceDetailActivity tab switch
 *    (container_detail_overview/hardware/storage/network/maintenance).
 *  - [expandSection]/[collapseSection] -> SettingsActivity toggleable areas
 *    (e.g. layout_settings_fallback_explanation).
 *  - [playSectionStagger] -> optional re-trigger of the layout animation that
 *    activity_settings.xml already wires statically via
 *    android:layoutAnimation="@anim/oe_layout_section_stagger".
 */
object OrchestellaMotion {

    // Mirror of res/values/oe_motion_tokens.xml (XML keeps the single source;
    // these constants exist so code hooks do not re-inflate animations).
    const val DURATION_ENTER_MS = 300          // oe_motion_duration_medium2
    const val DURATION_EXIT_MS = 150           // oe_motion_duration_short3
    const val DURATION_ACTIVITY_ENTER_MS = 210 // oe_motion_duration_emphasized_enter

    // Mirror of res/anim/oe_emphasized_decelerate.xml (0.05, 0.7, 0.1, 1.0).
    private val emphasizedDecelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)

    // Mirror of res/anim/oe_standard_accelerate.xml (0.3, 0, 1, 1).
    private val standardAccelerate = PathInterpolator(0.3f, 0.0f, 1.0f, 1.0f)

    /** In-place content state enter: fade + short rise, hardware layer. */
    fun stateChangeEnter(view: View) {
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
     * oe_section_expand_enter vocabulary (fade + downward settle). Pair with
     * [collapseSection]; neither hides the view by itself.
     */
    fun expandSection(view: View) {
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
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
        view.animate()
            .withLayer()
            .alpha(0f)
            .setDuration(DURATION_EXIT_MS.toLong())
            .setInterpolator(standardAccelerate)
            .withEndAction(onEnd)
            .start()
    }

    /**
     * Re-runs the staggered section entry of a ViewGroup. The static XML hook
     * (android:layoutAnimation on settings_root_container) already plays once
     * per layout; use this to replay deliberately after a data refresh. Falls
     * back to a code cascade when no XML controller is attached.
     */
    fun playSectionStagger(group: ViewGroup) {
        val childAnim = group.layoutAnimation?.animation
        if (childAnim == null) {
            group.children.forEachIndexed { index, child ->
                child.alpha = 0f
                child.animate()
                    .withLayer()
                    .alpha(1f)
                    .setStartDelay(index * 45L)
                    .setDuration(DURATION_ENTER_MS.toLong())
                    .setInterpolator(emphasizedDecelerate)
                    .setListener(null)
                    .start()
            }
        } else {
            group.startLayoutAnimation()
        }
    }

    private const val RISE_FRACTION_STATE = 0.02f
}
