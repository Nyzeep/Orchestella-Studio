package io.github.nyzeep.orchestella.ui.shell.nav

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeState
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassSurface
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3MotionTokens
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab

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

    /** Icon/label colour transition: the 220ms easeInOut page curve (spec §2.5). */
    fun colorTransition(motion: Oe3MotionTokens) = tween<Color>(
        durationMillis = motion.pageFade.durationMillis,
        easing = motion.pageFade.easing,
    )

    const val PRESS_SCALE = 0.96f
    const val REST_SCALE = 1f
}

/**
 * Floating capsule navigation bar (spec §2.3).
 *
 * UI3-10 upgrade: the background is the glass layer (`Oe3GlassSurface`) —
 * level `None` (the default) keeps the exact solid surface the shell shipped;
 * `Standard`/`Ultra` blur the content behind the bar through the shared
 * [glassState], and any environment without real blur renders the scrimmed
 * fallback. The selected pill now springs between cells, the icon/label
 * colours cross-fade on the 220ms page curve, and pressing a tab dips the
 * content on the ≈250ms press spring. Geometry, `Role.Tab` semantics and the
 * 48dp hit floor are owned here; [glassState] is optional so existing callers
 * (previews, goldens) keep compiling.
 */
@Composable
fun Oe3NavBar(
    selected: Oe3Tab,
    onSelect: (Oe3Tab) -> Unit,
    modifier: Modifier = Modifier,
    glassState: HazeState? = null,
) {
    val colors = Oe3ThemeValues.colors
    val motion = Oe3ThemeValues.motion
    val info = Oe3ThemeValues.info
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Oe3Sizes.navSideMargin)
            .padding(bottom = Oe3Sizes.navBottomInset(systemBottom))
            .height(Oe3Sizes.navBar),
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
                .clip(Oe3Shapes.pill),
            state = glassState,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Oe3Sizes.navPadding)
                    .selectableGroup(),
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
                                    .background(Color(colors.surfaceHigh)),
                            )
                        }
                        Oe3NavItemContent(
                            tab = tab,
                            isSelected = isSelected,
                            pressed = pressed,
                            motion = motion,
                        )
                    }
                }
            }
        }
    }
}

/** Icon and label of one tab, above the pill. */
@Composable
private fun Oe3NavItemContent(
    tab: Oe3Tab,
    isSelected: Boolean,
    pressed: Boolean,
    motion: Oe3MotionTokens,
) {
    val colors = Oe3ThemeValues.colors
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) Oe3NavPillSpec.PRESS_SCALE else Oe3NavPillSpec.REST_SCALE,
        animationSpec = Oe3NavPillSpec.pressSpring(motion),
        label = "oe3NavPress",
    )
    val labelColor by animateColorAsState(
        targetValue = Color(if (isSelected) colors.primary else colors.onSurfaceVariant),
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
            style = Oe3Typography.style(Oe3TextRole.NavLabel),
            color = labelColor,
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
