package io.github.nyzeep.orchestella.ui.theme

/**
 * One editable custom theme slot: a full user-defined palette persisted as flat
 * preference keys (JVM-testable, no org.json). Exactly three slots exist and coexist
 * with the built-in preset catalog; ids reference them as "custom:1".."custom:3".
 */
data class CustomThemeSlot(
    val name: String,
    val isDark: Boolean,
    val background: Int,
    val surface: Int,
    val primary: Int,
    val accent: Int,
    val text: Int,
    val textDim: Int,
    val terminalBackground: Int,
    val terminalForeground: Int,
) {
    companion object {
        const val ID_PREFIX = "custom:"
        const val SLOT_COUNT = 3
    }
}

/**
 * Builds the complete AppTheme for a slot: the translucent surface and glow derive
 * from the palette (glow = primary blended 50/50 into surface), the terminal ANSI
 * palette follows the catalog defaults for the slot's darkness.
 */
fun CustomThemeSlot.toAppTheme(id: String): AppTheme = AppTheme(
    id = id,
    displayName = name.ifBlank { "自定义" },
    isDark = isDark,
    background = background,
    surface = surface,
    surfaceTranslucent = (surface and 0x00FFFFFF) or 0xE6000000.toInt(),
    primary = primary,
    accent = accent,
    text = text,
    textDim = textDim,
    glow = ThemeColors.blend(primary, surface, 0.5f),
    terminalBackground = terminalBackground,
    terminalForeground = terminalForeground,
    ansi = ThemeCatalog.defaultAnsi(isDark),
)
