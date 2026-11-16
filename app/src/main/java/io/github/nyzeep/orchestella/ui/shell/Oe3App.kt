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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog

/**
 * Oe3 application root: brand bar, fade pager and floating capsule bar.
 *
 * The Activity owns the selected tab (via [Oe3ShellController]) so rotation and
 * the back dispatcher can drive the same state; this composable stays stateless.
 */
@Composable
fun Oe3App(
    selectedTab: Oe3Tab,
    onTabSelected: (Oe3Tab) -> Unit,
    theme: AppTheme,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
    reduceMotion: Boolean = false,
) {
    Oe3Theme(
        theme = theme,
        textScale = textScale,
        reduceMotion = reduceMotion,
    ) {
        val colors = Oe3ThemeValues.colors
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(colors.background))
                // 横屏/挖孔：只让左右安全区推开内容，顶部与底部仍由各自的
                // statusBarsPadding / navBar token 处理，避免重复叠加。
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            Column(Modifier.fillMaxSize()) {
                Oe3TopBar()
                Oe3Pager(
                    selected = selectedTab,
                    modifier = Modifier.weight(1f),
                )
            }
            Oe3NavBar(
                selected = selectedTab,
                onSelect = onTabSelected,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
