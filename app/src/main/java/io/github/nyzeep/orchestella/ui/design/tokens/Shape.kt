package io.github.nyzeep.orchestella.ui.design.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one corner ladder for the whole app (UI-01 single source of truth).
 *
 * Structure is expressed by surface tone, never by an outline, so these radii
 * carry all of the shape language. [groupedOuter] / [groupedInner] encode the
 * grouped-list rule: one clipped container with the outer radius, rows
 * themselves stay square. [alert] is the v4 dialog radius; cards keep [xl]
 * (24dp) — the ladder is additive, never a renumbering.
 */
object OeShape {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp

    /** v4 dialog / alert surface radius. */
    val alert = 32.dp

    val groupedOuter = xl
    val groupedInner = 0.dp

    fun insetRadius(outerRadius: Dp, inset: Dp): Dp = (outerRadius - inset).coerceAtLeast(0.dp)

    /** A true capsule for the floating bar, action bubbles and switches. */
    val pill: Shape = RoundedCornerShape(percent = 50)
}
