package io.github.nyzeep.orchestella.ui.oe3.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes

/**
 * 二级页返回钮：**48dp 命中区 + 44dp 视觉 + 24dp 字形**（shape.md §3「每个
 * 可点击元素命中区 ≥48dp」）。
 *
 * 之前主题页与连接页各写了一份 44dp 的盒子当命中区——视觉尺寸被当成了触控
 * 尺寸，a11y 上少 4dp。这里把两者收成同一原语：盒子先取 [Oe3Sizes.minTouchTarget]，
 * 内层才是 [Oe3Sizes.interactiveVisual]，与 `OeScaffoldBar` 的动作槽同构。
 *
 * [testTag] 由调用方给（`theme-studio:back` / `connections:back`）：标签是页面
 * 契约的一部分，不能因为换实现而改名。文案同理，由调用方传已本地化的
 * [contentDescription]。
 */
@Composable
fun Oe3BackButton(
    onBack: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val color = Oe3ThemeValues.colors.onSurface.oe3Color()
    Box(
        modifier = modifier
            .size(Oe3Sizes.minTouchTarget)
            .then(testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .clickable(role = Role.Button, onClick = onBack)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(Oe3Sizes.interactiveVisual),
            contentAlignment = Alignment.Center,
        ) {
            // 字形走图标管线（Oe3Icons.Back，1.75dp 描边、圆角端点），与本页
            // 旧手绘 chevron 同一几何。描边原语只属于 icons 目录——这是
            // Oe3StructureTest 的硬约束，也是 Oe3「只用色调表达层级」的语言。
            Oe3Icon(
                image = Oe3Icons.Back,
                contentDescription = null,
                tint = color,
            )
        }
    }
}
