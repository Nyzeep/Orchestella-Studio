package io.github.nyzeep.orchestella.ui.theme

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ThemeColorsTest {
    @Test fun everyExistingPaletteHasUniquePersistentIdAndCompleteOpaqueAnsiColors() {
        assertEquals(ThemeCatalog.all.size, ThemeCatalog.all.map { it.id }.toSet().size)
        for (theme in ThemeCatalog.all) {
            assertSame(theme, ThemeCatalog.resolve(theme.id))
            assertEquals(theme.displayName, 16, theme.ansi.size)
            assertTrue(theme.displayName, theme.ansi.all { it ushr 24 == 255 })
        }
    }

    @Test fun lightAndDarkPalettesHaveReadableButtonInkAndMutedLabels() {
        for (theme in ThemeCatalog.all) {
            assertTrue("${theme.id}: button ink", ThemeColors.contrast(ThemeColors.onColor(theme.primary), theme.primary) >= 4.5)
            val muted = ThemeColors.color(theme, ThemeColors.Role.MUTED)
            assertTrue("${theme.id}: secondary text", ThemeColors.contrast(muted, theme.surface) >= 4.5)
        }
        assertEquals(0xFF000000.toInt(), ThemeColors.onColor(ThemeCatalog.constructivistAmber.primary))
        assertEquals(0xFFFFFFFF.toInt(), ThemeColors.onColor(ThemeCatalog.constructivistCobalt.primary))
    }

    @Test fun sourceRolesStillResolveAgainstTheNewPaletteAfterRepeatedSwitches() {
        val textRole = ThemeColors.textRole(0xFF8E929B.toInt())
        val actionRole = ThemeColors.backgroundRole(0xFFD62828.toInt())
        for (theme in listOf(ThemeCatalog.constructivistWorkstation, ThemeCatalog.claudeLight,
            ThemeCatalog.github, ThemeCatalog.constructivistAmber, ThemeCatalog.catppuccin)) {
            assertEquals(theme.primary, ThemeColors.color(theme, actionRole))
            assertTrue(ThemeColors.contrast(ThemeColors.color(theme, textRole), theme.surface) >= 4.5)
            assertEquals(theme.background, ThemeColors.color(theme, ThemeColors.Role.BACKGROUND))
        }
    }

    @Test fun legacyDarkSurfaceHexesResolveToSurfaceEvenWhenTheyMatchLightTextInk() {
        // Session 17 root cause: #16171D is both every dashboard XML card fill and the
        // light Constructivist text ink. Role inference must pin these to SURFACE so
        // light themes repaint them light instead of "text ink" dark blocks.
        for (hex in intArrayOf(0x16171D, 0x1A1C22, 0x0C0D11)) {
            assertEquals("0x" + hex.toString(16), ThemeColors.Role.SURFACE,
                ThemeColors.backgroundRole(0xFF000000.toInt() or hex))
            assertTrue("isLegacySurfaceHex 0x" + hex.toString(16),
                ThemeColors.isLegacySurfaceHex(0xFF000000.toInt() or hex))
        }
        assertFalse(ThemeColors.isLegacySurfaceHex(0xFFFFFFFF.toInt()))
        assertFalse(ThemeColors.isLegacySurfaceHex(0xFFD62828.toInt()))
    }

    @Test fun onlyNestedDashboardContentIsTransparentToThePageWallpaper() {
        assertTrue(ThemeColors.isWallpaperContentWrapper("root_container", "root_coordinator"))
        assertFalse(ThemeColors.isWallpaperContentWrapper("root_coordinator", "content"))
        assertFalse(ThemeColors.isWallpaperContentWrapper("card_settings_theme", "root_coordinator"))
        assertFalse(ThemeColors.isWallpaperContentWrapper("root_container", "settings_root_coordinator"))
        assertFalse(ThemeColors.isWallpaperContentWrapper("root_container", "dialog_root"))
    }

    @Test fun blindSpotSurfaceShadesResolveToTheSurfaceRole() {
        // Audit S3: legacy dark surface tints must not depend on the luminance
        // heuristic band; they are explicitly SURFACE (card backgrounds).
        for (argb in intArrayOf(
            0xFF121318.toInt(), 0xFF0B0C0F.toInt(), 0xFF0C0D10.toInt(), 0xFF0D0E12.toInt(),
            0xFF0E0F14.toInt(), 0xFF1F2026.toInt(), 0xFF1E2028.toInt()
        )) {
            assertEquals("backgroundRole(${Integer.toHexString(argb)})",
                ThemeColors.Role.SURFACE, ThemeColors.backgroundRole(argb))
        }
    }

    @Test fun legacyMutedGrayTextResolvesToTheMutedRole() {
        // Audit S3: #CCCCCC sits at the upper edge of the heuristic band;
        // pin it explicitly to MUTED.
        assertEquals(ThemeColors.Role.MUTED, ThemeColors.textRole(0xFFCCCCCC.toInt()))
    }

    @Test fun allFontSizesRoundTripThroughTheZeroBasedSliderIncludingEndpoints() {
        for (sp in 10..22) assertEquals(sp, ThemeColors.fontSize(ThemeColors.fontProgress(sp)))
        assertEquals(10, ThemeColors.fontSize(-50))
        assertEquals(22, ThemeColors.fontSize(50))
        assertEquals(0f, ThemeColors.boundedSpacing(Float.NaN, 0.2f), 0f)
        assertEquals(0.2f, ThemeColors.boundedSpacing(0.5f, 0.2f), 0f)
    }

    /** Contrast against pure black is monotonic in luminance — the ordering proxy. */
    private fun relLum(color: Int): Double = ThemeColors.contrast(color, 0xFF000000.toInt())

    private fun assertBetween(lower: Double, upper: Double, value: Double, label: String) {
        assertTrue("$label $value between [$lower, $upper]",
            value > min(lower, upper) && value < max(lower, upper))
    }

    @Test fun surfaceTierLadderStaysStrictlyBetweenPageAndInkForEveryTheme() {
        // B-lane surface 三档: low sits between page and card, high between card
        // and ink. Ordering via luminance works for light AND dark palettes.
        for (theme in ThemeCatalog.all) {
            val low = ThemeColors.surfaceLow(theme)
            val high = ThemeColors.surfaceHigh(theme)
            assertBetween(relLum(theme.background), relLum(theme.surface), relLum(low),
                "${theme.id}: surfaceLow")
            assertBetween(relLum(theme.surface), relLum(theme.text), relLum(high),
                "${theme.id}: surfaceHigh")
            // The stroke register stays stronger than the fill register so hairline
            // edges never dissolve into a raised fill.
            assertTrue("${theme.id}: hairline != surfaceHigh step",
                Math.abs(relLum(ThemeColors.hairline(theme)) - relLum(theme.surface)) >
                    Math.abs(relLum(high) - relLum(theme.surface)))
        }
    }

    @Test fun blendRoundsHalfUpPerChannelAndKeepsEndpointsByteExact() {
        // Endpoints: fraction 0/1 must return the input byte-for-byte — rounding
        // may not nudge a corner case the old truncation happened to satisfy.
        assertEquals(0xFF123456.toInt(), ThemeColors.blend(0xFF123456.toInt(), 0xFFABCDEF.toInt(), 0f))
        assertEquals(0xFFABCDEF.toInt(), ThemeColors.blend(0xFF123456.toInt(), 0xFFABCDEF.toInt(), 1f))
        // The canonical half-step: black→white midpoint is 128 per channel, not the
        // truncated 127 the old formula produced.
        assertEquals(0xFF808080.toInt(), ThemeColors.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        // A .5 channel remainder rounds up instead of vanishing (ZD-W4 semantics).
        assertEquals(0xFF000001.toInt(), ThemeColors.blend(0xFF000000.toInt(), 0xFF000001.toInt(), 0.5f))
    }

    @Test fun opaqueBlendNeverLeaksATranslucentAlphaAtAnyStep() {
        // ZD-W4's core failure mode: a blend of two opaque colors must paint an
        // opaque pixel. The alpha channel of opaque endpoints sums to 255 exactly
        // (±float epsilon); rounding holds it at 255 across every blend fraction
        // the palette math actually uses (readable() walks step/20f, too).
        for (step in 0..20) {
            val fraction = step / 20f
            val blended = ThemeColors.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), fraction)
            assertEquals("f=$fraction alpha", 255, blended ushr 24 and 255)
        }
    }

    @Test fun everyCatalogThemeDerivesOnlyOpaqueBlends() {
        // Every ThemeColors derivation feeds on opaque theme roles, so each one
        // must come out fully opaque — surfaceHigh alpha 254 was the ticket's
        // report and rounding is the guarantee it never recurs.
        for (theme in ThemeCatalog.all) {
            assertEquals("${theme.id}: surfaceLow", 255, ThemeColors.surfaceLow(theme) ushr 24 and 255)
            assertEquals("${theme.id}: surfaceHigh", 255, ThemeColors.surfaceHigh(theme) ushr 24 and 255)
            assertEquals("${theme.id}: hairline", 255, ThemeColors.hairline(theme) ushr 24 and 255)
            assertEquals("${theme.id}: actionEdge", 255, ThemeColors.actionEdge(theme) ushr 24 and 255)
            assertEquals("${theme.id}: disabledInk", 255, ThemeColors.disabledInk(theme) ushr 24 and 255)
            assertEquals("${theme.id}: outline", 255, ThemeColors.color(theme, ThemeColors.Role.OUTLINE) ushr 24 and 255)
            assertEquals("${theme.id}: muted", 255, ThemeColors.color(theme, ThemeColors.Role.MUTED) ushr 24 and 255)
            val pressed = ThemeColors.pressedFill(theme.primary, ThemeColors.onColor(theme.primary))
            assertEquals("${theme.id}: pressedFill", 255, pressed ushr 24 and 255)
            val disabled = ThemeColors.disabledFill(theme, theme.primary)
            assertEquals("${theme.id}: disabledFill", 255, disabled ushr 24 and 255)
        }
    }

    @Test fun pureBlackOe3LadderStaysFullyOpaqueThroughBlendOrConstant() {
        // Oe3Colors is read-only here (ui/oe3 is another lane's surface); this pins
        // the ZD-W4 deliverable: in pure black mode the resolved surfaceHigh — and
        // the whole four-value ladder — carries alpha 255, matching the spec/JSON
        // constants byte-for-byte.
        val dark = oe3DarkStandIn()
        val tokens = io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors.resolve(dark, pureBlack = true)
        assertEquals(0xFF000000.toInt(), tokens.background)
        assertEquals(0xFF0B0B0C.toInt(), tokens.surface)
        assertEquals(0xFF131315.toInt(), tokens.surfaceRaised)
        assertEquals(0xFF1C1C1F.toInt(), tokens.surfaceHigh)
        for (role in intArrayOf(tokens.background, tokens.surface, tokens.surfaceRaised, tokens.surfaceHigh)) {
            assertEquals("pure black ladder alpha", 255, role ushr 24 and 255)
        }
        // The blend-fed dark path (pure black off) must be opaque as well — that is
        // the surfaceHigh the rounding fix actually recomputes.
        val resolved = io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors.resolve(dark)
        assertEquals("blend-fed surfaceRaised alpha", 255, resolved.surfaceRaised ushr 24 and 255)
        assertEquals("blend-fed surfaceHigh alpha", 255, resolved.surfaceHigh ushr 24 and 255)
    }

    /** Dark theme stand-in so this file never reaches into ui/oe3 test fixtures. */
    private fun oe3DarkStandIn() = AppTheme(
        id = "oe3-dark-standin",
        displayName = "Oe3 dark stand-in",
        isDark = true,
        background = 0xFF0D0E11.toInt(),
        surface = 0xFF16171D.toInt(),
        surfaceTranslucent = 0xE616171D.toInt(),
        primary = 0xFFD62828.toInt(),
        accent = 0xFFFDBB24.toInt(),
        text = 0xFFF5F4F0.toInt(),
        textDim = 0xFF8E929B.toInt(),
        glow = 0xFFD62828.toInt(),
        terminalBackground = 0xFF07080A.toInt(),
        terminalForeground = 0xFFF1F2F5.toInt(),
        ansi = List(16) { 0xFF000000.toInt() },
    )

    /** The retired truncation semantics, inlined as the ≤1-step budget's reference. */
    private fun truncationBlend(first: Int, second: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int =
            (((first ushr shift and 255) * (1f - f)) + ((second ushr shift and 255) * f)).toInt()
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun maxChannelDelta(first: Int, second: Int): Int =
        (0..3).maxOf { shift ->
            Math.abs((first ushr (shift * 8) and 255) - (second ushr (shift * 8) and 255))
        }

    @Test fun roundingMovesPressedFillByAtMostOneStepPerChannel() {
        // ZD-W4 budget: the pressedFill 0.10f wash (and every single-step blend
        // consumer) may shift by at most 1/255 per channel against the retired
        // truncation semantics — no repaint, just the rounding tail.
        for (theme in ThemeCatalog.all) {
            val ink = ThemeColors.onColor(theme.primary)
            val delta = maxChannelDelta(
                truncationBlend(theme.primary, ink, 0.10f),
                ThemeColors.pressedFill(theme.primary, ink),
            )
            assertTrue("${theme.id}: pressedFill delta $delta", delta <= 1)
            val ghost = ThemeColors.pressedFill(theme.surface, theme.text)
            assertTrue(
                "${theme.id}: ghost pressedFill delta",
                maxChannelDelta(truncationBlend(theme.surface, theme.text, 0.10f), ghost) <= 1,
            )
        }
    }

    @Test fun roundingMovesOe3ResolutionByAtMostTwoStepsPerChannel() {
        // Oe3Colors.resolve chains two blends (surface→raised→high), so the composed
        // shift against the truncation reference is bounded by 1 per blend step.
        // The flat tokens (background/surface/onSurface/primary/accent/error) are
        // constants or pass-throughs and must not move at all.
        val dark = oe3DarkStandIn()
        val text = dark.text
        fun raised(theme: AppTheme, blend: (Int, Int, Float) -> Int): Int =
            blend(theme.surface, text, 0.025f)
        fun high(theme: AppTheme, blend: (Int, Int, Float) -> Int): Int =
            blend(raised(theme, blend), text, 0.045f)
        val resolved = io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors.resolve(dark)
        assertEquals(dark.surface, resolved.surface)
        assertTrue(
            "surfaceRaised delta",
            maxChannelDelta(raised(dark, ::truncationBlend), resolved.surfaceRaised) <= 1,
        )
        assertTrue(
            "surfaceHigh delta",
            maxChannelDelta(high(dark, ::truncationBlend), resolved.surfaceHigh) <= 2,
        )
        assertTrue(
            "primaryContainer delta",
            maxChannelDelta(
                truncationBlend(dark.surface, dark.primary, 0.22f),
                resolved.primaryContainer,
            ) <= 1,
        )
    }

    @Test fun surfaceTierDerivationsKeepTheFourIdentityThemesOnPalette() {
        // 旗舰淡紫工控浅色 (UI-14 白底紫强调) + 构成主义三色 (dark): exact blends,
        // hand-computed so a regression in the derivation constants fails loudly.
        // mascot-light surfaceLow 是 background #FFFFFF → surface #F6F4F8 的 0.5
        // 中点 0xFBFAFC；surfaceHigh 以 ink #1A1720 按 0.06 混入 surface 得 0xE9E7EB。
        val mascot = ThemeCatalog.mascotLight
        assertEquals(0xFFFBFAFC.toInt(), ThemeColors.surfaceLow(mascot))
        assertEquals(0xFFE9E7EB.toInt(), ThemeColors.surfaceHigh(mascot))
        for (id in listOf("constructivist-workstation", "constructivist-cobalt", "constructivist-amber")) {
            val theme = ThemeCatalog.resolve(id)
            val low = ThemeColors.surfaceLow(theme)
            val high = ThemeColors.surfaceHigh(theme)
            assertTrue("$id: well tinted, not pure grey",
                (low ushr 16 and 255) != (low ushr 8 and 255) || (low ushr 8 and 255) != (low and 255))
            assertTrue("$id: raised stays subtle (measured 1.12-1.15 across the four identities)",
                ThemeColors.contrast(high, theme.surface) < 1.25)
        }
    }

    @Test fun opacityResolvedOverloadsMatchTheThemeDerivations() {
        for (theme in ThemeCatalog.all) {
            assertEquals(ThemeColors.surfaceLow(theme),
                ThemeColors.surfaceLow(theme.background, theme.surface))
            assertEquals(ThemeColors.surfaceHigh(theme),
                ThemeColors.surfaceHigh(theme.surface, theme.text))
        }
    }

    @Test fun pressedFillIsASubtleStateLayerNotARepaint() {
        for (theme in ThemeCatalog.all) {
            val ink = ThemeColors.onColor(theme.primary)
            val pressed = ThemeColors.pressedFill(theme.primary, ink)
            val delta = ThemeColors.contrast(pressed, theme.primary)
            assertTrue("${theme.id}: pressed visible", delta > 1.0)
            // M3 state layers land near ~1.3-1.4 contrast on dark primaries
            // (mascot-light primary measures ~1.38); anything beyond ~1.5
            // would read as a repaint instead of a press.
            assertTrue("${theme.id}: pressed subtle", delta < 1.50)
            val ghost = ThemeColors.pressedFill(theme.surface, ThemeColors.color(theme, ThemeColors.Role.TEXT))
            assertTrue("${theme.id}: ghost pressed visible", ThemeColors.contrast(ghost, theme.surface) > 1.0)
        }
    }
}
