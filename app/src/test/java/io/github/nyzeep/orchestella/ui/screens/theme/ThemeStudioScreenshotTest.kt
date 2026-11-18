package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v4（UI-10）后的主题工坊金样：根页 + 四个独立子页。
 *
 * 旧单页 12 分区长列表已不存在，旧 `theme-studio-<mode>-<scale>.png` 一并
 * 退役；这里按新 IA 每页各录一张（亮/暗只覆盖最需要的两页），命名仍以
 * `theme-studio-` 开头，便于与 UI-10 之前的基线区分。
 *
 * 页面本体透明（v4 壳层背景层），golden 用同样的壳层底色补底并整窗捕获。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h2400dp-mdpi")
class ThemeStudioScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rootDark() = capture("root-dark", ThemeCatalog.constructivistWorkstation, "theme-studio:screen") {
        ThemeStudioScreen(state = state(ThemeCatalog.constructivistWorkstation), onEvent = {})
    }

    @Test
    fun rootLight() = capture("root-light", ThemeCatalog.constructivistWorkstationLight, "theme-studio:screen") {
        ThemeStudioScreen(state = state(ThemeCatalog.constructivistWorkstationLight), onEvent = {})
    }

    @Test
    fun presetsDark() = capture("presets-dark", ThemeCatalog.constructivistWorkstation, "theme-presets:screen") {
        ThemePresetScreen(
            state = state(ThemeCatalog.constructivistWorkstation),
            onEvent = {},
            onBack = {},
        )
    }

    @Test
    fun presetsLight() = capture("presets-light", ThemeCatalog.constructivistWorkstationLight, "theme-presets:screen") {
        ThemePresetScreen(
            state = state(ThemeCatalog.constructivistWorkstationLight),
            onEvent = {},
            onBack = {},
        )
    }

    @Test
    fun accentDark() = capture("accent-dark", ThemeCatalog.constructivistWorkstation, "theme-accent:screen") {
        ThemeAccentScreen(
            state = state(ThemeCatalog.constructivistWorkstation),
            onEvent = {},
            onBack = {},
        )
    }

    @Test
    fun backgroundDark() = capture("background-dark", ThemeCatalog.constructivistWorkstation, "theme-background:screen") {
        ThemeBackgroundScreen(
            state = state(ThemeCatalog.constructivistWorkstation),
            onEvent = {},
            onBack = {},
            onPickBackground = {},
        )
    }

    @Test
    fun terminalDark() = capture("terminal-dark", ThemeCatalog.constructivistWorkstation, "theme-terminal:screen") {
        ThemeTerminalScreen(
            state = state(ThemeCatalog.constructivistWorkstation),
            onEvent = {},
            onBack = {},
            renderTerminalView = false,
            onPickTerminalBackground = {},
        )
    }

    private fun capture(
        name: String,
        theme: AppTheme,
        tag: String,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        compose.setContent {
            Oe3Theme(theme = theme, reduceMotion = true) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(Oe3ThemeValues.colors.background)),
                ) {
                    content()
                }
            }
        }
        compose.onNodeWithTag(tag).assertExists()
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/oe3/screens/theme-studio-$name.png",
        )
    }

    private fun state(theme: AppTheme) = ThemeStudioState(
        theme = theme,
        mode = if (theme.isDark) {
            io.github.nyzeep.orchestella.ui.theme.ThemeStore.Mode.DARK
        } else {
            io.github.nyzeep.orchestella.ui.theme.ThemeStore.Mode.LIGHT
        },
        cornerDp = 12,
        surfaceOpacityPercent = 100,
        terminalFontSizeSp = 14,
    )
}

/** Two terminal palette goldens: the Compose sketch, not the AndroidView. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h480dp-mdpi")
class TerminalPreviewScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun darkPaletteSketch() = capture("dark-100", ThemeCatalog.constructivistWorkstation)

    @Test
    fun lightPaletteSketch() = capture("light-100", ThemeCatalog.constructivistWorkstationLight)

    /** UI-14: both mascot ANSI sets on their own purple-black terminal fields. */
    @Test
    fun mascotLightPaletteSketch() = capture("mascot-light-100", ThemeCatalog.mascotLight)

    @Test
    fun mascotDarkPaletteSketch() = capture("mascot-dark-100", ThemeCatalog.mascotDark)

    private fun capture(name: String, theme: AppTheme) {
        compose.setContent { TerminalPreviewScreenshotBoard(theme) }
        compose.onNodeWithTag("terminal-board").captureRoboImage(
            "src/test/screenshots/oe3/screens/theme-terminal-$name.png",
        )
    }
}
