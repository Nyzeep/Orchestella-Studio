package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shell navigation state, hoisted out of the composition so the Activity can
 * save it and the back dispatcher can drive it without a second source of
 * truth. Selection is a single property, so "exactly one tab selected" holds
 * by construction rather than by convention.
 */
class Oe3ShellController(
    initialTab: Oe3Tab = Oe3Tab.Workstation,
) {
    var selectedTab: Oe3Tab by mutableStateOf(initialTab)
        private set

    fun select(tab: Oe3Tab) {
        selectedTab = tab
    }

    /**
     * Back semantics from the ticket: a later tab returns to the workstation.
     *
     * @return true when this controller consumed the event; false on the first
     *   tab, where the system's own back behavior must run.
     */
    fun back(): Boolean {
        if (selectedTab == Oe3Tab.Workstation) return false
        selectedTab = Oe3Tab.Workstation
        return true
    }

    companion object {
        const val STATE_KEY = "oe3_tab"

        fun restore(savedKey: String?): Oe3Tab = Oe3Tab.fromKey(savedKey)
    }
}
