package io.github.nyzeep.orchestella.ui.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI-12 向导金样（新增基线，只录向导）：四步 + 创建中 + 恢复保留态 +
 * 存储不足 E-STORAGE-FULL 文案态。全部使用设计态夹具（WizardPreviewStates），
 * 不跑探测链；输出到 `src/test/screenshots/ui3/wizard/`，与既有 ui3 golden
 * 约定一致。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1400dp-mdpi")
class WizardScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun step1StorageDark() {
        capture(name = "step1-storage-dark", state = WizardPreviewStates.storage)
    }

    @Test
    fun step1StorageInsufficientDark() {
        capture(name = "step1-storage-insufficient-dark", state = WizardPreviewStates.storageInsufficient)
    }

    @Test
    fun step2NetworkDark() {
        capture(name = "step2-network-dark", state = WizardPreviewStates.network)
    }

    @Test
    fun step3HardwareDark() {
        capture(name = "step3-hardware-dark", state = WizardPreviewStates.hardware)
    }

    @Test
    fun step4StartupDark() {
        capture(name = "step4-startup-dark", state = WizardPreviewStates.startup)
    }

    @Test
    fun creatingDark() {
        capture(name = "creating-dark", state = WizardPreviewStates.creating)
    }

    @Test
    fun keptRunningDark() {
        capture(name = "kept-running-dark", state = WizardPreviewStates.keptRunning)
    }

    private fun capture(name: String, state: WizardUiState) {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                Box(
                    modifier = Modifier
                        .size(width = 412.dp, height = 1400.dp)
                        // 页面本体透明：golden 自己补壳层底色（UI-02 约定）。
                        .background(Color(Oe3ThemeValues.colors.background))
                        .testTag("wizard-shot"),
                ) {
                    WizardScreen(
                        controller = WizardScreenModelStub(state),
                        onExit = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("wizard-shot").captureRoboImage(
            "src/test/screenshots/ui3/wizard/$name.png",
        )
    }
}
