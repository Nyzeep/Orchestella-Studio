package io.github.nyzeep.orchestella.ui.theme

import io.github.nyzeep.orchestella.FakeSharedPreferences
import io.github.nyzeep.orchestella.ui.theme.ThemeColors.SurfaceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U1 (2026-11-11) 三档 surface 运行时收口 contracts: the registry table, the tier
 * → fill derivation across every catalog theme plus custom slots, and the
 * byte-identical fallback for unregistered cards (the regression guarantee).
 */
class ThemeSurfaceTierTest {
    private fun relLum(color: Int): Double = ThemeColors.contrast(color, 0xFF000000.toInt())

    private fun themesUnderTest(): List<AppTheme> =
        ThemeCatalog.all + listOf(darkSlot().toAppTheme("custom:1"), lightSlot().toAppTheme("custom:2"))

    private fun darkSlot(name: String = "夜航") = CustomThemeSlot(
        name = name, isDark = true,
        background = 0xFF0D0E11.toInt(), surface = 0xFF16171D.toInt(),
        primary = 0xFFD62828.toInt(), accent = 0xFFFDBB24.toInt(),
        text = 0xFFF5F4F0.toInt(), textDim = 0xFF8E929B.toInt(),
        terminalBackground = 0xFF07080A.toInt(), terminalForeground = 0xFFF1F2F5.toInt(),
    )

    private fun lightSlot(name: String = "晨光") = CustomThemeSlot(
        name = name, isDark = false,
        background = 0xFFF5F4F0.toInt(), surface = 0xFFEAE8E2.toInt(),
        primary = 0xFF0047AB.toInt(), accent = 0xFFC2410C.toInt(),
        text = 0xFF16171D.toInt(), textDim = 0xFF5F6368.toInt(),
        terminalBackground = 0xFFF8F7F4.toInt(), terminalForeground = 0xFF16171D.toInt(),
    )

    // --- fallback contract: unregistered / CARD_PRIMARY stays byte-identical ---

    @Test fun unregisteredCardsAndPrimaryKeepTheExactLegacySurfaceFill() {
        for (theme in themesUnderTest()) {
            val resolved = SurfaceOpacity.resolveSurfaceColor(
                theme.surface, theme.background, theme.text, SurfaceOpacity.MAX_PERCENT, false)
            assertEquals(resolved, ThemeColors.cardSurfaceFill(null, theme, resolved))
            assertEquals(resolved, ThemeColors.cardSurfaceFill(SurfaceTier.CARD_PRIMARY, theme, resolved))
        }
    }

    @Test fun fallbackHoldsForEveryOpacityResolutionIncludingWallpaperBackdrop() {
        for (theme in themesUnderTest()) {
            for (percent in listOf(SurfaceOpacity.MIN_PERCENT, 80, SurfaceOpacity.MAX_PERCENT)) {
                for (backdrop in listOf(false, true)) {
                    val resolved = SurfaceOpacity.resolveSurfaceColor(
                        theme.surface, theme.background, theme.text, percent, backdrop)
                    assertEquals(resolved, ThemeColors.cardSurfaceFill(null, theme, resolved))
                }
            }
        }
    }

    // --- tier derivation: well one step toward the page, auxiliary one step up ---

    @Test fun wellTierDerivesTheSurfaceLowStepExactlyLikePlainViewWells() {
        for (theme in themesUnderTest()) {
            assertEquals(
                ThemeColors.surfaceLow(theme.background, theme.surface),
                ThemeColors.cardSurfaceFill(SurfaceTier.CARD_WELL, theme, theme.surface))
            // Same tactic at opacity-resolved fills: identical inputs → the plain-view
            // well path (recolorBackground → isWell) and the card path must agree.
            val resolved = SurfaceOpacity.resolveSurfaceColor(
                theme.surface, theme.background, theme.text, 70, true)
            assertEquals(
                ThemeColors.surfaceLow(theme.background, resolved),
                ThemeColors.cardSurfaceFill(SurfaceTier.CARD_WELL, theme, resolved))
        }
    }

    @Test fun auxiliaryTierDerivesTheSurfaceHighStepAboveTheCard() {
        for (theme in themesUnderTest()) {
            assertEquals(
                ThemeColors.surfaceHigh(theme.surface, theme.text),
                ThemeColors.cardSurfaceFill(SurfaceTier.CARD_AUXILIARY, theme, theme.surface))
        }
    }

    @Test fun cardLadderStaysOrderedBetweenPageAndInkForEveryTheme() {
        // background → well → card → auxiliary. Light themes walk the ladder the
        // opposite way from dark ones, so the steps are pinned direction-agnostically:
        // well sits between page and card in luminance, auxiliary between card and ink.
        for (theme in themesUnderTest()) {
            val page = relLum(theme.background)
            val card = relLum(theme.surface)
            val ink = relLum(theme.text)
            val well = relLum(ThemeColors.cardSurfaceFill(SurfaceTier.CARD_WELL, theme, theme.surface))
            val aux = relLum(ThemeColors.cardSurfaceFill(SurfaceTier.CARD_AUXILIARY, theme, theme.surface))
            assertTrue("${theme.id}: well between page and card",
                minOf(page, card) <= well && well <= maxOf(page, card))
            assertTrue("${theme.id}: auxiliary between card and ink",
                minOf(card, ink) <= aux && aux <= maxOf(card, ink))
        }
    }

    @Test fun wellTierKeepsRealContrastAgainstItsOwnCardNeighborOnTheFourIdentityThemes() {
        // The well step must be visible but subordinate: a real step below the
        // card fill, and never an ink-heavy repaint of it.
        for (id in listOf("mascot-lavender", "constructivist-workstation",
                "constructivist-cobalt", "constructivist-amber")) {
            val theme = ThemeCatalog.resolve(id)
            val well = ThemeColors.cardSurfaceFill(SurfaceTier.CARD_WELL, theme, theme.surface)
            val card = theme.surface
            assertTrue("$id: well differs from card", well != card)
            assertTrue("$id: step stays subtle (<2x contrast)",
                ThemeColors.contrast(well, card) < 2.0)
        }
    }

    // --- the 25-entry 三档归档 registry table ---

    @Test fun registryCoversExactlyTheTwentyFiveIdBearingMaterialCards() {
        assertEquals(25, cardTierEntries().size)
    }

    @Test fun registrySentinelsMatchTheArchiveVerdicts() {
        // detail: 一级决策卡 stays card, 二级数据卡 become well
        assertEquals(SurfaceTier.CARD_PRIMARY, ThemeColors.surfaceTierFor("card_header_dashboard"))
        assertEquals(SurfaceTier.CARD_WELL, ThemeColors.surfaceTierFor("card_detail_ssh_connect"))
        assertEquals(SurfaceTier.CARD_WELL, ThemeColors.surfaceTierFor("card_detail_specs"))
        assertEquals(SurfaceTier.CARD_WELL, ThemeColors.surfaceTierFor("card_hardware_specs_editor"))
        assertEquals(SurfaceTier.CARD_WELL, ThemeColors.surfaceTierFor("card_storage_manager"))
        assertEquals(SurfaceTier.CARD_WELL, ThemeColors.surfaceTierFor("card_network_port_forwarding"))
        // settings: 决策/表单区卡 all stay card
        for (id in listOf("card_settings_mirrors", "card_settings_virtualization", "card_settings_terminal",
                "card_settings_about", "card_settings_theme", "card_settings_accelerator",
                "card_settings_diagnostics")) {
            assertEquals(id, SurfaceTier.CARD_PRIMARY, ThemeColors.surfaceTierFor(id))
        }
        // wizard: readouts → well, selections/configs → card
        assertEquals(SurfaceTier.CARD_WELL, ThemeColors.surfaceTierFor("card_wizard_avf_detection"))
        assertEquals(SurfaceTier.CARD_WELL, ThemeColors.surfaceTierFor("card_network_diag"))
        assertEquals(SurfaceTier.CARD_WELL, ThemeColors.surfaceTierFor("card_spec_summary"))
        for (id in listOf("card_distro_selector", "card_storage_config", "card_mirror_select",
                "card_hardware_quota", "card_startup_tasks", "card_download_panel")) {
            assertEquals(id, SurfaceTier.CARD_PRIMARY, ThemeColors.surfaceTierFor(id))
        }
        // list/item cards
        assertEquals(SurfaceTier.CARD_PRIMARY, ThemeColors.surfaceTierFor("instance_card"))
        assertEquals(SurfaceTier.CARD_WELL, ThemeColors.surfaceTierFor("card_pf_rule"))
        // about (报告清单外): status readout → well
        assertEquals(SurfaceTier.CARD_WELL, ThemeColors.surfaceTierFor("card_version_update"))
    }

    @Test fun semanticDangerZoneAndWellNamespaceStayOutsideTheTierRegistry() {
        // The danger zone is the semantic-stroke exception and rides the unregistered
        // fallback; well_ ids belong to the plain-view banner path, never the card table.
        assertNull(ThemeColors.surfaceTierFor("card_danger_zone"))
        for (id in cardTierEntries().keys) {
            assertFalse("$id must not squat on the well_ namespace", ThemeColors.isWellId(id))
            assertFalse("$id must stay outside the semantic danger exception",
                ThemeColors.isSemanticStrokeName(id))
        }
    }

    @Test fun unregisteredNamesFallBackToNull() {
        assertNull(ThemeColors.surfaceTierFor(""))
        assertNull(ThemeColors.surfaceTierFor("root_container"))
        assertNull(ThemeColors.surfaceTierFor("well_ssh_bridge"))
        assertNull(ThemeColors.surfaceTierFor("txt_instance_name"))
        assertNull(ThemeColors.surfaceTierFor("card_header_dashboardX"))
        assertNull(ThemeColors.surfaceTierFor("card_header_dashboard_suffix"))
    }

    @Test fun registryKeysAreWellFormedLayoutIds() {
        val shape = Regex("^[a-z][a-z0-9_]*$")
        for (id in cardTierEntries().keys) {
            assertTrue("$id is not a valid layout id shape", shape.matches(id))
        }
    }

    private fun cardTierEntries(): Map<String, SurfaceTier> =
        listOf(
            "card_header_dashboard", "card_detail_ssh_connect", "card_detail_specs",
            "card_hardware_specs_editor", "card_storage_manager", "card_network_port_forwarding",
            "card_settings_mirrors", "card_settings_virtualization", "card_settings_terminal",
            "card_settings_about", "card_settings_theme", "card_settings_accelerator",
            "card_settings_diagnostics", "card_wizard_avf_detection", "card_distro_selector",
            "card_storage_config", "card_network_diag", "card_mirror_select",
            "card_hardware_quota", "card_startup_tasks", "card_spec_summary",
            "card_download_panel", "instance_card", "card_pf_rule", "card_version_update",
        ).associateWith { ThemeColors.surfaceTierFor(it) ?: error("$it missing from registry") }
}
