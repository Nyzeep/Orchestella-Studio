package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.ui.screens.connections.PortForwardingPageSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM contract for the port-forwarding page empty state.
 *
 * Migrated from the deleted BottomSheet: the invariant is unchanged — an empty
 * rule list shows the empty block, any persisted rule hides it completely, so a
 * populated list can never leave a collapsed well stroke behind. The subject is
 * now [PortForwardingPageSpec] because the UI is a Compose page.
 */
class PortForwardingSheetEmptyStateTest {

    @Test
    fun emptyRuleListShowsTheEmptyState() {
        assertTrue(PortForwardingPageSpec.emptyStateVisible(ruleCount = 0))
    }

    @Test
    fun populatedRuleListHidesTheEmptyStateSoNoStrokeGhostRemains() {
        for (ruleCount in 1..64) {
            assertFalse(
                "empty state must collapse for $ruleCount rules",
                PortForwardingPageSpec.emptyStateVisible(ruleCount),
            )
        }
    }

    @Test
    fun emptyStateVisibilityIsASingleBooleanPredicate() {
        for (ruleCount in -1..128) {
            assertEquals(
                "divergence at ruleCount=$ruleCount",
                ruleCount <= 0,
                PortForwardingPageSpec.emptyStateVisible(ruleCount),
            )
        }
    }
}
