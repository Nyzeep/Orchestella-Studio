package io.github.nyzeep.orchestella.ui.v4

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.screens.connections.PortForwardingScreenshotSample
import io.github.nyzeep.orchestella.ui.screens.connections.SshScreenshotSample
import io.github.nyzeep.orchestella.ui.screens.connections.VmHardwareScreenshotSample
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeBackgroundScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemePresetScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeTerminalScreen
import io.github.nyzeep.orchestella.ui.screens.theme.themeStudioScreenshotState
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardPreviewStates
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardScreen
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardScreenModelStub
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardUiState
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.OeBackStack
import io.github.nyzeep.orchestella.ui.shell.OeDestination
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h1200dp-mdpi")
class V4SecondaryPagesScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val testName = TestName()

    private val theme = ThemeCatalog.mascotDark
    private val studio = themeStudioScreenshotState(theme, pureBlack = false, textScalePercent = 100)

    @Test
    @Config(qualifiers = "zh-rCN-w412dp-h1600dp-mdpi")
    fun appearance_dark_root() = capturePushedPage(
        Oe3Tab.Config,
        R.string.ui3_theme_title,
        "theme-entry:terminal",
    ) { back ->
        ThemeStudioScreen(
            state = studio,
            onEvent = {},
            onBack = back,
            onOpenPresets = {},
            onOpenAccent = {},
            onOpenBackground = {},
            onOpenTerminal = {},
            reduceMotion = true,
        )
    }

    @Test
    fun presets_dark_grid() = capturePushedPage(
        Oe3Tab.Config,
        R.string.ui3_theme_page_presets,
        "theme-presets:screen",
    ) { back ->
        ThemePresetScreen(state = studio, onEvent = {}, onBack = back)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w412dp-h1600dp-mdpi")
    fun terminal_dark_customization() = capturePushedPage(
        Oe3Tab.Config,
        R.string.ui3_theme_page_terminal,
        "theme-terminal:input",
    ) { back ->
        ThemeTerminalScreen(
            state = studio.copy(cursorBlink = false),
            onEvent = {},
            onBack = back,
            onPickTerminalBackground = {},
        )
    }

    @Test
    fun background_dark_transparency() = capturePushedPage(
        Oe3Tab.Config,
        R.string.ui3_theme_page_background,
        "theme-background:screen",
    ) { back ->
        ThemeBackgroundScreen(state = studio, onEvent = {}, onBack = back, onPickBackground = {})
    }

    @Test
    fun wizard_dark_storage() = captureWizard(WizardPreviewStates.storage, "wizard:storage-card")

    @Test
    fun wizard_dark_network() {
        val state = WizardPreviewStates.network
        captureWizard(
            state.copy(
                network = state.network.copy(
                    ipLine = "宿主 IP：192.0.2.24",
                    gatewayLine = "默认网关：192.0.2.1",
                    dnsLine = "DNS 服务器：192.0.2.53",
                ),
            ),
            "wizard:network-card",
        )
    }

    @Test
    fun wizard_dark_hardware() = captureWizard(WizardPreviewStates.hardware, "wizard:hardware-card")

    @Test
    fun wizard_dark_startup() = captureWizard(WizardPreviewStates.startup, "wizard:identity-card")

    @Test
    fun `port-forwarding_dark_rules`() = capturePushedPage(
        Oe3Tab.Config,
        R.string.connections_port_forwarding_title,
        "connections:pf:add",
    ) { back ->
        PortForwardingScreenshotSample(onBack = back)
    }

    @Test
    fun ssh_dark_profiles() = capturePushedPage(
        Oe3Tab.Config,
        R.string.connections_ssh_title,
        "connections:ssh:delete-ssh-local",
    ) { back ->
        SshScreenshotSample(onBack = back)
    }

    @Test
    fun `vm-hardware_dark_settings`() {
        // VM hardware opens from instance detail in a standalone Activity.
        compose.setContent {
            Oe3Theme(theme = theme, reduceMotion = true) {
                CompositionLocalProvider(LocalOe3PageBottomPadding provides 0.dp) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(Oe3ThemeValues.colors.background))
                            .navigationBarsPadding(),
                    ) {
                        VmHardwareScreenshotSample()
                    }
                }
            }
        }
        capture("connections:vm:cpu")
    }

    private fun captureWizard(state: WizardUiState, pageTag: String) {
        val controller = WizardScreenModelStub(state)
        capturePushedPage(Oe3Tab.Distros, R.string.wizard_page_title, pageTag) { back ->
            WizardScreen(controller = controller, onExit = back)
        }
    }

    private fun capturePushedPage(
        tab: Oe3Tab,
        titleRes: Int,
        pageTag: String,
        content: @Composable (onBack: () -> Unit) -> Unit,
    ) {
        val stack = OeBackStack()
        compose.setContent {
            Oe3App(
                selectedTab = tab,
                onTabSelected = {},
                theme = theme,
                reduceMotion = true,
                backStack = stack,
            )
        }
        val title = ApplicationProvider.getApplicationContext<Context>().getString(titleRes)
        compose.runOnIdle {
            stack.push(OeDestination(key = testName.methodName, title = title) { content { stack.pop() } })
        }
        capture(pageTag)
    }

    private fun capture(pageTag: String) {
        compose.onNodeWithTag(pageTag).assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/v4/${testName.methodName}.png")
    }
}
