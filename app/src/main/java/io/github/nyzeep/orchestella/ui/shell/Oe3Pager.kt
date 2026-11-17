package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/**
 * Fade-only page container (ticket UI3-02 §4): no horizontal swipe, no shared
 * element, and the duration/easing comes from the Oe3 motion token so reduced
 * motion collapses it to 0ms.
 *
 * UI3-12: [pages] carries the real screens wired by the host activity; `null`
 * keeps the placeholder page so previews and goldens render the bare shell.
 * The bar reserve the pages must keep clear of is published through
 * [LocalOe3PageBottomPadding], derived from the active nav geometry.
 */
@Composable
fun Oe3Pager(
    selected: Oe3Tab,
    modifier: Modifier = Modifier,
    navStyle: Oe3NavStyle = Oe3NavStyle.Floating,
    pages: Oe3Pages? = null,
) {
    val fade = Oe3ThemeValues.motion.pageFade
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomReserve = pageBottomReserve(navStyle, systemBottom)

    AnimatedContent(
        targetState = selected,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            fadeIn(tween(durationMillis = fade.durationMillis, easing = fade.easing)) togetherWith
                fadeOut(tween(durationMillis = fade.durationMillis, easing = fade.easing))
        },
        label = "oe3Pager",
    ) { tab ->
        CompositionLocalProvider(LocalOe3PageBottomPadding provides bottomReserve) {
            val page = pages?.slot(tab)
            if (page != null) {
                page()
            } else {
                Oe3Placeholder(title = tab.label, bottomReserve = bottomReserve)
            }
        }
    }
}

/**
 * Page stand-in for Wave 2. Page tickets replace this with their own Composable
 * which UI3-12 wires into the pager; this ticket must not create files under
 * `ui/screens`.
 */
@Composable
fun Oe3Placeholder(
    title: String,
    modifier: Modifier = Modifier,
    bottomReserve: Dp = Oe3Spacing.contentBottom,
) {
    val colors = Oe3ThemeValues.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Oe3Spacing.pageInset)
            .padding(top = Oe3Spacing.lg, bottom = bottomReserve),
    ) {
        Text(
            text = title,
            style = Oe3Typography.style(Oe3TextRole.PageTitle),
            color = Color(colors.onSurface),
        )
        Spacer(Modifier.height(Oe3Spacing.md))
        Oe3Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "页面占位",
                style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                color = Color(colors.onSurface),
            )
            Spacer(Modifier.height(Oe3Spacing.xs))
            Text(
                text = "内容由后续工单接入。",
                style = Oe3Typography.style(Oe3TextRole.Body),
                color = Color(colors.onSurfaceVariant),
            )
        }
    }
}
