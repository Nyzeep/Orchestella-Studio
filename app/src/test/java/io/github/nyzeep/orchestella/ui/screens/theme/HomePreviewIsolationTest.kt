package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.FakeSharedPreferences
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h480dp-mdpi")
class HomePreviewIsolationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homePreviewTransitionsBetweenNavStylesAndThemesWithoutGhostNodes() {
        var navStyle by mutableStateOf(NavStyleOption.Floating)
        var theme by mutableStateOf(ThemeCatalog.constructivistWorkstation)
        var cornerDp by mutableStateOf(16)
        compose.setContent {
            Oe3Theme(theme = theme, reduceMotion = false) {
                HomePreview(
                    theme = theme,
                    pureBlack = false,
                    cornerDp = cornerDp,
                    navStyle = navStyle,
                    reduceMotion = false,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("theme-preview:nav-floating").assertExists()

        navStyle = NavStyleOption.Fixed
        theme = ThemeCatalog.mascotLight
        cornerDp = 24
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        compose.onNodeWithTag("theme-preview:nav-fixed").assertExists()
        compose.onAllNodesWithTag("theme-preview:nav-floating").assertCountEquals(0)
    }

    @Test
    fun rememberThemeStudioHandleRefreshesOnReattachWhenStoreChangedWhileDetached() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setThemeId(ThemeCatalog.constructivistWorkstation.id)

        var mounted by mutableStateOf(true)
        var observedThemeId = ""
        val port = object : CursorBlinkPort {
            override fun read(): Boolean = true
            override fun write(enabled: Boolean) = Unit
        }
        compose.setContent {
            if (mounted) {
                val handle = rememberThemeStudioHandle(store = store, cursorBlink = port)
                observedThemeId = handle.state.theme.id
            }
        }
        compose.waitForIdle()
        assertEquals(ThemeCatalog.constructivistWorkstation.id, observedThemeId)

        // Detach (listener unregistered), mutate ThemeStore externally, then reattach.
        mounted = false
        compose.waitForIdle()
        store.setThemeId(ThemeCatalog.github.id)

        mounted = true
        compose.waitForIdle()
        assertEquals(ThemeCatalog.github.id, observedThemeId)
    }
}
