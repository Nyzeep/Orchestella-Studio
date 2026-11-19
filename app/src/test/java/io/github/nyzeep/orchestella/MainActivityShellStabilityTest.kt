package io.github.nyzeep.orchestella

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.screens.theme.CursorBlinkPort
import io.github.nyzeep.orchestella.ui.screens.theme.ThemePresetScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioScreen
import io.github.nyzeep.orchestella.ui.screens.theme.rememberThemeStudioHandle
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3Pages
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.OeBackStack
import io.github.nyzeep.orchestella.ui.shell.OeDestination
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
class MainActivityShellStabilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun mainActivitySource(): String {
        val direct = File("src/main/java/io/github/nyzeep/orchestella/MainActivity.kt")
        val file = if (direct.exists()) direct else File("app/src/main/java/io/github/nyzeep/orchestella/MainActivity.kt")
        assertTrue("missing MainActivity.kt: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun mainActivityMemoizesPagesGatesPureBlackAndSharesThemeStudioHandle() {
        val text = mainActivitySource()
        assertTrue("MainActivity must memoize buildOe3Pages()", text.contains("remember { buildOe3Pages() }"))
        assertTrue("MainActivity must gate pureBlack on isDark", text.contains("resolvedTheme.isDark && themeStore.uiPureBlack()"))
        assertTrue("MainActivity must share ThemeStudioHandle", text.contains("LocalMainThemeStudioHandle"))
        assertTrue("MainActivity must update system bar appearance", text.contains("isAppearanceLightStatusBars"))
    }

    @Test
    fun recreatingMainActivityRestoresTheOpenThemeDestinationAndItsParent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText(Oe3Tab.Config.label).performClick()
            compose.onNodeWithTag("config:route:appearance.theme_studio").performClick()
            compose.onNodeWithTag("theme-entry:presets").performClick()
            compose.onNodeWithTag("theme-preset:github").performScrollTo().assertExists()

            scenario.recreate()
            compose.onNodeWithTag("theme-preset:github").assertExists()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("theme-entry:presets").assertExists()
        }
    }

    @Test
    fun sharedThemeStudioHandleAndMemoizedPagesSurviveTicksAndSubRoutePushPop() {
        val store = ThemeStore(FakeSharedPreferences())
        val stack = OeBackStack()
        val port = object : CursorBlinkPort {
            override fun read(): Boolean = true
            override fun write(enabled: Boolean) = Unit
        }
        var snapshotTick by mutableStateOf(0)
        var pageInitCount = 0

        compose.setContent {
            val tick = snapshotTick
            val pages = remember {
                Oe3Pages(
                    workstation = {
                        remember { pageInitCount++ }
                        Column(Modifier.fillMaxSize()) {
                            Text("tick=$tick", modifier = Modifier.testTag("stability:tick"))
                        }
                    },
                )
            }
            val sharedHandle = if (stack.containsKeyOrLeaving("theme-studio")) {
                rememberThemeStudioHandle(store, port)
            } else {
                null
            }
            val theme = sharedHandle?.state?.theme ?: store.resolve()
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = theme,
                reduceMotion = true,
                backStack = stack,
                pages = pages,
            )
        }
        compose.waitForIdle()
        assertEquals(1, pageInitCount)

        snapshotTick = 1
        compose.waitForIdle()
        assertEquals(1, pageInitCount)

        // Push Theme Studio root (depth 1) and preset subroute (depth 2), pick a theme, pop back.
        stack.push(
            OeDestination("theme-studio", "Theme") {
                val handle = rememberThemeStudioHandle(store, port)
                ThemeStudioScreen(
                    state = handle.state,
                    onEvent = handle.onEvent,
                    reduceMotion = true,
                    onBack = { stack.pop() },
                    onOpenPresets = {
                        stack.push(
                            OeDestination("theme-studio.presets", "Presets") {
                                ThemePresetScreen(
                                    state = handle.state,
                                    onEvent = handle.onEvent,
                                    onBack = { stack.pop() },
                                )
                            },
                        )
                    },
                )
            },
        )
        compose.waitForIdle()
        compose.onNodeWithTag("theme-entry:presets").performClick()
        compose.waitForIdle()

        // Selecting a preset in Mode.SYSTEM preserves SYSTEM mode and pops cleanly to depth 1.
        compose.onNodeWithTag("theme-preset:github").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ThemeStore.Mode.SYSTEM, store.mode())
        assertEquals(ThemeCatalog.githubLight.id, store.themeId())
        assertEquals(ThemeCatalog.github, store.resolveForMode(true))

        assertTrue(stack.pop())
        compose.waitForIdle()
        assertEquals(1, stack.size)
        assertTrue(stack.pop())
        compose.waitForIdle()
        assertEquals(0, stack.size)
    }
}
