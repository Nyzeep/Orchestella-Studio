package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/**
 * Floating capsule navigation bar (spec §2.3).
 *
 * This ticket ships the solid-surface form; UI3-10 replaces the background with
 * the glass layer. Geometry, the selected pill, `Role.Tab` semantics and the
 * 48dp hit floor are owned here.
 */
@Composable
fun Oe3NavBar(
    selected: Oe3Tab,
    onSelect: (Oe3Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Oe3Sizes.navSideMargin)
            .padding(bottom = Oe3Sizes.navBottomInset(systemBottom))
            .height(Oe3Sizes.navBar)
            .clip(Oe3Shapes.pill)
            .background(Color(colors.surface)),
    ) {
        val cellWidth = (maxWidth - Oe3Sizes.navPadding * 2) / Oe3Tab.entries.size
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(Oe3Sizes.navPadding)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oe3Tab.entries.forEach { tab ->
                val isSelected = tab == selected
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
                            onClick = { onSelect(tab) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .width(cellWidth + Oe3Sizes.navPillOverhang)
                                .height(Oe3Sizes.navPill)
                                .clip(Oe3Shapes.pill)
                                .background(Color(colors.surfaceHigh)),
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Oe3Icon(
                            image = tab.icon,
                            contentDescription = null,
                            selected = isSelected,
                        )
                        Spacer(Modifier.height(Oe3Sizes.navIconLabelGap))
                        Text(
                            text = tab.label,
                            style = Oe3Typography.style(Oe3TextRole.NavLabel),
                            color = Color(if (isSelected) colors.primary else colors.onSurfaceVariant),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
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
