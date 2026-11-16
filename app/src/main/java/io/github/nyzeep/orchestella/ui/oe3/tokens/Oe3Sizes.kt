package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed component sizes shared by the Oe3 primitives (spec §2.3). */
object Oe3Sizes {
    val topBar = 72.dp
    val brandLeft = 20.dp

    val navBar = 62.dp
    val navSideMargin = 21.dp
    val navBottomMargin = 21.dp
    val navPadding = 4.dp
    val navPill = 54.dp

    /** Total overhang of the selected pill over its cell, 4dp on each side. */
    val navPillOverhang = 8.dp
    val navIcon = 24.dp
    val navIconLabelGap = 2.dp

    val icon = 24.dp

    /** WCAG 2.5.5 / Material minimum; every interactive primitive meets it. */
    val minTouchTarget = 48.dp

    val button = 48.dp
    val chip = 32.dp

    val dividerThickness = 0.75.dp
    val dividerStart = 56.dp
    val dividerEnd = 14.dp

    /** Bottom bar offset: at least 21dp, and clear of the system bar by 8dp. */
    fun navBottomInset(systemBottom: Dp): Dp = maxOf(navBottomMargin, systemBottom + 8.dp)

    /** Rows and buttons grow to the 48dp floor; already-tall primitives keep their size. */
    fun interactiveMinHeight(visual: Dp): Dp = maxOf(visual, minTouchTarget)
}
