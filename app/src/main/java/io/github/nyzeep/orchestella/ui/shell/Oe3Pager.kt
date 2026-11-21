package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageTopPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3MotionTokens
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/**
 * Pure mapping from the Oe3 motion tokens to the page transition curves
 * (spec §2.5, ticket ZD-W1). The entering page fades on the pageFade curve
 * and slides on the pageSlide curve; the leaving page keeps the plain fade.
 * The spec pins duration and easing only — the slide distance is a quarter of
 * the page width, kept subtle so the fade stays the leading motion.
 */
object Oe3PageTransitionSpec {

    /** Slide distance as a fraction of the page width. */
    const val SLIDE_DISTANCE_FRACTION = 0.25f

    /** The 220ms easeInOut page fade, from the token. */
    fun fade(motion: Oe3MotionTokens) = tween<Float>(
        durationMillis = motion.pageFade.durationMillis,
        easing = motion.pageFade.easing,
    )

    /** The 300ms decelerate page slide, from the token. */
    fun slide(motion: Oe3MotionTokens) = tween<IntOffset>(
        durationMillis = motion.pageSlide.durationMillis,
        easing = motion.pageSlide.easing,
    )

    /**
     * Travel direction between two tabs: +1 when the target tab sits to the
     * right, -1 to the left, 0 when the tab does not change.
     */
    fun slideDirection(fromOrdinal: Int, toOrdinal: Int): Int =
        (toOrdinal - fromOrdinal).coerceIn(-1, 1)

    /** Start offset of the entering page, toward the direction of travel. */
    fun slideOffsetX(pageWidth: Int, direction: Int): Int =
        (pageWidth * SLIDE_DISTANCE_FRACTION).toInt() * direction

    /** Exit offset of the leaving page, moving away from the entering page. */
    fun exitSlideOffsetX(pageWidth: Int, direction: Int): Int =
        -slideOffsetX(pageWidth, direction)
}

/**
 * Page container (ticket UI3-02 §4, motion per spec §2.5): the entering page
 * fades on the pageFade curve and slides in from the direction of the tab
 * change on the pageSlide curve; the leaving page fades out symmetrically in
 * the opposite direction. No horizontal swipe, no shared element. Reduced
 * motion collapses both tokens to 0ms through [Oe3ThemeValues.motion].
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
    val motion = Oe3ThemeValues.motion
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomReserve = pageBottomReserve(navStyle, systemBottom)
    val stateHolder = rememberSaveableStateHolder()

    AnimatedContent(
        targetState = selected,
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
        transitionSpec = {
            val direction = Oe3PageTransitionSpec.slideDirection(
                initialState.ordinal,
                targetState.ordinal,
            )
            val fadeSpec = Oe3PageTransitionSpec.fade(motion)
            val slideSpec = Oe3PageTransitionSpec.slide(motion)
            if (direction == 0) {
                fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec)
            } else {
                (
                    fadeIn(fadeSpec) +
                        slideInHorizontally(slideSpec) { pageWidth ->
                            Oe3PageTransitionSpec.slideOffsetX(pageWidth, direction)
                        }
                    ) togetherWith (
                    fadeOut(fadeSpec) +
                        slideOutHorizontally(slideSpec) { pageWidth ->
                            Oe3PageTransitionSpec.exitSlideOffsetX(pageWidth, direction)
                        }
                    )
            }
        },
        label = "oe3Pager",
    ) { tab ->
        stateHolder.SaveableStateProvider(key = tab.key) {
            CompositionLocalProvider(LocalOe3PageBottomPadding provides bottomReserve) {
                // 底片必须挂在每一页内部：pager 的交叉淡化会让两页同时
                // 在屏，透明页叠化就是"残影/糊"的另一个来源。
                Box(Modifier.fillMaxSize()) {
                    OePagePlate()
                    val page = pages?.slot(tab)
                    if (page != null) {
                        page()
                    } else {
                        Oe3Placeholder(title = tab.label, bottomReserve = bottomReserve)
                    }
                }
            }
        }
    }
}

/**
 * Page stand-in for Wave 2. Page tickets replace this with their own Composable
 * which UI3-12 wires into the pager; this ticket must not create files under
 * `ui/screens`.
 *
 * v4 (UI-02): the placeholder now runs inside the same [OeScaffold] as the real
 * pages, so the shell golden proves the single-title rule instead of the old
 * "brand bar + in-page PageTitle" pair.
 */
@Composable
fun Oe3Placeholder(
    title: String,
    modifier: Modifier = Modifier,
    bottomReserve: Dp = Oe3Spacing.contentBottom,
) {
    val colors = Oe3ThemeValues.colors
    val scrollState = rememberScrollState()
    OeScaffold(
        title = title,
        modifier = modifier,
        scrollOffsetPx = { scrollState.oeScrollOffsetPx() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = Oe3Spacing.pageInset)
                .padding(
                    top = LocalOe3PageTopPadding.current + Oe3Spacing.sm,
                    bottom = bottomReserve,
                ),
        ) {
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
}
