package io.github.nyzeep.orchestella.ui.oe3.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Chip
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Divider
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3EmptyState
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3GridCard
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ListRow
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Ring
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SectionHeader
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SwitchRow
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3DistroIcons
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/** Fixed board width: the Pixel-class 412dp reference used by the spec. */
internal val BoardWidth = 412.dp

/** Tall enough for the 130% text-scale board to reach the empty state. */
internal val BoardHeight = 1220.dp

/**
 * Palette + component board for one theme mode and text scale.
 *
 * The board is deliberately a pure function of the Oe3 tokens: it never reads
 * a live preference, a clock, or an activity, so the PNG is reproducible on a
 * clean CI checkout.
 */
@Composable
internal fun Oe3TokenBoard(modifier: Modifier = Modifier) {
    val colors = Oe3ThemeValues.colors
    Column(
        modifier = modifier
            .background(Color(colors.background))
            .padding(Oe3Spacing.pageInset),
        verticalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
    ) {
        Text(
            text = "Oe3 tokens",
            style = Oe3Typography.style(Oe3TextRole.Brand),
            color = Color(colors.onSurface),
        )
        Text(
            text = "palette · components · 412dp",
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = Color(colors.onSurfaceVariant),
        )
        PaletteBoard()
        Oe3SectionHeader(title = "列表与卡片")
        Oe3Card(modifier = Modifier.fillMaxWidth()) {
            Oe3ListRow(
                title = "终端",
                subtitle = "打开当前实例的 PTY",
                leading = { Oe3Icon(Oe3Icons.Terminal, contentDescription = null) },
                trailing = { Oe3Chip(label = "运行中", selected = true) },
                showDivider = true,
            )
            Oe3ListRow(
                title = "网络与端口转发",
                leading = { Oe3Icon(Oe3Icons.Ssh, contentDescription = null) },
                trailing = { Oe3Chip(label = "3 条") },
                showDivider = true,
            )
            Oe3SwitchRow(
                title = "后台保活",
                subtitle = "屏幕关闭时保持实例运行",
                checked = true,
                onCheckedChange = {},
                leading = { Oe3Icon(Oe3Icons.Power, contentDescription = null) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
            Oe3Button(text = "启动", onClick = {}, style = Oe3ButtonStyle.Pill)
            Oe3Button(text = "导入", onClick = {}, style = Oe3ButtonStyle.Tonal)
            Oe3Button(text = "更多", onClick = {}, style = Oe3ButtonStyle.Text)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
            Oe3Chip(label = "全部", selected = true, onClick = {})
            Oe3Chip(label = "运行中", onClick = {})
            Oe3Chip(label = "已停止", onClick = {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
            Oe3GridCard(modifier = Modifier.weight(1f)) {
                Oe3Icon(Oe3Icons.Distros, contentDescription = null)
                Spacer(Modifier.height(Oe3Spacing.xs))
                Text(
                    text = "Ubuntu 24.04",
                    style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                    color = Color(colors.onSurface),
                )
                Text(
                    text = "ARM64",
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = Color(colors.onSurfaceVariant),
                )
            }
            Oe3GridCard(modifier = Modifier.weight(1f)) {
                Oe3Icon(Oe3Icons.Hardware, contentDescription = null)
                Spacer(Modifier.height(Oe3Spacing.xs))
                Text(
                    text = "硬件加速",
                    style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                    color = Color(colors.onSurface),
                )
                Text(
                    text = "VirGL",
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = Color(colors.onSurfaceVariant),
                )
            }
        }
        Oe3Divider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oe3Ring(progress = 0.72f, contentDescription = "72%") {
                Text(
                    text = "72%",
                    style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                    color = Color(colors.onSurface),
                )
            }
            Oe3EmptyState(
                title = "还没有实例",
                description = "创建一个发行版实例，或从备份导入。",
                graphic = { Oe3Icon(Oe3Icons.Instances, contentDescription = null) },
                action = { Oe3Button(text = "创建实例", onClick = {}) },
                modifier = Modifier.weight(1f),
            )
        }
        // Layout sentinel: the screenshot test asserts this stays inside the
        // fixed board bounds, so content overflow cannot silently clip.
        Spacer(Modifier.height(1.dp).testTag("board-end"))
    }
}

@Composable
private fun PaletteBoard() {
    val colors = Oe3ThemeValues.colors
    val swatches = listOf(
        "background" to colors.background,
        "surface" to colors.surface,
        "surfaceRaised" to colors.surfaceRaised,
        "surfaceHigh" to colors.surfaceHigh,
        "onSurface" to colors.onSurface,
        "onSurfaceVariant" to colors.onSurfaceVariant,
        "primary" to colors.primary,
        "primaryContainer" to colors.primaryContainer,
        "accent" to colors.accent,
        "error" to colors.error,
        "divider" to colors.divider,
    )
    Column(verticalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
        swatches.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
                row.forEach { (name, argb) ->
                    Swatch(name = name, argb = argb)
                }
            }
        }
    }
}

@Composable
private fun Swatch(name: String, argb: Int) {
    val colors = Oe3ThemeValues.colors
    Column(modifier = Modifier.width(112.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(Oe3Shapes.xs))
                .background(Color(argb)),
        )
        Text(
            text = name,
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = Color(colors.onSurfaceVariant),
            maxLines = 2,
            textAlign = TextAlign.Start,
        )
    }
}

/** Bottom-bar labels in destination order (spec §2.3). */
internal val NavLabels = listOf("工作站", "实例", "硬件", "发行版", "配置")

/** Icon sheet: one row per family so a weight/path regression is visible. */
@Composable
internal fun Oe3IconBoard(modifier: Modifier = Modifier) {
    val colors = Oe3ThemeValues.colors
    Column(
        modifier = modifier
            .background(Color(colors.background))
            .padding(Oe3Spacing.pageInset),
        verticalArrangement = Arrangement.spacedBy(Oe3Spacing.md),
    ) {
        Text(
            text = "Oe3 icons",
            style = Oe3Typography.style(Oe3TextRole.Brand),
            color = Color(colors.onSurface),
        )
        IconSection(title = "导航", icons = Oe3Icons.navigation, names = NavLabels)
        IconSection(
            title = "动作",
            icons = Oe3Icons.actions,
            names = listOf("终端", "电源", "更多", "创建", "导入", "SSH", "主题", "关于", "更新"),
        )
        IconSection(
            title = "发行版",
            icons = Oe3DistroIcons.entries.take(10).map { it.vector },
            names = Oe3DistroIcons.entries.take(10).map { it.displayName },
        )
        Spacer(Modifier.height(1.dp).testTag("board-end"))
    }
}

@Composable
private fun IconSection(
    title: String,
    icons: List<ImageVector>,
    names: List<String>,
) {
    val colors = Oe3ThemeValues.colors
    Column(verticalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
        Text(
            text = title,
            style = Oe3Typography.style(Oe3TextRole.Section),
            color = Color(colors.onSurfaceVariant),
        )
        icons.chunked(5).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.sm)) {
                row.forEachIndexed { columnIndex, icon ->
                    val nameIndex = rowIndex * 5 + columnIndex
                    Column(
                        modifier = Modifier.width(68.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Oe3Icon(icon, contentDescription = null)
                        }
                        Text(
                            text = names[nameIndex],
                            style = Oe3Typography.style(Oe3TextRole.Caption),
                            color = Color(colors.onSurfaceVariant),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
