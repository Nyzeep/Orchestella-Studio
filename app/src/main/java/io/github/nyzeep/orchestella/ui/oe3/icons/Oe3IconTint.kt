package io.github.nyzeep.orchestella.ui.oe3.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The one tint rule for Oe3 icons.
 *
 * Unselected glyphs use `onSurfaceVariant`; the selected one uses `primary`.
 * There is deliberately no per-icon colour and no multi-colour variant: brand
 * marks follow the same rule as line glyphs. This package reads the theme
 * directly instead of the Oe3 token layer so it can be built in parallel with
 * the design foundation.
 */
object Oe3IconTint {

    /** Colour a glyph should use in [selected] state. */
    @Composable
    fun of(selected: Boolean): Color =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
}

/**
 * 24dp Oe3 glyph at the package tint rule.
 *
 * [contentDescription] is passed through to [Icon]; pass null for decorative
 * marks that sit next to a text label, and a string for standalone actions.
 *
 * When the glyph sits inside a Material component that owns its content colour
 * (a disabled `IconButton`, a filled `Button`, a `NavigationBarItem`), pass
 * `tint = LocalContentColor.current` so the component's disabled alpha and
 * container contrast survive.
 */
@Composable
fun Oe3Icon(
    image: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color = Oe3IconTint.of(selected),
) {
    Icon(
        imageVector = image,
        contentDescription = contentDescription,
        modifier = modifier.size(Oe3IconStyle.size),
        tint = tint,
    )
}
