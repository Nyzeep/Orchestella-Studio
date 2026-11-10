package io.github.nyzeep.orchestella

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM contract for the port forwarding sheet empty-state well (U4 walkthrough fix).
 *
 * The sheet layout comment specifies that the outer [R.id.well_port_empty] container
 * and its inner [R.id.tv_no_rules_hint] must appear and disappear together; before the
 * fix only the inner hint was toggled and a populated rule list left the bg_oe_well
 * stroke behind as a collapsed hairline ghost.
 */
class PortForwardingSheetEmptyStateTest {

    @Test
    fun emptyRuleListShowsBothTheWellAndItsHint() {
        val (well, hint) = PortForwardingBottomSheet.emptyStateVisibility(ruleCount = 0)
        assertEquals(View.VISIBLE, well)
        assertEquals(View.VISIBLE, hint)
    }

    @Test
    fun populatedRuleListHidesTheWellSoNoStrokeGhostRemains() {
        for (ruleCount in 1..64) {
            val (well, hint) = PortForwardingBottomSheet.emptyStateVisibility(ruleCount)
            assertEquals("well must collapse for $ruleCount rules", View.GONE, well)
            assertEquals("hint must collapse for $ruleCount rules", View.GONE, hint)
        }
    }

    @Test
    fun outerWellAndInnerHintAlwaysShareTheSameVisibility() {
        // 同现同隐 invariant from the layout comment: the outer well container may
        // never diverge from its inner hint, in either direction.
        for (ruleCount in -1..128) {
            val (well, hint) = PortForwardingBottomSheet.emptyStateVisibility(ruleCount)
            assertTrue(
                "well/hint divergence at ruleCount=$ruleCount",
                (well == View.VISIBLE) == (hint == View.VISIBLE)
            )
            assertTrue(
                "visibility must be a plain VISIBLE/GONE pair at ruleCount=$ruleCount",
                well == View.VISIBLE || well == View.GONE
            )
        }
    }
}
