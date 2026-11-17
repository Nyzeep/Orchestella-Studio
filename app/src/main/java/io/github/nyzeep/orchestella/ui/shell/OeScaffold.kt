package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

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
 * 三个常量/函数都没有 Compose 依赖，透明度和折叠数量可以脱离设备断言。
 */
object OeScaffoldSpec {

    /** 顶栏可见槽位上限：超出后最后一个槽位换成溢出菜单。 */
    const val MAX_VISIBLE_ACTIONS = 3

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
}

/**
 * 统一页面壳：**单标题栏 + 右上动作槽**，页面内容在栏下滚动。
 *
 * 这是 v4 对 UI3 顶栏的替代（spec §0.1）：栏里只放当前页功能名，
 * 禁止拼接产品名；页内 30sp `PageTitle` 因此删除，全屏只留一个标题。
 *
 * 滚动联动走嵌套滚动，而不是让页面把 `ScrollState`/`LazyListState` 传上来：
 * 竖向 `verticalScroll`、`LazyColumn`、`LazyVerticalGrid` 与
 * `PullToRefreshBox` 都会把位移派发到本组件，页面无需知道自己被壳包住。
 *
 * 栏背景 alpha 0→1 的阈值是 [Oe3Spacing.sm]（12dp，spec §1.2 "位移 12dp
 * 起算"），标题不折叠、不缩放 —— 只有底色渐变。
 */
@Composable
fun OeScaffold(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: List<OeScaffoldAction> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val collapsePx = with(LocalDensity.current) { Oe3Spacing.sm.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    val scrollConnection = remember(collapsePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                offset = OeScaffoldSpec.advance(offset, available.y, collapsePx)
                return Offset.Zero
            }
        }
    }
    val alpha = OeScaffoldSpec.barAlpha(offset, collapsePx)

    Column(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollConnection),
    ) {
        OeScaffoldBar(
            title = title,
            alpha = alpha,
            leading = leading,
            actions = actions,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            content = content,
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
            .statusBarsPadding()
            .height(Oe3Sizes.topBar)
            .background(Color(colors.surface).copy(alpha = alpha))
            .padding(start = Oe3Spacing.md, end = Oe3Spacing.pageInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier.size(Oe3Sizes.interactiveVisual),
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
            modifier = Modifier.weight(1f),
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
