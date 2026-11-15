package io.github.nyzeep.orchestella.ui.design

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.theme.ThemeColors

/**
 * Oe v2 component set. Every component takes structural colors from
 * [MaterialTheme.colorScheme]; theme accents are small-area only (capsules,
 * switches, selected rows, single numbers), never a full card fill.
 *
 * Semantic chip tones (positive / warning / danger) intentionally use the
 * shipped status palette instead of theme roles: "running" must stay green in
 * every one of the 30+ palettes and in both brightness modes, exactly like the
 * XML status badges. Those values are contrast-corrected against the current
 * surface, never hardcoded here.
 */

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

/**
 * A scrolling page body. Responsibility boundary:
 *
 * - [OePage] does **not** consume status-bar or navigation-bar insets; the app
 *   shell owns those exactly once.
 * - [title] is optional. With a global floating top capsule (shell), pass
 *   `title = null` and let the shell draw the capsule. Sub-pages that have no
 *   shell capsule pass a title and get a 56dp header with an optional 44dp back
 *   button and an action slot.
 * - Bottom content padding reserves [bottomClearance] (96dp by default) so the
 *   last row can scroll clear of the floating dock.
 */
@Composable
fun OePage(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    bottomClearance: Dp = OeSize.dockClearance,
    content: LazyListScope.() -> Unit,
) {
    val hasHeader = title != null || onBack != null || action != null
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = OeSpace.page,
                end = OeSpace.page,
                top = if (hasHeader) OeSize.topBar + OeSpace.headerBottom else OeSpace.headerTop,
                bottom = bottomClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(OeSpace.gap),
            content = content,
        )
        if (hasHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OeSize.topBar)
                    .background(
                        Brush.verticalGradient(
                            0f to MaterialTheme.colorScheme.background,
                            0.72f to MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                            1f to MaterialTheme.colorScheme.background.copy(alpha = 0f),
                        ),
                    )
                    .padding(horizontal = OeSpace.page),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    val view = LocalView.current
                    Box(
                        modifier = Modifier
                            .size(OeSize.tap)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                            ) {
                                OeHaptics.tick(view)
                                onBack()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(OeIcons.Back),
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(OeSize.icon),
                        )
                    }
                    Spacer(Modifier.width(OeSpace.gap))
                }
                if (title != null) {
                    Text(
                        text = title,
                        style = OeType.pageTitle(),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                action?.invoke()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Section + card
// ---------------------------------------------------------------------------

/**
 * A titled group: the title sits **outside** the card (15/600, secondary), the
 * card is one unbroken [OeShape.card] surface with a single hairline. Content
 * must emit rows directly (not wrapped in a Column): the section measures each
 * child and draws a 56dp-inset divider between neighbours, which is how the
 * divider can skip the last row without every caller remembering to.
 *
 * [titleTopPadding] defaults to [OeSpace.headerTop] minus [OeSpace.gap] because
 * [OePage] already contributes one [OeSpace.gap] before every item, so the
 * visible space above a title is the specified 24dp.
 */
@Composable
fun OeSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    titleTopPadding: Dp = OeSpace.headerTop - OeSpace.gap,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(OeShape.card)
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = OeType.section(),
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    top = titleTopPadding,
                    bottom = OeSpace.headerBottom,
                ),
            )
        }
        val dividerColor = scheme.outlineVariant
        val layoutDirection = LocalLayoutDirection.current
        val density = LocalDensity.current
        val dividerYs = remember { mutableListOf<Int>() }
        Layout(
            content = content,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(scheme.surface)
                .border(BorderStroke(OeSpace.hairline, scheme.outlineVariant), shape)
                .drawWithContent {
                    drawContent()
                    if (dividerYs.isEmpty()) return@drawWithContent
                    val startInset = OeSpace.rowDividerInset.toPx()
                    val endInset = OeSpace.card.toPx()
                    val stroke = OeSpace.hairline.toPx()
                    val startX = if (layoutDirection == LayoutDirection.Rtl) {
                        size.width - startInset
                    } else {
                        startInset
                    }
                    val endX = if (layoutDirection == LayoutDirection.Rtl) {
                        endInset
                    } else {
                        size.width - endInset
                    }
                    dividerYs.forEach { y ->
                        drawLine(
                            color = dividerColor,
                            start = Offset(startX, y.toFloat()),
                            end = Offset(endX, y.toFloat()),
                            strokeWidth = stroke,
                        )
                    }
                },
        ) { measurables, constraints ->
            dividerYs.clear()
            val placeables = measurables.map {
                it.measure(constraints.copy(minWidth = 0, minHeight = 0))
            }
            var total = 0
            placeables.forEachIndexed { index, placeable ->
                if (index > 0) dividerYs += total
                total += placeable.height
            }
            val width = if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                placeables.maxOfOrNull { it.width } ?: 0
            }
            layout(width, total) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height
                }
            }
        }
        if (footer != null) {
            Text(
                text = footer,
                style = OeType.caption(),
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = OeSpace.headerBottom),
            )
        }
    }
}

/**
 * A single-hairline card. Press feedback is geometry only — scale 0.98 plus a
 * 4% shift toward the content color — never a glow or a second border.
 */
@Composable
fun OeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(OeSpace.card),
    shape: Shape = RoundedCornerShape(OeShape.card),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressFraction by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(OeMotion.press),
        label = "oeCardPress",
    )
    val scheme = MaterialTheme.colorScheme
    val scale = 1f - 0.02f * pressFraction
    val fill = lerp(scheme.surface, scheme.onSurface, 0.04f * pressFraction)
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(fill)
            .border(BorderStroke(OeSpace.hairline, scheme.outlineVariant), shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (onClick != null) {
                    // A clickable card reads as one node; inner interactive
                    // elements are not expected in this slot by contract.
                    Modifier.semantics(mergeDescendants = true) {}
                } else {
                    Modifier
                },
            )
            .padding(padding),
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Row
// ---------------------------------------------------------------------------

/**
 * A list row. 56dp minimum (48 dense), 15/500 title, 13 secondary subtitle.
 * The leading slot is 24dp and always followed by a 16dp gap, which is why the
 * section divider starts at 56dp. A row with [trailing] (for example a switch)
 * should normally omit [onClick]; otherwise the row and the control answer the
 * same tap twice.
 */
@Composable
fun OeRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    dense: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (dense) OeSize.rowDense else OeSize.row)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(
                if (selected) {
                    Modifier.semantics { this.selected = true }
                } else {
                    Modifier
                },
            )
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = OeSpace.page, vertical = OeSpace.rowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier.size(OeSize.icon),
                contentAlignment = Alignment.Center,
            ) {
                leading()
            }
            Spacer(Modifier.width(OeSpace.leadingGap))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = OeType.row(),
                color = if (selected) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = OeType.body(),
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(OeSpace.gap))
            Icon(
                painter = painterResource(OeIcons.Check),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(OeSize.chevron),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(OeSpace.gap))
            trailing()
        } else if (!selected && onClick != null) {
            Spacer(Modifier.width(OeSpace.gap))
            OeChevron(tint = scheme.onSurfaceVariant)
        }
    }
}

/** 20dp chevron drawn in the same 1.8dp round-cap language as [OeIcons]. */
@Composable
private fun OeChevron(tint: Color) {
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(
        modifier = Modifier
            .size(OeSize.chevron)
            .graphicsLayer { scaleX = if (mirrored) -1f else 1f },
    ) {
        val stroke = 1.8.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.34f, size.height * 0.22f)
            lineTo(size.width * 0.62f, size.height * 0.5f)
            lineTo(size.width * 0.34f, size.height * 0.78f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// ---------------------------------------------------------------------------
// Switch
// ---------------------------------------------------------------------------

/**
 * 52x32 switch with a 20dp thumb. On = primary track, off = surfaceVariant.
 * Toggling fires [OeHaptics.select] (a keyboard-tap tick), which is silenced by
 * both the in-app switch and the system haptic setting.
 */
@Composable
fun OeSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val view = LocalView.current
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) OeSize.switchTrackWidth - OeSize.switchThumb - OeSize.switchInset * 2 else 0.dp,
        animationSpec = tween(OeMotion.switch),
        label = "oeSwitchThumb",
    )
    val trackColor = when {
        !enabled -> scheme.surfaceVariant.copy(alpha = 0.5f)
        checked -> scheme.primary
        else -> scheme.surfaceVariant
    }
    val thumbColor = when {
        !enabled -> scheme.surface.copy(alpha = 0.6f)
        checked -> scheme.onPrimary
        else -> scheme.surface
    }
    Box(
        modifier = modifier
            // The visual track stays 52x32; the touch target is 44dp tall so
            // the switch remains comfortable without changing its geometry.
            .size(OeSize.switchTrackWidth, OeSize.tap)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    OeHaptics.select(view)
                    onCheckedChange(it)
                },
            )
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .padding(vertical = (OeSize.tap - OeSize.switchTrackHeight) / 2),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(OeSize.switchTrackHeight)
                .clip(CircleShape)
                .background(trackColor)
                .padding(OeSize.switchInset),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(OeSize.switchThumb)
                    .clip(CircleShape)
                    .background(thumbColor),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Segmented control
// ---------------------------------------------------------------------------

/**
 * Equal-width segmented control on a surfaceVariant track with the selected
 * segment painted in primary. The track radius follows the user's control
 * preference (the ticket explicitly puts segmented controls in that scope), so
 * at the default 16dp on a 40dp track it reads as a pill; an 8dp preference
 * deliberately makes it squarer. Fires [OeHaptics.select] on change.
 */
@Composable
fun OeSegmented(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (items.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val view = LocalView.current
    val index = selectedIndex.coerceIn(0, items.lastIndex)
    val outerShape = OeTheme.controlShape
    val segmentShape = RoundedCornerShape((OeTheme.controlCorner - 2.dp).coerceAtLeast(0.dp))
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(OeSize.segmented)
            .clip(outerShape)
            .background(scheme.surfaceVariant)
            .padding(2.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, label ->
            val selected = i == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(OeSize.segmented - 4.dp)
                    .clip(segmentShape)
                    .background(if (selected) scheme.primary else Color.Transparent)
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = {
                            if (i != index) {
                                OeHaptics.select(view)
                                onSelect(i)
                            }
                        },
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = OeType.row(),
                    color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Chip
// ---------------------------------------------------------------------------

enum class OeChipTone { Neutral, Accent, Positive, Warning, Danger }

/**
 * Compact status pill. 11sp, 8x4 padding, [OeShape.badge] corners, sentence
 * case (callers pass normal text; nothing here uppercases).
 */
@Composable
fun OeChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: OeChipTone = OeChipTone.Neutral,
) {
    val scheme = MaterialTheme.colorScheme
    val (background, foreground) = when (tone) {
        OeChipTone.Neutral -> scheme.surfaceVariant to scheme.onSurfaceVariant
        OeChipTone.Accent -> tinted(scheme.primary, scheme.surface)
        OeChipTone.Positive -> tinted(colorResource(R.color.status_running), scheme.surface)
        OeChipTone.Warning -> tinted(colorResource(R.color.status_suspended), scheme.surface)
        OeChipTone.Danger -> tinted(colorResource(R.color.status_crashed), scheme.surface)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(OeShape.badge))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = OeType.caption(),
            color = foreground,
            maxLines = 1,
        )
    }
}

/**
 * 14% semantic tint over the current surface plus a contrast-corrected label,
 * so the same green/amber/red reads on both dark and light palettes.
 */
private fun tinted(base: Color, surface: Color): Pair<Color, Color> {
    val blended = ThemeColors.blend(surface.toArgb(), base.toArgb(), 0.14f)
    val readable = ThemeColors.readable(base.toArgb(), blended)
    return Color(blended) to Color(readable)
}

// ---------------------------------------------------------------------------
// Button
// ---------------------------------------------------------------------------

enum class OeButtonStyle { Primary, Secondary, Ghost }

private data class OeButtonSpec(
    val height: Dp,
    val background: Color,
    val foreground: Color,
)

@Composable
private fun oeButtonSpec(style: OeButtonStyle): OeButtonSpec {
    val scheme = MaterialTheme.colorScheme
    return when (style) {
        OeButtonStyle.Primary -> OeButtonSpec(OeSize.buttonPrimary, scheme.primary, scheme.onPrimary)
        OeButtonStyle.Secondary -> OeButtonSpec(OeSize.buttonSecondary, scheme.surfaceVariant, scheme.onSurface)
        OeButtonStyle.Ghost -> OeButtonSpec(OeSize.buttonGhost, Color.Transparent, scheme.primary)
    }
}

/**
 * Three-height action button (48 / 44 / 36). [icon] is a vector drawable id;
 * the tint follows the text color so one asset works on every style.
 */
@Composable
fun OeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: OeButtonStyle = OeButtonStyle.Primary,
    icon: Int? = null,
    enabled: Boolean = true,
) {
    val spec = oeButtonSpec(style)
    Row(
        modifier = modifier
            .height(spec.height)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .clip(OeTheme.controlShape)
            .background(spec.background)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (style == OeButtonStyle.Ghost) OeSpace.gap else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = spec.foreground,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = OeType.row(),
            color = spec.foreground,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

/**
 * Empty state: 96dp squircle badge (12% primary + 40dp icon), 17/600 title,
 * up to two lines of explanation, and a caller-supplied action (usually an
 * [OeButton]).
 */
@Composable
fun OeEmptyState(
    icon: Int,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OeSpace.group, vertical = OeSpace.group),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(OeSize.emptyBadge)
                .clip(RoundedCornerShape(OeShape.badgeHero))
                .background(scheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(OeSize.iconLarge),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = OeType.emptyTitle(),
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = OeType.body(),
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

// ---------------------------------------------------------------------------
// Stat block
// ---------------------------------------------------------------------------

/**
 * In-card data block: label 12 secondary, value 28/600 tabular, optional unit,
 * and a 40x24 sparkline (no axes) in the lower-right corner.
 */
@Composable
fun OeStatBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    sparkline: List<Float>? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(OeShape.block))
            .background(scheme.surfaceVariant)
            .semantics(mergeDescendants = true) {}
            .padding(OeSpace.card),
    ) {
        Text(
            text = label,
            style = OeType.label(),
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = OeType.statValue(),
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = OeType.label(),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        if (sparkline != null) {
            Spacer(Modifier.height(8.dp))
            OeSparklineGraph(
                values = sparkline,
                color = scheme.primary,
                modifier = Modifier
                    .align(Alignment.End)
                    .size(OeSize.sparklineWidth, OeSize.sparklineHeight),
            )
        }
    }
}

@Composable
private fun OeSparklineGraph(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val points = OeSparkline.normalize(values)
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val stroke = 1.8.dp.toPx()
        if (points.size == 1) {
            drawCircle(color = color, radius = stroke, center = Offset(size.width / 2f, size.height / 2f))
            return@Canvas
        }
        val path = Path()
        points.forEachIndexed { index, value ->
            val x = size.width * index / (points.size - 1).toFloat()
            val y = size.height - value * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
