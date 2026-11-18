package io.github.nyzeep.orchestella.ui.v4

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.screens.config.ConfigScreen
import io.github.nyzeep.orchestella.ui.screens.config.ConfigScreenState
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardActions
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardEditState
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardScreen
import io.github.nyzeep.orchestella.ui.screens.dashboard.dashboardOverviewScreenshotState
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosActions
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosPreviewStates
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosScreen
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareActions
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareScreen
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareTestFixtures
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesActions
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesScreen
import io.github.nyzeep.orchestella.ui.screens.instances.instancesWithRowsScreenshotState
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3Pages
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** First screens use the production shell and page slots with fixed, populated UI states. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-mdpi")
class V4MainPagesScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun workstation_light_first() = capture(Oe3Tab.Workstation, ThemeCatalog.mascotLight)

    @Test
    fun workstation_dark_first() = capture(Oe3Tab.Workstation, ThemeCatalog.mascotDark)

    @Test
    fun instances_light_first() = capture(Oe3Tab.Instances, ThemeCatalog.mascotLight)

    @Test
    fun instances_dark_first() = capture(Oe3Tab.Instances, ThemeCatalog.mascotDark)

    @Test
    fun hardware_light_first() = capture(Oe3Tab.Hardware, ThemeCatalog.mascotLight)

    @Test
    fun hardware_dark_first() = capture(Oe3Tab.Hardware, ThemeCatalog.mascotDark)

    @Test
    fun distros_light_first() = capture(Oe3Tab.Distros, ThemeCatalog.mascotLight)

    @Test
    fun distros_dark_first() = capture(Oe3Tab.Distros, ThemeCatalog.mascotDark)

    @Test
    fun config_light_first() = capture(Oe3Tab.Config, ThemeCatalog.mascotLight)

    @Test
    fun config_dark_first() = capture(Oe3Tab.Config, ThemeCatalog.mascotDark)

    private fun capture(tab: Oe3Tab, theme: AppTheme) {
        compose.setContent {
            Oe3App(
                selectedTab = tab,
                onTabSelected = {},
                theme = theme,
                reduceMotion = true,
                pages = pages,
            )
        }
        compose.onNodeWithTag(
            when (tab) {
                Oe3Tab.Workstation -> "dashboard:cards"
                Oe3Tab.Instances -> "instances-list"
                Oe3Tab.Hardware -> "hardware:summary"
                Oe3Tab.Distros -> "distros-grid"
                Oe3Tab.Config -> "config:screen"
            },
        ).assertIsDisplayed()
        compose.onNode(hasText(tab.label) and isSelected()).assertIsDisplayed()
        val mode = if (theme.isDark) "dark" else "light"
        compose.onRoot().captureRoboImage("src/test/screenshots/v4/${tab.key}_${mode}_first.png")
    }

    private val pages = Oe3Pages(
        workstation = {
            DashboardScreen(
                state = dashboardOverviewScreenshotState(),
                actions = DashboardActions(),
                edit = DashboardEditState(store = null),
            )
        },
        instances = { InstancesScreen(instancesWithRowsScreenshotState(), InstancesActions()) },
        hardware = { HardwareScreen(HardwareTestFixtures.ready(), HardwareActions()) },
        distros = { DistrosScreen(DistrosPreviewStates.collapsed, DistrosActions()) },
        config = {
            ConfigScreen(
                state = ConfigScreenState(
                    versionName = "0.6.3",
                    engineSummary = "AVF 未授权 · QEMU 回退",
                ),
                onRoute = {},
                onOptionsChange = {},
            )
        },
    )
}
