package io.github.nyzeep.orchestella.ui.theme

/**
 * How the in-app text scale is sourced (ticket 05 wires the picker). Moved
 * here from the deleted Oe v1 `ui/design` package (UI3-12): the utility is
 * alive — ThemeStore persists it and Theme Studio renders its picker — while
 * the rest of the Oe v1 token set is gone with the legacy shell.
 */
enum class OeTextScaleMode { FollowSystem, Custom }

/**
 * In-app text scaling. The platform already applies `Configuration.fontScale`
 * exactly once when `sp` is converted to pixels (including Android 14's
 * non-linear curve), so this factor is only the explicit 85–130% app override;
 * in follow-system mode it stays 1.0.
 */
object OeTextScale {
    const val MIN_PERCENT = 85
    const val MAX_PERCENT = 130
    const val DEFAULT_PERCENT = 100

    /** The picker's discrete steps; 100% is an explicit custom choice, not "follow". */
    val PERCENT_OPTIONS: List<Int> = (MIN_PERCENT..MAX_PERCENT step 5).toList()

    fun clampPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /**
     * Picker values: the standard 5% steps, plus the saved custom percent when
     * a historic/hand-edited value falls outside them. Follow-system ignores
     * the saved percent, so the picker never shows a stale custom step.
     */
    fun optionsFor(mode: OeTextScaleMode, savedPercent: Int): List<Int> {
        if (mode != OeTextScaleMode.Custom) return PERCENT_OPTIONS
        val percent = clampPercent(savedPercent)
        if (percent in PERCENT_OPTIONS) return PERCENT_OPTIONS
        return (PERCENT_OPTIONS + percent).sorted()
    }

    /**
     * Clamps a factor handed to the theme into the ticket band. Non-finite or
     * non-positive input falls back to 1.0 instead of poisoning every `sp` in
     * the composition with NaN.
     */
    fun safeFactor(factor: Float): Float = when {
        !factor.isFinite() || factor <= 0f -> 1f
        else -> factor.coerceIn(MIN_PERCENT / 100f, MAX_PERCENT / 100f)
    }

    fun effectiveFactor(
        mode: OeTextScaleMode = OeTextScaleMode.FollowSystem,
        customPercent: Int = DEFAULT_PERCENT,
    ): Float = when (mode) {
        OeTextScaleMode.FollowSystem -> 1f
        OeTextScaleMode.Custom -> clampPercent(customPercent) / 100f
    }
}
