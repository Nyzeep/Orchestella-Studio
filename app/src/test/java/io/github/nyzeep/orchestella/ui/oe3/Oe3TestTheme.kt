package io.github.nyzeep.orchestella.ui.oe3

import io.github.nyzeep.orchestella.ui.theme.AppTheme

/** Shared AppTheme fixture so color and theme tests pin the same palette shape. */
internal fun oe3TestTheme(
    dark: Boolean,
    background: Int,
    surface: Int,
    primary: Int,
    text: Int,
    textDim: Int,
    surfaceTranslucent: Int = surface,
    accent: Int = primary,
): AppTheme = AppTheme(
    id = "test",
    displayName = "test",
    isDark = dark,
    background = background,
    surface = surface,
    surfaceTranslucent = surfaceTranslucent,
    primary = primary,
    accent = accent,
    text = text,
    textDim = textDim,
    glow = primary,
    terminalBackground = background,
    terminalForeground = text,
    ansi = List(16) { 0xFF000000.toInt() },
)
