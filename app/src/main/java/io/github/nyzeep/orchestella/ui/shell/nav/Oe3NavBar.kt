package io.github.nyzeep.orchestella.ui.shell.nav

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassSurface
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Glass
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3MotionTokens
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.Oe3NavStyle
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.theme.ThemeColors

/**
 * Selected-pill geometry and motion (spec §2.5, ticket UI3-10).
 *
 * The pill lives inside its cell — the exact structure the previous shell
 * drew — so the resting pixels are unchanged. Selecting another tab re-homes
 * the pill and animates [travel] from the old cell back to zero on the
 * selection spring: the pill visually slides between cells while every
 * static frame stays identical to the shipped bar.
 */
object Oe3NavPillSpec {

    val overhang: Dp = Oe3Sizes.navPillOverhang

    /** The pill bleeds over its cell by [overhang]; the bar capsule clips it. */
    fun width(cellWidth: Dp): Dp = cellWidth + overhang

    /**
     * Where the pill starts when the selection moves from [fromIndex] to
     * [toIndex]: one cell-width per skipped tab, in the direction of travel.
     * No selection change means no travel.
     */
    fun travel(cellWidth: Dp, fromIndex: Int, toIndex: Int): Dp =
        cellWidth * (fromIndex - toIndex)

    /**
     * ≈400ms, dampingRatio 0.85 (spec §2.5). Reduced motion withdraws the
     * spring entirely, so the pill lands instead of springing.
     */
    fun selectionSpring(motion: Oe3MotionTokens): SpringSpec<Float>? = motion.navSelectionSpring

    /** ≈250ms press/release spring at the tokens' damping. */
    fun pressSpring(motion: Oe3MotionTokens): SpringSpec<Float> = spring(
        dampingRatio = motion.press.dampingRatio,
        stiffness = Spring.StiffnessMedium,
    )

    /**
     * Icon/label colour transition: the 300ms standard themeTween colour
     * curve (spec §2.5 主题切换行), instead of the page fade curve it
     * borrowed before the token had a consumer.
     */
    fun colorTransition(motion: Oe3MotionTokens) = tween<Color>(
        durationMillis = motion.themeTween.durationMillis,
        easing = motion.themeTween.easing,
    )

    const val PRESS_SCALE = 0.96f
    const val REST_SCALE = 1f
}

/**
 * 浮动坞的两层投影（v4 spec §1.3 / 工单 UI-03）：black@0.08 blur24 y8 +
 * black@0.04 blur3 y1。坞与卡片的辨识度三要素「高一个色阶 + 阴影 +
 * 外露 21dp」中，阴影由这里给出数值真源；固定坞保留贴边向上淡影，与列表卡片分层。
 *
 * 硬件画布自 API 28 起支持 `BlurMaskFilter`；26/27 上直接跳过投影，
 * 不画硬边黑块。
 */
object Oe3NavShadowSpec {

    /** 一层投影：颜色恒黑，alpha/模糊半径/纵向位移按规格。 */
    data class Layer(val alpha: Float, val blur: Dp, val offsetY: Dp)

    val layers: List<Layer> = listOf(
        Layer(alpha = 0.08f, blur = 24.dp, offsetY = 8.dp),
        Layer(alpha = 0.04f, blur = 3.dp, offsetY = 1.dp),
    )

    private val fixedLayers: List<Layer> = listOf(
        Layer(alpha = 0.10f, blur = 12.dp, offsetY = (-2).dp),
    )

    fun layersFor(style: Oe3NavStyle): List<Layer> = when (style) {
        Oe3NavStyle.Floating -> layers
        Oe3NavStyle.Fixed -> fixedLayers
    }

    /** 浮动与固定底栏均有各自的分层投影。 */
    fun appliesTo(style: Oe3NavStyle): Boolean = true
}

/** Top corners of the docked (fixed) bar; the bottom edge stays square. */
private val FixedBarShape = RoundedCornerShape(
    topStart = Oe3Shapes.md,
    topEnd = Oe3Shapes.md,
)

/** The dock surface clips the outer half of the stroke to its silhouette. */
private fun Modifier.navRim(shape: Shape, isDark: Boolean): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val highlight = if (isDark) Oe3Glass.DARK_RIM_HIGHLIGHT_ALPHA else Oe3Glass.LIGHT_RIM_HIGHLIGHT_ALPHA
    val shade = if (isDark) Oe3Glass.DARK_RIM_SHADE_ALPHA else Oe3Glass.LIGHT_RIM_SHADE_ALPHA
    val brush = Brush.linearGradient(
        0f to Color.White.copy(alpha = highlight),
        0.5f to Color.Transparent,
        1f to Color.Black.copy(alpha = shade),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val stroke = Stroke(width = Oe3Glass.rimWidth.toPx() * 2f)
    onDrawBehind { drawOutline(outline = outline, brush = brush, style = stroke) }
}

/**
 * Bottom navigation bar (spec §2.3, Theme Studio 底栏样式).
 *
 * UI3-10 upgrade: the background is the glass layer (`Oe3GlassSurface`) —
 * level `None` (the default) keeps the exact solid surface the shell shipped;
 * `Standard`/`Ultra` blur the content behind the bar through the shared
 * [glassState], and any environment without real blur renders the scrimmed
 * fallback. The selected pill now springs between cells, the icon/label
 * colours cross-fade on the themeTween colour curve, and pressing a tab dips the
 * content on the ≈250ms press spring. Geometry, `Role.Tab` semantics and the
 * 48dp hit floor are owned here; [glassState] is optional so existing callers
 * (previews, goldens) keep compiling.
 *
 * [style] picks the geometry (UI3-12 wires Theme Studio's 底栏样式): the
 * default [Oe3NavStyle.Floating] is the shipped capsule; [Oe3NavStyle.Fixed]
 * docks the bar flush to the bottom edge as a full-width surface with rounded
 * top corners, extending behind the system navigation bar. Both share the
 * same 62dp tab strip, pill and semantics — only the container geometry
 * differs, so the preference never changes what a tab is or does.
 */
@Composable
fun Oe3NavBar(
    selected: Oe3Tab,
    onSelect: (Oe3Tab) -> Unit,
    modifier: Modifier = Modifier,
    style: Oe3NavStyle = Oe3NavStyle.Floating,
    glassState: HazeState? = null,
) {
    val colors = Oe3ThemeValues.colors
    val motion = Oe3ThemeValues.motion
    val info = Oe3ThemeValues.info
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    val fixed = style == Oe3NavStyle.Fixed
    val barShape = if (fixed) FixedBarShape else Oe3Shapes.pill
    val selectionFill = remember(colors, info.isDark) { Oe3Colors.navigationSelection(colors, info.isDark) }
    val selectedInk = remember(colors.primary, selectionFill) {
        Color(ThemeColors.readable(colors.primary, selectionFill))
    }
    val inactiveInk = remember(colors.onSurfaceVariant, colors.surfaceFloating) {
        Color(ThemeColors.readable(colors.onSurfaceVariant, colors.surfaceFloating))
    }

    // 分层投影：浮动坞双层外投影，固定坞上缘 2dp 分离投影；API < 28 跳过 BlurMaskFilter。
    val drawShadow = Oe3NavShadowSpec.appliesTo(style) && Build.VERSION.SDK_INT >= 28
    val shadowPaints = remember(drawShadow, style, density) {
        Oe3NavShadowSpec.layersFor(style).map { layer ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb((layer.alpha * 255).toInt(), 0, 0, 0)
                maskFilter = BlurMaskFilter(
                    with(density) { layer.blur.toPx() },
                    BlurMaskFilter.Blur.NORMAL,
                )
            }
            paint to with(density) { layer.offsetY.toPx() }
        }
    }
    val shadowRect = remember { RectF() }
    val fixedCornerPx = with(density) { Oe3Shapes.md.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (fixed) {
                    Modifier
                } else {
                    Modifier
                        .padding(horizontal = Oe3Sizes.navSideMargin)
                        .padding(bottom = Oe3Sizes.navBottomInset(systemBottom))
                },
            )
            .drawBehind {
                if (!drawShadow) return@drawBehind
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val radius = if (fixed) fixedCornerPx else size.height / 2f
                    shadowPaints.forEach { (paint, dy) ->
                        shadowRect.set(0f, dy, size.width, size.height + dy)
                        native.drawRoundRect(shadowRect, radius, radius, paint)
                    }
                }
            }
            .height(if (fixed) Oe3Sizes.navBar + systemBottom else Oe3Sizes.navBar),
    ) {
        val cellWidth = (maxWidth - Oe3Sizes.navPadding * 2) / Oe3Tab.entries.size

        // The selection travel: when the pill re-homes to the new cell it
        // starts translated back to the previous cell and springs to rest.
        val selectedIndex = selected.ordinal
        val previousIndex = remember { mutableIntStateOf(selectedIndex) }
        val pillTravel = remember { Animatable(0f) }
        LaunchedEffect(selectedIndex, cellWidth, motion) {
            val from = previousIndex.intValue
            if (from != selectedIndex) {
                previousIndex.intValue = selectedIndex
                val startPx = with(density) {
                    Oe3NavPillSpec.travel(cellWidth, from, selectedIndex).toPx()
                }
                val spring = Oe3NavPillSpec.selectionSpring(motion)
                if (spring == null) {
                    pillTravel.snapTo(0f)
                } else {
                    pillTravel.snapTo(startPx)
                    pillTravel.animateTo(0f, spring)
                }
            }
        }

        Oe3GlassSurface(
            level = info.glassLevel,
            modifier = Modifier
                .fillMaxSize()
                // 测试锚点挂在玻璃背板（视觉坞体）上：语义边界即坞体几何，
                // 不含悬浮形态的外边距（透传测试量 21dp 边距时依赖这一点）。
                .testTag("shell:nav-bar"),
            shape = barShape,
            state = glassState,
        ) {
            Row(
                modifier = (
                    if (fixed) {
                        Modifier
                            .height(Oe3Sizes.navBar)
                            .padding(horizontal = Oe3Sizes.navPadding)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .padding(Oe3Sizes.navPadding)
                    }
                    ).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Oe3Tab.entries.forEachIndexed { index, tab ->
                    val isSelected = index == selectedIndex
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .sizeIn(
                                minWidth = Oe3Sizes.minTouchTarget,
                                minHeight = Oe3Sizes.minTouchTarget,
                            )
                            .selectable(
                                selected = isSelected,
                                role = Role.Tab,
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                                onClick = { onSelect(tab) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .graphicsLayer { translationX = pillTravel.value }
                                    // requiredWidth: the cell constrains width to its
                                    // slot, so plain width() would clamp away the 8dp
                                    // overhang. The bar's capsule clip keeps the
                                    // overflow inside the chrome.
                                    .requiredWidth(Oe3NavPillSpec.width(cellWidth))
                                    .height(Oe3Sizes.navPill)
                                    .clip(Oe3Shapes.pill)
                                    .background(Color(selectionFill)),
                            )
                        }
                        Oe3NavItemContent(
                            tab = tab,
                            isSelected = isSelected,
                            pressed = pressed,
                            ink = if (isSelected) selectedInk else inactiveInk,
                            motion = motion,
                        )
                    }
                }
            }
            Box(Modifier.matchParentSize().navRim(barShape, info.isDark))
        }
    }
}

/** Icon and label of one tab, above the pill. */
@Composable
private fun Oe3NavItemContent(
    tab: Oe3Tab,
    isSelected: Boolean,
    pressed: Boolean,
    ink: Color,
    motion: Oe3MotionTokens,
) {
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) Oe3NavPillSpec.PRESS_SCALE else Oe3NavPillSpec.REST_SCALE,
        animationSpec = Oe3NavPillSpec.pressSpring(motion),
        label = "oe3NavPress",
    )
    val labelColor by animateColorAsState(
        targetValue = ink,
        animationSpec = Oe3NavPillSpec.colorTransition(motion),
        label = "oe3NavLabel",
    )

    Column(
        modifier = Modifier.graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Oe3Icon(
            image = tab.icon,
            contentDescription = null,
            selected = isSelected,
            tint = labelColor,
        )
        Spacer(Modifier.height(Oe3Sizes.navIconLabelGap))
        Text(
            text = tab.label,
            modifier = Modifier.fillMaxWidth(),
            style = Oe3Typography.style(Oe3TextRole.NavLabel),
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val Oe3Tab.icon: ImageVector
    get() = when (this) {
        Oe3Tab.Workstation -> Oe3Icons.Workstation
        Oe3Tab.Instances -> Oe3Icons.Instances
        Oe3Tab.Hardware -> Oe3Icons.Hardware
        Oe3Tab.Distros -> Oe3Icons.Distros
        Oe3Tab.Config -> Oe3Icons.Config
    }
