package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.animation.AnimatedContent
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
 * (spec §2.5, ticket ZD-W1; W2-03 retunes the timeline to the fade-through
 * windows 90 out + 20 gap + 210 in).
 *
 * Two consumers with two realizations of the same timeline:
 * - [Oe3Pager] sweeps whole pages **opaquely**: the leaving page exits to the
 *   opposite edge over [OeMotion.PAGE_FADE_EXIT_MS] and the entering page
 *   sweeps in over [OeMotion.PAGE_FADE_ENTER_MS] after
 *   [OeMotion.PAGE_FADE_ENTER_DELAY_MS]. No alpha animation touches a page
 *   subtree — the TransitionGhostPixelTest red-line probe counts any
 *   partial-alpha page pixel as cross-blend ghosting (R-01), and a fading
 *   solid page blends with the stage behind it no matter how the windows are
 *   separated. Opaque [IntOffset] slides keep every mid-frame pixel either a
 *   page color or the stage behind them.
 * - [WizardScreen] fades step content through the same windows (its content
 *   sits on the page plate, so a fade is a plain content fade, and the fade
 *   also hides the slide's delay window).
 */
object Oe3PageTransitionSpec {

    /** Slide distance as a fraction of the page width (wizard steps). */
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

    /** Outgoing fade-through window: 90ms emphasized-accelerate. */
    fun exitFade(motion: Oe3MotionTokens) = tween<Float>(
        durationMillis = motion.pageFadeExit.durationMillis,
        easing = motion.pageFadeExit.easing,
    )

    /** Incoming fade-through window: 210ms emphasized-decelerate, delayed past the exit window. */
    fun enterFade(motion: Oe3MotionTokens) = tween<Float>(
        durationMillis = motion.pageFadeEnter.durationMillis,
        easing = motion.pageFadeEnter.easing,
        delayMillis = motion.pageFadeEnterDelayMillis,
    )

    /** Outgoing slide for the fade-through timeline (same 90ms window). */
    fun exitSlide(motion: Oe3MotionTokens) = tween<IntOffset>(
        durationMillis = motion.pageFadeExit.durationMillis,
        easing = motion.pageFadeExit.easing,
    )

    /** Incoming slide for the fade-through timeline (210ms, delayed past the exit window). */
    fun enterSlide(motion: Oe3MotionTokens) = tween<IntOffset>(
        durationMillis = motion.pageFadeEnter.durationMillis,
        easing = motion.pageFadeEnter.easing,
        delayMillis = motion.pageFadeEnterDelayMillis,
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

    /** W2-03 pager: the entering page starts fully off-screen on the travel side. */
    fun enterSweepStartOffsetX(pageWidth: Int, direction: Int): Int =
        pageWidth * direction

    /** W2-03 pager: the leaving page sweeps fully out toward the opposite side. */
    fun exitSweepTargetOffsetX(pageWidth: Int, direction: Int): Int =
        -pageWidth * direction
}

/**
 * Page container (ticket UI3-02 §4; W2-03 fade-through retune): pages switch
 * through an **opaque** fade-through choreography — the leaving page sweeps
 * fully out toward the opposite edge over the 90ms exit window, the stage
 * rests for the 20ms gap, and the entering page sweeps in from the travel
 * edge over the 210ms window delayed by 110ms (total 320ms =
 * [OeMotion.DURATION_MEDIUM_MS]). No alpha animation touches a page subtree:
 * partial-alpha pages are exactly the cross-blend ghosting (R-01) that
 * `TransitionGhostPixelTest.pagerSwitch_hasNoCrossBlendMix` red-lines, so the
 * timeline is realized with opaque [IntOffset] slides and the windows stay
 * zero-overlap in time. No horizontal swipe, no shared element. Reduced
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
            // 端到端都不会在同标签内触发；方向为 0 时按 +1 兜底，保证分支完整。
            val sweep = if (direction == 0) 1 else direction
            val enterSpec = Oe3PageTransitionSpec.enterSlide(motion)
            val exitSpec = Oe3PageTransitionSpec.exitSlide(motion)
            slideInHorizontally(enterSpec) { pageWidth ->
                Oe3PageTransitionSpec.enterSweepStartOffsetX(pageWidth, sweep)
            } togetherWith slideOutHorizontally(exitSpec) { pageWidth ->
                Oe3PageTransitionSpec.exitSweepTargetOffsetX(pageWidth, sweep)
            }
        },
        label = "oe3Pager",
    ) { tab ->
        stateHolder.SaveableStateProvider(key = tab.key) {
            CompositionLocalProvider(LocalOe3PageBottomPadding provides bottomReserve) {
                // 底片挂在每一页内部：页全程不透明（W2-03 起转场不再做任何
                // 整页 alpha），与根背景逐像素同源，两页交叠窗口互不透底。
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
