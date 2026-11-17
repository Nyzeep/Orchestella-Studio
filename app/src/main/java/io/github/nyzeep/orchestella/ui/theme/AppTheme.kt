package io.github.nyzeep.orchestella.ui.theme

/**
 * One complete visual theme for Orchestella: UI chrome colors plus the terminal's
 * 16-color ANSI palette. Immutable data — every consumer (dashboard cards, terminal
 * renderer, ring progress) reads from here, never from hardcoded palette constants.
 *
 * @param terminalBackground distinct from [background] so the console can keep a
 *        denser, more opaque surface even under translucent UI chrome.
 * @param isFixed the palette exists in exactly one brightness and ignores the system
 *        dark/light setting. No shipped preset sets this since UI-14 — the mascot
 *        became the `mascot` paired family — but the flag and the [ThemeStore.Mode.FIXED]
 *        machinery stay so stored preferences from older releases keep resolving.
 */
data class AppTheme(
    val id: String,
    val displayName: String,
    val isDark: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceTranslucent: Int,
    val primary: Int,
    val accent: Int,
    val text: Int,
    val textDim: Int,
    val glow: Int,
    val terminalBackground: Int,
    val terminalForeground: Int,
    val ansi: List<Int>,
    val isFixed: Boolean = false,
) {
    val selectionLabel: String
        get() = "$displayName · " + when {
            isFixed -> "固定"
            isDark -> "暗"
            else -> "亮"
        }

    companion object {
        const val CUSTOM_ID = "custom"
    }
}

/**
 * The stored colour slots of a theme. These are the values a preset owns;
 * every Oe3 semantic role (`surfaceRaised`, `primaryContainer`, …) is derived
 * from them at resolve time by `Oe3Colors`, exactly as before — the palette is
 * data, the roles are behaviour.
 *
 * @param ansi the terminal's 16-colour ANSI palette in standard order.
 */
data class ThemePalette(
    val background: Int,
    val surface: Int,
    val surfaceTranslucent: Int,
    val primary: Int,
    val accent: Int,
    val text: Int,
    val textDim: Int,
    val glow: Int,
    val terminalBackground: Int,
    val terminalForeground: Int,
    val ansi: List<Int>,
)

/**
 * Catalog-layer theme data: one row per shipped preset.
 *
 * @param familyId ties the dark and light variants of the same visual family
 *        together. Mode resolution derives the twin from this id instead of a
 *        handwritten lookup table; a family has at most one dark and one light
 *        member. A fixed palette (none shipped since UI-14) is its own only member.
 * @param isFixed the palette exists in exactly one brightness and never swaps.
 */
data class ThemePreset(
    val id: String,
    val displayName: String,
    val familyId: String,
    val isDark: Boolean,
    val isFixed: Boolean = false,
    val palette: ThemePalette,
) {
    /** Projects the catalog row onto the runtime type every consumer already reads. */
    fun toAppTheme(): AppTheme = AppTheme(
        id = id,
        displayName = displayName,
        isDark = isDark,
        background = palette.background,
        surface = palette.surface,
        surfaceTranslucent = palette.surfaceTranslucent,
        primary = palette.primary,
        accent = palette.accent,
        text = palette.text,
        textDim = palette.textDim,
        glow = palette.glow,
        terminalBackground = palette.terminalBackground,
        terminalForeground = palette.terminalForeground,
        ansi = palette.ansi,
        isFixed = isFixed,
    )
}
