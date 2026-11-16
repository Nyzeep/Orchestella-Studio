package io.github.nyzeep.orchestella.ui.shell

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3GlassEnabled
import io.github.nyzeep.orchestella.ui.oe3.glass.oe3GlassSource
import io.github.nyzeep.orchestella.ui.oe3.glass.rememberOe3GlassState
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.shell.nav.Oe3NavBar
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog

/**
 * Oe3 application root: brand bar, fade pager and bottom bar.
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
    pages: Oe3Pages? = null,
) {
    Oe3Theme(
        theme = theme,
        pureBlack = pureBlack,
        glassLevel = glassLevel,
        textScale = textScale,
        reduceMotion = reduceMotion,
        monoBody = monoBody,
    ) {
        val colors = Oe3ThemeValues.colors
        // UI3-10 玻璃接线：内容区是唯一的 haze 源，底栏消费同一个 state。
        // 源标记仅在玻璃等级非 None 时挂载：None 级在结构上就是基线组件树
        // （不产生任何 haze 节点），像素与既有壳层完全一致。
        val glassState = rememberOe3GlassState()
        val glassSourceModifier =
            if (Oe3ThemeValues.info.glassLevel != Oe3GlassLevel.None) {
                Modifier.oe3GlassSource(glassState)
            } else {
                Modifier
            }
        CompositionLocalProvider(LocalOe3GlassEnabled provides glassEnabled) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color(colors.background))
                    // 横屏/挖孔：只让左右安全区推开内容，顶部与底部仍由各自的
                    // statusBarsPadding / navBar token 处理，避免重复叠加。
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(glassSourceModifier),
                ) {
                    Oe3TopBar()
                    Oe3Pager(
                        selected = selectedTab,
                        modifier = Modifier.weight(1f),
                    )
                }
                Oe3NavBar(
                    selected = selectedTab,
                    onSelect = onTabSelected,
                    style = navStyle,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    glassState = glassState,
                )
            }
        }
    }
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
