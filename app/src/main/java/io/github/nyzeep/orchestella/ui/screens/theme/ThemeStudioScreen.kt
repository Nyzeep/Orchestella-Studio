package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.components.OeSlider
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Chip
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.theme.OeTextScaleMode
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import kotlin.math.roundToInt

/**
 * 外观与主题 · 根页（v4 §2.2，UI-10）。
 *
 * 单页长列表改为「入口列表 + 独立子页」：根页只留主页预览、七行入口与
 * 「更多」。四个需要整页空间的入口（主题预设 / 强调色 / 背景与透明度 /
 * 终端个性化）由调用方 push 到壳层栈；明暗模式、圆角、界面字号按规格
 * 留在根页 inline。
 *
 * **本组件不自带 [io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme] 包装**：
 * 壳内已由 `Oe3App` 提供主题与字号缩放，再包一层会把应用内字号乘两次。
 * 预览与测试由调用方自行包 `Oe3Theme`。
 */
@Composable
fun ThemeStudioScreen(
    state: ThemeStudioState,
    onEvent: (ThemeStudioEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onOpenPresets: (() -> Unit)? = null,
    onOpenAccent: (() -> Unit)? = null,
    onOpenBackground: (() -> Unit)? = null,
    onOpenTerminal: (() -> Unit)? = null,
    reduceMotion: Boolean = false,
) {
    val colors = Oe3ThemeValues.colors
    val fontFamily = when (state.fontChoice) {
        FontChoice.Mono -> FontFamily.Monospace
        FontChoice.System, FontChoice.Imported -> FontFamily.Default
    }

    OeScaffold(
        title = stringResource(R.string.ui3_theme_title),
        modifier = modifier.testTag("theme-studio:screen"),
        leading = onBack?.let { back -> { ThemeBackButton(onBack = back) } },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Oe3Spacing.pageInset)
                .padding(top = Oe3Spacing.xs, bottom = LocalOe3PageBottomPadding.current),
        ) {
            // 所见即所得锚点：所有偏好改动的结果都先在这里出现。
            ThemeSectionHeader(
                key = ThemeStudioSection.HomePreview.key,
                title = stringResource(R.string.ui3_theme_section_preview),
                value = stringResource(R.string.ui3_theme_preview_caption),
            )
            HomePreview(
                theme = state.theme,
                pureBlack = state.pureBlack,
                cornerDp = state.cornerDp,
                surfaceOpacityPercent = state.surfaceOpacityPercent,
                glass = state.glass,
                navStyle = state.navStyle,
                fontFamily = fontFamily,
                reduceMotion = reduceMotion,
                contentDescription = stringResource(
                    R.string.ui3_theme_home_preview_desc,
                    state.theme.selectionLabel,
                ),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(Modifier.height(Oe3Spacing.md))
            EntryCard(
                state = state,
                onEvent = onEvent,
                onOpenPresets = onOpenPresets,
                onOpenAccent = onOpenAccent,
                onOpenBackground = onOpenBackground,
                onOpenTerminal = onOpenTerminal,
            )

            Spacer(Modifier.height(Oe3Spacing.md))
            ThemeSectionHeader(
                key = ThemeStudioSection.More.key,
                title = stringResource(R.string.ui3_theme_section_more),
            )
            MoreCard(state = state, onEvent = onEvent)
            Spacer(Modifier.height(1.dp).testTag("theme-studio:end"))
        }
    }
}

/**
 * 七行入口。四个 push 行带 chevron，三个 inline 组把控件直接放在本卡内
 * （spec 明确要求 明暗模式 / 圆角 / 界面字号 留在根页）。
 */
@Composable
private fun EntryCard(
    state: ThemeStudioState,
    onEvent: (ThemeStudioEvent) -> Unit,
    onOpenPresets: (() -> Unit)?,
    onOpenAccent: (() -> Unit)?,
    onOpenBackground: (() -> Unit)?,
    onOpenTerminal: (() -> Unit)?,
) {
    ThemeOptionGroup {
        ThemeEntryRow(
            title = stringResource(R.string.ui3_theme_section_presets),
            // 计数单位是「组」：一张卡 = 一个 family（亮暗成对），与预设页
            // 的两个分区（看板娘 1 + 其他 N）能对上；用目录条目数会显示 42，
            // 与页面上数得出的卡片数不符。
            value = stringResource(
                R.string.ui3_theme_presets_count,
                remember(state.theme.isDark) {
                    ThemePresetGrouping.groups(isDark = state.theme.isDark).size
                },
            ),
            onClick = { onOpenPresets?.invoke() },
            tag = "theme-entry:presets",
            valueTag = "theme-value:presets",
        )
        OptionDivider()
        InlineLabel(
            title = stringResource(R.string.ui3_theme_entry_mode),
            value = modeLabel(state.mode),
            valueTag = "theme-value:mode",
            tag = "theme-entry:mode",
        )
        ThemeStore.Mode.entries.filter { it.isUserSelectable }.forEachIndexed { index, mode ->
            if (index > 0) OptionDivider()
            ThemeOptionRow(
                title = modeLabel(mode),
                selected = state.mode == mode,
                enabled = !state.theme.isFixed,
                onSelect = { onEvent(ThemeStudioEvent.SelectMode(mode)) },
                modifier = Modifier.testTag("theme-row:mode:${mode.value}"),
                thumbnail = { ThemePreviewSketch(ThemePreviewKind.Mode(mode)) },
            )
        }
        if (state.isFixedBrightness) {
            OptionDivider()
            Text(
                text = stringResource(R.string.ui3_theme_mode_fixed_note),
                modifier = Modifier.padding(horizontal = Oe3Spacing.rowHorizontal, vertical = 10.dp),
                style = MetaTextStyle,
                color = Oe3ThemeValues.colors.onSurfaceVariant.oe3Color(),
            )
        }
        OptionDivider()
        ThemeEntryRow(
            title = stringResource(R.string.ui3_theme_entry_accent),
            value = state.customAccent?.let { hex(it) }
                ?: stringResource(R.string.ui3_theme_accent_follow),
            onClick = { onOpenAccent?.invoke() },
            tag = "theme-entry:accent",
            valueTag = "theme-value:accent",
        )
        OptionDivider()
        InlineLabel(
            title = stringResource(R.string.ui3_theme_section_corner),
            value = stringResource(R.string.ui3_theme_corner_value, state.cornerDp),
            valueTag = "theme-value:corner",
            tag = "theme-entry:corner",
        )
        CornerChooser(
            selectedDp = state.cornerDp,
            onSelect = { onEvent(ThemeStudioEvent.SetCornerDp(it)) },
        )
        OptionDivider()
        InlineLabel(
            title = stringResource(R.string.ui3_theme_entry_text_scale),
            value = if (state.textScaleMode == OeTextScaleMode.FollowSystem) {
                stringResource(R.string.ui3_theme_text_follow)
            } else {
                stringResource(R.string.ui3_theme_text_percent, state.textScalePercent)
            },
            valueTag = "theme-value:text-scale",
            tag = "theme-entry:text-scale",
        )
        TextScaleCard(state = state, onEvent = onEvent)
        OptionDivider()
        ThemeEntryRow(
            title = stringResource(R.string.ui3_theme_entry_background),
            value = stringResource(R.string.ui3_theme_opacity_desc, state.surfaceOpacityPercent),
            onClick = { onOpenBackground?.invoke() },
            tag = "theme-entry:background",
            valueTag = "theme-value:background",
        )
        OptionDivider()
        ThemeEntryRow(
            title = stringResource(R.string.ui3_theme_section_terminal),
            value = stringResource(R.string.ui3_theme_terminal_font_value, state.terminalFontSizeSp),
            onClick = { onOpenTerminal?.invoke() },
            tag = "theme-entry:terminal",
            valueTag = "theme-value:terminal",
        )
    }
}

/**
 * inline 组的标题行：不整行可点（控件在下面），只提供组名与当前值。
 * 保留 `theme-entry:*` / `theme-value:*` 锚点，语义测试不必按文案猜。
 */
@Composable
private fun InlineLabel(title: String, value: String, valueTag: String, tag: String) {
    val colors = Oe3ThemeValues.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .padding(horizontal = Oe3Spacing.rowHorizontal, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = Oe3Typography.style(Oe3TextRole.BodyStrong),
            color = colors.onSurface.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.testTag(valueTag),
            style = MetaTextStyle,
            color = colors.onSurfaceVariant.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 「更多」：spec §2.2 没给落点的既有能力（底栏样式 / 液态玻璃 / 字体）。
 * 它们是现网功能，不随 IA 重构删除，只收进一个分组。纯黑在 spec §2.2
 * 已明确落到「背景与透明度」页，因此不在此重复。
 */
@Composable
private fun MoreCard(state: ThemeStudioState, onEvent: (ThemeStudioEvent) -> Unit) {
    ThemeOptionGroup {
        MoreLabel(stringResource(R.string.ui3_theme_section_nav))
        NavStyleOption.entries.forEachIndexed { index, option ->
            if (index > 0) OptionDivider()
            ThemeOptionRow(
                title = if (option == NavStyleOption.Floating) {
                    stringResource(R.string.ui3_theme_nav_floating)
                } else {
                    stringResource(R.string.ui3_theme_nav_fixed)
                },
                subtitle = stringResource(R.string.ui3_theme_preview_only),
                selected = state.navStyle == option,
                onSelect = { onEvent(ThemeStudioEvent.SetNavStyle(option)) },
                modifier = Modifier.testTag("theme-row:nav:${option.key}"),
                thumbnail = { ThemePreviewSketch(ThemePreviewKind.NavStyle(option)) },
            )
        }
        MoreDivider()
        MoreLabel(stringResource(R.string.ui3_theme_section_glass))
        GlassOption.entries.forEachIndexed { index, option ->
            if (index > 0) OptionDivider()
            ThemeOptionRow(
                title = glassLabel(option),
                subtitle = stringResource(R.string.ui3_theme_glass_note),
                selected = state.glass == option,
                onSelect = { onEvent(ThemeStudioEvent.SetGlass(option)) },
                modifier = Modifier.testTag("theme-row:glass:${option.key}"),
                thumbnail = { ThemePreviewSketch(ThemePreviewKind.Glass(option)) },
            )
        }
        MoreDivider()
        MoreLabel(stringResource(R.string.ui3_theme_section_font))
        FontChoice.entries.forEachIndexed { index, choice ->
            if (index > 0) OptionDivider()
            ThemeOptionRow(
                title = fontLabel(choice),
                subtitle = when {
                    choice == FontChoice.Imported && state.importedFontLabel != null ->
                        stringResource(R.string.ui3_theme_font_imported_value, state.importedFontLabel)

                    choice == FontChoice.Imported -> stringResource(R.string.ui3_theme_font_import_hint)
                    choice == FontChoice.System -> stringResource(R.string.ui3_theme_font_note)
                    else -> null
                },
                selected = state.fontChoice == choice,
                // 暂不支持导入字体：禁用的行永远不写偏好。
                enabled = choice != FontChoice.Imported,
                onSelect = {
                    if (choice != FontChoice.Imported) {
                        onEvent(ThemeStudioEvent.SetFontChoice(choice))
                    }
                },
                modifier = Modifier.testTag("theme-row:font:${choice.key}"),
                thumbnail = { ThemePreviewSketch(ThemePreviewKind.Font(choice)) },
            )
        }
    }
}

@Composable
private fun MoreLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(
            horizontal = Oe3Spacing.rowHorizontal,
            vertical = 10.dp,
        ),
        style = MetaTextStyle,
        color = Oe3ThemeValues.colors.onSurfaceVariant.oe3Color(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 圆角分段：inline 保留在根页（spec §2.2）。 */
@Composable
private fun CornerChooser(selectedDp: Int, onSelect: (Int) -> Unit) {
    val options = if (selectedDp in ThemeStudioSpec.cornerOptions) {
        ThemeStudioSpec.cornerOptions
    } else {
        ThemeStudioSpec.cornerOptions + selectedDp
    }
    // 真机（411dp）上五档 + 遗留档会超出单行宽度：chips 行必须可横向滚动，
    // 否则最后一档被裁掉（Wave 4 真机走查发现）。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Oe3Spacing.rowHorizontal, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
    ) {
        options.forEach { dp ->
            Oe3Chip(
                label = stringResource(R.string.ui3_theme_corner_value, dp),
                selected = selectedDp == dp,
                onClick = { onSelect(dp) },
                modifier = Modifier.testTag("theme-corner:$dp"),
            )
        }
    }
}

/** 界面字号 inline：跟随系统 / 自定义 + 唯一滑块组件 [OeSlider]。 */
@Composable
private fun TextScaleCard(state: ThemeStudioState, onEvent: (ThemeStudioEvent) -> Unit) {
    val colors = Oe3ThemeValues.colors
    val custom = state.textScaleMode == OeTextScaleMode.Custom
    val sliderText = stringResource(R.string.ui3_theme_text_slider_desc, state.textScalePercent)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Oe3Spacing.rowHorizontal, vertical = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Oe3Chip(
                label = stringResource(R.string.ui3_theme_text_follow),
                selected = !custom,
                onClick = { onEvent(ThemeStudioEvent.SetTextScaleMode(OeTextScaleMode.FollowSystem)) },
                modifier = Modifier.testTag("theme-row:text-scale:follow"),
            )
            Oe3Chip(
                label = stringResource(R.string.ui3_theme_text_custom),
                selected = custom,
                onClick = { onEvent(ThemeStudioEvent.SetTextScaleMode(OeTextScaleMode.Custom)) },
                modifier = Modifier.testTag("theme-row:text-scale:custom"),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.ui3_theme_text_percent, state.textScalePercent),
                modifier = Modifier.testTag("theme-value:text-scale:percent"),
                style = Oe3Typography.style(Oe3TextRole.Mono),
                color = colors.onSurface.oe3Color(),
            )
        }
        OeSlider(
            value = state.textScalePercent.toFloat(),
            onValueChange = { raw ->
                val snapped = ((raw / 5f).roundToInt() * 5)
                    .coerceIn(ThemeStudioSpec.textScaleRange.first, ThemeStudioSpec.textScaleRange.last)
                onEvent(ThemeStudioEvent.SetTextScalePercent(snapped))
            },
            valueRange = ThemeStudioSpec.textScaleRange.first.toFloat()..
                ThemeStudioSpec.textScaleRange.last.toFloat(),
            enabled = custom,
            stateDescription = sliderText,
            modifier = Modifier.testTag("theme-slider:text-scale"),
        )
    }
}

@Composable
private fun modeLabel(mode: ThemeStore.Mode): String = when (mode) {
    ThemeStore.Mode.SYSTEM -> stringResource(R.string.ui3_theme_mode_auto)
    ThemeStore.Mode.LIGHT -> stringResource(R.string.ui3_theme_mode_light)
    ThemeStore.Mode.DARK -> stringResource(R.string.ui3_theme_mode_dark)
    ThemeStore.Mode.FIXED -> stringResource(R.string.ui3_theme_mode_fixed)
}

@Composable
internal fun glassLabel(option: GlassOption): String = when (option) {
    GlassOption.None -> stringResource(R.string.ui3_theme_glass_none)
    GlassOption.Standard -> stringResource(R.string.ui3_theme_glass_standard)
    GlassOption.Ultra -> stringResource(R.string.ui3_theme_glass_ultra)
}

@Composable
internal fun fontLabel(choice: FontChoice): String = when (choice) {
    FontChoice.System -> stringResource(R.string.ui3_theme_font_system)
    FontChoice.Mono -> stringResource(R.string.ui3_theme_font_mono)
    FontChoice.Imported -> stringResource(R.string.ui3_theme_font_imported)
}

internal fun hex(argb: Int): String = String.format("#%06X", argb and 0x00FFFFFF)

/** 「更多」内部的分组之间用一条满宽分隔，和行内分隔区分开。 */
@Composable
private fun MoreDivider() {
    io.github.nyzeep.orchestella.ui.oe3.components.Oe3Divider()
}
