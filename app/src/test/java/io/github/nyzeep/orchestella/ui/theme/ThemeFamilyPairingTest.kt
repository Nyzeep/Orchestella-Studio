package io.github.nyzeep.orchestella.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-01: the catalog is now preset-driven. Brightness variants pair through
 * `familyId`, so the handwritten `darkToLightMap` / `lightToDarkMap` tables are
 * gone. These tests assert the *behaviour* those tables used to provide, not
 * any replacement data structure.
 */
class ThemeFamilyPairingTest {

    @Test
    fun catalogStillCarriesExactlyFortyOnePresetsInTheSameOrder() {
        assertEquals(41, ThemeCatalog.presets.size)
        assertEquals(41, ThemeCatalog.all.size)
        assertEquals(ThemeCatalog.all.map { it.id }, ThemeCatalog.presets.map { it.id })
    }

    @Test
    fun everyRuntimeThemeIsTheProjectionOfItsPreset() {
        ThemeCatalog.all.forEach { theme ->
            val preset = ThemeCatalog.presetById(theme.id)
            assertNotNull("missing preset for ${theme.id}", preset)
            assertEquals(theme, preset!!.toAppTheme())
            assertEquals(theme.displayName, preset.displayName)
            assertEquals(theme.isDark, preset.isDark)
            assertEquals(theme.isFixed, preset.isFixed)
        }
    }

    @Test
    fun runtimeCatalogKeepsItsIdentityContract() {
        assertSame(ThemeCatalog.mascotLavender, ThemeCatalog.all.first())
        assertSame(ThemeCatalog.mascotLavender, ThemeCatalog.resolve(ThemeCatalog.mascotLavender.id))
        assertEquals(ThemeCatalog.all.size, ThemeCatalog.all.map { it.id }.toSet().size)
    }

    @Test
    fun everyFamilyHoldsOneOrTwoBrightnessVariants() {
        val families = ThemeCatalog.presets.groupBy { it.familyId }
        families.forEach { (familyId, members) ->
            assertTrue("$familyId has more than two variants", members.size <= 2)
            if (members.size == 2) {
                assertEquals("$familyId must pair one dark with one light", 1, members.count { it.isDark })
                assertEquals("$familyId must pair one dark with one light", 1, members.count { !it.isDark })
                assertTrue("a paired family cannot be fixed", members.none { it.isFixed })
            }
        }
        assertEquals("exactly the 20 documented dark/light pairs", 20, families.count { it.value.size == 2 })
    }

    @Test
    fun pairedFamiliesResolveSymmetricallyInBothModes() {
        val families = ThemeCatalog.presets.groupBy { it.familyId }.filterValues { it.size == 2 }
        assertEquals(20, families.size)
        families.forEach { (familyId, members) ->
            val dark = members.first { it.isDark }
            val light = members.first { !it.isDark }
            val darkTheme = ThemeCatalog.resolve(dark.id)
            val lightTheme = ThemeCatalog.resolve(light.id)
            assertSame(
                "$familyId: dark preset must pair to its light twin",
                lightTheme,
                ThemeCatalog.resolveForMode(dark.id, isDark = false),
            )
            assertSame(
                "$familyId: light preset must pair to its dark twin",
                darkTheme,
                ThemeCatalog.resolveForMode(light.id, isDark = true),
            )
            assertSame(darkTheme, ThemeCatalog.resolveForMode(dark.id, isDark = true))
            assertSame(lightTheme, ThemeCatalog.resolveForMode(light.id, isDark = false))
        }
    }

    @Test
    fun fixedPresetsHaveNoTwinAndIgnoreTheMode() {
        val fixed = ThemeCatalog.presets.filter { it.isFixed }
        assertEquals("only the mascot palette is fixed", 1, fixed.size)
        val mascot = fixed.single()
        assertEquals(1, ThemeCatalog.presets.count { it.familyId == mascot.familyId })
        val theme = ThemeCatalog.resolve(mascot.id)
        assertSame(theme, ThemeCatalog.resolveForMode(mascot.id, isDark = true))
        assertSame(theme, ThemeCatalog.resolveForMode(mascot.id, isDark = false))
        assertFalse(theme.isDark)
    }

    @Test
    fun unpairedBrightnessReturnsTheBaseThemeInsteadOfNull() {
        ThemeCatalog.presets.filter { it.isFixed }.forEach { preset ->
            assertSame(ThemeCatalog.resolve(preset.id), ThemeCatalog.resolveForMode(preset.id, !preset.isDark))
        }
    }

    @Test
    fun unknownOrMissingIdsStillFallBackToTheMascot() {
        assertSame(ThemeCatalog.mascotLavender, ThemeCatalog.resolveForMode(null, isDark = true))
        assertSame(ThemeCatalog.mascotLavender, ThemeCatalog.resolveForMode("no-such-theme", isDark = false))
        assertEquals(null, ThemeCatalog.presetById("no-such-theme"))
    }

    @Test
    fun paletteCarriesEveryStoredColorSlotAndSixteenAnsiColors() {
        ThemeCatalog.presets.forEach { preset ->
            assertEquals("${preset.id} ANSI palette", 16, preset.palette.ansi.size)
            assertEquals(preset.palette.background, preset.toAppTheme().background)
            assertEquals(preset.palette.surface, preset.toAppTheme().surface)
            assertEquals(preset.palette.surfaceTranslucent, preset.toAppTheme().surfaceTranslucent)
            assertEquals(preset.palette.primary, preset.toAppTheme().primary)
            assertEquals(preset.palette.accent, preset.toAppTheme().accent)
            assertEquals(preset.palette.text, preset.toAppTheme().text)
            assertEquals(preset.palette.textDim, preset.toAppTheme().textDim)
            assertEquals(preset.palette.glow, preset.toAppTheme().glow)
            assertEquals(preset.palette.terminalBackground, preset.toAppTheme().terminalBackground)
            assertEquals(preset.palette.terminalForeground, preset.toAppTheme().terminalForeground)
            assertEquals(preset.palette.ansi, preset.toAppTheme().ansi)
        }
    }
}
