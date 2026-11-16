package io.github.nyzeep.orchestella.ui.oe3.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3ColorTokens
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Glass
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassTokens
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3MotionTokens
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TypeScale
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.theme.AppTheme

@Immutable
data class Oe3ThemeInfo(
    val isDark: Boolean,
    val pureBlack: Boolean,
    val glassLevel: Oe3GlassLevel,
    val appTextScale: Float,
    val fontScale: Float,
    val reducedMotion: Boolean,
)

val LocalOe3Colors = staticCompositionLocalOf<Oe3ColorTokens> {
    error("Oe3 components must be composed inside Oe3Theme")
}

val LocalOe3Motion = staticCompositionLocalOf { Oe3Motion.standard() }

val LocalOe3Glass = staticCompositionLocalOf<Oe3GlassTokens> {
    error("Oe3 components must be composed inside Oe3Theme")
}

val LocalOe3ThemeInfo = staticCompositionLocalOf {
    Oe3ThemeInfo(
        isDark = true,
        pureBlack = false,
        glassLevel = Oe3GlassLevel.None,
        appTextScale = 1f,
        fontScale = 1f,
        reducedMotion = false,
    )
}

/** Ambient accessors for the Oe3 primitives. */
object Oe3ThemeValues {
    val colors: Oe3ColorTokens
        @Composable get() = LocalOe3Colors.current
    val motion: Oe3MotionTokens
        @Composable get() = LocalOe3Motion.current
    val glass: Oe3GlassTokens
        @Composable get() = LocalOe3Glass.current
    val info: Oe3ThemeInfo
        @Composable get() = LocalOe3ThemeInfo.current
}

/**
 * Pure Material3 projection of the Oe3 tokens, so the mapping is testable
 * without a composition.
 */
object Oe3ThemeScheme {

    fun colorScheme(tokens: Oe3ColorTokens, isDark: Boolean): ColorScheme {
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = Color(tokens.primary),
            onPrimary = Color(tokens.onPrimary),
            primaryContainer = Color(tokens.primaryContainer),
            onPrimaryContainer = Color(tokens.onSurface),
            background = Color(tokens.background),
            onBackground = Color(tokens.onSurface),
            surface = Color(tokens.surface),
            onSurface = Color(tokens.onSurface),
            surfaceVariant = Color(tokens.surfaceRaised),
            onSurfaceVariant = Color(tokens.onSurfaceVariant),
            surfaceContainerLow = Color(tokens.surfaceRaised),
            surfaceContainer = Color(tokens.surfaceRaised),
            surfaceContainerHigh = Color(tokens.surfaceHigh),
            surfaceContainerHighest = Color(tokens.surfaceHigh),
            error = Color(tokens.error),
            onError = Color(tokens.onError),
            outline = Color(tokens.divider),
            outlineVariant = Color(tokens.divider),
        )
    }

    fun shapes(): Shapes = Shapes(
        extraSmall = RoundedCornerShape(Oe3Shapes.xs),
        small = RoundedCornerShape(Oe3Shapes.sm),
        medium = RoundedCornerShape(Oe3Shapes.md),
        large = RoundedCornerShape(Oe3Shapes.lg),
        extraLarge = RoundedCornerShape(Oe3Shapes.xl),
    )

    fun typography(): Typography = Oe3Typography.materialTypography()
}

/**
 * Oe3 root. The palette is the user's resolved [AppTheme]; brightness is
 * [AppTheme.isDark] (mode was already chosen in `ThemeStore.resolve()`), so
 * there is no second mode argument that could drift from the colors.
 *
 * [textScale] is the in-app 85–130% override. The theme multiplies it into
 * `LocalDensity.fontScale`, which makes every `sp` token scale exactly once —
 * pre-multiplying the tokens themselves would apply the factor twice.
 */
@Composable
fun Oe3Theme(
    theme: AppTheme,
    pureBlack: Boolean = false,
    glassLevel: Oe3GlassLevel = Oe3GlassLevel.None,
    textScale: Float = 1f,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = remember(theme, pureBlack) { Oe3Colors.resolve(theme, pureBlack) }
    val glass = remember(theme, glassLevel) { Oe3Glass.resolve(theme, glassLevel) }
    val motion = remember(reduceMotion) {
        if (reduceMotion) Oe3Motion.reduced() else Oe3Motion.standard()
    }
    val scheme = remember(colors, theme.isDark) {
        Oe3ThemeScheme.colorScheme(colors, theme.isDark)
    }
    val shapes = remember { Oe3ThemeScheme.shapes() }
    val typography = remember { Oe3ThemeScheme.typography() }

    val density = LocalDensity.current
    val scaledDensity = remember(density, textScale) { Oe3TypeScale.density(density, textScale) }
    val info = remember(theme.isDark, pureBlack, glassLevel, textScale, scaledDensity, reduceMotion) {
        Oe3ThemeInfo(
            isDark = theme.isDark,
            pureBlack = pureBlack && theme.isDark,
            glassLevel = glassLevel,
            appTextScale = Oe3TypeScale.clamp(textScale),
            fontScale = scaledDensity.fontScale,
            reducedMotion = reduceMotion,
        )
    }

    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalOe3Colors provides colors,
        LocalOe3Motion provides motion,
        LocalOe3Glass provides glass,
        LocalOe3ThemeInfo provides info,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = shapes,
            typography = typography,
            content = content,
        )
    }
}
