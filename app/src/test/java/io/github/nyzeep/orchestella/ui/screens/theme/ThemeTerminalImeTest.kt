package io.github.nyzeep.orchestella.ui.screens.theme

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3NavStyle
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.OeBackStack
import io.github.nyzeep.orchestella.ui.shell.OeDestination
import io.github.nyzeep.orchestella.ui.shell.pageBottomReserve
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * W2-06（A4-P4 / M5）：[ThemeTerminalScreen] 软键盘（IME）避让与防双倍底边距。
 *
 * - 键盘未弹起（ime = 0dp）时，保留完整 [LocalOe3PageBottomPadding]，静置金样不变；
 * - 键盘部分遮挡底栏留白（0 < ime < pageBottom）时，底边距取差值，总底距保持 pageBottom；
 * - 键盘高于底栏留白（ime >= pageBottom）时，额外底边距归零，内容滚动至最底贴紧键盘上缘
 *   而不产生双倍底边距空白，且输入行完全不被键盘遮挡。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
class ThemeTerminalImeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun scrollToBottom() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
                scrollBy(0f, 10_000f)
            }
        compose.waitForIdle()
    }

    @Test
    fun residualBottomPaddingAvoidsDoubleBottomSpacing() {
        assertEquals(83.dp, ThemeTerminalImeSpec.residualBottomPadding(pageBottom = 83.dp, imeBottom = 0.dp))
        assertEquals(43.dp, ThemeTerminalImeSpec.residualBottomPadding(pageBottom = 83.dp, imeBottom = 40.dp))
        assertEquals(0.dp, ThemeTerminalImeSpec.residualBottomPadding(pageBottom = 83.dp, imeBottom = 83.dp))
        assertEquals(0.dp, ThemeTerminalImeSpec.residualBottomPadding(pageBottom = 83.dp, imeBottom = 300.dp))
    }

    @Test
    fun terminalDestinationReceivesTheFixedDockAndNavigationInset() {
        val stack = OeBackStack()
        val state = themeStudioPreviewState(ThemeCatalog.constructivistWorkstation)
        var hostView: View? = null
        var reserve = Dp.Unspecified
        compose.setContent {
            hostView = LocalView.current
            Oe3App(
                selectedTab = Oe3Tab.Config,
                onTabSelected = {},
                theme = state.theme,
                navStyle = Oe3NavStyle.Fixed,
                reduceMotion = true,
                backStack = stack,
            )
        }
        compose.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 31))
                .build()
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
            stack.push(OeDestination("terminal", "Terminal settings") {
                reserve = LocalOe3PageBottomPadding.current
                ThemeTerminalScreen(state = state, onEvent = {}, onBack = {}, renderTerminalView = false)
            })
        }
        compose.waitForIdle()
        assertEquals(pageBottomReserve(Oe3NavStyle.Fixed, 31.dp), reserve)
    }

    @Test
    fun imeInsetShrinksScrollViewportWithoutDoubleBottomPadding() {
        val state = themeStudioPreviewState(ThemeCatalog.constructivistWorkstation)
        var hostView: View? = null

        compose.setContent {
            hostView = LocalView.current
            Oe3Theme(theme = state.theme, reduceMotion = true) {
                CompositionLocalProvider(LocalOe3PageBottomPadding provides 83.dp) {
                    ThemeTerminalScreen(
                        state = state,
                        onEvent = {},
                        onBack = {},
                        renderTerminalView = false,
                    )
                }
            }
        }
        compose.waitForIdle()

        val rootHeight = compose.onNodeWithTag("theme-terminal:screen")
            .fetchSemanticsNode().boundsInRoot.height
        assertEquals(915f, rootHeight, 1f)

        // 基线（IME = 0dp）：滚到最底时底部留白恰为 83dp。
        scrollToBottom()
        val endRestingBottom = compose.onNodeWithTag("theme-terminal:end")
            .fetchSemanticsNode().boundsInRoot.bottom
        assertEquals(rootHeight - 83f, endRestingBottom, 1.5f)

        // 局部键盘（IME = 40dp < 83dp）：40dp imePadding + 43dp residual = 83dp，总底距不叠加。
        val view = requireNotNull(hostView)
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 40))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()
        scrollToBottom()
        val endPartialImeBottom = compose.onNodeWithTag("theme-terminal:end")
            .fetchSemanticsNode().boundsInRoot.bottom
        assertEquals(rootHeight - 83f, endPartialImeBottom, 1.5f)

        // 弹起 300dp 软键盘：视口底边上移至 rootHeight - 300，无 83dp 双倍底边距。
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()

        scrollToBottom()
        val endWithImeBottom = compose.onNodeWithTag("theme-terminal:end")
            .fetchSemanticsNode().boundsInRoot.bottom
        assertEquals(rootHeight - 300f, endWithImeBottom, 1.5f)

        compose.onNodeWithTag("theme-terminal:input").performScrollTo()
        val inputBounds = compose.onNodeWithTag("theme-terminal:input")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "terminal input (${inputBounds.bottom}) must stay above 300dp IME (${rootHeight - 300f})",
            inputBounds.bottom <= rootHeight - 300f + 1.5f,
        )
    }
}
