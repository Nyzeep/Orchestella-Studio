package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.ui.design.OeTextScaleMode
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.ThemeStore

/** Pure fixture used by the screenshot tests; no preferences or clock are read. */
internal fun themeStudioScreenshotState(
    theme: AppTheme,
    pureBlack: Boolean,
    textScalePercent: Int,
): ThemeStudioState = ThemeStudioState(
    theme = theme,
    mode = when {
        theme.isFixed -> ThemeStore.Mode.FIXED
        theme.isDark -> ThemeStore.Mode.DARK
        else -> ThemeStore.Mode.LIGHT
    },
    pureBlack = pureBlack,
    cornerDp = 16,
    customSlots = listOf(
        CustomThemeSlot(
            name = "Ocean",
            isDark = true,
            background = 0xFF001018.toInt(),
            surface = 0xFF002030.toInt(),
            primary = 0xFF55CCFF.toInt(),
            accent = 0xFF88DDFF.toInt(),
            text = 0xFFEAF8FF.toInt(),
            textDim = 0xFF9AB8C8.toInt(),
            terminalBackground = 0xFF00080C.toInt(),
            terminalForeground = 0xFFEAF8FF.toInt(),
        ),
        null,
        null,
    ),
    textScaleMode = if (textScalePercent == 100) OeTextScaleMode.FollowSystem else OeTextScaleMode.Custom,
    textScalePercent = textScalePercent,
    surfaceOpacityPercent = 100,
    terminalFontSizeSp = 14,
    terminalLetterSpacingEm = 0f,
    terminalLineSpacingPx = 0f,
)

/**
 * Terminal-only screenshot board. AndroidView cannot be trusted under
 * Robolectric, so this is explicitly the pure-Compose palette sketch, not a
 * capture of the real view.
 */
@Composable
internal fun TerminalPreviewScreenshotBoard(theme: AppTheme) {
    Oe3Theme(theme = theme, reduceMotion = true) {
        val colors = Oe3ThemeValues.colors
        Column(
            modifier = Modifier
                .testTag("terminal-board")
                .width(412.dp)
                .height(470.dp)
                .background(Color(colors.background))
                .padding(20.dp),
        ) {
            Text(
                text = "Terminal Preview · ${theme.displayName}",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp),
                color = Color(colors.onSurface),
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(theme.terminalBackground)),
            ) {
                TerminalPaletteSketch(
                    theme = theme,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            AnsiPaletteStrip(theme)
            Spacer(Modifier.height(12.dp))
            TerminalBaseSwatches(theme)
        }
    }
}
