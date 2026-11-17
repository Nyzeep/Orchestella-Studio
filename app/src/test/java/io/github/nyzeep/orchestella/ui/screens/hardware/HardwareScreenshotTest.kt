package io.github.nyzeep.orchestella.ui.screens.hardware

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
 * 硬件页截图（UI3-11A 补录）：就绪态暗/亮各一张。
 *
 * golden 收进 `src/test/screenshots/ui3/hardware/`，命名与 UI3-06/08 的
 * `<state>-<mode>` 约定一致；状态复用语义用例共用的 [HardwareTestFixtures]
 * 确定性样本（无真机标识），动作全为空实现，动效关闭，所以干净检出重放
 * 得到同一批像素。捕获页内已声明的 `hardware:screen` 节点。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class HardwareScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun readyDark() {
        capture("ready-dark", ThemeCatalog.moonshotDark)
    }

    @Test
    fun readyLight() {
        capture("ready-light", ThemeCatalog.moonshotLight)
    }

    private fun capture(name: String, theme: AppTheme) {
        compose.setContent {
            Oe3Theme(theme = theme, reduceMotion = true) {
                // UI-02 起页面本体透明：页级 golden 补壳层底色并整窗捕获，
                // 这样单标题栏也在基线里。
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(Oe3ThemeValues.colors.background)),
                ) {
                    HardwareScreen(
                        state = HardwareTestFixtures.ready(),
                        actions = HardwareActions(),
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/ui3/hardware/$name.png",
        )
    }
}
