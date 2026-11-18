package io.github.nyzeep.orchestella.ui.screens.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes

/**
 * 全仓唯一的 chevron 几何（UI-15 §7）。
 *
 * 清扫前存在三份逐字相同的私有实现：`ConfigScreen` 的 `drawChevron`、
 * `ThemeOptionRow` 的 `drawChevron`、`WizardScreen` 的 `WizardChevron`。
 * 三处都画同一组线（1.75dp 圆帽、x=0.38/0.62、y=0.28/0.5/0.72），但各自
 * 复制一份，任何一处调形都会让「入口箭头」和「返回箭头」不再同族。
 * 收敛到本文件后，页面只表达「左/右 + 颜色 + 语义」，不再持有几何。
 */
fun DrawScope.drawOeChevron(color: Color, pointsLeft: Boolean) {
    val stroke = 1.75.dp.toPx()
    val nearX = size.width * 0.38f
    val farX = size.width * 0.62f
    val top = Offset(if (pointsLeft) farX else nearX, size.height * 0.28f)
    val middle = Offset(if (pointsLeft) nearX else farX, size.height * 0.5f)
    val bottom = Offset(if (pointsLeft) farX else nearX, size.height * 0.72f)
    drawLine(color, top, middle, strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, middle, bottom, strokeWidth = stroke, cap = StrokeCap.Round)
}

/**
 * [contentDescription] 只给「装饰性但仍需读屏命名」的入口行用；纯装饰的返回
 * 箭头由所在按钮自己提供描述，留 `null` 即可（读屏跳过）。
 */
@Composable
fun OeChevron(
    color: Color,
    modifier: Modifier = Modifier,
    pointsLeft: Boolean = false,
    contentDescription: String? = null,
) {
    val semanticsModifier = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(modifier = modifier.size(Oe3Sizes.icon).then(semanticsModifier)) {
        drawOeChevron(color = color, pointsLeft = pointsLeft)
    }
}
