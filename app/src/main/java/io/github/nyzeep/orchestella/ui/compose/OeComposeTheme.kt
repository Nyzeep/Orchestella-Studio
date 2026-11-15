package io.github.nyzeep.orchestella.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.design.LocalOeTheme
import io.github.nyzeep.orchestella.ui.design.OeGlassLevel
import io.github.nyzeep.orchestella.ui.design.OeTextScale
import io.github.nyzeep.orchestella.ui.design.OeThemeData
import io.github.nyzeep.orchestella.ui.theme.AppTheme

/**
 * Bridges the app's own [AppTheme] palettes into a Material 3 [MaterialTheme], so
 * Compose screens inherit the user's theme (including every custom-theme slot)
 * instead of inventing a second palette.
 *
 * `cornerRadiusDp` comes from ThemeStore — the user's rounding choice is
 * authoritative for controls (buttons, inputs, segmented tracks), exactly as
 * ThemeApplier enforces for XML. Surface radii stay on [io.github.nyzeep.orchestella.ui.design.OeShape]
 * by design: stretching a 24dp card to a control preference is what made the
 * old UI read as unrelated boxes.
 *
 * This three-argument entry point keeps the pre-ticket-05 signature intact and
 * follows the system font scale. Use the overload below to apply the stored
 * 85–130% in-app override.
 */
@Composable
fun OrchestellaComposeTheme(
    theme: AppTheme,
    cornerRadiusDp: Int = 16,
    content: @Composable () -> Unit,
) {
    OrchestellaComposeTheme(
        theme = theme,
        cornerRadiusDp = cornerRadiusDp,
        textScale = 1f,
        content = content,
    )
}

/**
 * [textScale] is the ticket-05 in-app override (85–130%); 1.0 means the
 * composition follows the system font scale. The value is clamped here because
 * this is the public boundary: a hand-edited preference or a preview caller
 * must not produce NaN or 3× display type.
 */
@Composable
fun OrchestellaComposeTheme(
    theme: AppTheme,
    cornerRadiusDp: Int = 16,
    textScale: Float,
    content: @Composable () -> Unit,
) {
    val outlineVariant = Color(theme.glow).copy(alpha = if (theme.isDark) 0.32f else 0.24f)
        .compositeOver(Color(theme.surface))
    val scheme = run {
        val base = if (theme.isDark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = Color(theme.primary),
            onPrimary = Color(theme.background),
            secondary = Color(theme.accent),
            onSecondary = Color(theme.background),
            tertiary = Color(theme.glow),
            background = Color(theme.background),
            onBackground = Color(theme.text),
            surface = Color(theme.surface),
            onSurface = Color(theme.text),
            surfaceVariant = Color(theme.surfaceTranslucent),
            onSurfaceVariant = Color(theme.textDim),
            surfaceContainerLow = Color(theme.surface),
            surfaceContainer = Color(theme.surfaceTranslucent),
            outline = Color(theme.glow),
            outlineVariant = outlineVariant,
            scrim = Color(theme.terminalBackground),
        )
    }
    val radius = cornerRadiusDp.dp
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(radius / 2),
        small = RoundedCornerShape(radius * 3 / 4),
        medium = RoundedCornerShape(radius),
        large = RoundedCornerShape(radius * 3 / 2),
        extraLarge = RoundedCornerShape(radius * 2),
    )
    MaterialTheme(colorScheme = scheme, shapes = shapes) {
        // The Oe v2 component layer reads rounding/brightness/glass level from
        // one ambient state instead of re-deriving it from M3 shapes per call
        // site. glassLevel stays None until ticket 02 fills the material engine.
        CompositionLocalProvider(
            LocalOeTheme provides OeThemeData(
                cornerDp = radius,
                isDark = theme.isDark,
                glassLevel = OeGlassLevel.None,
                // The 85–130% ticket band is enforced here so a hand-edited
                // preference or a preview caller cannot produce 3× display type.
                textScale = OeTextScale.safeFactor(textScale),
            ),
            content = content,
        )
    }
}
