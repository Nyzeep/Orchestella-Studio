package io.github.nyzeep.orchestella.ui.oe3

import io.github.nyzeep.orchestella.ui.oe3.tokens.HomePreviewBlockKind
import io.github.nyzeep.orchestella.ui.oe3.tokens.HomePreviewSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM contract for the shared workstation preview structure. */
class HomePreviewSpecTest {

    @Test
    fun blocksFollowTheWorkstationReadingOrder() {
        assertEquals(
            listOf(
                HomePreviewBlockKind.Title,
                HomePreviewBlockKind.Meta,
                HomePreviewBlockKind.HeroCard,
                HomePreviewBlockKind.StorageRing,
                HomePreviewBlockKind.Metrics,
                HomePreviewBlockKind.RecentInstances,
            ),
            HomePreviewSpec.blocks.map { it.kind },
        )
    }

    @Test
    fun relativeHeightsFillThePageExactlyOnce() {
        assertEquals(1f, HomePreviewSpec.totalFraction(), 0.0001f)
        HomePreviewSpec.blocks.forEach { block ->
            assertTrue("${block.kind} must occupy space", block.heightFraction > 0f)
            assertTrue("${block.kind} must not fill the page alone", block.heightFraction < 1f)
        }
    }

    @Test
    fun previewGeometryMatchesTheThemeStudioContract() {
        assertEquals(168, HomePreviewSpec.WIDTH_DP)
        assertEquals(28, HomePreviewSpec.CORNER_DP)
        assertEquals(9f / 19.5f, HomePreviewSpec.ASPECT_RATIO, 0.0001f)
        assertEquals(1, HomePreviewSpec.VERSION)
    }

    @Test
    fun everyBlockKindIsReachableByLookup() {
        HomePreviewBlockKind.entries.forEach { kind ->
            assertEquals(kind, HomePreviewSpec.block(kind).kind)
        }
    }
}
