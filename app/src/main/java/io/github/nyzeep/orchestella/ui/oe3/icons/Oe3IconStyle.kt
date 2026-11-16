package io.github.nyzeep.orchestella.ui.oe3.icons

import androidx.compose.ui.unit.dp

/**
 * Geometry shared by every Oe3 icon.
 *
 * Oe3 keeps one 24dp grid and one line weight for navigation and actions, so a
 * row of glyphs reads as one family. Distro marks are filled brand outlines
 * instead of strokes, but they use the same viewport and the same tint rule.
 */
object Oe3IconStyle {
    /** Rendered size of every icon in this package. */
    val size = 24.dp

    /** Viewport width/height of the hand-drawn line glyphs. */
    const val viewport = 24f

    /** Visual line weight for navigation and action glyphs, in viewport units. */
    const val strokeWidth = 1.75f
}
