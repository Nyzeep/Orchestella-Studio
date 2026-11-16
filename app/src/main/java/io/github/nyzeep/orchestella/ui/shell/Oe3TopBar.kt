package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/**
 * Oe3 top bar: the product name and nothing else (spec §2.3, §3.2).
 *
 * It owns the status-bar inset, so the 72dp content box always starts below
 * the system bar while the root stays edge-to-edge.
 */
@Composable
fun Oe3TopBar(modifier: Modifier = Modifier) {
    val colors = Oe3ThemeValues.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(Oe3Sizes.topBar)
            .padding(start = Oe3Sizes.brandLeft, end = Oe3Spacing.pageInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "Orchestella",
            style = Oe3Typography.style(Oe3TextRole.Brand),
            color = Color(colors.onSurface),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
