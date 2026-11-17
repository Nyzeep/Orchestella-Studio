package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.runtime.Immutable
import io.github.nyzeep.orchestella.DraculaPalette
import io.github.nyzeep.orchestella.ui.theme.AppTheme

/** The terminal slots the preview paints; the ANSI list is always 16 entries. */
@Immutable
data class TerminalPalette(
    val background: Int,
    val foreground: Int,
    val ansi: List<Int>,
)

fun terminalPalette(theme: AppTheme): TerminalPalette = TerminalPalette(
    background = theme.terminalBackground,
    foreground = theme.terminalForeground,
    // ThemeCatalog guarantees 16; take(16)/fill protects a hand-edited custom slot.
    ansi = List(16) { index -> theme.ansi.getOrElse(index) { theme.terminalForeground } },
)

/**
 * Installs the theme in the shared parser/view palette. ModernTerminalView's
 * spans resolve through DraculaPalette at parse time, so the Theme Studio must
 * refresh this override before replaying its sample transcript.
 */
fun applyTerminalPalette(theme: AppTheme) {
    val palette = terminalPalette(theme)
    DraculaPalette.setPaletteOverride(
        palette.ansi.toIntArray(),
        palette.foreground,
        palette.background,
    )
}

/** ANSI 16 index for a standard or bright SGR foreground code; null for other codes. */
fun ansiIndexForSgr(code: Int): Int? = when (code) {
    in 30..37 -> code - 30
    in 90..97 -> code - 90 + 8
    else -> null
}

/** Stable label used by swatch semantics and the palette board. */
fun ansiLabel(index: Int): String = "ANSI $index"
