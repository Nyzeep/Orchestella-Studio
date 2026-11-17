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
import io.github.nyzeep.orchestella.ui.theme.OeTextScaleMode
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h9000dp-mdpi")
class ThemeStudioScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val testPresets = listOf(
        ThemeCatalog.constructivistWorkstation,
        ThemeCatalog.github,
        ThemeCatalog.mascotLavender,
    )

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

    @Test
    fun everySpecSectionIsRendered() {
        compose.setContent {
            ThemeStudioScreen(state = fixturePureBlack(), onEvent = {}, renderTerminalView = false, reduceMotion = true, presets = testPresets)
        }

        // The exact order is pinned by ThemeStudioSpecTest; here we prove the
        // screen actually composes one header for every section in the list.
        ThemeStudioSpec.sectionOrder.forEach { section ->
            compose.onNodeWithTag("theme-section:${section.key}").assertExists()
        }
    }

    @Test
    fun backButtonIsVisibleClickableAndDescribed() {
        var backInvoked = 0
        compose.setContent {
            ThemeStudioScreen(
                state = fixturePureBlack(),
                onEvent = {},
                renderTerminalView = false,
                reduceMotion = true,
                presets = testPresets,
                onBack = { backInvoked++ },
            )
        }
        // 子页头返回钮（navigation.md §5）：48dp 命中盒 + 非空 contentDescription。
        val back = compose.onNodeWithTag("theme-studio:back")
        back.assertContentDescriptionEquals("返回")
        val floor = with(compose.density) { 48.dp.toPx() }
        val bounds = back.fetchSemanticsNode().boundsInRoot
        assertTrue("back hit box is only ${bounds.height} px tall", bounds.height >= floor)
        back.performClick()
        assertEquals(1, backInvoked)
    }

    @Test
    fun backButtonIsAbsentWithoutOnBack() {
        compose.setContent {
            ThemeStudioScreen(state = fixturePureBlack(), onEvent = {}, renderTerminalView = false, reduceMotion = true, presets = testPresets)
        }
        compose.onNodeWithTag("theme-studio:back").assertDoesNotExist()
    }

    @Test
    fun optionRowsMeetTheFortyEightDpFloor() {
        compose.setContent {
            ThemeStudioScreen(state = fixturePureBlack(), onEvent = {}, renderTerminalView = false, reduceMotion = true, presets = testPresets)
        }

        val floor = with(compose.density) { 48.dp.toPx() }
        // Rows near the top are already laid out; any row uses the same
        // ThemeOptionRow primitive, whose min height is the 48dp floor.
        listOf(
            "theme-row:mode:system",
            "theme-row:mode:light",
            "theme-row:mode:dark",
            "theme-row:pure-black:standard",
            "theme-row:pure-black:pure-black",
        ).forEach { tag ->
            val height = compose.onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.height
            assertTrue("$tag is only $height px tall", height >= floor)
        }
    }

    @Test
    fun currentValuesAreReadableNextToTheSectionTitle() {
        compose.setContent {
            ThemeStudioScreen(state = fixturePureBlack(), onEvent = {}, renderTerminalView = false, reduceMotion = true, presets = testPresets)
        }
        compose.onNodeWithTag("theme-value:mode").assertTextEquals("深色")
        compose.onNodeWithTag("theme-value:pure-black").assertTextEquals("纯黑")
        compose.onNodeWithTag("theme-value:corner").assertTextEquals("12 dp")
        compose.onNodeWithTag("theme-value:terminal").assertTextEquals("14 sp")
    }

    @Test
    fun slidersExposeAReadableStateDescription() {
        compose.setContent {
            ThemeStudioScreen(
                state = fixture(pureBlack = true, textScalePercent = 130),
                onEvent = {},
                renderTerminalView = false,
                reduceMotion = true,
                presets = testPresets,
            )
        }
        compose.onNodeWithTag("theme-slider:text-scale")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "应用内字号 130%"))
        compose.onNodeWithTag("theme-slider:surface-opacity")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "表面不透明度 80%"))
        compose.onNodeWithTag("theme-slider:terminal-font-size")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "终端字号 14 sp"))
    }

    @Test
    fun everyPresetIsRenderedAndTheCurrentOneIsSelected() {
        compose.setContent {
            ThemeStudioScreen(
                state = fixture(theme = ThemeCatalog.github),
                onEvent = {},
                renderTerminalView = false,
                reduceMotion = true,
                presets = ThemeCatalog.all,
            )
        }
        ThemeCatalog.all.forEach { preset ->
            compose.onNodeWithTag("theme-preset:${preset.id}").assertExists()
        }
        compose.onNodeWithTag("theme-preset:${ThemeCatalog.github.id}").assertIsSelected()
    }

    @Test
    fun pickingAPresetEmitsTheExistingThemeId() {
        val events = mutableListOf<ThemeStudioEvent>()
        compose.setContent {
            ThemeStudioScreen(
                state = fixture(),
                onEvent = { events += it },
                renderTerminalView = false,
                reduceMotion = true,
                presets = testPresets,
            )
        }
        compose.onNodeWithTag("theme-preset:${ThemeCatalog.github.id}")
            .performScrollTo()
            .performClick()
        assertEquals(ThemeStudioEvent.SelectTheme(ThemeCatalog.github.id), events.last())
    }

    @Test
    fun cornerAndTextScaleChipsEmitTheirEvents() {
        val events = mutableListOf<ThemeStudioEvent>()
        compose.setContent {
            ThemeStudioScreen(
                state = fixture(),
                onEvent = { events += it },
                renderTerminalView = false,
                reduceMotion = true,
                presets = testPresets,
            )
        }
        compose.onNodeWithTag("theme-corner:24").performScrollTo().performClick()
        assertEquals(ThemeStudioEvent.SetCornerDp(24), events.last())
        compose.onNodeWithTag("theme-row:text-scale:custom").performScrollTo().performClick()
        assertEquals(ThemeStudioEvent.SetTextScaleMode(OeTextScaleMode.Custom), events.last())
    }

    @Test
    fun fixedThemeDisablesModeSelection() {
        compose.setContent {
            ThemeStudioScreen(
                state = fixture(theme = ThemeCatalog.mascotLavender),
                onEvent = {},
                renderTerminalView = false,
                reduceMotion = true,
                presets = testPresets,
            )
        }
        compose.onNodeWithTag("theme-row:mode:light").assertIsNotEnabled()
        compose.onNodeWithTag("theme-row:mode:dark").assertIsNotEnabled()
        compose.onNodeWithTag("theme-row:mode:system").assertIsNotEnabled()
    }

    @Test
    fun importedFontRowIsDisabledUntilFontImportShips() {
        compose.setContent {
            ThemeStudioScreen(state = fixture(), onEvent = {}, renderTerminalView = false, reduceMotion = true, presets = testPresets)
        }
        // 暂不支持导入字体: no font can be picked yet, so the row must not accept
        // a selection that would persist an unloadable font choice.
        compose.onNodeWithTag("theme-row:font:imported")
            .performScrollTo()
            .assertIsNotEnabled()
            .assertTextContains("暂不支持导入字体")
        compose.onNodeWithTag("theme-row:font:system")
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun terminalInputAcceptsImeSubmit() {
        compose.setContent {
            ThemeStudioScreen(state = fixture(), onEvent = {}, renderTerminalView = false, reduceMotion = true, presets = testPresets)
        }
        val input = compose.onNodeWithTag("theme-terminal:input")
        input.performScrollTo()
        input.performTextInput("help")
        input.performImeAction()
        compose.onNodeWithTag("theme-terminal:preview").assertExists()
    }

    private fun fixturePureBlack(): ThemeStudioState =
        fixture(theme = ThemeCatalog.constructivistWorkstation, pureBlack = true, textScalePercent = 100)
}
