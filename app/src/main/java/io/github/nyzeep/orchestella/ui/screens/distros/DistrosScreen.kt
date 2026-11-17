package io.github.nyzeep.orchestella.ui.screens.distros

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Chip
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3EmptyState
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3GridCard
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SectionHeader
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3DistroIcons
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.shell.oeCardShape
import java.util.Locale

/** 页面级几何常量；语义与命中区断言直接读这里。 */
object DistrosSpec {
    /** 票面：2 列网格。 */
    const val GRID_COLUMNS = 2

    /** 票面：手风琴 220ms；减少动态时由主题 token 归零。 */
    const val ACCORDION_MILLIS = 220

    /** 折叠卡的图标盒（单色矢量）。 */
    val iconSize = 32.dp

    /** 展开后的主 CTA 命中区下限。 */
    val installButtonHeight = 48.dp

    /** UML 下载进度条视觉高度。 */
    val progressHeight = 4.dp
}

/** 页面标题下的元信息（spec §2.3：13/18）。 */
private val MetaTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/**
 * 发行版页：2 列目录 + 单开手风琴 + 独立的 UML rootfs 组。
 *
 * 页面只读 [DistrosUiState]；安装与 UML 下载都由 [DistrosActions] 交给
 * 接线方（UI3-12）。展开状态用 `rememberSaveable` 存一个 id，旋转后保留。
 * [initialExpandedId] 只供预览与截图用例使用，生产入口保持全部收起。
 */
@Composable
fun DistrosScreen(
    state: DistrosUiState,
    actions: DistrosActions,
    modifier: Modifier = Modifier,
    initialExpandedId: String? = null,
) {
    val colors = Oe3ThemeValues.colors
    val motion = Oe3ThemeValues.motion
    var openId by rememberSaveable { mutableStateOf(initialExpandedId) }
    // 目录刷新后若打开的 id 已不存在，清掉保存值；否则同一 id 以后重新出现时
    // 会在用户没操作的情况下自己展开。
    LaunchedEffect(state.distros) {
        if (openId != null && state.distros.none { it.id == openId }) {
            openId = null
        }
    }
    val expandedId = state.distros.firstOrNull { it.id == openId }?.id
    val animationMillis = motion.pageFade.durationMillis
    val expansionEasing = motion.pageFade.easing

    OeScaffold(
        title = stringResource(R.string.ui3_distros_title),
        modifier = modifier,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(DistrosSpec.GRID_COLUMNS),
            modifier = Modifier
                .fillMaxSize()
                .testTag("distros-grid"),
            contentPadding = PaddingValues(
                start = Oe3Spacing.pageInset,
                end = Oe3Spacing.pageInset,
                top = Oe3Spacing.xl,
                bottom = LocalOe3PageBottomPadding.current,
            ),
            horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.gridGap),
            verticalArrangement = Arrangement.spacedBy(Oe3Spacing.gridGap),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                DistrosHeader()
            }

            if (state.distros.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyCatalogCard()
                }
            } else {
                items(items = state.distros, key = { it.id }) { distro ->
                    DistroGridCard(
                        distro = distro,
                        expanded = expandedId == distro.id,
                        animationMillis = animationMillis,
                        easing = expansionEasing,
                        onToggle = { openId = DistrosAccordion.toggle(openId, distro.id) },
                        onInstall = { actions.install(distro.id) },
                    )
                }
            }

            val uml = state.uml
            if (uml != null && uml.visible) {
                item(key = "uml", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Oe3SectionHeader(title = stringResource(R.string.ui3_distros_uml_section))
                        UmlRootfsCard(uml = uml, onProvision = actions.provisionUmlRootfs)
                    }
                }
            }
        }
    }
}

/** 页头只剩目录说明；标题已在 OeScaffold 顶栏（UI-02）。 */
@Composable
private fun DistrosHeader() {
    val colors = Oe3ThemeValues.colors
    Text(
        text = stringResource(R.string.ui3_distros_meta_line),
        style = MetaTextStyle,
        color = colors.onSurfaceVariant.oe3Color(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Oe3Spacing.xs),
    )
}

@Composable
private fun DistroGridCard(
    distro: DistroCardUi,
    expanded: Boolean,
    animationMillis: Int,
    easing: Easing,
    onToggle: () -> Unit,
    onInstall: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    val stateLabel = stringResource(
        if (expanded) R.string.ui3_distros_state_expanded else R.string.ui3_distros_state_collapsed,
    )

    Oe3GridCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("distro-card-${distro.id}")
            .semantics { stateDescription = stateLabel },
        onClick = onToggle,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Oe3Icon(
                image = Oe3DistroIcons.of(distro.id),
                contentDescription = null,
                modifier = Modifier.size(DistrosSpec.iconSize),
                selected = expanded,
            )
            Spacer(Modifier.width(Oe3Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = distro.name,
                    style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                    color = colors.onSurface.oe3Color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = distro.version,
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        distro.capability?.let { capability ->
            Spacer(Modifier.height(Oe3Spacing.xs))
            Oe3Chip(label = stringResource(capability.labelRes))
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(animationMillis, easing = easing)) +
                fadeIn(animationSpec = tween(animationMillis, easing = easing)),
            exit = shrinkVertically(animationSpec = tween(animationMillis, easing = easing)) +
                fadeOut(animationSpec = tween(animationMillis, easing = easing)),
        ) {
            Column {
                distro.descriptionRes?.let { descriptionRes ->
                    Spacer(Modifier.height(Oe3Spacing.xs))
                    Text(
                        text = stringResource(descriptionRes),
                        style = Oe3Typography.style(Oe3TextRole.Body),
                        color = colors.onSurfaceVariant.oe3Color(),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(Oe3Spacing.xs))
                Text(
                    text = stringResource(
                        R.string.ui3_distros_card_meta,
                        distro.architecture,
                        formatDistroSize(distro.sizeBytes),
                        distro.shaSummary,
                    ),
                    style = Oe3Typography.style(Oe3TextRole.Mono),
                    color = colors.onSurfaceVariant.oe3Color(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Oe3Spacing.sm))
                Oe3Button(
                    text = stringResource(R.string.ui3_distros_install),
                    onClick = onInstall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = DistrosSpec.installButtonHeight)
                        .testTag("distro-install-${distro.id}"),
                )
            }
        }
    }
}

@Composable
private fun EmptyCatalogCard() {
    Oe3Card(modifier = Modifier.fillMaxWidth(), shape = oeCardShape()) {
        Oe3EmptyState(
            title = stringResource(R.string.ui3_distros_empty_title),
            description = stringResource(R.string.ui3_distros_empty_body),
            graphic = {
                Oe3Icon(image = Oe3Icons.Distros, contentDescription = null)
            },
        )
    }
}

/**
 * UML rootfs 组。状态与进度全部映射自既有 `UmlRootfsCardState`，这里只
 * 负责把五个诚实相位画出来；不新增、不改写任何 phase 语义。
 */
@Composable
private fun UmlRootfsCard(uml: UmlRootfsUi, onProvision: () -> Unit) {
    val colors = Oe3ThemeValues.colors
    val statusColor = if (uml.phase == UmlRootfsPhase.Failed) {
        colors.error
    } else {
        colors.onSurfaceVariant
    }
    val action = umlAction(uml.phase)

    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("uml-rootfs-card"),
        shape = oeCardShape(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Oe3Chip(label = stringResource(R.string.engine_uml_rootfs_tag), selected = true)
            Spacer(Modifier.width(Oe3Spacing.sm))
            Text(
                text = stringResource(R.string.ui3_distros_uml_title),
                style = Oe3Typography.style(Oe3TextRole.Section),
                color = colors.onSurface.oe3Color(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Oe3Spacing.xs))
        Text(
            text = stringResource(R.string.engine_uml_rootfs_desc),
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = colors.onSurfaceVariant.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.sm))
        Text(
            text = umlStatusLine(uml),
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = statusColor.oe3Color(),
        )
        if (uml.isDownloading) {
            Spacer(Modifier.height(Oe3Spacing.xs))
            UmlProgressBar(percent = uml.progressPercent, contentDescription = umlStatusLine(uml))
        }
        Spacer(Modifier.height(Oe3Spacing.md))
        Oe3Button(
            text = stringResource(action.labelRes),
            onClick = onProvision,
            enabled = action.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DistrosSpec.installButtonHeight)
                .testTag("uml-rootfs-action"),
        )
    }
}

private data class UmlAction(
    val labelRes: Int,
    val enabled: Boolean,
)

private fun umlAction(phase: UmlRootfsPhase): UmlAction = when (phase) {
    UmlRootfsPhase.Disabled -> UmlAction(R.string.engine_uml_rootfs_download_action, enabled = false)
    UmlRootfsPhase.NotReady -> UmlAction(R.string.engine_uml_rootfs_download_action, enabled = true)
    UmlRootfsPhase.Downloading -> UmlAction(R.string.engine_uml_rootfs_downloading_action, enabled = false)
    UmlRootfsPhase.Ready -> UmlAction(R.string.engine_uml_rootfs_ready, enabled = false)
    UmlRootfsPhase.Failed -> UmlAction(R.string.engine_uml_rootfs_retry_action, enabled = true)
}

@Composable
private fun umlStatusLine(uml: UmlRootfsUi): String = when (uml.phase) {
    UmlRootfsPhase.Disabled -> ""
    UmlRootfsPhase.NotReady -> stringResource(R.string.engine_uml_rootfs_status_not_ready)
    UmlRootfsPhase.Downloading -> if (uml.progressPercent >= 0) {
        stringResource(R.string.engine_uml_rootfs_downloading, uml.progressPercent)
    } else {
        stringResource(R.string.engine_uml_rootfs_connecting)
    }
    UmlRootfsPhase.Ready -> stringResource(
        R.string.engine_uml_rootfs_ready_line,
        stringResource(
            if (uml.alreadyVerified) {
                R.string.engine_uml_rootfs_ready_provenance
            } else {
                R.string.engine_uml_rootfs_ready
            },
        ),
        uml.extractedEntries,
        stringResource(
            if (uml.compileBridgeWired) {
                R.string.engine_uml_rootfs_ready_wired
            } else {
                R.string.engine_uml_rootfs_ready_unwired
            },
        ),
    )
    UmlRootfsPhase.Failed -> stringResource(
        R.string.engine_uml_rootfs_failed,
        uml.failureReason ?: "uml-rootfs-unknown-failure",
    )
}

@Composable
private fun UmlProgressBar(percent: Int, contentDescription: String) {
    val colors = Oe3ThemeValues.colors
    val bar = Modifier
        .fillMaxWidth()
        .height(DistrosSpec.progressHeight)
        .clip(Oe3Shapes.pill)
        .semantics {
            this.contentDescription = contentDescription
            progressBarRangeInfo = if (percent < 0) {
                ProgressBarRangeInfo.Indeterminate
            } else {
                ProgressBarRangeInfo((percent / 100f).coerceIn(0f, 1f), 0f..1f)
            }
        }

    if (percent < 0) {
        // 连接中没有可诚实的百分比：用虚线底纹表示不确定，绝不画成某个固定进度。
        IndeterminateBar(bar)
    } else {
        val fraction = (percent / 100f).coerceIn(0f, 1f)
        Box(modifier = bar.background(colors.surfaceHigh.oe3Color())) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(Oe3Shapes.pill)
                    .background(colors.primary.oe3Color()),
            )
        }
    }
}

@Composable
private fun IndeterminateBar(modifier: Modifier) {
    val colors = Oe3ThemeValues.colors
    val track = colors.surfaceHigh.oe3Color()
    val mark = colors.primary.oe3Color().copy(alpha = 0.75f)
    Canvas(modifier = modifier) {
        val y = size.height / 2f
        drawLine(
            color = track,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = mark,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                intervals = floatArrayOf(10.dp.toPx(), 7.dp.toPx()),
                phase = 0f,
            ),
        )
    }
}

/**
 * rootfs 体积读数：≥1 GiB 用一位小数 GB，其余用整数 MB（<10 MB 保一位，
 * 免得 3.8 MB 被说成 4 MB）。以 1024 进位，与清单的字节数口径一致。
 */
internal fun formatDistroSize(bytes: Long): String {
    val mib = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
    return when {
        mib >= 1024.0 -> String.format(Locale.US, "%.1f GB", mib / 1024.0)
        mib >= 10.0 -> String.format(Locale.US, "%.0f MB", mib)
        else -> String.format(Locale.US, "%.1f MB", mib)
    }
}
