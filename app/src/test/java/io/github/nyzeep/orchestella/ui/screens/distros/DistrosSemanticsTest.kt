package io.github.nyzeep.orchestella.ui.screens.distros

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 发行版页的零设备语义契约：每卡可点、展开状态可读、安装按钮 48dp、
 * 单开手风琴与旋转后展开保持。[reduceMotion] 让 220ms 动画归零，
 * 断言不需要等待动画。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h2000dp-mdpi")
class DistrosSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val collapsed = DistrosPreviewStates.collapsed

    @Test
    fun everyCatalogCardIsClickable() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                DistrosScreen(state = collapsed, actions = DistrosActions())
            }
        }

        assertEquals(7, collapsed.distros.size)
        collapsed.distros.forEach { distro ->
            compose.onNodeWithTag("distro-card-${distro.id}")
                .assertIsDisplayed()
                .assertHasClickAction()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        "已收起",
                    ),
                )
        }
    }

    @Test
    fun expandedStateIsReadableAndClosingWorksFromTheSameCard() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                DistrosScreen(state = collapsed, actions = DistrosActions())
            }
        }

        val card = compose.onNodeWithTag("distro-card-ubuntu")
        card.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        card.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"),
        )
        compose.onNodeWithTag("distro-install-ubuntu", useUnmergedTree = true).assertIsDisplayed()

        card.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        card.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"),
        )
        compose.onNodeWithTag("distro-install-ubuntu", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun openingASecondCardCollapsesTheFirst() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                DistrosScreen(state = collapsed, actions = DistrosActions())
            }
        }

        compose.onNodeWithTag("distro-card-ubuntu").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("distro-install-ubuntu", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithTag("distro-card-debian").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.onNodeWithTag("distro-install-ubuntu", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("distro-install-debian", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("distro-card-ubuntu").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"),
        )
        compose.onNodeWithTag("distro-card-debian").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"),
        )
    }

    @Test
    fun installButtonMeetsThe48DpFloor() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                DistrosScreen(state = collapsed, actions = DistrosActions())
            }
        }

        compose.onNodeWithTag("distro-card-ubuntu").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertTouchTarget(
            compose.onNodeWithTag("distro-install-ubuntu", useUnmergedTree = true),
        )
        assertTouchTarget(
            compose.onNodeWithTag("uml-rootfs-action", useUnmergedTree = true),
        )
    }

    @Test
    fun installActionDoesNotToggleTheCard() {
        var installed: String? = null
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                DistrosScreen(
                    state = collapsed,
                    actions = DistrosActions(install = { installed = it }),
                )
            }
        }

        compose.onNodeWithTag("distro-card-ubuntu").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        // 合并树里 CTA 必须仍是自己的可点击节点；点它只装配，不收起卡片。
        compose.onNodeWithTag("distro-install-ubuntu")
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertEquals("ubuntu", installed)
        compose.onNodeWithTag("distro-card-ubuntu").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"),
        )
    }

    @Test
    fun vanishingCatalogueEntryClearsTheSavedOpenId() {
        val single = DistrosUiState(distros = collapsed.distros.filter { it.id == "ubuntu" })
        val state = mutableStateOf(single)
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                DistrosScreen(state = state.value, actions = DistrosActions())
            }
        }

        compose.onNodeWithTag("distro-card-ubuntu").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("distro-card-ubuntu").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"),
        )

        compose.runOnIdle { state.value = DistrosUiState(distros = emptyList()) }
        compose.waitForIdle()
        compose.runOnIdle { state.value = single }
        compose.waitForIdle()

        compose.onNodeWithTag("distro-card-ubuntu").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"),
        )
    }

    @Test
    fun expandedCardSurvivesStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                DistrosScreen(state = collapsed, actions = DistrosActions())
            }
        }

        compose.onNodeWithTag("distro-card-arch").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("distro-card-arch").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"),
        )

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithTag("distro-card-arch").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"),
        )
        compose.onNodeWithTag("distro-install-arch", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun unknownDistroStillRendersAClickableCard() {
        val registry = DistroCatalogRegistry()
        registry.register(
            DistroManifest(
                "gentoo",
                "2024",
                "aarch64",
                "https://example.com/gentoo.tar.xz",
                "gentoo.tar.xz",
                "c".repeat(64),
                64L * 1024L * 1024L,
            ),
        )
        val state = DistrosUiState.from(registry)

        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                DistrosScreen(state = state, actions = DistrosActions())
            }
        }

        compose.onNodeWithTag("distro-card-gentoo")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.onNodeWithTag("distro-install-gentoo", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun assertTouchTarget(node: SemanticsNodeInteraction) {
        val semanticsNode = node.fetchSemanticsNode()
        val density = semanticsNode.layoutInfo.density
        val width = with(density) { semanticsNode.touchBoundsInRoot.width.toDp() }
        val height = with(density) { semanticsNode.touchBoundsInRoot.height.toDp() }
        assertTrue("touch target $width x $height is below 48dp", width >= 48.dp && height >= 48.dp)
    }
}
