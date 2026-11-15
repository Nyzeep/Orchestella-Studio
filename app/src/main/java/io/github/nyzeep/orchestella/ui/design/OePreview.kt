package io.github.nyzeep.orchestella.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.compose.OrchestellaComposeTheme
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog

/**
 * Visual acceptance board for the Oe v2 foundation (ticket 01, acceptance #4).
 *
 * Three full-component boards (light, dark, RTL) and three page-composition
 * previews (shell page, titled sub-page, dark sub-page).
 * This file is the review entry point in Android Studio; the device-side
 * walkthrough list lives in the lane report because this environment has no
 * emulator.
 */

// --- boards ---------------------------------------------------------------

@Preview(
    name = "Oe v2 组件板 · 浅色",
    widthDp = 412,
    heightDp = 1500,
    showBackground = true,
)
@Composable
private fun OeBoardLightPreview() {
    OeBoard(ThemeCatalog.mascotLavender)
}

@Preview(
    name = "Oe v2 组件板 · 深色",
    widthDp = 412,
    heightDp = 1500,
    showBackground = true,
)
@Composable
private fun OeBoardDarkPreview() {
    OeBoard(ThemeCatalog.constructivistWorkstation)
}

@Preview(
    name = "Oe v2 组件板 · RTL",
    widthDp = 412,
    heightDp = 900,
    locale = "ar",
    showBackground = true,
)
@Composable
private fun OeBoardRtlPreview() {
    OeBoard(ThemeCatalog.mascotLavender, compact = true)
}

@Preview(
    name = "Oe v2 组件板 · 字号 130%",
    widthDp = 412,
    heightDp = 1500,
    showBackground = true,
)
@Composable
private fun OeBoardLargeTextPreview() {
    OeBoard(ThemeCatalog.mascotLavender, textScale = 1.3f)
}

@Preview(
    name = "Oe v2 组件板 · 字号 85%",
    widthDp = 412,
    heightDp = 1500,
    showBackground = true,
)
@Composable
private fun OeBoardSmallTextPreview() {
    OeBoard(ThemeCatalog.constructivistWorkstation, textScale = 0.85f)
}

@Composable
private fun OeBoard(theme: AppTheme, compact: Boolean = false, textScale: Float = 1f) {
    OrchestellaComposeTheme(theme = theme, textScale = textScale) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = OeSpace.page, vertical = OeSpace.page),
                verticalArrangement = Arrangement.spacedBy(OeSpace.gap),
            ) {
                Column {
                    Text(
                        text = "Oe v2 组件板",
                        style = OeType.pageTitle(),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = if (textScale == 1f) {
                            "10 个基础组件 · 一套几何 · 深浅两套主题"
                        } else {
                            "10 个基础组件 · 应用内字号 ${(textScale * 100).toInt()}%"
                        },
                        style = OeType.body(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OeRowsSection(compact)
                OeControlsSection()
                OeCardsSection()
                if (!compact) {
                    OeStatsSection()
                    OeEmptySection()
                }
                Spacer(Modifier.height(OeSpace.group))
            }
        }
    }
}

@Composable
private fun OeRowsSection(compact: Boolean) {
    var enabled by remember { mutableStateOf(true) }
    OeSection(
        title = "分组 · 列表行",
        footer = "组标题在卡外；分隔线从 56dp 起，最后一行自动省略。",
    ) {
        OeRow(
            title = "终端",
            subtitle = "打开当前实例的 PTY",
            leading = { OeIcon(OeIcons.Terminal) },
            onClick = {},
        )
        OeRow(
            title = "网络与端口转发",
            leading = { OeIcon(OeIcons.Network) },
            trailing = {
                OeChip(text = "3 条规则", tone = OeChipTone.Neutral)
            },
            onClick = {},
        )
        OeRow(
            title = "后台保活",
            subtitle = "屏幕关闭时保持实例运行",
            leading = { OeIcon(OeIcons.Shield) },
            trailing = { OeSwitch(checked = enabled, onCheckedChange = { enabled = it }) },
        )
        if (!compact) {
            OeRow(
                title = "深色外观",
                leading = { OeIcon(OeIcons.Theme) },
                selected = true,
                onClick = {},
            )
            OeRow(
                title = "备份与恢复",
                leading = { OeIcon(OeIcons.Backup) },
                dense = true,
                onClick = {},
            )
        }
    }
}

@Composable
private fun OeControlsSection() {
    var index by remember { mutableIntStateOf(1) }
    OeSection(title = "控件") {
        Column(
            modifier = Modifier.padding(
                horizontal = OeSpace.page,
                vertical = OeSpace.gap,
            ),
            verticalArrangement = Arrangement.spacedBy(OeSpace.gap),
        ) {
            OeSegmented(
                items = listOf("AVF", "UML", "QEMU"),
                selectedIndex = index,
                onSelect = { index = it },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OeChip(text = "中性", tone = OeChipTone.Neutral)
                OeChip(text = "强调", tone = OeChipTone.Accent)
                OeChip(text = "运行中", tone = OeChipTone.Positive)
                OeChip(text = "待确认", tone = OeChipTone.Warning)
                OeChip(text = "失败", tone = OeChipTone.Danger)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(OeSpace.gap)) {
                OeButton(text = "启动", style = OeButtonStyle.Primary, icon = OeIcons.Play, onClick = {})
                OeButton(text = "次要", style = OeButtonStyle.Secondary, onClick = {})
                OeButton(text = "文字", style = OeButtonStyle.Ghost, onClick = {})
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OeSpace.gap),
            ) {
                OeSwitch(checked = true, onCheckedChange = {}, contentDescription = "启用")
                OeSwitch(checked = false, onCheckedChange = {}, contentDescription = "停用")
                OeSwitch(checked = true, onCheckedChange = {}, enabled = false, contentDescription = "禁用")
            }
        }
    }
}

@Composable
private fun OeCardsSection() {
    OeCard(onClick = {}) {
        Text(
            text = "OeCard",
            style = OeType.emptyTitle(),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "单一发丝线、无发光；按下 0.98 缩放 + 4% 明度偏移。",
            style = OeType.body(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OeStatsSection() {
    OeSection(title = "遥测块") {
        Row(
            modifier = Modifier.padding(OeSpace.page),
            horizontalArrangement = Arrangement.spacedBy(OeSpace.gap),
        ) {
            OeStatBlock(
                label = "CPU",
                value = "38",
                unit = "%",
                sparkline = listOf(12f, 18f, 15f, 24f, 31f, 28f, 38f),
                modifier = Modifier.weight(1f),
            )
            OeStatBlock(
                label = "内存",
                value = "2.4",
                unit = "GB",
                sparkline = listOf(2.1f, 2.2f, 2.2f, 2.3f, 2.4f),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OeEmptySection() {
    OeCard {
        OeEmptyState(
            icon = OeIcons.Instances,
            title = "还没有 Linux 实例",
            message = "选择一个发行版，几分钟内就能得到一台完整的 ARM64 工作站。",
            action = {
                OeButton(text = "选择发行版", style = OeButtonStyle.Primary, icon = OeIcons.Plus, onClick = {})
            },
        )
    }
}

// --- page composition -----------------------------------------------------

@Preview(
    name = "OePage · 工作台结构",
    widthDp = 412,
    heightDp = 900,
    showBackground = true,
)
@Composable
private fun OePagePreview() {
    OePageBoard(ThemeCatalog.constructivistWorkstation, withHeader = false)
}

@Preview(
    name = "OePage · 二级页",
    widthDp = 412,
    heightDp = 900,
    showBackground = true,
)
@Composable
private fun OePageTitledPreview() {
    OePageBoard(ThemeCatalog.mascotLavender, withHeader = true)
}

@Preview(
    name = "OePage · 深色",
    widthDp = 412,
    heightDp = 900,
    showBackground = true,
)
@Composable
private fun OePageDarkPreview() {
    OePageBoard(ThemeCatalog.constructivistWorkstation, withHeader = true)
}

@Composable
private fun OePageBoard(theme: AppTheme, withHeader: Boolean) {
    OrchestellaComposeTheme(theme = theme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            OePage(
                title = if (withHeader) "外观与主题" else null,
                onBack = if (withHeader) ({}) else null,
            ) {
                item {
                    OeCard {
                        Text(
                            text = "Arch Linux",
                            style = OeType.pageTitle(),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OeChip(text = "运行中", tone = OeChipTone.Positive)
                            OeChip(text = "QEMU", tone = OeChipTone.Accent)
                        }
                        Spacer(Modifier.height(OeSpace.card))
                        Row(horizontalArrangement = Arrangement.spacedBy(OeSpace.gap)) {
                            OeButton(text = "暂停", style = OeButtonStyle.Secondary, icon = OeIcons.Pause, onClick = {})
                            OeButton(text = "停止", style = OeButtonStyle.Ghost, icon = OeIcons.Stop, onClick = {})
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(OeSpace.gap)) {
                        OeStatBlock(
                            label = "CPU",
                            value = "38",
                            unit = "%",
                            sparkline = listOf(10f, 20f, 16f, 30f, 22f, 38f),
                            modifier = Modifier.weight(1f),
                        )
                        OeStatBlock(
                            label = "内存",
                            value = "2.4",
                            unit = "GB",
                            sparkline = listOf(2.1f, 2.2f, 2.4f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item {
                    OeSection(title = "快捷动作") {
                        OeRow(title = "终端", leading = { OeIcon(OeIcons.Terminal) }, onClick = {})
                        OeRow(title = "端口转发", leading = { OeIcon(OeIcons.Port) }, onClick = {})
                        OeRow(title = "查看日志", leading = { OeIcon(OeIcons.Log) }, onClick = {})
                        OeRow(title = "立即备份", leading = { OeIcon(OeIcons.Backup) }, onClick = {})
                    }
                }
            }
        }
    }
}

@Composable
private fun OeIcon(id: Int) {
    Icon(
        painter = painterResource(id),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(OeSize.icon),
    )
}
