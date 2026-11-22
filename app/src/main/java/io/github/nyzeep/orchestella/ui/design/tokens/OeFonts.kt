package io.github.nyzeep.orchestella.ui.design.tokens

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.nyzeep.orchestella.R

/**
 * The one place that maps shipped font files to Compose families (W1-06).
 *
 * [JetBrainsMono] is JetBrains Mono NL v2.304 (SIL OFL-1.1, pinned in
 * NOTICE-jetbrains-mono.md). The NL variant ships no programming ligatures, so
 * terminal per-cell indexing, cursor alignment and selection handles never see
 * a merged glyph; every weight maps to a real shipped TTF instead of a
 * synthesized fake bold. Global body text is untouched — it still follows
 * ThemeStore.KEY_UI_FONT_CHOICE.
 */
object OeFonts {

    val JetBrainsMono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal, FontStyle.Normal),
        Font(R.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold, FontStyle.Normal),
        Font(R.font.jetbrains_mono_bold_italic, FontWeight.Bold, FontStyle.Italic),
    )
}
