package io.github.nyzeep.orchestella.ui.oe3.motion

import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Bridge between the Oe3 motion tokens and the system animator scale
 * (spec §2.5, ticket UI3-10).
 *
 * The tokens themselves stay pure (`ui/oe3/tokens/Oe3Motion`): this object is
 * the single reader of `Settings.Global.ANIMATOR_DURATION_SCALE`, so "reduce
 * motion" has exactly one system-side definition. A scale of 0 means the user
 * (or power saving) disabled animations; every Oe3 duration then collapses to
 * 0ms via `Oe3Motion.reduced()`, which also withdraws the nav selection spring.
 */
object Oe3MotionSystem {

    /** Mirrors [Settings.Global.ANIMATOR_DURATION_SCALE]. */
    const val SETTING_ANIMATOR_DURATION_SCALE = "animator_duration_scale"

    /** The scale the system reports when the setting is absent or unreadable. */
    const val DEFAULT_SCALE = 1f

    /**
     * Parses the raw setting value. `null`/blank/corrupt reads as the default
     * scale; negative values are corrupt data, not slower motion.
     */
    fun parseScale(raw: String?): Float {
        val scale = raw?.trim()?.toFloatOrNull() ?: return DEFAULT_SCALE
        return if (scale >= 0f) scale else DEFAULT_SCALE
    }

    /** Scale 0 is the platform's "animations off" signal. */
    fun isReducedMotion(scale: Float): Boolean = scale == 0f

    /** Convenience overload for the raw setting string. */
    fun isReducedMotion(raw: String?): Boolean = isReducedMotion(parseScale(raw))

    /**
     * Reads the scale through a [ContentResolver]. Kept visible for callers
     * that poll the setting outside a composition (UI3-12 wiring, tests).
     */
    fun readScale(resolver: ContentResolver): Float =
        Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            DEFAULT_SCALE,
        )

    /**
     * One-shot read of the system reduced-motion signal. The app shell already
     * receives its reduce-motion flag from the Activity
     * (`ValueAnimator.areAnimatorsEnabled()` covers the same setting); this
     * composable is the in-composition reader for surfaces that need to verify
     * the signal themselves.
     */
    fun isReducedMotion(resolver: ContentResolver): Boolean =
        isReducedMotion(readScale(resolver))
}

/**
 * Composition-side read of [Oe3MotionSystem]. Evaluated once per composition
 * instance; a settings change re-enters through activity recreation, which is
 * where the shell re-resolves its reduce-motion flag today.
 */
@Composable
fun rememberOe3ReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Oe3MotionSystem.isReducedMotion(resolver) }
}
