package io.github.nyzeep.orchestella.ui.design.tokens

import androidx.compose.ui.unit.dp

/**
 * The one spacing rhythm for the whole app (UI-01 single source of truth).
 * [pageInset] is the only page gutter; [contentBottom] reserves room for the
 * floating navigation bar (62dp bar + 21dp margin).
 */
object OeSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp

    val pageInset = 20.dp
    val contentBottom = 83.dp
    val cardPadding = 20.dp

    val rowHeight = 56.dp
    val rowHorizontal = 16.dp
    val iconTextGap = 16.dp

    val sectionTop = 24.dp
    val sectionBottom = 8.dp

    val gridGap = 8.dp
}
