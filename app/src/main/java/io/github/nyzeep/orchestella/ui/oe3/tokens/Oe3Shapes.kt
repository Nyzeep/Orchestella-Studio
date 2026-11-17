package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Oe3 corner ladder. Structure is expressed by surface tone, never by an
 * outline, so these radii carry all of the shape language.
 *
 * [groupedOuter] / [groupedInner] encode the grouped-list rule: one clipped
 * container with the outer radius, rows themselves stay square.
 */
object Oe3Shapes {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp

    val groupedOuter = xl
    val groupedInner = 0.dp

    /** A true capsule for the floating bar, action bubbles and switches. */
    val pill: Shape = RoundedCornerShape(percent = 50)
}
