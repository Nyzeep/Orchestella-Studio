package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.OeTextScaleMode
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v4（UI-10）后的 Composable 契约：根页是入口列表，四个子页各自独立。
 * 断言的**覆盖意图不变**——入口可达、当前值可读、滑块语义可读、模式禁用
 * 规则、预设可选中、终端预览可输入——只是位置从单页改到新 IA 的落点。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h9000dp-mdpi")
class ThemeStudioScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun fixture(
        theme: AppTheme = ThemeCatalog.constructivistWorkstation,
        pureBlack: Boolean = false,
        textScalePercent: Int = 100,
    ) = ThemeStudioState(
        theme = theme,
        mode = when {
            theme.isFixed -> ThemeStore.Mode.FIXED
            theme.isDark -> ThemeStore.Mode.DARK
            else -> ThemeStore.Mode.LIGHT
        },
        pureBlack = pureBlack,
        cornerDp = 12,
        textScaleMode = if (textScalePercent == 100) OeTextScaleMode.FollowSystem else OeTextScaleMode.Custom,
        textScalePercent = textScalePercent,
        surfaceOpacityPercent = 80,
        terminalFontSizeSp = 14,
    )

    private fun setRoot(
        state: ThemeStudioState = fixture(),
        events: MutableList<ThemeStudioEvent>? = null,
        onBack: (() -> Unit)? = null,
    ) {
        compose.setContent {
            Oe3Theme(theme = state.theme, reduceMotion = true) {
                ThemeStudioScreen(
                    state = state,
                    onEvent = { events?.add(it) },
                    onBack = onBack,
                )
            }
        }
    }

    @Test
    fun rootRendersTheSevenEntryPointsInSpecOrder() {
        setRoot()
        listOf(
            "theme-entry:presets",
            "theme-entry:mode",
            "theme-entry:accent",
            "theme-entry:corner",
            "theme-entry:text-scale",
            "theme-entry:background",
            "theme-entry:terminal",
            "theme-section:more",
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertExists()
        }
    }

    @Test
    fun rootEntryPointsLeadToTheirOwnPages() {
        var presets = 0
        var accent = 0
        var background = 0
        var terminal = 0
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                ThemeStudioScreen(
                    state = fixture(),
                    onEvent = {},
                    onOpenPresets = { presets++ },
                    onOpenAccent = { accent++ },
                    onOpenBackground = { background++ },
                    onOpenTerminal = { terminal++ },
                )
            }
        }
        compose.onNodeWithTag("theme-entry:presets").performClick()
        compose.onNodeWithTag("theme-entry:accent").performClick()
        compose.onNodeWithTag("theme-entry:background").performClick()
        compose.onNodeWithTag("theme-entry:terminal").performClick()
        assertEquals(1, presets)
        assertEquals(1, accent)
        assertEquals(1, background)
        assertEquals(1, terminal)
    }

    @Test
    fun backButtonIsVisibleClickableAndDescribed() {
        var backInvoked = 0
        setRoot(onBack = { backInvoked++ })
        val back = compose.onNodeWithTag("theme-studio:back")
        back.assertContentDescriptionEquals("返回")
        back.performClick()
        assertEquals(1, backInvoked)
    }

    @Test
    fun backButtonIsAbsentWithoutOnBack() {
        setRoot()
        compose.onNodeWithTag("theme-studio:back").assertDoesNotExist()
    }

    @Test
    fun optionRowsMeetTheFortyEightDpFloor() {
        setRoot(fixture(pureBlack = false))
        val floor = with(compose.density) { 48.dp.toPx() }
        listOf(
            "theme-row:mode:system",
            "theme-row:mode:light",
            "theme-row:mode:dark",
            // 「更多」里的既有能力仍走同一个 48dp 行原语。
            "theme-row:nav:floating",
            "theme-row:glass:standard",
            "theme-row:font:system",
        ).forEach { tag ->
            val height = compose.onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.height
            assertTrue("$tag is only $height px tall", height >= floor)
        }
    }

    @Test
    fun currentValuesAreReadableAtTheirEntries() {
        setRoot()
        // push 行整行合并语义，读数节点要从未合并树取。
        compose.onNodeWithTag("theme-value:presets", useUnmergedTree = true).assertTextEquals("42 套")
        compose.onNodeWithTag("theme-value:mode").assertTextEquals("深色")
        compose.onNodeWithTag("theme-value:accent", useUnmergedTree = true).assertTextEquals("跟随主题")
        compose.onNodeWithTag("theme-value:corner").assertTextEquals("12 dp")
        compose.onNodeWithTag("theme-value:text-scale").assertTextEquals("跟随系统")
        compose.onNodeWithTag("theme-value:background", useUnmergedTree = true)
            .assertTextEquals("表面不透明度 80%")
        compose.onNodeWithTag("theme-value:terminal", useUnmergedTree = true).assertTextEquals("14 sp")
    }

    @Test
    fun slidersExposeAReadableStateDescription() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                ThemeStudioScreen(
                    state = fixture(pureBlack = true, textScalePercent = 130),
                    onEvent = {},
                )
            }
        }
        compose.onNodeWithTag("theme-slider:text-scale")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "应用内字号 130%"))
    }

    @Test
    fun backgroundPageKeepsWallpaperAndOpacityControls() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                ThemeBackgroundScreen(
                    state = fixture(pureBlack = true),
                    onEvent = {},
                    onBack = {},
                    onPickBackground = {},
                )
            }
        }
        compose.onNodeWithTag("theme-slider:surface-opacity")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "表面不透明度 80%"))
        compose.onNodeWithTag("theme-wallpaper:pick").assertExists()
        compose.onNodeWithTag("theme-wallpaper:clear").assertExists()
        compose.onNodeWithTag("theme-row:pure-black:pure-black").assertIsSelected()
    }

    @Test
    fun presetPageShowsExactlyOneCardPerFamily() {
        val state = fixture(theme = ThemeCatalog.github)
        compose.setContent {
            Oe3Theme(theme = state.theme, reduceMotion = true) {
                ThemePresetScreen(state = state, onEvent = {}, onBack = {})
            }
        }
        ThemeCatalog.presets.map { it.familyId }.distinct().forEach { family ->
            compose.onNodeWithTag("theme-preset:$family").assertExists()
        }
        // 同一 family 的另一半不得并列出现（v4 §2.2：一卡一套）。
        compose.onNodeWithTag("theme-preset:github-light").assertDoesNotExist()
        compose.onNodeWithTag("theme-preset:github").assertIsSelected()
    }

    @Test
    fun presetPageFollowsTheCurrentModeWithoutReordering() {
        val lightState = fixture(theme = ThemeCatalog.githubLight)
        compose.setContent {
            Oe3Theme(theme = lightState.theme, reduceMotion = true) {
                ThemePresetScreen(state = lightState, onEvent = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("theme-preset:github").assertIsSelected()
        // 浅色模式下卡片仍是 github family（配色由纯函数换成 light 那套）。
        assertEquals(
            "github-light",
            ThemePresetGrouping.groups(ThemeCatalog.presets, isDark = false)
                .first { it.familyId == "github" }.theme.id,
        )
    }

    @Test
    fun pickingAPresetEmitsTheDisplayedThemeId() {
        val events = mutableListOf<ThemeStudioEvent>()
        val state = fixture()
        compose.setContent {
            Oe3Theme(theme = state.theme, reduceMotion = true) {
                ThemePresetScreen(
                    state = state,
                    onEvent = { events += it },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("theme-preset:github").performScrollTo().performClick()
        assertEquals(ThemeStudioEvent.SelectTheme("github"), events.last())
    }

    @Test
    fun cornerAndTextScaleChipsEmitTheirEvents() {
        val events = mutableListOf<ThemeStudioEvent>()
        setRoot(fixture(), events)
        compose.onNodeWithTag("theme-corner:24").performScrollTo().performClick()
        assertEquals(ThemeStudioEvent.SetCornerDp(24), events.last())
        compose.onNodeWithTag("theme-row:text-scale:custom").performScrollTo().performClick()
        assertEquals(ThemeStudioEvent.SetTextScaleMode(OeTextScaleMode.Custom), events.last())
    }

    /**
     * UI-14 撤掉了 shipped fixed palette，禁用行为用合成固定主题钉住：
     * 目录里没有 FIXED 行，但屏幕仍要尊重该标志。
     */
    @Test
    fun fixedThemeDisablesModeSelection() {
        setRoot(fixture(theme = ThemeCatalog.mascotLight.copy(isFixed = true)))
        compose.onNodeWithTag("theme-row:mode:light").assertIsNotEnabled()
        compose.onNodeWithTag("theme-row:mode:dark").assertIsNotEnabled()
        compose.onNodeWithTag("theme-row:mode:system").assertIsNotEnabled()
    }

    @Test
    fun importedFontRowIsDisabledUntilFontImportShips() {
        setRoot()
        compose.onNodeWithTag("theme-row:font:imported")
            .performScrollTo()
            .assertIsNotEnabled()
            .assertTextContains("暂不支持导入字体")
        compose.onNodeWithTag("theme-row:font:system")
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun terminalPageKeepsTheLivePreviewAndImeInput() {
        val state = fixture()
        compose.setContent {
            Oe3Theme(theme = state.theme, reduceMotion = true) {
                ThemeTerminalScreen(
                    state = state,
                    onEvent = {},
                    onBack = {},
                    renderTerminalView = false,
                )
            }
        }
        compose.onNodeWithTag("theme-slider:terminal-font-size")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "终端字号 14 sp"))
        val input = compose.onNodeWithTag("theme-terminal:input")
        input.performScrollTo()
        input.performTextInput("help")
        input.performImeAction()
        compose.onNodeWithTag("theme-terminal:preview").assertExists()
    }

    @Test
    fun accentPageKeepsEightSwatchesAndCustomSlots() {
        val state = fixture()
        compose.setContent {
            Oe3Theme(theme = state.theme, reduceMotion = true) {
                ThemeAccentScreen(state = state, onEvent = {}, onBack = {})
            }
        }
        ThemeStudioSpec.accentSwatches.forEach { argb ->
            compose.onNodeWithTag("theme-accent:${hex(argb)}").assertExists()
        }
        compose.onNodeWithTag("theme-accent:follow").assertIsSelected()
        compose.onNodeWithTag("theme-slot:0").assertExists()
        compose.onNodeWithTag("theme-slot:1").assertExists()
        compose.onNodeWithTag("theme-slot:2").assertExists()
    }
}
