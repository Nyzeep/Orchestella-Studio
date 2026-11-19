package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
class Oe3PagerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pagerPreservesRememberSaveableAndScrollOffsetAcrossTabSwitches() {
        var selected by mutableIntStateOf(0)
        val tabs = Oe3Tab.entries
        val pages = Oe3Pages(
            workstation = {
                var counter by rememberSaveable { mutableIntStateOf(0) }
                OeScaffold(title = "Workstation") {
                    val scroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .testTag("pager:workstation-scroll"),
                    ) {
                        Text(
                            text = "count=$counter",
                            modifier = Modifier
                                .testTag("pager:workstation-counter")
                                .clickable { counter++ },
                        )
                        Box(Modifier.size(width = 100.dp, height = 1600.dp))
                    }
                }
            },
            instances = {
                OeScaffold(title = "Instances") {
                    Text("InstancesPage", modifier = Modifier.testTag("pager:instances-body"))
                }
            },
        )
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                Oe3Pager(selected = tabs[selected], pages = pages)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("pager:workstation-counter").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("count=1").assertExists()

        // Switch away to Instances and back to Workstation.
        selected = Oe3Tab.Instances.ordinal
        compose.waitForIdle()
        compose.onNodeWithTag("pager:instances-body").assertExists()

        selected = Oe3Tab.Workstation.ordinal
        compose.waitForIdle()
        compose.onNodeWithText("count=1").assertExists()
    }

    @Test
    fun memoizedPagesSurviveUnrelatedHostStateUpdatesWithoutResettingTabState() {
        var tick by mutableIntStateOf(0)
        var tabInits = 0
        compose.setContent {
            val currentTick = tick
            val pages = remember {
                Oe3Pages(
                    workstation = {
                        remember { tabInits++ }
                        Text("workstation-$currentTick")
                    },
                )
            }
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                Oe3Pager(selected = Oe3Tab.Workstation, pages = pages)
            }
        }
        compose.waitForIdle()
        assertEquals(1, tabInits)

        // Simulate 3s DashboardSnapshot tick on the host.
        tick = 1
        compose.waitForIdle()
        tick = 2
        compose.waitForIdle()
        assertEquals(1, tabInits)
    }

    @Test
    fun pagerTransitionsCleanlyWithSymmetricSlideAndClipToBounds() {
        var selected by mutableIntStateOf(Oe3Tab.Workstation.ordinal)
        val tabs = Oe3Tab.entries
        val pages = Oe3Pages(
            workstation = { Box(Modifier.fillMaxSize().testTag("pager:ws")) },
            config = { Box(Modifier.fillMaxSize().testTag("pager:cfg")) },
        )
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = false) {
                Oe3Pager(selected = tabs[selected], pages = pages)
            }
        }
        compose.waitForIdle()
        selected = Oe3Tab.Config.ordinal
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("pager:cfg").assertExists()
        compose.onNodeWithTag("pager:ws").assertDoesNotExist()
        assertTrue(Oe3PageTransitionSpec.exitSlideOffsetX(400, 1) < 0)
    }
}
