package io.github.nyzeep.orchestella.ui.oe3.motion

import android.animation.ValueAnimator
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
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

    fun isReducedMotion(resolver: ContentResolver): Boolean =
        isReducedMotion(readScale(resolver))

    fun isReducedMotion(context: Context): Boolean {
        // The setting observer can fire before WindowManager updates the
        // process's ValueAnimator scale. Use the setting for user changes,
        // and keep the effective scale for power saver and absent settings.
        val rawScale = Settings.Global.getString(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE,
        )
        if (rawScale == null) return !ValueAnimator.areAnimatorsEnabled()
        if (isReducedMotion(rawScale)) return true
        return context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true &&
            !ValueAnimator.areAnimatorsEnabled()
    }

    /** Observes user and effective scale changes on the main thread. */
    internal fun observeAnimatorScale(context: Context, onChanged: () -> Unit): () -> Unit {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChanged()
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        val stopEffectiveObserver = if (Build.VERSION.SDK_INT >= 33) observeEffectiveScale(onChanged) else null
        return {
            resolver.unregisterContentObserver(observer)
            stopEffectiveObserver?.invoke()
        }
    }

    @RequiresApi(33)
    private fun observeEffectiveScale(onChanged: () -> Unit): () -> Unit {
        val listener = ValueAnimator.DurationScaleChangeListener { onChanged() }
        ValueAnimator.registerDurationScaleChangeListener(listener)
        return { ValueAnimator.unregisterDurationScaleChangeListener(listener) }
    }
}

/**
 * Composition-side read of [Oe3MotionSystem]. Evaluated once per composition
 * instance. Activity and dialog hosts observe changes during their lifetimes.
 */
@Composable
fun rememberOe3ReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Oe3MotionSystem.isReducedMotion(resolver) }
}
