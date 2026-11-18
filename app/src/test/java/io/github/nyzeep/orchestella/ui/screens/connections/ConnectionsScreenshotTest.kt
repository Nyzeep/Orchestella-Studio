package io.github.nyzeep.orchestella.ui.screens.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
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
 * UI-13 基线：三张新增二级页各浅色/深色一张，共六张。
 *
 * 只捕获页面本体（外壳底色由测试补齐），样本为字面量，动效关闭。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class ConnectionsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, theme: AppTheme, content: @Composable () -> Unit) {
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
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/ui4/connections/$name.png",
        )
    }

    @Test
    fun portForwardingDark() {
        capture("port-forwarding-dark", ThemeCatalog.moonshotDark) { PortForwardingScreenshotSample() }
    }

    @Test
    fun portForwardingLight() {
        capture("port-forwarding-light", ThemeCatalog.moonshotLight) { PortForwardingScreenshotSample() }
    }

    @Test
    fun sshManagerDark() {
        capture("ssh-manager-dark", ThemeCatalog.moonshotDark) { SshScreenshotSample() }
    }

    @Test
    fun sshManagerLight() {
        capture("ssh-manager-light", ThemeCatalog.moonshotLight) { SshScreenshotSample() }
    }

    @Test
    fun vmHardwareDark() {
        capture("vm-hardware-dark", ThemeCatalog.moonshotDark) { VmHardwareScreenshotSample() }
    }

    @Test
    fun vmHardwareLight() {
        capture("vm-hardware-light", ThemeCatalog.moonshotLight) { VmHardwareScreenshotSample() }
    }
}
