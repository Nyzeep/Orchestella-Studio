package io.github.nyzeep.orchestella.ui.oe3

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeScheme
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Glass
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM contract for the Oe3 → Material3 projection. */
class Oe3ThemeTest {

    private val theme = oe3TestTheme(
        dark = true,
        background = 0xFF0A0B0E.toInt(),
        surface = 0xFF14151A.toInt(),
        surfaceTranslucent = 0xE614151A.toInt(),
        primary = 0xFF7C9CFF.toInt(),
        accent = 0xFFFFB4AB.toInt(),
        text = 0xFFF4F5F7.toInt(),
        textDim = 0xFF9BA1AB.toInt(),
    )

    @Test
    fun materialSchemeCarriesEveryOe3Role() {
        val tokens = Oe3Colors.resolve(theme)
        val scheme = Oe3ThemeScheme.colorScheme(tokens, theme.isDark)

        assertEquals(Color(tokens.background), scheme.background)
        assertEquals(Color(tokens.surface), scheme.surface)
        assertEquals(Color(tokens.surfaceRaised), scheme.surfaceContainer)
        assertEquals(Color(tokens.surfaceHigh), scheme.surfaceContainerHigh)
        assertEquals(Color(tokens.onSurface), scheme.onSurface)
        assertEquals(Color(tokens.onSurfaceVariant), scheme.onSurfaceVariant)
        assertEquals(Color(tokens.primary), scheme.primary)
        assertEquals(Color(tokens.onPrimary), scheme.onPrimary)
        assertEquals(Color(tokens.accent), scheme.secondary)
        assertEquals(Color(tokens.error), scheme.error)
        assertEquals(Color(tokens.divider), scheme.outlineVariant)
        assertEquals(Color(tokens.onSurface), scheme.inverseSurface)
        assertEquals(Color(tokens.surface), scheme.inverseOnSurface)
    }

    @Test
    fun materialTypographyUsesBodyWeightForTheDefaultTextStyle() {
        val typography = Oe3ThemeScheme.typography()
        assertEquals(14f, typography.bodyLarge.fontSize.value, 0.001f)
        assertEquals(FontWeight.Normal, typography.bodyLarge.fontWeight)
        assertEquals(FontWeight.SemiBold, typography.titleSmall.fontWeight)
    }

    @Test
    fun materialShapesUseTheFiveStepLadder() {
        val shapes = Oe3ThemeScheme.shapes()
        assertEquals(RoundedCornerShape(Oe3Shapes.xs), shapes.extraSmall)
        assertEquals(RoundedCornerShape(Oe3Shapes.sm), shapes.small)
        assertEquals(RoundedCornerShape(Oe3Shapes.md), shapes.medium)
        assertEquals(RoundedCornerShape(Oe3Shapes.lg), shapes.large)
        assertEquals(RoundedCornerShape(Oe3Shapes.xl), shapes.extraLarge)
    }

    @Test
    fun materialTypographyIsTheOe3Scale() {
        val typography = Oe3ThemeScheme.typography()
        assertEquals(30f, typography.headlineMedium.fontSize.value, 0.001f)
        assertEquals(17f, typography.titleMedium.fontSize.value, 0.001f)
        assertEquals(14f, typography.bodyMedium.fontSize.value, 0.001f)
        assertEquals(10f, typography.labelSmall.fontSize.value, 0.001f)
    }

    @Test
    fun glassLevelsResolveToTheSpecifiedBlurAndTint() {
        val none = Oe3Glass.resolve(theme, Oe3GlassLevel.None)
        assertEquals(theme.surface, none.tint)
        assertEquals(0.dp, none.blurRadius)
        assertEquals(1f, none.tintAlpha, 0.001f)

        val standard = Oe3Glass.resolve(theme, Oe3GlassLevel.Standard)
        assertEquals(theme.surfaceTranslucent or 0xFF000000.toInt(), standard.tint)
        assertEquals(16.dp, standard.blurRadius)
        assertEquals(0.72f, standard.tintAlpha, 0.001f)
        assertEquals(0xF5, standard.fallbackTint ushr 24)

        val ultra = Oe3Glass.resolve(theme, Oe3GlassLevel.Ultra)
        assertEquals(28.dp, ultra.blurRadius)
        assertEquals(0.55f, ultra.tintAlpha, 0.001f)
        assertEquals(0xF5, ultra.fallbackTint ushr 24)
        assertEquals(theme.surface and 0x00FFFFFF, ultra.fallbackTint and 0x00FFFFFF)
    }

    @Test
    fun pureBlackGlassUsesTheBlackSurfaceInsteadOfThePaletteSurface() {
        val glass = Oe3Glass.resolve(theme, Oe3GlassLevel.Standard, pureBlack = true)
        assertEquals(0x0B0B0C, glass.tint and 0x00FFFFFF)
        assertEquals(0x0B0B0C, glass.fallbackTint and 0x00FFFFFF)
    }
}
