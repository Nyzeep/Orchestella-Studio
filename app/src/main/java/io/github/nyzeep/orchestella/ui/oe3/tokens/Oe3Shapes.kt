package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.ui.graphics.Shape
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape

/**
 * Legacy entry point for the corner ladder. The values live in
 * [OeShape] (`ui/design/tokens`) since UI-01; this object only delegates so
 * existing call sites keep their names and numbers unchanged.
 *
 * [groupedOuter] / [groupedInner] encode the grouped-list rule: one clipped
 * container with the outer radius, rows themselves stay square.
 */
object Oe3Shapes {
    val xs = OeShape.xs
    val sm = OeShape.sm
    val md = OeShape.md
    val lg = OeShape.lg
    val xl = OeShape.xl

    /** v4 dialog radius, forwarded from the single source. */
    val alert = OeShape.alert

    val groupedOuter = OeShape.groupedOuter
    val groupedInner = OeShape.groupedInner

    /** A true capsule for the floating bar, action bubbles and switches. */
    val pill: Shape = OeShape.pill
}
