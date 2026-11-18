package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.ui.design.tokens.OeSpacing
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3BackButton
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Divider
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ListRow
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SectionHeader
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.screens.common.OeChevron
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/** 页面标题下的元信息与内联「当前值」读数（spec §2.3：13/18）。 */
internal val MetaTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/**
 * One radio-style option row: ≥48dp, a 30dp sketch, a selected check and one
 * merged accessibility node. The current value is mirrored in the section
 * header, where a screen reader reaches it without entering the choices.
 */
@Composable
fun ThemeOptionRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    thumbnail: @Composable () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ThemeStudioSpec.optionRowMinHeight)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Oe3Spacing.rowHorizontal, vertical = OeSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                color = colors.onSurface.oe3TextColor(enabled),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3TextColor(enabled),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        thumbnail()
        Spacer(Modifier.width(Oe3Spacing.sm))
        if (selected) {
            CheckMark(Color(colors.primary))
        } else {
            Spacer(Modifier.size(18.dp))
        }
    }
}

/** Section title plus the current value on the same baseline. */
@Composable
fun ThemeSectionHeader(
    key: String,
    title: String,
    value: String? = null,
    valueTag: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    Oe3SectionHeader(
        title = title,
        modifier = modifier.testTag("theme-section:$key"),
        action = {
            if (value != null) {
                Text(
                    text = value,
                    modifier = valueTag?.let { Modifier.testTag(it) } ?: Modifier,
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3TextColor(true),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

/** Grouped card that follows the Theme Studio radius choice. */
@Composable
fun ThemeOptionGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val radius = LocalOe3CornerRadius.current ?: Oe3Shapes.xl
    Oe3Card(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(radius),
        content = content,
    )
}

@Composable
fun OptionDivider() {
    Oe3Divider(startIndent = Oe3Spacing.rowHorizontal, endIndent = Oe3Spacing.rowHorizontal)
}

@Composable
fun CheckMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.16f, size.height * 0.54f)
            lineTo(size.width * 0.42f, size.height * 0.78f)
            lineTo(size.width * 0.84f, size.height * 0.24f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/**
 * 顶栏 leading 槽里的返回钮；视觉 44dp（spec §1.2 槽位尺寸），返回语义与
 * 系统返回一致（壳层栈 pop）。
 */
@Composable
fun ThemeBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Oe3BackButton(
        onBack = onBack,
        contentDescription = stringResource(R.string.ui3_theme_back),
        modifier = modifier,
        testTag = "theme-studio:back",
    )
}

/**
 * 根页的一条入口：标题 + 当前值 + 右向 chevron，整行 56dp 可点。
 * [valueTag] 给「当前值」留稳定锚点（读屏与语义测试都不必猜文案）。
 */
@Composable
fun ThemeEntryRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    valueTag: String? = null,
) {
    val colors = Oe3ThemeValues.colors
    Oe3ListRow(
        title = title,
        modifier = modifier.testTag(tag),
        onClick = onClick,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
            ) {
                if (value != null) {
                    Text(
                        text = value,
                        modifier = valueTag?.let { Modifier.testTag(it) } ?: Modifier,
                        style = MetaTextStyle,
                        color = colors.onSurfaceVariant.oe3Color(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OeChevron(color = colors.onSurfaceVariant.oe3Color())
            }
        },
    )
}

private fun Int.oe3Color(): Color = Color(this)

private fun Int.oe3TextColor(enabled: Boolean): Color =
    Color(this).copy(alpha = if (enabled) 1f else 0.45f)
