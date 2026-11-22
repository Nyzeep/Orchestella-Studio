package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.ui.oe3.glass.rememberOe3ScrollEdgeGlass
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageTopPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import kotlin.math.roundToInt

/**
 * 主壳卡片圆角：Theme Studio 的圆角偏好（0..24，已在 Oe3Theme 夹紧）优先，
 * 未注入时回落到规范阶梯的 `xl`（24dp）。页面把返回值传给 `Oe3Card(shape = …)`，
 * 这样「圆角」偏好改了，主壳卡片半径真的跟着变（UI-02 红线）。
 */
@Composable
fun oeCardShape(): Shape = RoundedCornerShape(LocalOe3CornerRadius.current ?: OeShape.xl)

/**
 * 页面级右上动作。顶栏只负责排版与折叠，动作语义由页面给（spec §2.4）。
 *
 * [testTag] 是给页面既有语义用例留的锚点：动作从页内搬到顶栏后，
 * `instances-create` 这类标签不能改名。
 */
@Immutable
data class OeScaffoldAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val testTag: String? = null,
)

/**
 * 顶栏动作槽与滚动联动的纯规则（spec §1.2）。
 *
 * 常量/函数都没有 Compose 依赖，透明度、折叠数量与折叠滞回可以脱离设备断言。
 */
object OeScaffoldSpec {

    /** 顶栏可见槽位上限：超出后最后一个槽位换成溢出菜单。 */
    const val MAX_VISIBLE_ACTIONS = 3

    /**
     * 折叠滞回进入阈值（dp，W2-05）：净滚动越过该距离才折叠顶栏滚动边缘玻璃。
     * 滚过首屏一小段（内容明显离开顶栏）才值得切换材质，避免滚动 1px 就触发。
     */
    const val ENTER_COLLAPSE_DP = 56

    /**
     * 折叠滞回退出阈值（dp，W2-05）：折叠后净滚动回到该距离之内才重新展开。
     * 必须不大于 [ENTER_COLLAPSE_DP]（调用侧 coerceIn 兜底）；两阈值之间的
     * 区间就是滞回带——带内状态保持不变。
     */
    const val EXIT_COLLAPSE_DP = 24

    /** 直接摆在栏里的动作数：溢出时给 More 留一格。 */
    fun directCount(total: Int): Int =
        if (total <= MAX_VISIBLE_ACTIONS) total else MAX_VISIBLE_ACTIONS - 1

    /** 收进溢出菜单的动作数。 */
    fun overflowCount(total: Int): Int = (total - directCount(total)).coerceAtLeast(0)

    /**
     * 栏背景透明度：位移 0 完全透明，[collapsePx] 处变为实心 surface。
     * 未测量到阈值时给不透明，避免除零后整栏隐身。
     */
    fun barAlpha(offsetPx: Float, collapsePx: Float): Float =
        if (collapsePx <= 0f) 1f else (offsetPx / collapsePx).coerceIn(0f, 1f)

    /**
     * 累积滚动位移：内容上滑（负 delta）把位移推高，回到顶部（正 delta）
     * 把它压回 0，两端都夹紧，所以栏不会越滚越不透明、也不会变成负值。
     */
    fun advance(offsetPx: Float, availablePx: Float, collapsePx: Float): Float =
        (offsetPx - availablePx).coerceIn(0f, collapsePx.coerceAtLeast(0f))

    /**
     * 顶栏折叠滞回（W2-05，净室重写）：展开态滚过 [enterPx] 才折叠；折叠态
     * 必须回到 [exitPx] 之内才展开。两次翻转之间必须跨越整个滞回带
     * （enterPx − exitPx），临界滚动位置上 1px 的抖动不会再把状态来回打翻。
     *
 * [offsetPx] 可能是 `oeScrollOffsetPx()` 滚过首项时的 `Int.MAX_VALUE`
 * （转 Float 仍大于任何 enterPx，自然折叠，无需特判）。[exitPx] 必须
 * ≤ [enterPx]，由调用侧 `coerceIn(0f, enterPx)` 保证；[exitPx] = 0 时
 * 折叠态只在回到顶（位移 0）才展开——单一阈值开关，不构成滞回带。
     */
    fun nextCollapsed(collapsed: Boolean, offsetPx: Float, enterPx: Float, exitPx: Float): Boolean =
        if (collapsed) offsetPx > exitPx else offsetPx >= enterPx
}

internal fun LazyListState.oeScrollOffsetPx(): Int =
    if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset else Int.MAX_VALUE

internal fun LazyGridState.oeScrollOffsetPx(): Int =
    if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset else Int.MAX_VALUE

internal fun ScrollState.oeScrollOffsetPx(): Int = value

/**
 * 顶栏折叠滞回状态（W2-05）：位移源 [offsetPx]（页面列表/滚动状态或内部
 * NestedScrollConnection 累积的 offset，由调用方桥接）经 [OeScaffoldSpec.nextCollapsed]
 * 驱动一个布尔状态，只在滞回真实翻转时变值。
 *
 * 为什么用滞回而不是连续阈值：顶栏滚动边缘效果以本状态为修饰符键——
 * - 连续 alpha>0 键会在临界滚动位置（约 12dp 折叠距离）随 1px 抖动反复
 *   增删修饰符、重建整个边缘子树（R-02 修饰符树抖动源）；
 * - 滞回键下修饰符树只在「滚过 56dp 折叠 / 回到 24dp 之内展开」两个相距
 *   32dp 的翻转点变化，临界位置抖动不触发重建。
 *
 * 不引入任何动画：attach/detach 在滞回翻转点瞬切即可（翻转点离静置位足够
 * 远，材质切换不构成动效）；顶栏 scrim 的视觉连续性由 [OeScaffoldBar] 既有的
 * 连续 alpha 承担，与本状态无关。
 */
@Composable
internal fun rememberOe3HeaderCollapse(offsetPx: () -> Int): State<Boolean> {
    val density = LocalDensity.current
    val enterPx = remember(density) { with(density) { OeScaffoldSpec.ENTER_COLLAPSE_DP.dp.toPx() } }
    // 退出阈值钳在进入阈值之内：滞回带宽度非负，翻转规则才有单侧语义。
    val exitPx = remember(density) {
        with(density) { OeScaffoldSpec.EXIT_COLLAPSE_DP.dp.toPx() }.coerceIn(0f, enterPx)
    }
    // 初值按静止位展开；snapshotFlow 首次发射自带当前值，恢复滚动位时自校正。
    val collapsed = remember {
        mutableStateOf(OeScaffoldSpec.nextCollapsed(false, 0f, enterPx, exitPx))
    }
    // 页面内联 lambda 每次重组都是新实例：effect 键固定，运行期总是调最新
    // lambda，收集管道不随重组取消重启。
    val currentOffsetPx by rememberUpdatedState(offsetPx)
    LaunchedEffect(enterPx, exitPx) {
        snapshotFlow { currentOffsetPx() }
            .collect { value ->
                val next = OeScaffoldSpec.nextCollapsed(
                    collapsed.value,
                    value.toFloat(),
                    enterPx,
                    exitPx,
                )
                if (next != collapsed.value) collapsed.value = next
            }
    }
    return collapsed
}

/**
 * 统一页面壳：**单标题栏 + 右上动作槽**，叠加在全出血滚动内容之上（R4 顶栏融合）。
 *
 * 标题直接悬浮在背景层之上；内容通过 [LocalOe3PageTopPadding] 预留初始顶部留白并从栏下滚过。
 * 传 [scrollOffsetPx] 时直接从列表/滚动状态同步位移，保证程序化 `scrollToItem(0)` 后立即归零。
 *
 * W2-05：顶栏滚动边缘玻璃由折叠滞回驱动（[OeScaffoldSpec.ENTER_COLLAPSE_DP]/
 * [OeScaffoldSpec.EXIT_COLLAPSE_DP]，见 [rememberOe3HeaderCollapse]），效果本身
 * 消费 shell 根的唯一 haze 源，不再自建 sourceModifier——底栏实时玻璃不因滚动降档。
 */
@Composable
fun OeScaffold(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: List<OeScaffoldAction> = emptyList(),
    scrollOffsetPx: (() -> Int)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val collapsePx = with(LocalDensity.current) { Oe3Spacing.sm.toPx() }
    var offset by rememberSaveable { mutableFloatStateOf(0f) }
    // W2-05（审查 P1-2）：滞回消费的是**未钳制**的净滚动距离。[advance] 的
    // collapsePx 上限只服务栏 alpha 的渐变刻度（12dp 处饱和）——若把钳制后的
    // offset 喂给 56dp 折叠阈值，无列表位移源的页面永远够不到折叠点，顶栏
    // 滚动边缘玻璃会被静默禁用。两个累积器在同一分支里同步维护：复位条件、
    // 消费口径完全一致，只有钳制差异。
    var scrollDistancePx by rememberSaveable { mutableFloatStateOf(0f) }
    val scrollConnection = remember(collapsePx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val atTop = available.y > 0f && consumed.y == 0f
                offset = if (atTop) {
                    0f
                } else {
                    OeScaffoldSpec.advance(offset, consumed.y, collapsePx)
                }
                scrollDistancePx = if (atTop) {
                    0f
                } else {
                    (scrollDistancePx - consumed.y).coerceAtLeast(0f)
                }
                return Offset.Zero
            }
        }
    }
    val alpha = OeScaffoldSpec.barAlpha(scrollOffsetPx?.invoke()?.toFloat() ?: offset, collapsePx)
    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + Oe3Sizes.topBar
    // 折叠滞回（W2-05）：页面自带位移源时直接消费；否则桥接到内部
    // NestedScrollConnection 累积的未钳制净滚动距离（同一 snapshotFlow
    // 收集管道，56dp/24dp 滞回阈值按同一物理语义工作）。
    val collapsed = rememberOe3HeaderCollapse(
        offsetPx = scrollOffsetPx ?: { scrollDistancePx.roundToInt() },
    ).value
    // 顶栏边缘效果以滞回折叠态为键（不再以连续 alpha>0 为键）：修饰符子树
    // 只在真实翻转时增删；静置（collapsed=false）无边缘效果，与旧实现滚动前
    // 像素一致。内容 Column 不再挂任何 haze 源——shell 根已是唯一源
    // （见 Oe3Backdrop.kt 所有权仲裁），R-02 的滚动 1px 即夺走底栏玻璃的
    // 缺陷链源头（独立 sourceModifier + hasScrollEdge 降档）就此拆除。
    val scrollEdgeEffect = rememberOe3ScrollEdgeGlass(active = collapsed)

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (scrollOffsetPx == null) Modifier.nestedScroll(scrollConnection) else Modifier),
    ) {
        CompositionLocalProvider(LocalOe3PageTopPadding provides topPadding) {
            Column(
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
        }
        OeScaffoldBar(
            title = title,
            alpha = alpha,
            leading = leading,
            actions = actions,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(scrollEdgeEffect ?: Modifier),
        )
    }
}

/**
 * 72dp 栏本体（不含滚动逻辑，便于截图与预览直接摆姿态）。
 *
 * 左 16dp 起标题（spec §1.2），右 20dp 收动作槽；leading 固定 44dp，
 * 只为配置页这类需要返回入口的页面准备。图标按钮视觉 44dp、命中 48dp。
 */
@Composable
fun OeScaffoldBar(
    title: String,
    alpha: Float,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: List<OeScaffoldAction> = emptyList(),
) {
    val colors = Oe3ThemeValues.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawWithCache {
                val featherPx = Oe3Spacing.sm.toPx()
                val fadeHeight = size.height + featherPx
                val base = Color(colors.background)
                val fade = Brush.verticalGradient(
                    0f to base.copy(alpha = 0.85f),
                    0.55f to base.copy(alpha = 0.45f),
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = fadeHeight,
                )
                onDrawBehind {
                    if (alpha > 0f) {
                        drawRect(brush = fade, alpha = alpha, size = Size(size.width, fadeHeight))
                    }
                }
            }
            .statusBarsPadding()
            .height(Oe3Sizes.topBar)
            .padding(start = Oe3Spacing.md, end = Oe3Spacing.pageInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(
                // 槽位必须是命中区下限 48dp（shape.md §3），44dp 只是返回字形的
                // 视觉尺寸——槽位钳 44 会把里面任何 48dp 原语一起压到 44
                //（Wave 4 code-review 抓到的真实命中区损失）。
                modifier = Modifier.size(Oe3Sizes.minTouchTarget),
                contentAlignment = Alignment.Center,
            ) {
                leading()
            }
            Spacer(Modifier.width(Oe3Spacing.xs))
        }
        Text(
            text = title,
            style = Oe3Typography.style(Oe3TextRole.Brand),
            color = Color(colors.onSurface),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        val direct = OeScaffoldSpec.directCount(actions.size)
        actions.take(direct).forEach { action ->
            Spacer(Modifier.width(Oe3Spacing.xs))
            OeScaffoldActionButton(action)
        }
        val overflow = actions.drop(direct)
        if (overflow.isNotEmpty()) {
            Spacer(Modifier.width(Oe3Spacing.xs))
            OeScaffoldOverflowButton(overflow)
        }
    }
}

@Composable
private fun OeScaffoldActionButton(action: OeScaffoldAction) {
    val colors = Oe3ThemeValues.colors
    OeScaffoldActionChrome(
        testTag = action.testTag,
        onClick = action.onClick,
        contentDescription = action.contentDescription,
    ) {
        Oe3Icon(
            image = action.icon,
            contentDescription = null,
            tint = Color(colors.onSurface),
        )
    }
}

@Composable
private fun OeScaffoldOverflowButton(overflow: List<OeScaffoldAction>) {
    val colors = Oe3ThemeValues.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        OeScaffoldActionChrome(
            testTag = "scaffold:overflow",
            onClick = { expanded = true },
            contentDescription = stringResource(R.string.ui3_scaffold_more_actions),
        ) {
            Oe3Icon(
                image = Oe3Icons.More,
                contentDescription = null,
                tint = Color(colors.onSurface),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            overflow.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = action.contentDescription,
                            style = Oe3Typography.style(Oe3TextRole.Body),
                            color = Color(colors.onSurface),
                        )
                    },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                    modifier = action.testTag?.let { Modifier.testTag(it) } ?: Modifier,
                )
            }
        }
    }
}

/** 视觉 44dp 的胶囊动作位；外层补到 48dp 命中区。 */
@Composable
private fun OeScaffoldActionChrome(
    testTag: String?,
    onClick: () -> Unit,
    contentDescription: String,
    icon: @Composable () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Box(
        modifier = Modifier
            .size(Oe3Sizes.minTouchTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .then(testTag?.let { Modifier.testTag(it) } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Oe3Sizes.interactiveVisual)
                .clip(CircleShape)
                .background(Color(colors.surfaceHigh)),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    }
}
