package io.github.nyzeep.orchestella.ui.shell

import android.content.res.Configuration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3GlassChrome
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3GlassEnabled
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3ShellGlassState
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassChromeState
import io.github.nyzeep.orchestella.ui.oe3.glass.oe3GlassSource
import io.github.nyzeep.orchestella.ui.oe3.glass.rememberOe3GlassState
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.shell.nav.Oe3NavBar
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog

/**
 * Oe3 application root: background canvas, page pager and bottom bar.
 *
 * The Activity owns the selected tab (via [Oe3ShellController]) so rotation and
 * the back dispatcher can drive the same state; this composable stays stateless.
 *
 * UI3-12 preference wiring (Theme Studio → shell): the Activity reads the
 * persisted presentation options from ThemeStore and passes them in here —
 * [pureBlack], [glassLevel], [navStyle] and [monoBody] — so this root keeps no
 * preference of its own. [glassEnabled] is the runtime kill switch UI3-10's
 * surface documents: reduced dynamics (animators disabled) or system power
 * saving route every non-None level to the scrimmed fallback surface.
 *
 * v4 (UI-02) adds the piece that made glass impossible before: the root no
 * longer paints `colors.background`. It stacks [OeAppBackground] at the bottom,
 * the pager above it, and every page keeps its content transparent — so the
 * nav bar (and UI-06's panels) have something to sample. [cornerRadiusDp] is
 * now forwarded into [Oe3Theme] instead of being silently dropped, and
 * [navStyle] reaches [Oe3Pager], which turns it into the page bottom reserve.
 *
 * v4 (UI-03) wraps the pager in [OeBackStackHost]: pushed secondary pages
 * ([OeDestination]) overlay the pager while the system back (and the
 * predictive-back gesture, via the dispatcher) pops them. [backStack] lets a
 * host supply its own stack (Wave 4 hoists it into `Oe3ShellController` for
 * restoration); `null` keeps a shell-owned stack. Secondary pages obtain the
 * handle through [LocalOeBackStack]; tapping a dock tab clears the stack.
 */
@Composable
fun Oe3App(
    selectedTab: Oe3Tab,
    onTabSelected: (Oe3Tab) -> Unit,
    theme: AppTheme,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
    reduceMotion: Boolean = false,
    pureBlack: Boolean = false,
    glassLevel: Oe3GlassLevel = Oe3GlassLevel.None,
    glassEnabled: Boolean = true,
    navStyle: Oe3NavStyle = Oe3NavStyle.Floating,
    monoBody: Boolean = false,
    cornerRadiusDp: Int? = null,
    surfaceAlpha: Float = 1f,
    backgroundUri: String? = null,
    backStack: OeBackStack? = null,
    pages: Oe3Pages? = null,
) {
    Oe3Theme(
        theme = theme,
        pureBlack = pureBlack,
        glassLevel = glassLevel,
        textScale = textScale,
        reduceMotion = reduceMotion,
        cornerRadiusDp = cornerRadiusDp,
        monoBody = monoBody,
    ) {
        // UI3-10 玻璃接线：内容区是唯一的 haze 源（W2-05 所有权仲裁：底栏是
        // 第一类玻璃所有者，顶栏滚动边缘是同一源的次级消费者，见
        // LocalOe3ShellGlassState）。源标记仅在玻璃等级非 None 时挂载：None 级
        // 在结构上就是基线组件树（不产生任何 haze 节点），像素与既有壳层一致。
        val glassChrome = remember { Oe3GlassChromeState() }
        val glassState = rememberOe3GlassState()
        val glassSourceModifier =
            if (Oe3ThemeValues.info.glassLevel != Oe3GlassLevel.None) {
                Modifier.oe3GlassSource(glassState)
            } else {
                Modifier
            }
        // UI-03 栈式导航：宿主 Activity 可显式提供栈（ Wave 4 由 Oe3ShellController
        // 提升并做恢复）；缺省时壳层自持一份。二级页面覆盖在 pager 之上、
        // 背景层之上、坞之下——背景不随栈转场缩放，坞持续采样玻璃源。
        val stack = backStack ?: rememberOeBackStack()
        val backgroundMode = OeAppBackgroundSpec.resolve(backgroundUri, Oe3ThemeValues.info.pureBlack)
        var reselectCounts by rememberSaveable { mutableStateOf(LongArray(Oe3Tab.entries.size)) }
        val wrappedPages = remember(pages) {
            pages?.let { raw ->
                val sequenceFor: (Oe3Tab) -> Long = { tab ->
                    reselectCounts.getOrElse(tab.ordinal) { 0L }
                }
                Oe3Pages(
                    workstation = wrapReselectSlot(Oe3Tab.Workstation, raw.workstation, sequenceFor),
                    instances = wrapReselectSlot(Oe3Tab.Instances, raw.instances, sequenceFor),
                    hardware = wrapReselectSlot(Oe3Tab.Hardware, raw.hardware, sequenceFor),
                    distros = wrapReselectSlot(Oe3Tab.Distros, raw.distros, sequenceFor),
                    config = wrapReselectSlot(Oe3Tab.Config, raw.config, sequenceFor),
                )
            }
        }
        CompositionLocalProvider(
            LocalOe3GlassChrome provides glassChrome,
            LocalOe3GlassEnabled provides glassEnabled,
            // shell 唯一 haze 源的共享句柄：底栏与顶栏滚动边缘效果都从这里采样，
            // 全屏单一采样源（W2-05）。
            LocalOe3ShellGlassState provides glassState,
            LocalOeSurfaceAlpha provides surfaceAlpha,
            LocalOeBackStack provides stack,
            // 页面底片复用同一份背景模式：转场时进入页先盖住下层，静止时与
            // 根背景逐像素重合（见 OePagePlate）。
            LocalOeShellBackgroundMode provides backgroundMode,
        ) {
            val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            Box(modifier.fillMaxSize()) {
                // 背景层 + 页面内容一起作为 haze 源：底栏模糊的是"壁纸 + 卡片"，
                // 而不是旧壳的纯色平面。
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(glassSourceModifier),
                ) {
                    OeAppBackground(mode = backgroundMode)
                    OeBackStackHost(
                        backStack = stack,
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(horizontalInsets),
                    ) {
                        Oe3Pager(
                            selected = selectedTab,
                            modifier = Modifier.fillMaxSize(),
                            navStyle = navStyle,
                            pages = wrappedPages,
                        )
                    }
                }
                // 二级页面压栈时点坞 tab：先清栈再切页，栈语义不跨 tab 拖尾。
                Oe3NavBar(
                    selected = selectedTab,
                    onSelect = { tab ->
                        stack.popAll()
                        onTabSelected(tab)
                    },
                    style = navStyle,
                    modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(horizontalInsets),
                    // W2-05 所有权仲裁：底栏无条件持有实时玻璃预算。顶栏滚动边缘
                    // 只是共享源的次级消费者（见 Oe3Backdrop.kt），不再降档底栏——
                    // 滚动 1px 就把底栏从 L1/L2 打回 96% 回退面的 R-02 缺陷链就此切断。
                    glassState = glassState,
                    onTabReselected = { tab ->
                        val next = LongArray(maxOf(reselectCounts.size, Oe3Tab.entries.size)) { i ->
                            reselectCounts.getOrElse(i) { 0L }
                        }
                        next[tab.ordinal] += 1L
                        reselectCounts = next
                    },
                )
            }
        }
    }
}

private fun wrapReselectSlot(
    tab: Oe3Tab,
    slot: (@Composable () -> Unit)?,
    sequenceFor: (Oe3Tab) -> Long,
): (@Composable () -> Unit)? = slot?.let { content ->
    {
        CompositionLocalProvider(LocalOe3TabReselectSequence provides sequenceFor(tab)) {
            content()
        }
    }
}

/**
 * 当前页签槽位收到的单调复点序号（W2-06 / Cashew 模式净室转译）：
 * 由宿主在 [Oe3Pages] 槽位边界按页签独立注入，页面无需知晓自身所在页签或路由根。
 */
internal val LocalOe3TabReselectSequence = compositionLocalOf { 0L }

/**
 * 监听当前页签槽位最新一次复点序号并执行 [onReselect]。
 * 通过 `rememberSaveable` 持久记录已消费的最大序号，保证切离其他页签再切回时不误触发，
 * 且二级页清栈回到本页时能够消费当次复点。
 */
@Composable
internal fun Oe3TabReselectEffect(
    onReselect: suspend () -> Unit,
) {
    val sequence = LocalOe3TabReselectSequence.current
    val currentOnReselect by rememberUpdatedState(onReselect)
    var lastHandledSeq by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(sequence) {
        if (sequence > lastHandledSeq) {
            lastHandledSeq = sequence
            currentOnReselect()
        }
    }
}

@Composable
private fun Oe3ScrollToTopOnTabReselect(
    needsScroll: () -> Boolean,
    snapToTop: suspend () -> Unit,
    animateToTop: suspend () -> Unit,
) {
    val reducedMotion by rememberUpdatedState(Oe3ThemeValues.info.reducedMotion)
    Oe3TabReselectEffect {
        if (needsScroll()) {
            if (reducedMotion) snapToTop() else animateToTop()
        }
    }
}

/** 为使用 [LazyListState] 的主壳页面接线复点回顶（标准档平滑回顶，减动效直跳顶部）。 */
@Composable
internal fun Oe3ScrollToTopOnTabReselect(listState: LazyListState) {
    Oe3ScrollToTopOnTabReselect(
        needsScroll = { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 },
        snapToTop = { listState.scrollToItem(0) },
        animateToTop = { listState.animateScrollToItem(0) },
    )
}

/** 为使用 [LazyGridState] 的主壳页面接线复点回顶（标准档平滑回顶，减动效直跳顶部）。 */
@Composable
internal fun Oe3ScrollToTopOnTabReselect(gridState: LazyGridState) {
    Oe3ScrollToTopOnTabReselect(
        needsScroll = { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 },
        snapToTop = { gridState.scrollToItem(0) },
        animateToTop = { gridState.animateScrollToItem(0) },
    )
}

/** 为使用 [ScrollState] 的主壳页面接线复点回顶（标准档平滑回顶，减动效直跳顶部）。 */
@Composable
internal fun Oe3ScrollToTopOnTabReselect(scrollState: ScrollState) {
    Oe3ScrollToTopOnTabReselect(
        needsScroll = { scrollState.value > 0 },
        snapToTop = { scrollState.scrollTo(0) },
        animateToTop = { scrollState.animateScrollTo(0) },
    )
}

@Preview(
    name = "Oe3 shell · dark 1.0x",
    showBackground = true,
    backgroundColor = 0xFF0A0B0E,
    widthDp = 360,
    heightDp = 780,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun Oe3AppPreview() {
    Oe3App(
        selectedTab = Oe3Tab.Workstation,
        onTabSelected = {},
        theme = ThemeCatalog.resolveForMode("constructivist-workstation", isDark = true),
    )
}

@Preview(
    name = "Oe3 shell · dark 1.3x",
    showBackground = true,
    backgroundColor = 0xFF0A0B0E,
    widthDp = 360,
    heightDp = 780,
    fontScale = 1.3f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun Oe3AppPreviewLargeText() {
    Oe3App(
        selectedTab = Oe3Tab.Distros,
        onTabSelected = {},
        theme = ThemeCatalog.resolveForMode("constructivist-workstation", isDark = true),
    )
}
