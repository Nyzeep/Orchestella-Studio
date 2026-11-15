package io.github.nyzeep.orchestella.ui.design

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The one haptic entry point. No screen calls [View.performHapticFeedback]
 * directly; that keeps the "system haptics off means silence" contract in one
 * place instead of spread across every click listener.
 *
 * No VIBRATE permission is used: these are view-level haptic constants, not
 * vibration effects.
 */
object OeHaptics {
    /**
     * In-app switch reserved for the appearance settings (ticket 05). Defaults
     * to on; the system setting still wins because [play] checks it too.
     */
    @Volatile
    var enabled: Boolean = true

    /** Navigation ticks: destination switch, card selection. */
    fun tick(view: View) = play(view, HapticFeedbackConstants.CLOCK_TICK)

    /** State changes: switches, segmented controls, slider steps. */
    fun select(view: View) = play(view, HapticFeedbackConstants.KEYBOARD_TAP)

    /** A successful action (save, apply, connect). */
    fun confirm(view: View) = play(
        view,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )

    /** A rejected or failed action. */
    fun reject(view: View) = play(
        view,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )

    /** Destructive confirmation (delete instance, wipe storage). */
    fun heavy(view: View) = play(view, HapticFeedbackConstants.LONG_PRESS)

    /** Pure gate used by the JVM test; the view path uses the same rule. */
    fun shouldPlay(inAppEnabled: Boolean, systemEnabled: Boolean): Boolean =
        inAppEnabled && systemEnabled

    /** `HAPTIC_FEEDBACK_ENABLED` is readable without any permission. */
    @Suppress("DEPRECATION")
    fun isSystemEnabled(context: Context): Boolean =
        try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1,
            ) != 0
        } catch (_: Exception) {
            // A vendor build that hides the setting should not break input.
            true
        }

    private fun play(view: View, constant: Int) {
        if (!shouldPlay(enabled, isSystemEnabled(view.context))) return
        if (!view.isHapticFeedbackEnabled) return
        view.performHapticFeedback(constant)
    }
}
