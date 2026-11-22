package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.ui.design.tokens.OeFonts
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import io.github.nyzeep.orchestella.ui.theme.ThemeStore

/** What an inline option thumbnail illustrates; each variant is a static sketch. */
sealed interface ThemePreviewKind {
    data class Mode(val mode: ThemeStore.Mode) : ThemePreviewKind
    data class PureBlack(val enabled: Boolean) : ThemePreviewKind
    data class NavStyle(val style: NavStyleOption) : ThemePreviewKind
    data class Glass(val option: GlassOption) : ThemePreviewKind
    data class Font(val choice: FontChoice) : ThemePreviewKind
    data class Preset(val theme: AppTheme) : ThemePreviewKind
}

/**
 * The shared 44x30, 8dp-corner thumbnail. It paints from the live Oe3 tokens,
 * so the mode/pure-black/glass sketches follow the current theme instead of
 * carrying a second hardcoded palette.
 */
@Composable
fun ThemePreviewSketch(kind: ThemePreviewKind, modifier: Modifier = Modifier) {
    val colors = Oe3ThemeValues.colors
    val box = modifier
        .width(ThemeStudioSpec.thumbnailWidth)
        .height(ThemeStudioSpec.thumbnailHeight)
        .clip(RoundedCornerShape(ThemeStudioSpec.thumbnailCorner))

    when (kind) {
        is ThemePreviewKind.Font -> FontThumb(kind.choice, box)
        else -> Box(box.background(Color(colors.surfaceRaised))) {
            Canvas(Modifier.fillMaxSize()) {
                when (kind) {
                    is ThemePreviewKind.Mode -> drawModeThumb(kind.mode, colors)
                    is ThemePreviewKind.PureBlack -> drawPureBlackThumb(kind.enabled, colors)
                    is ThemePreviewKind.NavStyle -> drawNavThumb(kind.style, colors)
                    is ThemePreviewKind.Glass -> drawGlassThumb(kind.option, colors)
                    is ThemePreviewKind.Preset -> drawPresetThumb(kind.theme)
                    is ThemePreviewKind.Font -> Unit
                }
            }
        }
    }
}

@Composable
private fun FontThumb(choice: FontChoice, modifier: Modifier) {
    val colors = Oe3ThemeValues.colors
    val family = if (choice == FontChoice.Mono) OeFonts.JetBrainsMono else FontFamily.Default
    Box(
        modifier = modifier.background(Color(colors.surfaceHigh)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Aa",
            style = TextStyle(
                fontFamily = family,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color(colors.onSurface),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawModeThumb(
    mode: ThemeStore.Mode,
    colors: io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3ColorTokens,
) {
    val light = colors.surfaceHigh
    val dark = Color(ThemeColors.blend(colors.background, 0xFF000000.toInt(), 0.78f))
    val split = when (mode) {
        ThemeStore.Mode.LIGHT -> 0.78f
        ThemeStore.Mode.DARK -> 0.22f
        ThemeStore.Mode.SYSTEM, ThemeStore.Mode.FIXED -> 0.5f
    }
    drawRect(Color(light))
    val path = Path().apply {
        moveTo(size.width * split, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width, size.height)
        lineTo(size.width * (split - 0.16f).coerceAtLeast(0f), size.height)
        close()
    }
    drawPath(path, dark)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPureBlackThumb(
    enabled: Boolean,
    colors: io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3ColorTokens,
) {
    val base = if (enabled) Color(0xFF000000) else Color(colors.surfaceHigh)
    drawRect(base)
    drawRoundRect(
        color = if (enabled) Color(0xFF0B0B0C) else Color(colors.surface),
        topLeft = Offset(size.width * 0.15f, size.height * 0.22f),
        size = Size(size.width * 0.7f, size.height * 0.56f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
    )
    drawCircle(
        color = Color(colors.primary),
        radius = 2.5.dp.toPx(),
        center = Offset(size.width * 0.32f, size.height * 0.5f),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNavThumb(
    style: NavStyleOption,
    colors: io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3ColorTokens,
) {
    drawRect(Color(colors.background))
    if (style == NavStyleOption.Floating) {
        drawRoundRect(
            color = Color(colors.surfaceHigh),
            topLeft = Offset(size.width * 0.13f, size.height * 0.62f),
            size = Size(size.width * 0.74f, size.height * 0.26f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.13f),
        )
        drawRoundRect(
            color = Color(colors.primary),
            topLeft = Offset(size.width * 0.18f, size.height * 0.67f),
            size = Size(size.width * 0.2f, size.height * 0.16f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.08f),
        )
    } else {
        drawRect(
            color = Color(colors.surfaceHigh),
            topLeft = Offset(0f, size.height * 0.68f),
            size = Size(size.width, size.height * 0.32f),
        )
        drawRect(
            color = Color(colors.primary),
            topLeft = Offset(size.width * 0.06f, size.height * 0.74f),
            size = Size(size.width * 0.18f, size.height * 0.2f),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassThumb(
    option: GlassOption,
    colors: io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3ColorTokens,
) {
    drawRect(Color(colors.background))
    drawCircle(
        color = Color(colors.primary).copy(alpha = 0.45f),
        radius = size.height * 0.5f,
        center = Offset(size.width * 0.3f, size.height * 0.45f),
    )
    drawCircle(
        color = Color(colors.accent).copy(alpha = 0.35f),
        radius = size.height * 0.38f,
        center = Offset(size.width * 0.72f, size.height * 0.6f),
    )
    val alpha = when (option) {
        GlassOption.None -> 1f
        GlassOption.Standard -> 0.72f
        GlassOption.Ultra -> 0.55f
    }
    drawRect(
        color = if (option == GlassOption.None) {
            Color(colors.surface)
        } else {
            Color(colors.surface).copy(alpha = alpha)
        },
        topLeft = Offset(size.width * 0.1f, size.height * 0.2f),
        size = Size(size.width * 0.8f, size.height * 0.6f),
    )
    drawRoundRect(
        color = Color(colors.onSurface).copy(alpha = 0.55f),
        topLeft = Offset(size.width * 0.16f, size.height * 0.3f),
        size = Size(size.width * 0.4f, 2.dp.toPx()),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPresetThumb(theme: AppTheme) {
    val stripes = listOf(
        theme.background,
        theme.surface,
        theme.primary,
        theme.accent,
        theme.text,
    )
    val stripeWidth = size.width / stripes.size
    stripes.forEachIndexed { index, argb ->
        drawRect(
            color = Color(argb),
            topLeft = Offset(index * stripeWidth, 0f),
            size = Size(stripeWidth, size.height),
        )
    }
    // One inner block makes the strip read as a surface, not a rainbow bar.
    drawRoundRect(
        color = Color(theme.surface).copy(alpha = 0.9f),
        topLeft = Offset(size.width * 0.08f, size.height * 0.25f),
        size = Size(size.width * 0.34f, size.height * 0.5f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
        style = Stroke(width = 0.8.dp.toPx()),
    )
}
