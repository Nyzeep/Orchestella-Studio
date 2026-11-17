package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes

/**
 * The five page composables the shell renders in its pager, supplied by the
 * host activity (UI3-12 wires the real screens). A null slot renders the
 * pager's placeholder page: previews, goldens and the brief first frame before
 * a page's state snapshot lands all fall back to it.
 */
@Immutable
data class Oe3Pages(
    val workstation: (@Composable () -> Unit)? = null,
    val instances: (@Composable () -> Unit)? = null,
    val hardware: (@Composable () -> Unit)? = null,
    val distros: (@Composable () -> Unit)? = null,
    val config: (@Composable () -> Unit)? = null,
) {
    /** Maps a tab to its slot, so the pager needs no per-tab branching. */
    fun slot(tab: Oe3Tab): (@Composable () -> Unit)? = when (tab) {
        Oe3Tab.Workstation -> workstation
        Oe3Tab.Instances -> instances
        Oe3Tab.Hardware -> hardware
        Oe3Tab.Distros -> distros
        Oe3Tab.Config -> config
    }
}

/** Reserve for one page above the bottom bar, per the active nav geometry. */
fun pageBottomReserve(navStyle: Oe3NavStyle, systemBottom: Dp): Dp = when (navStyle) {
    Oe3NavStyle.Floating -> Oe3Sizes.navBar + Oe3Sizes.navBottomInset(systemBottom)
    Oe3NavStyle.Fixed -> Oe3Sizes.navBar + systemBottom
}
