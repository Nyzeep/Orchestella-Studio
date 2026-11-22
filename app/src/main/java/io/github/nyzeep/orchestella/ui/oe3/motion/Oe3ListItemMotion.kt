package io.github.nyzeep.orchestella.ui.oe3.motion

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3MotionTokens

/**
 * 列表项增删与重排的共享动效规格（W2-06 / A4-P4 M5）：
 * 统一消费 [Oe3ThemeValues.motion]（与 [Oe3CardEnterSpec] 等同源，由 `Oe3Theme`
 * 随系统 `ANIMATOR_DURATION_SCALE` 决定标准/减动效实例），时长对齐
 * [OeMotion.EDIT_APPEAR_MS] / [OeMotion.EDIT_REORDER_SETTLE_MS]，减动效下全时长归零。
 */
object Oe3ListItemMotionSpec {

    private fun isReduced(motion: Oe3MotionTokens): Boolean =
        motion.cardEnter.durationMillis == 0

    private fun appearFade(motion: Oe3MotionTokens): TweenSpec<Float> = tween(
        durationMillis = if (isReduced(motion)) 0 else OeMotion.EDIT_APPEAR_MS,
        easing = OeMotion.easeOutCubicEasing,
    )

    fun fadeIn(motion: Oe3MotionTokens): TweenSpec<Float> = appearFade(motion)

    fun placement(motion: Oe3MotionTokens): TweenSpec<IntOffset> = tween(
        durationMillis = if (isReduced(motion)) 0 else OeMotion.EDIT_REORDER_SETTLE_MS,
        easing = OeMotion.standardEasing,
    )

    fun fadeOut(motion: Oe3MotionTokens): TweenSpec<Float> = appearFade(motion)
}

/**
 * 为 [LazyItemScope] 项附加令牌化的增删与重排动画，替代裸 `.animateItem()`。
 */
@Composable
fun LazyItemScope.oe3AnimateListItem(modifier: Modifier = Modifier): Modifier {
    val motion = Oe3ThemeValues.motion
    return modifier.animateItem(
        fadeInSpec = Oe3ListItemMotionSpec.fadeIn(motion),
        placementSpec = Oe3ListItemMotionSpec.placement(motion),
        fadeOutSpec = Oe3ListItemMotionSpec.fadeOut(motion),
    )
}
