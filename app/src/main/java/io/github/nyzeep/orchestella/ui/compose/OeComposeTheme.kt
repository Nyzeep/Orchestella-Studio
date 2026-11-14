package io.github.nyzeep.orchestella.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.theme.AppTheme

/**
 * Compose design tokens (batch 1 of the 2026-11-14 UI round).
 *
 * Same numbers as `res/values/oe_design_tokens.xml` on purpose: the XML pages and
 * the Compose pages must agree on geometry, otherwise the three reported defects
 * (mixed single/double card borders, over-wide card gaps, inconsistent button
 * heights) simply reappear on whichever side is not migrated yet. One scale, two
 * consumers.
 */
object OeSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

object OeSizes {
    /** Single hairline for every card. There is no second border style. */
    val cardBorder = 1.dp
    val cardPad = 16.dp
    val cardGap = 12.dp
    val groupGap = 24.dp
    val btnPrimary = 48.dp
    val btnSecondary = 44.dp
    val btnInline = 36.dp
}

/**
 * Bridges the app's own [AppTheme] palettes into a Material 3 [MaterialTheme], so
 * Compose screens inherit the user's theme (including every custom-theme slot)
 * instead of inventing a second palette. `cornerRadiusDp` comes from ThemeStore —
 * the user's rounding choice is authoritative, exactly as ThemeApplier enforces
 * for XML.
 */
@Composable
fun OrchestellaComposeTheme(
    theme: AppTheme,
    cornerRadiusDp: Int = 16,
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
    MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
}
