package io.github.nyzeep.orchestella.ui.shell

import android.app.Activity
import android.content.Context
import io.github.nyzeep.orchestella.BuildConfig

/**
 * Gated entry point for the Compose shell.
 *
 * The flag is deliberately debug-only: the legacy XML shell stays the release
 * behavior until UI3-12 flips the default. Debug builds open the new shell with
 * `adb shell am start -n <pkg>/.MainActivity --ez oe3 true`.
 */
object Oe3Feature {

    /** Intent extra accepted by debug builds. */
    const val EXTRA_ENABLED = "oe3"

    /** `savedInstanceState` key so a recreation keeps the chosen shell. */
    const val STATE_KEY = "oe3_enabled"

    /** Default-off read for callers that only have a context. */
    fun enabled(context: Context): Boolean {
        val activity = context as? Activity ?: return false
        return enabled(
            requested = activity.intent?.getBooleanExtra(EXTRA_ENABLED, false) == true,
            debugBuild = BuildConfig.DEBUG,
        )
    }

    /** Pure policy seam used by the tests and by [enabled]. */
    fun enabled(requested: Boolean, debugBuild: Boolean): Boolean = debugBuild && requested

    /**
     * Resolve the flag for a fresh `onCreate`.
     *
     * A saved `true` survives recreation even if the launch extra is gone; a
     * saved `false` cannot suppress an explicit launch extra. Release builds
     * never enable the shell either way.
     */
    fun resolve(savedState: Boolean?, requested: Boolean, debugBuild: Boolean): Boolean =
        combine(savedState, liveEnabled = enabled(requested, debugBuild), debugBuild = debugBuild)

    fun resolve(savedState: Boolean?, context: Context): Boolean =
        combine(savedState, liveEnabled = enabled(context), debugBuild = BuildConfig.DEBUG)

    private fun combine(
        savedState: Boolean?,
        liveEnabled: Boolean,
        debugBuild: Boolean,
    ): Boolean = liveEnabled || (debugBuild && savedState == true)
}
