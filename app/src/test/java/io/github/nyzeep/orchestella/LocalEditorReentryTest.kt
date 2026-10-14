package io.github.nyzeep.orchestella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEditorReentryTest {
    @Test
    fun selectingAnotherInstanceRetiresItsOldScopeBeforeRecreation() {
        val events = mutableListOf<String>()
        var oldScopeAllowed = true
        LocalEditorReentry.select("instance-a", "instance-b", false,
            retire = { oldScopeAllowed = false; events.add("retire-a") },
            recreate = { assertFalse(oldScopeAllowed); events.add("recreate-b") },
            finish = { events.add("finish") })
        assertEquals(listOf("retire-a", "recreate-b"), events)
    }

    @Test
    fun selectingTheCurrentLiveInstancePreservesItsScope() {
        val events = mutableListOf<String>()
        LocalEditorReentry.select("instance-a", "instance-a", false,
            retire = { events.add("retire") },
            recreate = { events.add("recreate") },
            finish = { events.add("finish") })
        assertTrue(events.isEmpty())
    }

    @Test
    fun selectingTheCurrentClosedInstanceCreatesAFreshScopeAfterRetirement() {
        val events = mutableListOf<String>()
        LocalEditorReentry.select("instance-a", "instance-a", true,
            retire = { events.add("retire") },
            recreate = { events.add("recreate") },
            finish = { events.add("finish") })
        assertEquals(listOf("retire", "recreate"), events)
    }

    @Test
    fun missingOrBlankSelectionRetiresAndFinishesWithoutReopeningAnOrigin() {
        for (selection in listOf(null, "", "  ")) {
            val events = mutableListOf<String>()
            LocalEditorReentry.select("instance-a", selection, false,
                retire = { events.add("retire") },
                recreate = { events.add("recreate") },
                finish = { events.add("finish") })
            assertEquals(listOf("retire", "finish"), events)
        }
    }

    @Test
    fun repeatedInstanceSelectionsRetireEachScopeBeforePublishingTheNext() {
        val events = mutableListOf<String>()
        var current = "instance-a"
        for (selection in listOf("instance-b", "instance-c")) {
            LocalEditorReentry.select(current, selection, false,
                retire = { events.add("retire-$current") },
                recreate = { current = selection; events.add("recreate-$current") },
                finish = { events.add("finish") })
        }
        assertEquals(listOf("retire-instance-a", "recreate-instance-b",
            "retire-instance-b", "recreate-instance-c"), events)
    }
}
