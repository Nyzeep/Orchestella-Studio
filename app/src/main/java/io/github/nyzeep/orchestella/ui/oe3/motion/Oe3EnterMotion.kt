package io.github.nyzeep.orchestella.ui.oe3.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3MotionTokens

/**
 * Pure mapping from the Oe3 motion tokens to the two entrance curves that had
 * no consumer before ticket ZD-W1 (spec §2.5): [cardEnter] for the first
 * screen of instance cards, [dialog] for floating layers. Both are fade-ins
 * that scale from the tokens' shared 0.96 scale-from, and both collapse to
 * 0ms / scale 1.0 under reduced motion through [Oe3MotionTokens].
 */
object Oe3CardEnterSpec {

    /** The 300ms decelerate card fade, from the token. */
    fun fade(motion: Oe3MotionTokens): TweenSpec<Float> = tween(
        durationMillis = motion.cardEnter.durationMillis,
        easing = motion.cardEnter.easing,
    )

    /** The 300ms decelerate card scale, from the token. */
    fun scale(motion: Oe3MotionTokens): TweenSpec<Float> = tween(
        durationMillis = motion.cardEnter.durationMillis,
        easing = motion.cardEnter.easing,
    )

    /** 0.96f documented start scale; 1.0f (no scale) under reduced motion. */
    fun scaleFrom(motion: Oe3MotionTokens): Float = motion.cardEnterScaleFrom
}

object Oe3DialogEnterSpec {

    /** The 250ms easeOutCubic floating-layer fade, from the token. */
    fun fade(motion: Oe3MotionTokens): TweenSpec<Float> = tween(
        durationMillis = motion.dialog.durationMillis,
        easing = motion.dialog.easing,
    )

    /** The 250ms easeOutCubic floating-layer scale, from the token. */
    fun scale(motion: Oe3MotionTokens): TweenSpec<Float> = tween(
        durationMillis = motion.dialog.durationMillis,
        easing = motion.dialog.easing,
    )

    /**
     * The scale a floating layer starts from. The tokens define one scale-from
     * ([Oe3MotionTokens.cardEnterScaleFrom]); the design-site dialog keyframe
     * uses the same 0.96→1 curve, so the floating layer reuses it instead of
     * introducing a second constant. Under reduced motion it is 1.0f.
     */
    fun scaleFrom(motion: Oe3MotionTokens): Float = motion.cardEnterScaleFrom
}

/**
 * 卡片入场（spec §2.5：300ms decelerate，scale 0.96→1 + fade）。
 *
 * Plays once per [entered] lifetime: the host screen flips the flag after the
 * first composition, so cards scrolled in later — or restored across tab
 * switches through the host's `rememberSaveable` — never replay the entrance
 * (网格内换行/插入不做动画，components/grid-card.md)。The Oe3 card primitives
 * stay animation-free; the entrance belongs to the page-level consumption.
 *
 * Under reduced motion the state starts visible, so the content is final at
 * the very first frame — no clock advancement needed, which keeps Roborazzi
 * goldens and semantics tests deterministic.
 */
@Composable
fun Oe3CardEnterMotion(
    entered: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = Oe3ThemeValues.motion
    val appeared = remember {
        MutableTransitionState(entered || motion.cardEnter.durationMillis == 0)
            .apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = appeared,
        modifier = modifier,
        enter = fadeIn(Oe3CardEnterSpec.fade(motion)) +
            scaleIn(
                initialScale = Oe3CardEnterSpec.scaleFrom(motion),
                animationSpec = Oe3CardEnterSpec.scale(motion),
            ),
    ) {
        content()
    }
}

/**
 * 浮层进场（spec §2.5 对话框/底部表行：250ms easeOutCubic）。
 *
 * Compose primitives only: the Material3 `AlertDialog`/`ModalBottomSheet`
 * hosts are not forked, so the token plays on the floating layer's content
 * while the window/sheet keeps its stock behaviour — the platform window pop,
 * the sheet's own slide. Exit is intentionally not animated: hosts close by
 * removing the layer from composition, so an exit tween would never be seen.
 *
 * Under reduced motion the state starts visible (content final at the first
 * frame) — same determinism guarantee as [Oe3CardEnterMotion].
 */
@Composable
fun Oe3DialogEnterMotion(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = Oe3ThemeValues.motion
    val appeared = remember {
        MutableTransitionState(motion.dialog.durationMillis == 0).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = appeared,
        modifier = modifier,
        enter = fadeIn(Oe3DialogEnterSpec.fade(motion)) +
            scaleIn(
                initialScale = Oe3DialogEnterSpec.scaleFrom(motion),
                animationSpec = Oe3DialogEnterSpec.scale(motion),
            ),
    ) {
        content()
    }
}
