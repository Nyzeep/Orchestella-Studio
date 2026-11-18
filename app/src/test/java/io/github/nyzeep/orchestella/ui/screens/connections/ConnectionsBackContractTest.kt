package io.github.nyzeep.orchestella.ui.screens.connections

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ssh.SshProfile
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.shell.OeBackStack
import io.github.nyzeep.orchestella.ui.shell.OeBackStackHost
import io.github.nyzeep.orchestella.ui.shell.OeDestination
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * UI-13 返回一致性：可见返回钮与系统返回键都走同一个 `onBack`，也就是
 * `OeBackStack.pop()`。页面自身不直接依赖栈，宿主各自注入行为。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class ConnectionsBackContractTest {

    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    private fun destination(stack: OeBackStack): OeDestination = OeDestination(
        key = ConnectionsPage.SSH_KEY,
        title = "SSH 连接",
        content = {
            SshManagerContent(
                profiles = listOf(
                    SshProfile(
                        id = "profile-1",
                        label = "Local VM",
                        host = "127.0.0.1",
                        port = 2222,
                        username = "root",
                    ),
                ),
                onBack = { stack.pop() },
                onNew = {},
                onConnect = {},
                onCopy = {},
                onEdit = {},
                onDelete = {},
            )
        },
    )

    private fun setContent(stack: OeBackStack) {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(Oe3ThemeValues.colors.background)),
                ) {
                    OeBackStackHost(backStack = stack, base = {})
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun visibleBackAndSystemBackPopTheSameShellStack() {
        val stack = OeBackStack()
        setContent(stack)

        stack.push(destination(stack))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        assertFalse("visible back must pop the shell stack", stack.canPop)

        stack.push(destination(stack))
        compose.waitForIdle()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertFalse("system back must pop the same shell stack", stack.canPop)
    }
}
