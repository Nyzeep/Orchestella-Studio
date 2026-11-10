package io.github.nyzeep.orchestella.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-driven pin of the id-name contracts that survived as the runtime
 * FALLBACK after the 2026-11-11 U1 registry round: well banners, semantic
 * danger strokes, brand artwork exclusions, status badges and the wallpaper
 * wrapper pair. Each row is a real id or a realistic shape; regressions in the
 * tables change what whole screens look like, so they fail loudly here.
 */
class ThemeNameContractsTest {
    // --- well_ prefix: page-level banners take the surfaceLow tier (plain views) ---

    @Test fun wellBannerIdsKeepTheSurfaceLowFallbackTier() {
        // The F-lane measured seven well points, all LinearLayouts.
        val wellIds = listOf(
            "well_ssh_bridge", "well_telemetry_graphic", "well_hw_readout_avf",
            "well_hw_readout_cpu", "well_hw_readout_mem", "well_detail_specs", "well_port_empty",
        )
        for (id in wellIds) assertTrue(id, ThemeColors.isWellId(id))
    }

    @Test fun nonBannerNamesNeverRideTheWellPrefix() {
        for (id in listOf("", "well", "Well_ssh", "card_well_demo", "card_detail_specs",
                "instance_card", "wellx_marker")) {
            assertFalse(id, ThemeColors.isWellId(id))
        }
    }

    // --- danger: the red-ring stroke survives every repaint ---

    @Test fun dangerZoneNamesKeepTheirSemanticStroke() {
        for (name in listOf("card_danger_zone", "danger_actions", "row_danger_confirm")) {
            assertTrue(name, ThemeColors.isSemanticStrokeName(name))
        }
        for (name in listOf("card_header_dashboard", "card_detail_specs", "well_ssh_bridge", "")) {
            assertFalse(name, ThemeColors.isSemanticStrokeName(name))
        }
    }

    // --- artwork: brand pixels are never tinted ---

    @Test fun artworkNamesStayExcludedFromTinting() {
        val artworkIds = listOf(
            "img_distro_ubuntu", "logo_header", "brand_banner", "qr_payment",
            "screenshot_preview", "preview_image_card",
        )
        for (id in artworkIds) assertTrue(id, ThemeColors.isArtworkName(id))
        // Control icons and UI chrome must NOT be swept up by the artwork net.
        for (id in listOf("ic_sym_bolt", "swatch_theme_mascot", "img_author_avatar",
                "card_version_update", "")) {
            assertFalse(id, ThemeColors.isArtworkName(id))
        }
    }

    // --- status badges keep their ink ---

    @Test fun statusBadgeNamesStayOutsideSurfaceRepaints() {
        val statusIds = listOf(
            "status_badge", "avf_badge", "badge_cfg_zone", "ram_safety_zone", "storage_warning",
            "txt_storage_warning", "net_test_result", "download_status", "runtime_badge", "fps_ping",
        )
        for (id in statusIds) assertTrue(id, ThemeColors.isStatusName(id))
        for (id in listOf("card_detail_specs", "txt_instance_name", "")) {
            assertFalse(id, ThemeColors.isStatusName(id))
        }
    }

    // --- wallpaper wrapper pair stays exact (no prefix, no fuzzy ancestor) ---

    @Test fun onlyTheDashboardContentWrapperIsTransparentToThePageWallpaper() {
        assertTrue(ThemeColors.isWallpaperContentWrapper("root_container", "root_coordinator"))
        assertFalse(ThemeColors.isWallpaperContentWrapper("root_container", "root_container"))
        assertFalse(ThemeColors.isWallpaperContentWrapper("root_coordinator", "root_coordinator"))
        assertFalse(ThemeColors.isWallpaperContentWrapper("card_header_dashboard", "root_coordinator"))
        assertFalse(ThemeColors.isWallpaperContentWrapper("", ""))
    }
}
