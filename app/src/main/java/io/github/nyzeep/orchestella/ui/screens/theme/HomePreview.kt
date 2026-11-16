package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Ring
import io.github.nyzeep.orchestella.ui.oe3.tokens.HomePreviewBlockKind
import io.github.nyzeep.orchestella.ui.oe3.tokens.HomePreviewSpec
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.SurfaceOpacity
import kotlin.math.min

/**
 * The miniature workstation page. Structure comes only from [HomePreviewSpec];
 * colours come from [homePreviewColors], i.e. the same Oe3 roles as the real
 * page. The 300 ms colour tween is the spec's theme-transition duration and
 * collapses to a snap when motion is reduced.
 */
@Composable
fun HomePreview(
    theme: AppTheme,
    pureBlack: Boolean,
    cornerDp: Int,
    surfaceOpacityPercent: Int = SurfaceOpacity.DEFAULT_PERCENT,
    glass: GlassOption = GlassOption.None,
    navStyle: NavStyleOption = NavStyleOption.Floating,
    fontFamily: FontFamily = FontFamily.Default,
    reduceMotion: Boolean = false,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val target = homePreviewColors(theme, pureBlack, surfaceOpacityPercent, glass)
    val colors = animatedColors(target, reduceMotion)
    val cardRadius = min(cornerDp, 24).dp

    Box(
        modifier = modifier
            .testTag("theme-preview:home")
            .width(HomePreviewSpec.WIDTH_DP.dp)
            .aspectRatio(HomePreviewSpec.ASPECT_RATIO)
            .clip(RoundedCornerShape(HomePreviewSpec.CORNER_DP.dp))
            .background(Color(colors.background))
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        Column(Modifier.fillMaxSize()) {
            HomePreviewSpec.blocks.forEach { block ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(block.heightFraction.coerceAtLeast(0.001f)),
                ) {
                    when (block.kind) {
                        HomePreviewBlockKind.Title -> PreviewTitle(colors, fontFamily)
                        HomePreviewBlockKind.Meta -> PreviewMeta(colors, fontFamily)
                        HomePreviewBlockKind.HeroCard -> PreviewHero(colors, cardRadius, fontFamily)
                        HomePreviewBlockKind.StorageRing -> PreviewStorage(colors, fontFamily)
                        HomePreviewBlockKind.Metrics -> PreviewMetrics(colors, cardRadius, fontFamily)
                        HomePreviewBlockKind.RecentInstances -> PreviewRecent(colors, cardRadius, fontFamily)
                    }
                }
            }
        }
        if (navStyle == NavStyleOption.Floating) {
            FloatingNav(colors, Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp))
        } else {
            FixedNav(colors, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun animatedColors(target: HomePreviewColors, reduceMotion: Boolean): HomePreviewColors {
    val spec = if (reduceMotion) snap<Color>() else tween(ThemeStudioSpec.previewTweenMillis)
    return HomePreviewColors(
        background = animateColorAsState(Color(target.background), spec, label = "previewBackground").value.toArgb(),
        surface = animateColorAsState(Color(target.surface), spec, label = "previewSurface").value.toArgb(),
        surfaceRaised = animateColorAsState(Color(target.surfaceRaised), spec, label = "previewRaised").value.toArgb(),
        surfaceHigh = animateColorAsState(Color(target.surfaceHigh), spec, label = "previewHigh").value.toArgb(),
        onSurface = animateColorAsState(Color(target.onSurface), spec, label = "previewInk").value.toArgb(),
        onSurfaceVariant = animateColorAsState(Color(target.onSurfaceVariant), spec, label = "previewDim").value.toArgb(),
        primary = animateColorAsState(Color(target.primary), spec, label = "previewPrimary").value.toArgb(),
        primaryContainer = animateColorAsState(Color(target.primaryContainer), spec, label = "previewPrimaryContainer").value.toArgb(),
        onPrimary = animateColorAsState(Color(target.onPrimary), spec, label = "previewOnPrimary").value.toArgb(),
        accent = animateColorAsState(Color(target.accent), spec, label = "previewAccent").value.toArgb(),
        ringTrack = animateColorAsState(Color(target.ringTrack), spec, label = "previewRingTrack").value.toArgb(),
        navFill = animateColorAsState(Color(target.navFill), spec, label = "previewNav").value.toArgb(),
    )
}

@Composable
private fun PreviewTitle(colors: HomePreviewColors, fontFamily: FontFamily) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp)
            .padding(top = 7.dp),
    ) {
        Text(
            text = "工作站",
            style = TextStyle(fontFamily = fontFamily, fontSize = 9.sp, lineHeight = 11.sp),
            color = Color(colors.onSurface),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun PreviewMeta(colors: HomePreviewColors, fontFamily: FontFamily) {
    Text(
        text = "2 个实例 · 1 个运行中",
        modifier = Modifier.padding(horizontal = 10.dp),
        style = TextStyle(fontFamily = fontFamily, fontSize = 4.5.sp, lineHeight = 6.sp),
        color = Color(colors.onSurfaceVariant),
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

@Composable
private fun PreviewHero(colors: HomePreviewColors, cardRadius: androidx.compose.ui.unit.Dp, fontFamily: FontFamily) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(cardRadius))
            .background(Color(colors.surface))
            .padding(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color(colors.primaryContainer)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "U",
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 7.sp, lineHeight = 8.sp),
                    color = Color(colors.primary),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Ubuntu 24.04",
                    style = TextStyle(fontFamily = fontFamily, fontSize = 6.5.sp, lineHeight = 8.sp),
                    color = Color(colors.onSurface),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    text = "4 vCPU · 4096 MB",
                    style = TextStyle(fontFamily = fontFamily, fontSize = 4.5.sp, lineHeight = 5.5.sp),
                    color = Color(colors.onSurfaceVariant),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(colors.primaryContainer))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "运行中",
                    style = TextStyle(fontFamily = fontFamily, fontSize = 4.sp, lineHeight = 5.sp),
                    color = Color(colors.primary),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(17.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(colors.primary)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "打开终端",
                style = TextStyle(fontFamily = fontFamily, fontSize = 5.sp, lineHeight = 6.sp),
                color = Color(colors.onPrimary),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PreviewStorage(colors: HomePreviewColors, fontFamily: FontFamily) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Oe3Ring(
            progress = 0.72f,
            diameter = 62.dp,
            ringWidth = 6.dp,
            color = Color(colors.primary),
            trackColor = Color(colors.ringTrack),
            contentDescription = null,
        ) {
            Text(
                text = "72%",
                style = TextStyle(fontFamily = fontFamily, fontSize = 9.sp, lineHeight = 11.sp),
                color = Color(colors.onSurface),
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = "存储 · 已用 72%",
            style = TextStyle(fontFamily = fontFamily, fontSize = 4.5.sp, lineHeight = 5.5.sp),
            color = Color(colors.onSurfaceVariant),
            maxLines = 1,
        )
    }
}

@Composable
private fun PreviewMetrics(colors: HomePreviewColors, cardRadius: androidx.compose.ui.unit.Dp, fontFamily: FontFamily) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PreviewMetric("CPU", "34%", 0.34f, colors.primary, colors, cardRadius, fontFamily, Modifier.weight(1f))
        PreviewMetric("内存", "2.1 / 8 GB", 0.62f, colors.accent, colors, cardRadius, fontFamily, Modifier.weight(1f))
    }
}

@Composable
private fun PreviewMetric(
    label: String,
    value: String,
    fraction: Float,
    barColor: Int,
    colors: HomePreviewColors,
    cardRadius: androidx.compose.ui.unit.Dp,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(cardRadius))
            .background(Color(colors.surface))
            .padding(6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(fontFamily = fontFamily, fontSize = 4.5.sp, lineHeight = 5.5.sp),
            color = Color(colors.onSurfaceVariant),
            maxLines = 1,
        )
        Text(
            text = value,
            style = TextStyle(fontFamily = fontFamily, fontSize = 6.5.sp, lineHeight = 7.5.sp),
            color = Color(colors.onSurface),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(colors.surfaceHigh)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(Color(barColor)),
            )
        }
    }
}

@Composable
private fun PreviewRecent(colors: HomePreviewColors, cardRadius: androidx.compose.ui.unit.Dp, fontFamily: FontFamily) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .padding(top = 2.dp, bottom = 31.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        PreviewRecentRow("Debian 12", "已停止", colors, cardRadius, fontFamily)
        PreviewRecentRow("Alpine 3.20", "已停止", colors, cardRadius, fontFamily)
    }
}

@Composable
private fun PreviewRecentRow(
    name: String,
    status: String,
    colors: HomePreviewColors,
    cardRadius: androidx.compose.ui.unit.Dp,
    fontFamily: FontFamily,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(cardRadius))
            .background(Color(colors.surface))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = fontFamily, fontSize = 4.5.sp, lineHeight = 5.5.sp),
            color = Color(colors.onSurface),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            text = status,
            style = TextStyle(fontFamily = fontFamily, fontSize = 4.sp, lineHeight = 5.sp),
            color = Color(colors.onSurfaceVariant),
            maxLines = 1,
        )
    }
}

@Composable
private fun FloatingNav(colors: HomePreviewColors, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .testTag("theme-preview:nav-floating")
            .width(146.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(colors.navFill))
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItems(colors)
    }
}

/** Fixed bar: full width, flush to the bottom edge, no floating margin. */
@Composable
private fun FixedNav(colors: HomePreviewColors, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .testTag("theme-preview:nav-fixed")
            .fillMaxWidth()
            .height(28.dp)
            .background(Color(colors.navFill))
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItems(colors)
    }
}

@Composable
private fun NavItems(colors: HomePreviewColors) {
    repeat(5) { index ->
        val selected = index == 0
        Box(
            modifier = Modifier
                .width(if (selected) 34.dp else 14.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) Color(colors.surfaceHigh) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(if (selected) 4.dp else 3.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) Color(colors.primary) else Color(colors.onSurfaceVariant),
                    ),
            )
        }
    }
}
