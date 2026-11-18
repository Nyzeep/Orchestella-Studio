package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.components.OeSlider
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import kotlin.math.roundToInt

/**
 * 背景与透明度页（spec §2.2）：壁纸选/清、表面不透明度、纯黑开关。
 * 纯黑是暗色专用覆盖，亮色主题下整组禁用——这与 store 的读时丢弃语义一致。
 */
@Composable
fun ThemeBackgroundScreen(
    state: ThemeStudioState,
    onEvent: (ThemeStudioEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPickBackground: (() -> Unit)? = null,
) {
    OeScaffold(
        title = stringResource(R.string.ui3_theme_page_background),
        modifier = modifier.testTag("theme-background:screen"),
        leading = { ThemeBackButton(onBack = onBack) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Oe3Spacing.pageInset)
                .padding(top = Oe3Spacing.xs, bottom = LocalOe3PageBottomPadding.current),
        ) {
            ThemeSectionHeader(
                key = "wallpaper",
                title = stringResource(R.string.ui3_theme_wallpaper_title),
                value = stringResource(R.string.ui3_theme_opacity_desc, state.surfaceOpacityPercent),
            )
            WallpaperCard(
                state = state,
                onEvent = onEvent,
                onPickBackground = onPickBackground,
            )

            Spacer(Modifier.height(Oe3Spacing.md))
            ThemeSectionHeader(
                key = "pure-black",
                title = stringResource(R.string.ui3_theme_section_pure_black),
                value = if (state.pureBlack) {
                    stringResource(R.string.ui3_theme_pure_black)
                } else {
                    stringResource(R.string.ui3_theme_pure_standard)
                },
            )
            ThemeOptionGroup {
                PureBlackOption.entries.forEachIndexed { index, option ->
                    if (index > 0) OptionDivider()
                    val enabled = state.theme.isDark
                    ThemeOptionRow(
                        title = if (option == PureBlackOption.Standard) {
                            stringResource(R.string.ui3_theme_pure_standard)
                        } else {
                            stringResource(R.string.ui3_theme_pure_black)
                        },
                        subtitle = when {
                            !enabled -> stringResource(R.string.ui3_theme_pure_black_note)
                            option == PureBlackOption.PureBlack ->
                                stringResource(R.string.ui3_theme_pure_black_preview)

                            else -> null
                        },
                        selected = state.pureBlack == (option == PureBlackOption.PureBlack),
                        enabled = enabled,
                        onSelect = {
                            onEvent(ThemeStudioEvent.SetPureBlack(option == PureBlackOption.PureBlack))
                        },
                        modifier = Modifier.testTag("theme-row:pure-black:${option.labelKey}"),
                        thumbnail = {
                            ThemePreviewSketch(
                                ThemePreviewKind.PureBlack(option == PureBlackOption.PureBlack),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(1.dp).testTag("theme-background:end"))
        }
    }
}

@Composable
private fun WallpaperCard(
    state: ThemeStudioState,
    onEvent: (ThemeStudioEvent) -> Unit,
    onPickBackground: (() -> Unit)?,
) {
    val colors = Oe3ThemeValues.colors
    val radius = LocalOe3CornerRadius.current ?: Oe3Shapes.xl
    val wallpaper = if (state.backgroundUri == null) {
        stringResource(R.string.ui3_theme_wallpaper_none)
    } else {
        stringResource(R.string.ui3_theme_wallpaper_set)
    }
    val opacityText = stringResource(R.string.ui3_theme_opacity_desc, state.surfaceOpacityPercent)
    Oe3Card(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        shape = RoundedCornerShape(radius),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ui3_theme_wallpaper_title),
                    style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                    color = colors.onSurface.oe3Color(),
                )
                Text(
                    text = wallpaper,
                    style = MetaTextStyle,
                    color = colors.onSurfaceVariant.oe3Color(),
                )
            }
            Oe3Button(
                text = stringResource(R.string.ui3_theme_wallpaper_pick),
                onClick = { onPickBackground?.invoke() },
                enabled = onPickBackground != null,
                style = Oe3ButtonStyle.Tonal,
                modifier = Modifier.testTag("theme-wallpaper:pick"),
            )
            Spacer(Modifier.width(4.dp))
            Oe3Button(
                text = stringResource(R.string.ui3_theme_wallpaper_clear),
                onClick = { onEvent(ThemeStudioEvent.ClearBackground) },
                enabled = state.backgroundUri != null,
                style = Oe3ButtonStyle.Text,
                modifier = Modifier.testTag("theme-wallpaper:clear"),
            )
        }
        Text(
            text = stringResource(R.string.ui3_theme_opacity),
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = colors.onSurface.oe3Color(),
        )
        OeSlider(
            value = state.surfaceOpacityPercent.toFloat(),
            onValueChange = { onEvent(ThemeStudioEvent.SetSurfaceOpacity(it.roundToInt())) },
            valueRange = ThemeStudioSpec.surfaceOpacityRange.first.toFloat()..
                ThemeStudioSpec.surfaceOpacityRange.last.toFloat(),
            stateDescription = opacityText,
            modifier = Modifier.testTag("theme-slider:surface-opacity"),
        )
        Text(
            text = opacityText,
            style = MetaTextStyle,
            color = colors.onSurfaceVariant.oe3Color(),
        )
    }
}
