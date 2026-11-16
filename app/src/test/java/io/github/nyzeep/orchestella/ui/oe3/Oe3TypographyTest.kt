package io.github.nyzeep.orchestella.ui.oe3

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TypeScale
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM contract for the Oe3 type scale (spec §2.4). */
class Oe3TypographyTest {

    @Test
    fun everyRoleMatchesTheDocumentedSizeLineHeightAndWeight() {
        val brand = Oe3Typography.spec(Oe3TextRole.Brand)
        assertEquals(24f, brand.sizeSp, 0.001f)
        assertEquals(30f, brand.lineHeightSp, 0.001f)
        assertEquals(FontWeight.SemiBold, brand.weight)

        val pageTitle = Oe3Typography.spec(Oe3TextRole.PageTitle)
        assertEquals(30f, pageTitle.sizeSp, 0.001f)
        assertEquals(36f, pageTitle.lineHeightSp, 0.001f)
        assertEquals(FontWeight.SemiBold, pageTitle.weight)

        val section = Oe3Typography.spec(Oe3TextRole.Section)
        assertEquals(17f, section.sizeSp, 0.001f)
        assertEquals(22f, section.lineHeightSp, 0.001f)
        assertEquals(FontWeight.Medium, section.weight)

        val body = Oe3Typography.spec(Oe3TextRole.Body)
        assertEquals(14f, body.sizeSp, 0.001f)
        assertEquals(20f, body.lineHeightSp, 0.001f)
        assertEquals(FontWeight.Normal, body.weight)

        val bodyStrong = Oe3Typography.spec(Oe3TextRole.BodyStrong)
        assertEquals(14f, bodyStrong.sizeSp, 0.001f)
        assertEquals(FontWeight.SemiBold, bodyStrong.weight)

        val caption = Oe3Typography.spec(Oe3TextRole.Caption)
        assertEquals(12f, caption.sizeSp, 0.001f)
        assertEquals(16f, caption.lineHeightSp, 0.001f)

        val nav = Oe3Typography.spec(Oe3TextRole.NavLabel)
        assertEquals(10f, nav.sizeSp, 0.001f)
        assertEquals(14.5f, nav.lineHeightSp, 0.001f)
        assertEquals(FontWeight.Medium, nav.weight)

        val mono = Oe3Typography.spec(Oe3TextRole.Mono)
        assertEquals(13f, mono.sizeSp, 0.001f)
        assertEquals(18f, mono.lineHeightSp, 0.001f)
    }

    @Test
    fun stylesKeepTheBaseSizeForThePlatformToScale() {
        val style = Oe3Typography.styleStatic(Oe3TextRole.PageTitle)
        assertEquals(30f, style.fontSize.value, 0.001f)
        assertEquals(36f, style.lineHeight.value, 0.001f)
        assertEquals(FontWeight.SemiBold, style.fontWeight)
        assertEquals(FontFamily.Default, style.fontFamily)
    }

    @Test
    fun monoIsAMonospaceRole() {
        assertEquals(FontFamily.Monospace, Oe3Typography.styleStatic(Oe3TextRole.Mono).fontFamily)
    }

    /**
     * UI3-12 mono wiring: the shell's 等宽 choice overrides the base family
     * for every non-mono role, and the mono role stays monospace regardless.
     */
    @Test
    fun monoOverrideReplacesTheDefaultFamilyOnly() {
        assertEquals(
            FontFamily.Monospace,
            Oe3Typography.styleStatic(Oe3TextRole.Body, baseFamily = FontFamily.Monospace).fontFamily,
        )
        assertEquals(
            FontFamily.Monospace,
            Oe3Typography.styleStatic(Oe3TextRole.Mono, baseFamily = FontFamily.Monospace).fontFamily,
        )
        assertEquals(
            FontFamily.Default,
            Oe3Typography.styleStatic(Oe3TextRole.Body, baseFamily = null).fontFamily,
        )
    }

    @Test
    fun appScaleClampsToTheDocumentedBand() {
        assertEquals(0.85f, Oe3TypeScale.clamp(0.5f), 0.0001f)
        assertEquals(0.85f, Oe3TypeScale.clamp(0.85f), 0.0001f)
        assertEquals(1.30f, Oe3TypeScale.clamp(1.30f), 0.0001f)
        assertEquals(1.30f, Oe3TypeScale.clamp(2f), 0.0001f)
        assertEquals(1.30f, Oe3TypeScale.clamp(100f), 0.0001f)
    }

    @Test
    fun degenerateAppScaleFallsBackToOne() {
        assertEquals(1f, Oe3TypeScale.clamp(0f), 0.0001f)
        assertEquals(1f, Oe3TypeScale.clamp(-1f), 0.0001f)
        assertEquals(1f, Oe3TypeScale.clamp(Float.NaN), 0.0001f)
        assertEquals(1f, Oe3TypeScale.clamp(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun systemFontScaleIsPreservedAndTheAppScaleIsAppliedOnce() {
        assertEquals(1f, Oe3TypeScale.effective(1f, 1f), 0.0001f)
        assertEquals(1.3f, Oe3TypeScale.effective(1f, 1.3f), 0.0001f)
        assertEquals(0.85f, Oe3TypeScale.effective(1f, 0.5f), 0.0001f)
        // A user who runs the system at 200% keeps that choice; the app
        // override multiplies it instead of replacing it.
        assertEquals(2.6f, Oe3TypeScale.effective(2f, 1.3f), 0.0001f)
        // A degenerate platform value cannot zero out all text.
        assertEquals(1.3f, Oe3TypeScale.effective(0f, 1.3f), 0.0001f)
    }

    @Test
    fun densityOverrideKeepsDensityAndChangesOnlyFontScale() {
        val base = Density(density = 2.75f, fontScale = 1f)
        val scaled = Oe3TypeScale.density(base, appScale = 1.3f)
        assertEquals(2.75f, scaled.density, 0.0001f)
        assertEquals(1.3f, scaled.fontScale, 0.0001f)
        assertTrue("font scale must not be reduced to zero", scaled.fontScale > 0f)
    }
}
