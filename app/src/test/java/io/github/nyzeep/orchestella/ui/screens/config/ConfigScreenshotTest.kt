package io.github.nyzeep.orchestella.ui.screens.config

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
 * 配置页截图（UI3-11A 补录）：目录态暗/亮各一张。
 *
 * golden 收进 `src/test/screenshots/ui3/config/`，命名与 UI3-06/08 的
 * `<state>-<mode>` 约定一致；[ConfigScreenState] 为纯字面量样本（取值与
 * `ConfigScreen.kt` 的预览样本同源，仅去掉待更新字段：`updateAvailable` 与
 * 更新说明置 null），路由与选项回调全为空实现，动效关闭，
 * 所以干净检出重放得到同一批像素。捕获页内已声明的 `config:screen` 节点。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class ConfigScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun catalogDark() {
        capture("catalog-dark", ThemeCatalog.moonshotDark)
    }

    @Test
    fun catalogLight() {
        capture("catalog-light", ThemeCatalog.moonshotLight)
    }

    private fun capture(name: String, theme: AppTheme) {
        compose.setContent {
            Oe3Theme(theme = theme, reduceMotion = true) {
                // UI-02 起页面本体透明：页级 golden 补壳层底色并整窗捕获，
                // 单标题栏因此进入基线。
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(Oe3ThemeValues.colors.background)),
                ) {
                    ConfigScreen(
                        state = ConfigScreenState(
                            versionName = "0.6.2",
                            engineSummary = "AVF 未授权 · QEMU 回退",
                            updateAvailable = null,
                            updateNote = null,
                        ),
                        onRoute = {},
                        onOptionsChange = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/ui3/config/$name.png",
        )
    }
}
