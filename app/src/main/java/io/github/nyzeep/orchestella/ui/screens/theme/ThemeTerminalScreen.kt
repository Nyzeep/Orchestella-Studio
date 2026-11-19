package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.components.OeSlider
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SwitchRow
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageTopPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.shell.oeScrollOffsetPx
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import kotlin.math.roundToInt

/**
 * 终端个性化页（spec §2.2）：ANSI 调色板、终端底色、字号/字距/行距、遮罩、
 * 终端背景图、光标闪烁与实时预览。
 *
 * 预览的接线原样保留（[TerminalPreview] 的渲染契约不改）；页面只负责布局。
 * 所有滑块均用 UI-05 的 [OeSlider]，不另造控件。
 */
@Composable
fun ThemeTerminalScreen(
    state: ThemeStudioState,
    onEvent: (ThemeStudioEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    renderTerminalView: Boolean = true,
    onPickTerminalBackground: (() -> Unit)? = null,
) {
    val colors = Oe3ThemeValues.colors
    val radius = LocalOe3CornerRadius.current ?: Oe3Shapes.xl
    val scrollState = rememberScrollState()
    OeScaffold(
        title = stringResource(R.string.ui3_theme_page_terminal),
        modifier = modifier.testTag("theme-terminal:screen"),
        leading = { ThemeBackButton(onBack = onBack) },
        scrollOffsetPx = { scrollState.oeScrollOffsetPx() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = Oe3Spacing.pageInset)
                .padding(top = LocalOe3PageTopPadding.current + Oe3Spacing.xs, bottom = LocalOe3PageBottomPadding.current),
        ) {
            ThemeSectionHeader(
                key = "terminal-palette",
                title = stringResource(R.string.ui3_theme_terminal_palette),
                value = stringResource(R.string.ui3_theme_terminal_font_value, state.terminalFontSizeSp),
            )
            Oe3Card(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                shape = RoundedCornerShape(radius),
            ) {
                AnsiPaletteStrip(state.theme)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.ui3_theme_terminal_base),
                    style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                    color = colors.onSurface.oe3Color(),
                )
                Spacer(Modifier.height(4.dp))
                TerminalBaseSwatches(state.theme)
            }

            Spacer(Modifier.height(Oe3Spacing.md))
            ThemeSectionHeader(
                key = "terminal-display",
                title = stringResource(R.string.ui3_theme_terminal_display),
            )
            Oe3Card(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                shape = RoundedCornerShape(radius),
            ) {
                TerminalSliderRow(
                    title = stringResource(R.string.ui3_theme_terminal_font),
                    valueText = stringResource(R.string.ui3_theme_terminal_font_value, state.terminalFontSizeSp),
                    description = stringResource(R.string.ui3_theme_terminal_font_desc, state.terminalFontSizeSp),
                    value = state.terminalFontSizeSp.toFloat(),
                    range = ThemeStudioSpec.terminalFontRange.first.toFloat()..
                        ThemeStudioSpec.terminalFontRange.last.toFloat(),
                    steps = 0,
                    tag = "theme-slider:terminal-font-size",
                    onChange = { onEvent(ThemeStudioEvent.SetTerminalFontSizeSp(it.roundToInt())) },
                )
                TerminalSliderRow(
                    title = stringResource(R.string.ui3_theme_terminal_letter),
                    valueText = stringResource(
                        R.string.ui3_theme_terminal_letter_value,
                        state.terminalLetterSpacingEm,
                    ),
                    description = stringResource(
                        R.string.ui3_theme_terminal_letter_desc,
                        state.terminalLetterSpacingEm,
                    ),
                    value = state.terminalLetterSpacingEm,
                    range = 0f..ThemeStudioSpec.terminalLetterSpacingMax,
                    steps = 0,
                    tag = "theme-slider:terminal-letter-spacing",
                    onChange = { onEvent(ThemeStudioEvent.SetTerminalLetterSpacing(it)) },
                )
                TerminalSliderRow(
                    title = stringResource(R.string.ui3_theme_terminal_line),
                    valueText = stringResource(
                        R.string.ui3_theme_terminal_line_value,
                        state.terminalLineSpacingPx,
                    ),
                    description = stringResource(
                        R.string.ui3_theme_terminal_line_desc,
                        state.terminalLineSpacingPx,
                    ),
                    value = state.terminalLineSpacingPx,
                    range = 0f..ThemeStudioSpec.terminalLineSpacingMax,
                    steps = 7,
                    tag = "theme-slider:terminal-line-spacing",
                    onChange = { onEvent(ThemeStudioEvent.SetTerminalLineSpacing(it)) },
                )
                TerminalSliderRow(
                    title = stringResource(R.string.ui3_theme_terminal_scrim),
                    valueText = stringResource(
                        R.string.ui3_theme_terminal_scrim_value,
                        state.terminalScrimAlpha * 100 / 255,
                    ),
                    description = stringResource(
                        R.string.ui3_theme_terminal_scrim_desc,
                        state.terminalScrimAlpha * 100 / 255,
                    ),
                    value = (state.terminalScrimAlpha * 100f / 255f),
                    range = 0f..100f,
                    steps = 0,
                    tag = "theme-slider:terminal-scrim",
                    onChange = { raw ->
                        val percent = ((raw / 5f).roundToInt() * 5).coerceIn(0, 100)
                        onEvent(
                            ThemeStudioEvent.SetTerminalScrimAlpha(
                                (percent * 255 / 100f).roundToInt(),
                            ),
                        )
                    },
                )
            }

            Spacer(Modifier.height(Oe3Spacing.md))
            ThemeSectionHeader(
                key = "terminal-background",
                title = stringResource(R.string.ui3_theme_terminal_background_section),
            )
            Oe3Card(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                shape = RoundedCornerShape(radius),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = if (state.terminalBackgroundUri == null) {
                            stringResource(R.string.ui3_theme_wallpaper_none)
                        } else {
                            stringResource(R.string.ui3_theme_terminal_bg_set)
                        },
                        style = Oe3Typography.style(Oe3TextRole.Meta),
                        color = colors.onSurfaceVariant.oe3Color(),
                    )
                    Spacer(Modifier.height(Oe3Spacing.xs))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
                    ) {
                        Oe3Button(
                            text = stringResource(R.string.ui3_theme_terminal_bg_pick),
                            onClick = { onPickTerminalBackground?.invoke() },
                            enabled = onPickTerminalBackground != null,
                            style = Oe3ButtonStyle.Tonal,
                            modifier = Modifier.testTag("theme-terminal:bg-pick"),
                        )
                        Oe3Button(
                            text = stringResource(R.string.ui3_theme_terminal_bg_clear),
                            onClick = { onEvent(ThemeStudioEvent.ClearTerminalBackground) },
                            enabled = state.terminalBackgroundUri != null,
                            style = Oe3ButtonStyle.Text,
                            modifier = Modifier.testTag("theme-terminal:bg-clear"),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.ui3_theme_terminal_bg_note),
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                )
                Oe3SwitchRow(
                    title = stringResource(R.string.ui3_theme_terminal_cursor),
                    checked = state.cursorBlink,
                    onCheckedChange = { onEvent(ThemeStudioEvent.SetCursorBlink(it)) },
                    modifier = Modifier.testTag("theme-row:cursor-blink"),
                )
            }

            Spacer(Modifier.height(Oe3Spacing.md))
            ThemeSectionHeader(
                key = "terminal-preview",
                title = stringResource(R.string.ui3_theme_terminal_preview_label),
                value = stringResource(R.string.ui3_theme_terminal_font_value, state.terminalFontSizeSp),
            )
            TerminalPreview(
                theme = state.theme,
                fontSizeSp = state.terminalFontSizeSp,
                letterSpacingEm = state.terminalLetterSpacingEm,
                lineSpacingPx = state.terminalLineSpacingPx,
                cursorBlink = state.cursorBlink,
                renderAndroidView = renderTerminalView,
                backgroundUri = state.terminalBackgroundUri,
                // 与真实终端同一层遮罩：主题色 + 选定 alpha。
                scrimColor = Color(
                    ThemeColors.terminalScrimArgb(
                        terminalBackground = state.theme.terminalBackground,
                        alpha = state.terminalScrimAlpha,
                    ),
                ),
            )
            Spacer(Modifier.height(1.dp).testTag("theme-terminal:end"))
        }
    }
}

@Composable
private fun TerminalSliderRow(
    title: String,
    valueText: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    tag: String,
    onChange: (Float) -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = colors.onSurface.oe3Color(),
        )
        Text(
            text = valueText,
            style = Oe3Typography.style(Oe3TextRole.Mono),
            color = colors.onSurfaceVariant.oe3Color(),
        )
    }
    OeSlider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        stateDescription = description,
        modifier = Modifier.testTag(tag),
    )
}
