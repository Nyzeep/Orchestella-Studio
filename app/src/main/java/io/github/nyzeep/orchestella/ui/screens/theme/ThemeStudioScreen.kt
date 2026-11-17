package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.components.OeSlider
import io.github.nyzeep.orchestella.ui.theme.OeTextScaleMode
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Chip
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SwitchRow
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import kotlin.math.roundToInt

/** 页面标题下的元信息与「当前值」读数（spec §2.3：13/18）。 */
private val MetaTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/**
 * Theme Studio (UI3-09). Root-only: it owns the [Oe3Theme] wrapper because the
 * page must repaint itself the moment a preset, mode or corner is picked. Do
 * not nest it inside another Oe3Theme with the same text scale — that would
 * apply the in-app font multiplier twice.
 *
 * All persisted writes arrive as [ThemeStudioEvent] and are mapped by
 * [ThemeStudioController] onto ThemeStore/SettingsManager setters. Pure black,
 * glass, bottom-bar style and app font family are real preferences since
 * UI3-13; the shell still applies them page-wide once it wires the store, and
 * the imported-font row stays disabled (暂不支持导入字体).
 */
@Composable
fun ThemeStudioScreen(
    state: ThemeStudioState,
    onEvent: (ThemeStudioEvent) -> Unit,
    modifier: Modifier = Modifier,
    renderTerminalView: Boolean = true,
    reduceMotion: Boolean = false,
    presets: List<io.github.nyzeep.orchestella.ui.theme.AppTheme> = ThemeCatalog.all,
    onBack: (() -> Unit)? = null,
    onPickBackground: (() -> Unit)? = null,
    onPickTerminalBackground: (() -> Unit)? = null,
    onPickFont: (() -> Unit)? = null,
) {
    Oe3Theme(
        theme = state.theme,
        pureBlack = state.pureBlack,
        glassLevel = state.glass.level,
        textScale = state.appTextScale,
        reduceMotion = reduceMotion,
        cornerRadiusDp = state.cornerDp,
    ) {
        ThemeStudioContent(
            state = state,
            onEvent = onEvent,
            modifier = modifier,
            renderTerminalView = renderTerminalView,
            reduceMotion = reduceMotion,
            presets = presets,
            onBack = onBack,
            onPickBackground = onPickBackground,
            onPickTerminalBackground = onPickTerminalBackground,
            onPickFont = onPickFont,
        )
    }
}

@Composable
private fun ThemeStudioContent(
    state: ThemeStudioState,
    onEvent: (ThemeStudioEvent) -> Unit,
    modifier: Modifier,
    renderTerminalView: Boolean,
    reduceMotion: Boolean,
    presets: List<io.github.nyzeep.orchestella.ui.theme.AppTheme>,
    onBack: (() -> Unit)?,
    onPickBackground: (() -> Unit)?,
    onPickTerminalBackground: (() -> Unit)?,
    onPickFont: (() -> Unit)?,
) {
    val colors = Oe3ThemeValues.colors
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    val fontFamily = when (state.fontChoice) {
        FontChoice.Mono -> FontFamily.Monospace
        FontChoice.System, FontChoice.Imported -> FontFamily.Default
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("theme-studio:screen")
            .background(colors.background.oe3Color())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Oe3Spacing.pageInset)
            .padding(top = Oe3Spacing.xl, bottom = Oe3Spacing.contentBottom),
    ) {
        ThemeStudioPageHeader(onBack = onBack)

        // 1. Full home preview.
        ThemeSectionHeader(
            section = ThemeStudioSection.HomePreview,
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

        // 2. Theme mode.
        ThemeSectionHeader(
            section = ThemeStudioSection.Mode,
            title = stringResource(R.string.ui3_theme_section_mode),
            value = modeLabel(state.mode),
        )
        ThemeOptionGroup {
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
                    color = colors.onSurfaceVariant.oe3Color(),
                )
            }
        }

        // 3. Pure black.
        ThemeSectionHeader(
            section = ThemeStudioSection.PureBlack,
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
                    onSelect = { onEvent(ThemeStudioEvent.SetPureBlack(option == PureBlackOption.PureBlack)) },
                    modifier = Modifier.testTag("theme-row:pure-black:${option.labelKey}"),
                    thumbnail = {
                        ThemePreviewSketch(ThemePreviewKind.PureBlack(option == PureBlackOption.PureBlack))
                    },
                )
            }
        }

        // 4. Presets.
        ThemeSectionHeader(
            section = ThemeStudioSection.Presets,
            title = stringResource(R.string.ui3_theme_section_presets),
            value = stringResource(R.string.ui3_theme_presets_count, presets.size),
        )
        PresetGrid(
            presets = presets,
            selectedId = state.theme.id,
            onSelect = { onEvent(ThemeStudioEvent.SelectTheme(it)) },
        )

        // 5. Accent and custom slots.
        ThemeSectionHeader(
            section = ThemeStudioSection.AccentSlots,
            title = stringResource(R.string.ui3_theme_section_accent),
            value = state.customAccent?.let { hex(it) } ?: stringResource(R.string.ui3_theme_accent_follow),
        )
        AccentCard(
            customAccent = state.customAccent,
            onEvent = onEvent,
        )
        Spacer(Modifier.height(8.dp))
        CustomSlotCard(
            slots = state.customSlots,
            activeId = state.theme.id,
            onEdit = { editingSlot = it },
        )

        // 6. Bottom bar style.
        ThemeSectionHeader(
            section = ThemeStudioSection.NavStyle,
            title = stringResource(R.string.ui3_theme_section_nav),
            value = if (state.navStyle == NavStyleOption.Floating) {
                stringResource(R.string.ui3_theme_nav_floating)
            } else {
                stringResource(R.string.ui3_theme_nav_fixed)
            },
        )
        ThemeOptionGroup {
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
        }

        // 7. Liquid glass.
        ThemeSectionHeader(
            section = ThemeStudioSection.Glass,
            title = stringResource(R.string.ui3_theme_section_glass),
            value = glassLabel(state.glass),
        )
        ThemeOptionGroup {
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
        }

        // 8. Corner radius.
        ThemeSectionHeader(
            section = ThemeStudioSection.Corner,
            title = stringResource(R.string.ui3_theme_section_corner),
            value = stringResource(R.string.ui3_theme_corner_value, state.cornerDp),
        )
        CornerChooser(
            selectedDp = state.cornerDp,
            onSelect = { onEvent(ThemeStudioEvent.SetCornerDp(it)) },
        )

        // 9. Font family.
        ThemeSectionHeader(
            section = ThemeStudioSection.Font,
            title = stringResource(R.string.ui3_theme_section_font),
            value = fontLabel(state),
        )
        ThemeOptionGroup {
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
                    // Font import does not exist yet (暂不支持导入字体): the row stays
                    // disabled so a selection can never persist an unloadable font.
                    enabled = choice != FontChoice.Imported,
                    // No-op for Imported: the row is disabled, and never wiring a
                    // callback here makes "a font can never persist" structural.
                    // onPickFont can be re-wired when the font-import ticket lands.
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

        // 10. Text scale.
        ThemeSectionHeader(
            section = ThemeStudioSection.TextScale,
            title = stringResource(R.string.ui3_theme_section_text_scale),
            value = if (state.textScaleMode == OeTextScaleMode.FollowSystem) {
                stringResource(R.string.ui3_theme_text_follow)
            } else {
                stringResource(R.string.ui3_theme_text_percent, state.textScalePercent)
            },
        )
        TextScaleCard(state = state, onEvent = onEvent)

        // 11. Wallpaper and surface opacity.
        ThemeSectionHeader(
            section = ThemeStudioSection.WallpaperOpacity,
            title = stringResource(R.string.ui3_theme_section_wallpaper),
            value = stringResource(R.string.ui3_theme_opacity_desc, state.surfaceOpacityPercent),
        )
        WallpaperCard(
            state = state,
            onEvent = onEvent,
            onPickBackground = onPickBackground,
        )

        // 12. Terminal personalization + live preview.
        ThemeSectionHeader(
            section = ThemeStudioSection.Terminal,
            title = stringResource(R.string.ui3_theme_section_terminal),
            value = stringResource(R.string.ui3_theme_terminal_font_value, state.terminalFontSizeSp),
        )
        TerminalCard(
            state = state,
            onEvent = onEvent,
            renderTerminalView = renderTerminalView,
            onPickTerminalBackground = onPickTerminalBackground,
        )

        Spacer(Modifier.height(1.dp).testTag("theme-studio:end"))
    }

    editingSlot?.let { index ->
        CustomSlotEditorDialog(
            index = index,
            existing = state.customSlots.getOrNull(index),
            fallback = state.theme,
            onDismiss = { editingSlot = null },
            onSave = { slot, apply ->
                onEvent(ThemeStudioEvent.SaveCustomSlot(index, slot, apply))
                editingSlot = null
            },
        )
    }
}

/**
 * 子页头（patterns/navigation.md §5 / page-templates §5 工具页）：返回 48dp + 4dp +
 * 标题 30/36 + 元信息 13/18，标题仍留在内容区。[onBack] 为空时不渲染返回钮，
 * 页面保持可复用（预览/嵌入宿主）。返回语义与系统返回键一致（MainActivity 的
 * OnBackPressedCallback：工坊打开时返回即关闭工坊，壳层回到配置 tab）。
 */
@Composable
private fun ThemeStudioPageHeader(onBack: (() -> Unit)?) {
    val colors = Oe3ThemeValues.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            val back = stringResource(R.string.ui3_theme_back)
            Box(
                modifier = Modifier
                    .size(Oe3Sizes.minTouchTarget)
                    .testTag("theme-studio:back")
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = back },
                contentAlignment = Alignment.Center,
            ) {
                BackChevron()
            }
            Spacer(Modifier.width(Oe3Spacing.xxs))
        }
        Column {
            Text(
                text = stringResource(R.string.ui3_theme_title),
                style = Oe3Typography.style(Oe3TextRole.PageTitle),
                color = colors.onSurface.oe3Color(),
            )
            Text(
                // 预设数从 ThemeStudioSpec 取数：UI-14 拆分后目录随代码演进，
                // 硬编码字面量会在下次目录变化时再过期（真机走查发现 41≠42）。
                text = stringResource(R.string.ui3_theme_meta, ThemeStudioSpec.expectedPresetCount),
                style = MetaTextStyle,
                color = colors.onSurfaceVariant.oe3Color(),
            )
        }
    }
}

/** 子页头返回箭头：方向 chevron，与 ConfigScreen 的 DirectionChevron 同形。 */
@Composable
private fun BackChevron() {
    val color = Oe3ThemeValues.colors.onSurface.oe3Color()
    Canvas(modifier = Modifier.size(Oe3Sizes.icon)) {
        val stroke = 1.75.dp.toPx()
        val nearX = size.width * 0.38f
        val farX = size.width * 0.62f
        drawLine(
            color,
            Offset(farX, size.height * 0.28f),
            Offset(nearX, size.height * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(nearX, size.height * 0.5f),
            Offset(farX, size.height * 0.72f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun PresetGrid(
    presets: List<io.github.nyzeep.orchestella.ui.theme.AppTheme>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { preset ->
                    PresetCard(
                        theme = preset,
                        selected = preset.id == selectedId,
                        onClick = { onSelect(preset.id) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("theme-preset:${preset.id}"),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PresetCard(
    theme: io.github.nyzeep.orchestella.ui.theme.AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    val radius = LocalOe3CornerRadius.current ?: Oe3Shapes.lg
    Oe3Card(
        modifier = modifier.semantics { this.selected = selected },
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
        shape = RoundedCornerShape(radius),
    ) {
        PresetStrip(theme)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = theme.displayName,
                modifier = Modifier.weight(1f),
                style = MetaTextStyle,
                color = colors.onSurface.oe3Color(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Spacer(Modifier.width(4.dp))
                CheckMark(Color(colors.primary))
            }
        }
        Text(
            text = when {
                theme.isFixed -> stringResource(R.string.ui3_theme_preset_fixed)
                theme.isDark -> stringResource(R.string.ui3_theme_preset_dark)
                else -> stringResource(R.string.ui3_theme_preset_light)
            },
            style = MetaTextStyle,
            color = colors.onSurfaceVariant.oe3Color(),
        )
    }
}

@Composable
private fun PresetStrip(theme: io.github.nyzeep.orchestella.ui.theme.AppTheme) {
    val stripes = listOf(theme.background, theme.surface, theme.primary, theme.accent, theme.text)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(5.dp)),
    ) {
        stripes.forEach { argb ->
            Box(Modifier.weight(1f).fillMaxSize().background(Color(argb)))
        }
    }
}

@Composable
private fun AccentCard(customAccent: Int?, onEvent: (ThemeStudioEvent) -> Unit) {
    val colors = Oe3ThemeValues.colors
    val radius = LocalOe3CornerRadius.current ?: Oe3Shapes.xl
    Oe3Card(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        shape = RoundedCornerShape(radius),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ui3_theme_accent_title),
                modifier = Modifier.weight(1f),
                style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                color = colors.onSurface.oe3Color(),
            )
            Oe3Chip(
                label = stringResource(R.string.ui3_theme_accent_follow),
                selected = customAccent == null,
                onClick = { onEvent(ThemeStudioEvent.SetCustomAccent(null)) },
                modifier = Modifier.testTag("theme-accent:follow"),
            )
        }
        Spacer(Modifier.height(8.dp))
        ThemeStudioSpec.accentSwatches.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { argb ->
                    AccentSwatch(
                        argb = argb,
                        selected = customAccent == argb,
                        onClick = { onEvent(ThemeStudioEvent.SetCustomAccent(argb)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccentSwatch(
    argb: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentDescription = stringResource(R.string.ui3_theme_accent_desc, hex(argb))
    Box(
        modifier = modifier
            .height(48.dp)
            .testTag("theme-accent:${hex(argb)}")
            .semantics {
                this.selected = selected
                contentDescription = accentDescription
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(argb)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                CheckMark(Color(io.github.nyzeep.orchestella.ui.theme.ThemeColors.onColor(argb)).copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun CustomSlotCard(
    slots: List<CustomThemeSlot?>,
    activeId: String,
    onEdit: (Int) -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    val radius = LocalOe3CornerRadius.current ?: Oe3Shapes.xl
    Oe3Card(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        shape = RoundedCornerShape(radius),
    ) {
        slots.forEachIndexed { index, slot ->
            if (index > 0) OptionDivider()
            val active = activeId == "custom:${index + 1}"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("theme-slot:$index")
                    .clickable(role = Role.Button) { onEdit(index) }
                    .padding(horizontal = Oe3Spacing.rowHorizontal, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (slot != null) {
                    Row(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    ) {
                        listOf(slot.background, slot.surface, slot.primary, slot.accent).forEach {
                            Box(Modifier.weight(1f).fillMaxSize().background(Color(it)))
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceHigh.oe3Color()),
                    )
                }
                Spacer(Modifier.width(Oe3Spacing.iconTextGap))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ui3_theme_slot, index + 1),
                        style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                        color = colors.onSurface.oe3Color(),
                        maxLines = 1,
                    )
                    Text(
                        text = when {
                            slot == null -> stringResource(R.string.ui3_theme_slot_unset)
                            active -> "${slot.name} · ${stringResource(R.string.ui3_theme_slot_current)}"
                            else -> slot.name
                        },
                        style = MetaTextStyle,
                        color = colors.onSurfaceVariant.oe3Color(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.ui3_theme_slot_edit),
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.primary.oe3Color(),
                )
            }
        }
    }
}

@Composable
private fun CornerChooser(selectedDp: Int, onSelect: (Int) -> Unit) {
    val options = if (selectedDp in ThemeStudioSpec.cornerOptions) {
        ThemeStudioSpec.cornerOptions
    } else {
        ThemeStudioSpec.cornerOptions + selectedDp
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.take(ThemeStudioSpec.cornerOptions.size).forEach { dp ->
                Oe3Chip(
                    label = stringResource(R.string.ui3_theme_corner_value, dp),
                    selected = selectedDp == dp,
                    onClick = { onSelect(dp) },
                    modifier = Modifier.testTag("theme-corner:$dp"),
                )
            }
        }
        if (options.size > ThemeStudioSpec.cornerOptions.size) {
            Oe3Chip(
                label = stringResource(R.string.ui3_theme_corner_value, selectedDp),
                selected = true,
                onClick = { onSelect(selectedDp) },
                modifier = Modifier.testTag("theme-corner:$selectedDp"),
            )
        }
    }
}

@Composable
private fun TextScaleCard(state: ThemeStudioState, onEvent: (ThemeStudioEvent) -> Unit) {
    val colors = Oe3ThemeValues.colors
    val radius = LocalOe3CornerRadius.current ?: Oe3Shapes.xl
    val custom = state.textScaleMode == OeTextScaleMode.Custom
    val sliderText = stringResource(R.string.ui3_theme_text_slider_desc, state.textScalePercent)
    Oe3Card(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        shape = RoundedCornerShape(radius),
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
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

@Composable
private fun TerminalCard(
    state: ThemeStudioState,
    onEvent: (ThemeStudioEvent) -> Unit,
    renderTerminalView: Boolean,
    onPickTerminalBackground: (() -> Unit)?,
) {
    val colors = Oe3ThemeValues.colors
    val radius = LocalOe3CornerRadius.current ?: Oe3Shapes.xl
    Oe3Card(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        shape = RoundedCornerShape(radius),
    ) {
        Text(
            text = stringResource(R.string.ui3_theme_terminal_palette),
            style = Oe3Typography.style(Oe3TextRole.BodyStrong),
            color = colors.onSurface.oe3Color(),
        )
        Spacer(Modifier.height(6.dp))
        AnsiPaletteStrip(state.theme)
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.ui3_theme_terminal_base),
            style = Oe3Typography.style(Oe3TextRole.BodyStrong),
            color = colors.onSurface.oe3Color(),
        )
        Spacer(Modifier.height(4.dp))
        TerminalBaseSwatches(state.theme)

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
            valueText = stringResource(R.string.ui3_theme_terminal_letter_value, state.terminalLetterSpacingEm),
            description = stringResource(R.string.ui3_theme_terminal_letter_desc, state.terminalLetterSpacingEm),
            value = state.terminalLetterSpacingEm,
            range = 0f..ThemeStudioSpec.terminalLetterSpacingMax,
            steps = 0,
            tag = "theme-slider:terminal-letter-spacing",
            onChange = { onEvent(ThemeStudioEvent.SetTerminalLetterSpacing(it)) },
        )
        TerminalSliderRow(
            title = stringResource(R.string.ui3_theme_terminal_line),
            valueText = stringResource(R.string.ui3_theme_terminal_line_value, state.terminalLineSpacingPx),
            description = stringResource(R.string.ui3_theme_terminal_line_desc, state.terminalLineSpacingPx),
            value = state.terminalLineSpacingPx,
            range = 0f..ThemeStudioSpec.terminalLineSpacingMax,
            steps = 7,
            tag = "theme-slider:terminal-line-spacing",
            onChange = { onEvent(ThemeStudioEvent.SetTerminalLineSpacing(it)) },
        )

        Oe3SwitchRow(
            title = stringResource(R.string.ui3_theme_terminal_cursor),
            checked = state.cursorBlink,
            onCheckedChange = { onEvent(ThemeStudioEvent.SetCursorBlink(it)) },
            modifier = Modifier.testTag("theme-row:cursor-blink"),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (state.terminalBackgroundUri == null) {
                    stringResource(R.string.ui3_theme_wallpaper_none)
                } else {
                    stringResource(R.string.ui3_theme_terminal_bg_set)
                },
                modifier = Modifier.weight(1f),
                style = MetaTextStyle,
                color = colors.onSurfaceVariant.oe3Color(),
            )
            Oe3Button(
                text = stringResource(R.string.ui3_theme_terminal_bg_pick),
                onClick = { onPickTerminalBackground?.invoke() },
                enabled = onPickTerminalBackground != null,
                style = Oe3ButtonStyle.Tonal,
                modifier = Modifier.testTag("theme-terminal:bg-pick"),
            )
            Spacer(Modifier.width(4.dp))
            Oe3Button(
                text = stringResource(R.string.ui3_theme_terminal_bg_clear),
                onClick = { onEvent(ThemeStudioEvent.ClearTerminalBackground) },
                enabled = state.terminalBackgroundUri != null,
                style = Oe3ButtonStyle.Text,
                modifier = Modifier.testTag("theme-terminal:bg-clear"),
            )
        }
        Text(
            text = stringResource(R.string.ui3_theme_terminal_bg_note),
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
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
                onEvent(ThemeStudioEvent.SetTerminalScrimAlpha((percent * 255 / 100f).roundToInt()))
            },
        )

        Text(
            text = stringResource(R.string.ui3_theme_terminal_preview_label),
            style = Oe3Typography.style(Oe3TextRole.BodyStrong),
            color = colors.onSurface.oe3Color(),
        )
        Spacer(Modifier.height(6.dp))
        TerminalPreview(
            theme = state.theme,
            fontSizeSp = state.terminalFontSizeSp,
            letterSpacingEm = state.terminalLetterSpacingEm,
            lineSpacingPx = state.terminalLineSpacingPx,
            cursorBlink = state.cursorBlink,
            renderAndroidView = renderTerminalView,
            backgroundUri = state.terminalBackgroundUri,
            // Same scrim the real terminal gets: theme color at the chosen alpha.
            scrimColor = Color(
                ThemeColors.terminalScrimArgb(
                    terminalBackground = state.theme.terminalBackground,
                    alpha = state.terminalScrimAlpha,
                ),
            ),
        )
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

@Composable
private fun modeLabel(mode: ThemeStore.Mode): String = when (mode) {
    ThemeStore.Mode.SYSTEM -> stringResource(R.string.ui3_theme_mode_auto)
    ThemeStore.Mode.LIGHT -> stringResource(R.string.ui3_theme_mode_light)
    ThemeStore.Mode.DARK -> stringResource(R.string.ui3_theme_mode_dark)
    ThemeStore.Mode.FIXED -> stringResource(R.string.ui3_theme_mode_fixed)
}

@Composable
private fun glassLabel(option: GlassOption): String = when (option) {
    GlassOption.None -> stringResource(R.string.ui3_theme_glass_none)
    GlassOption.Standard -> stringResource(R.string.ui3_theme_glass_standard)
    GlassOption.Ultra -> stringResource(R.string.ui3_theme_glass_ultra)
}

@Composable
private fun fontLabel(choice: FontChoice): String = when (choice) {
    FontChoice.System -> stringResource(R.string.ui3_theme_font_system)
    FontChoice.Mono -> stringResource(R.string.ui3_theme_font_mono)
    FontChoice.Imported -> stringResource(R.string.ui3_theme_font_imported)
}

@Composable
private fun fontLabel(state: ThemeStudioState): String = when {
    state.fontChoice == FontChoice.Imported && state.importedFontLabel != null -> state.importedFontLabel
    else -> fontLabel(state.fontChoice)
}

private fun hex(argb: Int): String = String.format("#%06X", argb and 0x00FFFFFF)
