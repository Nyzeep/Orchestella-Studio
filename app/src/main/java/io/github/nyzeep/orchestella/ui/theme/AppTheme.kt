package io.github.nyzeep.orchestella.ui.theme

/**
 * One complete visual theme for Orchestella: UI chrome colors plus the terminal's
 * 16-color ANSI palette. Immutable data — every consumer (dashboard cards, terminal
 * renderer, ring progress) reads from here, never from hardcoded palette constants.
 *
 * @param terminalBackground distinct from [background] so the console can keep a
 *        denser, more opaque surface even under translucent UI chrome.
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
) {
    companion object {
        const val CUSTOM_ID = "custom"
    }
}
