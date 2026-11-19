package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Top content padding the pages must reserve for the overlaid status bar and
 * integrated [OeScaffold] header. Provided by `OeScaffold` at runtime.
 */
val LocalOe3PageTopPadding = staticCompositionLocalOf { 0.dp }

/**
 * Bottom content padding the pages must reserve for the bottom bar.
 *
 * The shell publishes the reserve per the active nav geometry (floating keeps
 * the ≥21dp margin; fixed adds the exact system inset). Pages read this
 * instead of hardcoding [Oe3Spacing.contentBottom], which stays the provider
 * default — under zero insets both spellings agree, so goldens keep replaying.
 *
 * Lives in the token layer so pages never import from the shell package.
 */
val LocalOe3PageBottomPadding = staticCompositionLocalOf {
    Oe3Spacing.contentBottom
}
