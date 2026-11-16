package io.github.nyzeep.orchestella.ui.screens.theme

import io.github.nyzeep.orchestella.DraculaPalette
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalPaletteTest {

    private val theme = ThemeCatalog.github

    @Test
    fun sgrForegroundCodesMapToTheSixteenPaletteIndices() {
        for (code in 30..37) assertEquals(code - 30, ansiIndexForSgr(code))
        for (code in 90..97) assertEquals(code - 90 + 8, ansiIndexForSgr(code))
        assertNull(ansiIndexForSgr(39))
        assertNull(ansiIndexForSgr(38))
        assertNull(ansiIndexForSgr(97 + 1))
    }

    @Test
    fun everyAnsiIndexReadsItsOwnSlot() {
        val palette = terminalPalette(theme)
        assertEquals(16, palette.ansi.size)
        for (index in 0 until 16) {
            assertEquals("ANSI $index", theme.ansi[index], palette.ansi[index])
        }
        assertEquals(theme.terminalBackground, palette.background)
        assertEquals(theme.terminalForeground, palette.foreground)
    }

    @Test
    fun shortPaletteFallsBackToTheForegroundInsteadOfCrashing() {
        val short = theme.copy(ansi = theme.ansi.take(3))
        val palette = terminalPalette(short)
        assertEquals(16, palette.ansi.size)
        assertEquals(theme.terminalForeground, palette.ansi[3])
        assertEquals(theme.terminalForeground, palette.ansi[15])
    }

    @Test
    fun applyInstallsTheSameSixteenColoursInTheSharedParserPalette() {
        try {
            applyTerminalPalette(theme)
            assertEquals(theme.terminalBackground, DraculaPalette.themedBackground())
            assertEquals(theme.terminalForeground, DraculaPalette.themedForeground())
            for (index in 0 until 16) {
                assertEquals(theme.ansi[index], DraculaPalette.getAnsi16(index))
            }
        } finally {
            DraculaPalette.setPaletteOverride(null, 0, 0)
        }
    }
}
