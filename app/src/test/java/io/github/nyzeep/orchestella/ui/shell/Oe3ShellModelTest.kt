package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM contract for the Oe3 shell model (spec §2.3, ticket UI3-02). */
class Oe3ShellModelTest {

    @Test
    fun fiveTabsUseTheDocumentedOrderAndLabels() {
        assertEquals(
            listOf("工作站", "实例", "硬件", "发行版", "配置"),
            Oe3Tab.entries.map { it.label },
        )
        assertEquals(5, Oe3Tab.entries.size)
        assertEquals(
            "tab keys must stay unique because they are persisted",
            Oe3Tab.entries.size,
            Oe3Tab.entries.map { it.key }.toSet().size,
        )
    }

    @Test
    fun exactlyOneTabIsSelectedAtATime() {
        val controller = Oe3ShellController()
        assertEquals(Oe3Tab.Workstation, controller.selectedTab)

        controller.select(Oe3Tab.Hardware)

        assertEquals(Oe3Tab.Hardware, controller.selectedTab)
        assertEquals(1, Oe3Tab.entries.count { it == controller.selectedTab })
    }

    @Test
    fun backReturnsLaterTabsToWorkstationAndFallsThroughOnTheFirst() {
        val controller = Oe3ShellController(Oe3Tab.Config)

        assertTrue("a later tab consumes back", controller.back())
        assertEquals(Oe3Tab.Workstation, controller.selectedTab)

        assertFalse("workstation must let the system handle back", controller.back())
        assertEquals(Oe3Tab.Workstation, controller.selectedTab)
    }

    @Test
    fun everyNonFirstTabBacksOutToWorkstation() {
        Oe3Tab.entries.filter { it != Oe3Tab.Workstation }.forEach { tab ->
            val controller = Oe3ShellController(tab)
            assertTrue("back must handle $tab", controller.back())
            assertEquals(Oe3Tab.Workstation, controller.selectedTab)
        }
    }

    @Test
    fun savedTabKeyRestoresTheTabAndUnknownValuesFallBackToWorkstation() {
        Oe3Tab.entries.forEach { tab ->
            assertEquals(tab, Oe3Tab.fromKey(tab.key))
            assertEquals(tab, Oe3ShellController.restore(tab.key))
        }
        assertEquals(Oe3Tab.Workstation, Oe3Tab.fromKey(null))
        assertEquals(Oe3Tab.Workstation, Oe3Tab.fromKey(""))
        assertEquals(Oe3Tab.Workstation, Oe3Tab.fromKey("missing"))
        assertEquals(Oe3Tab.Workstation, Oe3ShellController.restore(null))
    }

    @Test
    fun navigationBarUsesTheDocumentedGeometry() {
        assertEquals(62.dp, Oe3Sizes.navBar)
        assertEquals(54.dp, Oe3Sizes.navPill)
        assertEquals(8.dp, Oe3Sizes.navPillOverhang)
        assertEquals(21.dp, Oe3Sizes.navSideMargin)
        assertEquals(4.dp, Oe3Sizes.navPadding)
        assertEquals(24.dp, Oe3Sizes.navIcon)
    }

    @Test
    fun navigationCellsMeetThe48dpFloorAtPhoneWidths() {
        listOf(320.dp, 360.dp).forEach { width ->
            val cellWidth = (width - Oe3Sizes.navSideMargin * 2 - Oe3Sizes.navPadding * 2) / 5
            assertTrue(
                "cell width $cellWidth at ${width} must reach 48dp",
                cellWidth >= Oe3Sizes.minTouchTarget,
            )
        }
        assertTrue(Oe3Sizes.navBar >= Oe3Sizes.minTouchTarget)
        assertTrue(Oe3Sizes.navPill >= Oe3Sizes.minTouchTarget)
    }
}
