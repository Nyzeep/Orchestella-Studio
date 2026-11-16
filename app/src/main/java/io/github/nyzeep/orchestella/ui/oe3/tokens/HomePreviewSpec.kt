package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.runtime.Immutable

/** Structural blocks of the workstation page, in reading order. */
enum class HomePreviewBlockKind { Title, Meta, HeroCard, StorageRing, Metrics, RecentInstances }

@Immutable
data class HomePreviewBlock(
    val kind: HomePreviewBlockKind,
    val heightFraction: Float,
)

/**
 * The one structural description of the workstation page, shared by the real
 * page (UI3-05) and the Theme Studio preview (UI3-09). It deliberately holds
 * no colors, text styles or drawing commands: previews resolve those from the
 * live Oe3 theme, which is what keeps the miniature from drifting away from
 * the page it claims to preview.
 */
object HomePreviewSpec {
    const val VERSION = 1

    const val WIDTH_DP = 168
    const val CORNER_DP = 28
    const val ASPECT_RATIO = 9f / 19.5f

    val blocks: List<HomePreviewBlock> = listOf(
        HomePreviewBlock(HomePreviewBlockKind.Title, 0.06f),
        HomePreviewBlock(HomePreviewBlockKind.Meta, 0.03f),
        HomePreviewBlock(HomePreviewBlockKind.HeroCard, 0.27f),
        HomePreviewBlock(HomePreviewBlockKind.StorageRing, 0.28f),
        HomePreviewBlock(HomePreviewBlockKind.Metrics, 0.16f),
        HomePreviewBlock(HomePreviewBlockKind.RecentInstances, 0.20f),
    )

    fun totalFraction(): Float = blocks.sumOf { it.heightFraction.toDouble() }.toFloat()
}
