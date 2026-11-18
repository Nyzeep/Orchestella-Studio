package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog

/**
 * 主题预设页（v4 §2.2）。
 *
 * 规则逐条落在 [ThemePresetGrouping]：一个 family 只渲染一张卡、卡片只显示
 * 当前模式那一套配色（切明暗自动跟随）、看板娘单列在最前。卡片色条恰好
 * 4 色，选中态读当前主题。
 */
@Composable
fun ThemePresetScreen(
    state: ThemeStudioState,
    onEvent: (ThemeStudioEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    presetSpecs: List<io.github.nyzeep.orchestella.ui.theme.ThemePreset> = ThemeCatalog.presets,
) {
    val groups = remember(presetSpecs, state.theme.isDark) {
        ThemePresetGrouping.groups(presetSpecs, state.theme.isDark)
    }
    val mascot = groups.firstOrNull { it.isMascot }
    val others = groups.filterNot { it.isMascot }

    OeScaffold(
        title = stringResource(R.string.ui3_theme_page_presets),
        modifier = modifier.testTag("theme-presets:screen"),
        leading = { ThemeBackButton(onBack = onBack) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Oe3Spacing.pageInset)
                .padding(top = Oe3Spacing.xs, bottom = LocalOe3PageBottomPadding.current),
        ) {
            if (mascot != null) {
                ThemeSectionHeader(
                    key = "preset-mascot",
                    title = stringResource(R.string.ui3_theme_group_mascot),
                )
                PresetCard(
                    group = mascot,
                    selected = state.theme.id == mascot.theme.id,
                    onClick = { onEvent(ThemeStudioEvent.SelectTheme(mascot.theme.id)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("theme-preset:${mascot.familyId}"),
                )
                Spacer(Modifier.height(Oe3Spacing.md))
            }

            ThemeSectionHeader(
                key = "presets",
                title = stringResource(R.string.ui3_theme_group_other),
                value = stringResource(R.string.ui3_theme_presets_count, others.size),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                others.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { group ->
                            PresetCard(
                                group = group,
                                selected = state.theme.id == group.theme.id,
                                onClick = { onEvent(ThemeStudioEvent.SelectTheme(group.theme.id)) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("theme-preset:${group.familyId}"),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(Oe3Spacing.md))
            ThemeSectionHeader(
                key = "custom-slots",
                title = stringResource(R.string.ui3_theme_custom_slots),
            )
            CustomSlotSection(
                slots = state.customSlots,
                activeId = state.theme.id,
                fallbackTheme = state.theme,
                onEvent = onEvent,
            )
            Spacer(Modifier.height(1.dp).testTag("theme-presets:end"))
        }
    }
}

@Composable
private fun PresetCard(
    group: ThemePresetGroup,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    val radius = LocalOe3CornerRadius.current ?: Oe3Shapes.lg
    Oe3Card(
        modifier = modifier.semantics { this.selected = selected },
        onClick = onClick,
        contentPadding = PaddingValues(10.dp),
        shape = RoundedCornerShape(radius),
    ) {
        PresetStrip(group.theme)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = group.title,
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
    }
}

@Composable
private fun PresetStrip(theme: AppTheme) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(5.dp)),
    ) {
        ThemePresetGrouping.stripColors(theme).forEach { argb ->
            Box(Modifier.weight(1f).fillMaxSize().background(Color(argb)))
        }
    }
}
