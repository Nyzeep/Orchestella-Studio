package io.github.nyzeep.orchestella.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Chip
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3EmptyState
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ListRow
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Ring
import io.github.nyzeep.orchestella.ui.oe3.components.OeCardHeader
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.components.oeCardTitleStyle
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3DistroIcons
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3IconTint
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.motion.Oe3CardEnterMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3ThemeInfo
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion
import io.github.nyzeep.orchestella.ui.screens.common.formatGigabytes
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.shell.OeScaffoldAction
import io.github.nyzeep.orchestella.ui.shell.oeCardShape
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 页面级几何常量；语义与命中区断言直接读这里。 */
object DashboardSpec {
    /** “打开终端”是主卡唯一主动作，命中区不得低于 48dp。 */
    val terminalButtonHeight = 48.dp

    /** 存储环是页面主视觉。 */
    val ringDiameter = 120.dp
    val ringStroke = 12.dp

    /** CPU / RAM 紧凑卡的用量条。 */
    val metricBarHeight = 4.dp

    /** 指标读数由壳层每 3 秒刷新一次。 */
    const val refreshSeconds = 3

    // --- 编辑态几何（UI-09） ------------------------------------------------

    /** 删除徽标的视觉直径；命中区仍走 [Oe3Sizes.minTouchTarget]。 */
    val editBadgeVisual = 28.dp

    /** 删除徽标内的减号图标。 */
    val editBadgeIcon = 16.dp

    /**
     * 拖起时的瞬态投影。规格只说「e2」——FlClash 的 e2 是自定义多层阴影，
     * 这里用单层 8dp elevation 近似，且只在拖拽瞬间存在，绝不用静态投影
     * 表达层级（层级走色阶）。
     */
    val editDragElevation = 8.dp

    /** 徽标与卡片右上角的留白。 */
    val editBadgeInset = 6.dp
}

/** 页面标题下的元信息（spec §2.3：13/18）。 */
private val MetaTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/** 环心与指标卡的大数字；不在八级 token 内，属于页面局部样式。 */
private val FigureTextStyle = TextStyle(
    fontSize = 24.sp,
    lineHeight = 28.sp,
    fontWeight = FontWeight.SemiBold,
)

/** LazyColumn 的固定槽位 key；卡片 key 由 [DashboardCardId.itemKey] 生成。 */
private const val KEY_META = "dashboard:meta"
private const val KEY_RESTORE = "dashboard:restore"

private fun DashboardCardId.itemKey(): String = "card:$name"

private fun cardIdFromKey(key: String): DashboardCardId? =
    key.removePrefix("card:").let { parsed ->
        DashboardCardId.entries.firstOrNull { it.name == parsed }
    }

/**
 * 编辑态的拖拽会话（UI-09）。一次只拖一张卡：key 是被拖卡的槽位 key，
 * dragOffsetPx 是手指下的瞬态位移（px），lift 0→1 驱动「拖起」的
 * scale 1.03 + 瞬态投影 ramp（easeOut，时长走 [OeMotion]）。
 */
private class DashboardDragState {
    var draggingKey: String? by mutableStateOf(null)
    val dragOffset = Animatable(0f)
    val lift = Animatable(0f)
}

/**
 * 编辑态待机摆动的 Modifier：sin 旋转 ±0.7°、cos 位移 ±1.25dp、周期 3600ms，
 * 每卡按 [phaseIndex]（卡身份的 ordinal，稳定不随重排变化）错开相位。
 *
 * [paused] 在拖拽瞬间压平振幅（被拖卡静止），不销毁过渡，松手后继续。
 * 减动效（`areAnimatorsEnabled()==false` 经壳层折算成 motion token 归零）
 * 时不创建过渡，卡片完全静止。
 */
@Composable
private fun rememberEditWiggleModifier(enabled: Boolean, paused: Boolean, phaseIndex: Int): Modifier {
    val motion = Oe3ThemeValues.motion
    if (!enabled || motion.editWiggle.durationMillis == 0) return Modifier
    val period = motion.editWiggle.durationMillis
    val progress = rememberInfiniteTransition(label = "dashboardEditWiggle").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = period, easing = LinearEasing),
            initialStartOffset = StartOffset((phaseIndex * OeMotion.EDIT_WIGGLE_PHASE_STEP_MS) % period),
        ),
        label = "dashboardEditWiggleProgress",
    )
    return Modifier.graphicsLayer {
        val amplitude = if (paused) 0f else 1f
        val angle = (progress.value * 2f * PI.toFloat())
        rotationZ = OeMotion.EDIT_WIGGLE_ROTATION_DEG * amplitude * sin(angle)
        translationY = OeMotion.EDIT_WIGGLE_SHIFT_DP.dp.toPx() * amplitude * cos(angle)
    }
}

/**
 * 编辑态出现动画（UI-09：200ms，scale 0.92→1 + fade）。只在编辑态下新组合
 * 进来的卡（恢复显示的那张）播放：`remember` 持有的过渡态在翻牌进/出编辑态
 * 时不重建，所以整页进入编辑不会重播。减动效下首帧即终态。
 */
@Composable
private fun DashboardEditAppearMotion(isEditing: Boolean, content: @Composable () -> Unit) {
    val reduced = LocalOe3ThemeInfo.current.reducedMotion
    // 无 key 的 remember：过渡态只在卡槽首次组合时创建。浏览态入场的卡从
    // 可见态开始（不重播）；编辑态下新组合的卡（恢复显示）才播放出现动画。
    val appeared = remember {
        MutableTransitionState(!isEditing || reduced).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = appeared,
        enter = fadeIn(tween(OeMotion.EDIT_APPEAR_MS, easing = OeMotion.easeOutCubicEasing)) +
            scaleIn(
                initialScale = OeMotion.EDIT_APPEAR_SCALE_FROM,
                animationSpec = tween(OeMotion.EDIT_APPEAR_MS, easing = OeMotion.easeOutCubicEasing),
            ),
    ) {
        content()
    }
}

/**
 * 生产装配：编辑态持久层挂在应用的单一设置文件（`orchestella_settings`）上。
 * 预览与截图传显式 [DashboardEditState]，绕开真实偏好。
 */
@Composable
internal fun rememberDashboardEditState(): DashboardEditState {
    val context = LocalContext.current
    return remember { DashboardEditState(SharedPreferencesDashboardEditStore(context)) }
}

/**
 * 工作站页：当前实例 + 存储 + 两台指标 + 最近实例，全部是**自带标题**的
 * 卡片（UI-09：卡外分区标题退役），整页按 [DashboardEditState] 的顺序排列。
 *
 * 页面只读 [DashboardUiState]；路由、真实动作与 3 秒刷新由 UI3-12 接线。
 * 独立可预览，不依赖壳层。
 *
 * v4（UI-02）：整页 `background` 与页内 30sp 标题已删除 —— 标题交给
 * [OeScaffold]，页面本体保持透明，壳层背景层才有机会露出来。
 *
 * v4（UI-04）：存储卡与最近实例卡的标题移入卡内 `title` 槽。
 *
 * v4（UI-09）：卡片全部标题内嵌（指标分区头退役，CPU/内存各自带标题）；
 * 右上 44dp 动作槽承载「编辑卡片布局 → 对勾保存」；编辑态为同页状态切换
 * （严禁弹窗）——长按拖拽整卡重排、28dp 圆形删除徽标只做隐藏（绝不删数据，
 * 恢复入口在列表尾部的 chips 行）、待机摆动 ±0.7°/±1.25dp、拖起 scale 1.03
 * + 瞬态投影；顺序与隐藏集合写穿到设置偏好，杀进程重进仍在。动效时长全部
 * 来自 [OeMotion]，减动效下摆动静止、动画直接落位。
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
    edit: DashboardEditState = rememberDashboardEditState(),
) {
    OeScaffold(
        title = stringResource(
            if (edit.isEditing) R.string.ui3_dashboard_edit_title else R.string.ui3_dashboard_title,
        ),
        modifier = modifier,
        actions = listOf(
            OeScaffoldAction(
                icon = if (edit.isEditing) Oe3Icons.Check else Oe3Icons.Edit,
                contentDescription = stringResource(
                    if (edit.isEditing) R.string.ui3_dashboard_edit_save else R.string.ui3_dashboard_edit_enter,
                ),
                onClick = { edit.setEditing(!edit.isEditing) },
                testTag = "dashboard:edit-toggle",
            ),
        ),
    ) {
        DashboardCardList(state = state, actions = actions, edit = edit)
    }
}

@Composable
private fun DashboardCardList(
    state: DashboardUiState,
    actions: DashboardActions,
    edit: DashboardEditState,
) {
    val listState = rememberLazyListState()
    val drag = remember { DashboardDragState() }
    val scope = rememberCoroutineScope()
    // 卡片入场只播一次（spec §1.5 cardEnter；tab 切换回来不重播）。
    var cardsEntered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { cardsEntered = true }

    val moveUpLabel = stringResource(R.string.ui3_dashboard_edit_move_up)
    val moveDownLabel = stringResource(R.string.ui3_dashboard_edit_move_down)
    val hideLabel = stringResource(R.string.ui3_dashboard_edit_hide)

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard:cards"),
        contentPadding = PaddingValues(
            start = Oe3Spacing.pageInset,
            end = Oe3Spacing.pageInset,
            top = Oe3Spacing.xl,
            bottom = LocalOe3PageBottomPadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(Oe3Spacing.gridGap),
    ) {
        item(key = KEY_META) {
            Text(
                text = metaLine(state),
                style = MetaTextStyle,
                color = Oe3ThemeValues.colors.onSurfaceVariant.oe3Color(),
                modifier = Modifier.padding(bottom = Oe3Spacing.xs),
            )
        }
        items(edit.visible, key = { it.itemKey() }) { cardId ->
            Oe3CardEnterMotion(
                entered = cardsEntered,
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(OeMotion.EDIT_APPEAR_MS, easing = OeMotion.easeOutCubicEasing),
                    placementSpec = tween(OeMotion.EDIT_REORDER_SETTLE_MS, easing = OeMotion.standardEasing),
                    fadeOutSpec = tween(OeMotion.EDIT_APPEAR_MS, easing = OeMotion.easeOutCubicEasing),
                ),
            ) {
                DashboardCardSlot(
                    cardId = cardId,
                    state = state,
                    actions = actions,
                    edit = edit,
                    drag = drag,
                    listState = listState,
                    scope = scope,
                    moveUpLabel = moveUpLabel,
                    moveDownLabel = moveDownLabel,
                    hideLabel = hideLabel,
                )
            }
        }
        if (edit.isEditing && edit.hidden.isNotEmpty()) {
            item(key = KEY_RESTORE) {
                RestoreRow(edit = edit)
            }
        }
    }
}

@Composable
private fun DashboardCardSlot(
    cardId: DashboardCardId,
    state: DashboardUiState,
    actions: DashboardActions,
    edit: DashboardEditState,
    drag: DashboardDragState,
    listState: LazyListState,
    scope: CoroutineScope,
    moveUpLabel: String,
    moveDownLabel: String,
    hideLabel: String,
) {
    val cardShape = oeCardShape()
    val isDragging = drag.draggingKey == cardId.itemKey()
    val wiggle = rememberEditWiggleModifier(
        enabled = edit.isEditing,
        paused = isDragging,
        phaseIndex = cardId.ordinal,
    )
    // Compose 1.11 的自定义无障碍动作返回「是否已处理」。
    val moveUp = CustomAccessibilityAction(moveUpLabel) {
        val visibleIndex = edit.visible.indexOf(cardId)
        if (visibleIndex > 0) {
            edit.moveVisible(visibleIndex, visibleIndex - 1)
            true
        } else {
            false
        }
    }
    val moveDown = CustomAccessibilityAction(moveDownLabel) {
        val visibleIndex = edit.visible.indexOf(cardId)
        if (visibleIndex in 0 until edit.visible.lastIndex) {
            edit.moveVisible(visibleIndex, visibleIndex + 1)
            true
        } else {
            false
        }
    }
    val hideAction = CustomAccessibilityAction(hideLabel) {
        edit.hide(cardId)
        true
    }

    DashboardEditAppearMotion(isEditing = edit.isEditing) {
        Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(edit.isEditing, cardId) {
                        if (!edit.isEditing) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                if (drag.draggingKey == null) {
                                    drag.draggingKey = cardId.itemKey()
                                    scope.launch {
                                        drag.dragOffset.snapTo(0f)
                                        drag.lift.animateTo(
                                            1f,
                                            tween(OeMotion.EDIT_DRAG_LIFT_MS, easing = OeMotion.easeOutCubicEasing),
                                        )
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                val currentKey = drag.draggingKey ?: return@detectDragGesturesAfterLongPress
                                change.consume()
                                val layout = listState.layoutInfo
                                val dragging = layout.visibleItemsInfo
                                    .firstOrNull { it.key == currentKey }
                                    ?: return@detectDragGesturesAfterLongPress
                                val neighbours = layout.visibleItemsInfo.filter { info ->
                                    val key = info.key as? String
                                    key != null && key in edit.visible.map { it.itemKey() } && key != currentKey
                                }
                                val next = if (dragAmount.y > 0f) {
                                    neighbours.filter { it.offset > dragging.offset }.minByOrNull { it.offset }
                                } else {
                                    neighbours.filter { it.offset < dragging.offset }.maxByOrNull { it.offset }
                                }
                                var delta = dragAmount.y
                                if (next != null) {
                                    val anticipatedCenter = dragging.offset +
                                        drag.dragOffset.targetValue + dragAmount.y + dragging.size / 2f
                                    val nextCenter = next.offset + next.size / 2f
                                    val crossed = if (dragAmount.y > 0f) {
                                        anticipatedCenter > nextCenter
                                    } else {
                                        anticipatedCenter < nextCenter
                                    }
                                    if (crossed) {
                                        val fromId = cardIdFromKey(currentKey)
                                        val toId = cardIdFromKey(next.key as? String ?: "")
                                        val from = fromId?.let { edit.visible.indexOf(it) } ?: -1
                                        val to = toId?.let { edit.visible.indexOf(it) } ?: -1
                                        if (from >= 0 && to >= 0) {
                                            edit.moveVisible(from, to)
                                            // 槽位互换后 item 的布局 offset 会跳，抵消掉，
                                            // 卡片在手指下保持不动。
                                            delta += dragging.offset - next.offset
                                        }
                                    }
                                }
                                scope.launch { drag.dragOffset.snapTo(drag.dragOffset.targetValue + delta) }
                            },
                            onDragEnd = { settleDrag(drag, scope) },
                            onDragCancel = { settleDrag(drag, scope) },
                        )
                    }
                    .graphicsLayer {
                        translationY = drag.dragOffset.value
                        val lifted = drag.lift.value
                        val scale = 1f + lifted * OeMotion.EDIT_DRAG_LIFT_SCALE_DELTA
                        scaleX = scale
                        scaleY = scale
                        // 拖起瞬态投影（v4 spec §1.4：阴影无，仅编辑态拖起时 e2）。
                        // 放在同一个 graphicsLayer 里，投影跟随位移与缩放；lift 归零
                        // 后 elevation 为 0，页面没有任何静态投影（层级走色阶）。
                        shape = cardShape
                        shadowElevation = DashboardSpec.editDragElevation.toPx() * lifted
                    }
                    .then(wiggle)
                    .semantics {
                        if (edit.isEditing) {
                            customActions = listOf(moveUp, moveDown, hideAction)
                        }
                    },
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    when (cardId) {
                        DashboardCardId.Primary -> {
                            val primary = state.primary
                            if (primary == null) {
                                EmptyPrimaryCard(actions = actions)
                            } else {
                                PrimaryInstanceCard(
                                    instance = primary,
                                    onOpenTerminal = { actions.openTerminal(primary.id) },
                                )
                            }
                        }
                        DashboardCardId.Storage -> StorageCard(
                            state = state.storage,
                            onOpenSettings = actions.openStorageSettings,
                        )
                        DashboardCardId.Metrics -> MetricsGroup(state = state)
                        DashboardCardId.Recent -> RecentCard(
                            state = state,
                            onOpenInstance = actions.openInstance,
                        )
                    }
                    if (edit.isEditing) {
                        HideBadge(
                            onHide = { edit.hide(cardId) },
                            hideLabel = hideLabel,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(DashboardSpec.editBadgeInset),
                        )
                    }
                }
            }
        }
}

/** 松手落位：scale/投影与残余位移都按拖起时长 easeOut 收回，再结束拖拽会话。 */
private fun settleDrag(drag: DashboardDragState, scope: CoroutineScope) {
    scope.launch {
        drag.lift.animateTo(0f, tween(OeMotion.EDIT_DRAG_LIFT_MS, easing = OeMotion.easeOutCubicEasing))
        drag.dragOffset.animateTo(0f, tween(OeMotion.EDIT_DRAG_LIFT_MS, easing = OeMotion.easeOutCubicEasing))
        drag.draggingKey = null
    }
}

/** 编辑态的删除徽标：28dp 圆、errorContainer 底 + onErrorContainer 减号 16dp，右上对齐；命中区仍是 48dp。 */
@Composable
private fun HideBadge(
    onHide: () -> Unit,
    hideLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    Box(
        modifier = modifier
            .size(Oe3Sizes.minTouchTarget)
            .clickable(role = Role.Button, onClick = onHide)
            .semantics { contentDescription = hideLabel },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(DashboardSpec.editBadgeVisual)
                .clip(CircleShape)
                .background(Color(colors.errorContainer)),
            contentAlignment = Alignment.Center,
        ) {
            Oe3Icon(
                image = Oe3Icons.Remove,
                contentDescription = null,
                tint = Color(colors.onErrorContainer),
                modifier = Modifier.size(DashboardSpec.editBadgeIcon),
            )
        }
    }
}

/** 编辑态尾部：被隐藏的卡以 chips 列出，点按恢复到原槽位（不是删除）。 */
@Composable
private fun RestoreRow(edit: DashboardEditState) {
    Column {
        Text(
            text = stringResource(R.string.ui3_dashboard_edit_restore_hint),
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = Oe3ThemeValues.colors.onSurfaceVariant.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.xxs))
        Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
            edit.hidden.forEach { cardId ->
                Oe3Chip(
                    label = stringResource(cardId.labelRes),
                    onClick = { edit.restore(cardId) },
                )
            }
        }
    }
}

@Composable
private fun metaLine(state: DashboardUiState): String = if (state.primary == null) {
    stringResource(R.string.ui3_dashboard_meta_empty)
} else {
    stringResource(R.string.ui3_dashboard_meta_summary, state.instanceCount, state.runningCount)
}

@Composable
private fun PrimaryInstanceCard(
    instance: DashboardInstanceUi,
    onOpenTerminal: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(modifier = Modifier.fillMaxWidth(), shape = oeCardShape()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DistroMark(distroId = instance.distroId)
            Spacer(Modifier.width(Oe3Spacing.iconTextGap))
            Column(modifier = Modifier.weight(1f)) {
                // UI-09：主卡标题也走 17/22 w600 的卡内标题样式。
                Text(
                    text = instance.name,
                    style = oeCardTitleStyle(),
                    color = colors.onSurface.oe3Color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.ui3_dashboard_instance_specs,
                        instance.cpuCores,
                        instance.ramMb,
                        instance.diskGb,
                    ),
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Oe3Spacing.sm))
            DashboardPill(label = stringResource(instance.status.labelRes), tone = instance.status.tone)
        }
        if (instance.engineLabel != null) {
            Spacer(Modifier.height(Oe3Spacing.sm))
            DashboardPill(
                label = instance.engineLabel,
                tone = DashboardStatusTone.Idle,
            )
        }
        Spacer(Modifier.height(Oe3Spacing.md))
        Oe3Button(
            text = stringResource(R.string.ui3_dashboard_open_terminal),
            onClick = onOpenTerminal,
            enabled = instance.canOpenTerminal,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DashboardSpec.terminalButtonHeight),
            leading = {
                Oe3Icon(
                    image = Oe3Icons.Terminal,
                    contentDescription = null,
                    tint = if (instance.canOpenTerminal) {
                        colors.onPrimary.oe3Color()
                    } else {
                        colors.onPrimary.oe3Color().copy(alpha = 0.5f)
                    },
                )
            },
        )
    }
}

@Composable
private fun EmptyPrimaryCard(actions: DashboardActions) {
    Oe3Card(modifier = Modifier.fillMaxWidth(), shape = oeCardShape()) {
        Oe3EmptyState(
            title = stringResource(R.string.ui3_dashboard_empty_title),
            description = stringResource(R.string.ui3_dashboard_empty_body),
            graphic = {
                Oe3Icon(image = Oe3Icons.Create, contentDescription = null)
            },
            action = {
                Oe3Button(
                    text = stringResource(R.string.ui3_dashboard_create_instance),
                    onClick = actions.createInstance,
                    modifier = Modifier.heightIn(min = DashboardSpec.terminalButtonHeight),
                )
            },
        )
    }
}

@Composable
private fun StorageCard(state: DashboardStorageUi, onOpenSettings: () -> Unit) {
    val colors = Oe3ThemeValues.colors
    val usedText = formatGigabytes(state.usedBytes)
    val totalText = formatGigabytes(state.totalBytes)
    Oe3Card(
        modifier = Modifier.fillMaxWidth(),
        shape = oeCardShape(),
        // UI-04/UI-09：卡内标题，20dp 内边距下直接排标题文本，与环行同缩进。
        title = {
            Text(
                text = stringResource(R.string.ui3_dashboard_section_storage),
                style = oeCardTitleStyle(),
                color = colors.onSurface.oe3Color(),
            )
        },
    ) {
        Spacer(Modifier.height(Oe3Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Oe3Ring(
                progress = state.fraction,
                diameter = DashboardSpec.ringDiameter,
                ringWidth = DashboardSpec.ringStroke,
                color = colors.primary.oe3Color(),
                trackColor = colors.surfaceHigh.oe3Color(),
                contentDescription = stringResource(
                    R.string.ui3_dashboard_storage_ring_description,
                    state.percent,
                    usedText,
                    totalText,
                ),
            ) {
                Text(
                    text = "${state.percent}%",
                    style = FigureTextStyle,
                    color = colors.onSurface.oe3Color(),
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            Spacer(Modifier.width(Oe3Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ui3_dashboard_storage_used, usedText),
                    style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                    color = colors.onSurface.oe3Color(),
                )
                Text(
                    text = stringResource(R.string.ui3_dashboard_storage_total, totalText),
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                )
                Spacer(Modifier.height(Oe3Spacing.sm))
                Oe3Button(
                    text = stringResource(R.string.ui3_dashboard_storage_settings),
                    onClick = onOpenSettings,
                    style = Oe3ButtonStyle.Text,
                )
            }
        }
    }
}

/** 指标组：两张紧凑卡 + 刷新说明，一起作为工作台的一个重排/隐藏单元。 */
@Composable
private fun MetricsGroup(state: DashboardUiState) {
    val colors = Oe3ThemeValues.colors
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.gridGap)) {
            MetricCard(
                title = stringResource(R.string.ui3_dashboard_metric_cpu),
                value = if (state.cpu.enabled) "${state.cpu.instanceCores}" else {
                    stringResource(R.string.ui3_dashboard_metric_placeholder)
                },
                caption = if (state.cpu.enabled) {
                    stringResource(R.string.ui3_dashboard_metric_cpu_caption, state.cpu.hostCores)
                } else {
                    stringResource(R.string.ui3_dashboard_metric_placeholder_caption)
                },
                fraction = state.cpu.fraction,
                barColor = colors.primary.oe3Color(),
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = stringResource(R.string.ui3_dashboard_metric_ram),
                value = if (state.ram.enabled) formatGigabytes(state.ram.usedBytes) else {
                    stringResource(R.string.ui3_dashboard_metric_placeholder)
                },
                caption = if (state.ram.enabled) {
                    stringResource(
                        R.string.ui3_dashboard_metric_ram_caption,
                        formatGigabytes(state.ram.totalBytes),
                    )
                } else {
                    stringResource(R.string.ui3_dashboard_metric_placeholder_caption)
                },
                fraction = state.ram.fraction,
                barColor = colors.accent.oe3Color(),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Oe3Spacing.xs))
        Text(
            text = stringResource(R.string.ui3_dashboard_metric_refresh, DashboardSpec.refreshSeconds),
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
        )
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    caption: String,
    fraction: Float,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = modifier,
        contentPadding = PaddingValues(Oe3Spacing.md),
        shape = oeCardShape(),
    ) {
        // UI-09：标题在卡内第一行（17/22 w600），主数值大号，辅助 caption。
        Text(
            text = title,
            style = oeCardTitleStyle(),
            color = colors.onSurface.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Oe3Spacing.xs))
        Text(
            text = value,
            style = FigureTextStyle,
            color = colors.onSurface.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Oe3Spacing.sm))
        UsageBar(fraction = fraction, barColor = barColor)
        Spacer(Modifier.height(Oe3Spacing.xs))
        Text(
            text = caption,
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UsageBar(fraction: Float, barColor: Color) {
    val colors = Oe3ThemeValues.colors
    val safeFraction = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DashboardSpec.metricBarHeight)
            .clip(Oe3Shapes.pill)
            .background(colors.surfaceHigh.oe3Color()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(safeFraction)
                .height(DashboardSpec.metricBarHeight)
                .clip(Oe3Shapes.pill)
                .background(barColor),
        )
    }
}

@Composable
private fun RecentCard(state: DashboardUiState, onOpenInstance: (String) -> Unit) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
        shape = oeCardShape(),
        // UI-04/UI-09：零内边距卡用 OeCardHeader 作卡内标题，16dp 与行对齐。
        title = {
            OeCardHeader(title = stringResource(R.string.ui3_dashboard_section_recent))
        },
    ) {
        if (state.recent.isEmpty()) {
            Text(
                text = stringResource(R.string.ui3_dashboard_recent_empty),
                style = Oe3Typography.style(Oe3TextRole.Body),
                color = colors.onSurfaceVariant.oe3Color(),
                modifier = Modifier.padding(
                    horizontal = Oe3Spacing.rowHorizontal,
                    vertical = Oe3Spacing.md,
                ),
            )
        } else {
            state.recent.forEachIndexed { index, item ->
                Oe3ListRow(
                    title = item.name,
                    subtitle = stringResource(item.status.labelRes),
                    leading = {
                        Oe3Icon(
                            image = Oe3DistroIcons.of(item.distroId),
                            contentDescription = null,
                            tint = Oe3IconTint.of(selected = false),
                        )
                    },
                    onClick = { onOpenInstance(item.id) },
                    showDivider = index < state.recent.lastIndex,
                )
            }
        }
    }
}

/** 发行版单色标记，放在卡片首列的 48dp well 内。 */
@Composable
private fun DistroMark(distroId: String) {
    val colors = Oe3ThemeValues.colors
    Box(
        modifier = Modifier
            .size(Oe3Sizes.minTouchTarget)
            .clip(RoundedCornerShape(Oe3Shapes.md))
            .background(colors.surfaceRaised.oe3Color()),
        contentAlignment = Alignment.Center,
    ) {
        Oe3Icon(
            image = Oe3DistroIcons.of(distroId),
            contentDescription = null,
            tint = Oe3IconTint.of(selected = false),
        )
    }
}

/**
 * 状态 / 引擎 chip。文字永远表达语义，颜色只是辅助；不设点击，所以不占
 * 48dp 命中区，视觉高度 28dp。
 */
@Composable
private fun DashboardPill(label: String, tone: DashboardStatusTone) {
    val colors = Oe3ThemeValues.colors
    val (fill, ink) = when (tone) {
        DashboardStatusTone.Active -> colors.primaryContainer to colors.primary
        DashboardStatusTone.Busy -> colors.surfaceHigh to colors.accent
        DashboardStatusTone.Idle -> colors.surfaceHigh to colors.onSurfaceVariant
        DashboardStatusTone.Error -> {
            ThemeColors.blend(colors.surface, colors.error, 0.18f) to colors.error
        }
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Oe3Shapes.sm))
            .background(fill.oe3Color())
            .heightIn(min = 28.dp)
            .padding(horizontal = 10.dp, vertical = Oe3Spacing.xxs)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(ink.oe3Color()),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = ink.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
