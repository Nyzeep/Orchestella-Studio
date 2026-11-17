package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The bottom-bar choice must reach the miniature, otherwise the preview drifts
 * from the page it claims to show. These compositions are just the 168dp
 * preview, so they stay cheap.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h480dp-mdpi")
class HomePreviewNavStyleTest {

    @get:Rule
    val compose = createComposeRule()

    private val theme = ThemeCatalog.constructivistWorkstation

    @Test
    fun floatingStyleDrawsTheFloatingBar() {
        compose.setContent {
            HomePreview(theme = theme, pureBlack = false, cornerDp = 16)
        }
        compose.onNodeWithTag("theme-preview:nav-floating").assertExists()
        compose.onAllNodesWithTag("theme-preview:nav-fixed").assertCountEquals(0)
    }

    @Test
    fun fixedStyleDrawsTheEdgeToEdgeBar() {
        compose.setContent {
            HomePreview(
                theme = theme,
                pureBlack = false,
                cornerDp = 16,
                navStyle = NavStyleOption.Fixed,
            )
        }
        compose.onNodeWithTag("theme-preview:nav-fixed").assertExists()
        compose.onAllNodesWithTag("theme-preview:nav-floating").assertCountEquals(0)
    }
}
