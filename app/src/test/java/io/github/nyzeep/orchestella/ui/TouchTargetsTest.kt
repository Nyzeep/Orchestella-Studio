package io.github.nyzeep.orchestella.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W3 (2026-11-11) min-touch-target contracts for the theme picker rows: the
 * grown hit spans tile the whole stack (no dead zone), never shrink below the
 * 48dp minimum while the geometry allows it, and never swallow neighbouring
 * controls. Pure geometry — the view-side delegate just applies these spans.
 */
class TouchTargetsTest {
    private val minPx = TouchTargets.MIN_TARGET_DP

    private fun span(
        rowTop: Int, rowBottom: Int, limitAbove: Int, limitBelow: Int,
        contentTop: Int = 0, contentBottom: Int = 1000, minTargetPx: Int = minPx,
    ) = TouchTargets.verticalSpan(rowTop, rowBottom, limitAbove, limitBelow,
        contentTop, contentBottom, minTargetPx)

    // --- shipped dashboard geometry: 46dp rows, 8dp label gap, 6dp row gaps ---

    @Test fun shippedThemeRowsReachTheFortyEightDpMinimumWithoutVisualChange() {
        // Density 1: rows at 100..146, 152..198, 204..250, 256..302; the section
        // label ends at 92; card content ends at 314.
        val rows = listOf(100 to 146, 152 to 198, 204 to 250, 256 to 302)
        val spans = rows.mapIndexed { index, (top, bottom) ->
            span(top, bottom,
                limitAbove = if (index == 0) 92 else rows[index - 1].second,
                limitBelow = if (index == rows.lastIndex) 314 else rows[index + 1].first,
                contentTop = 92, contentBottom = 314)
        }
        for ((index, s) in spans.withIndex()) {
            val (top, bottom) = rows[index]
            assertTrue("row $index span must cover its own bounds",
                s.top <= top && s.bottom >= bottom)
            assertTrue("row $index must reach the 48dp minimum (was ${s.height})",
                s.height >= TouchTargets.MIN_TARGET_DP)
        }
        assertEquals(53, spans[0].height) // 46 + 4 into the label gap + 3 into the row gap
        assertEquals(52, spans[1].height) // 46 + 3 + 3
        assertEquals(55, spans[3].height) // 46 + 3 + 6 into the trailing padding
    }

    @Test fun adjacentRowSpansTileTheStackWithZeroDeadZoneAndZeroOverlap() {
        val rows = listOf(100 to 146, 152 to 198, 204 to 250, 256 to 302)
        val spans = rows.mapIndexed { index, (top, bottom) ->
            span(top, bottom,
                limitAbove = if (index == 0) 92 else rows[index - 1].second,
                limitBelow = if (index == rows.lastIndex) 314 else rows[index + 1].first,
                contentTop = 92, contentBottom = 314)
        }
        for (i in 0 until spans.lastIndex) {
            assertEquals("gap between rows $i/${i + 1} must be split at the midpoint",
                spans[i].bottom, spans[i + 1].top)
        }
    }

    @Test fun edgeRowsNeverSwallowTheNeighbouringControl() {
        // The section label above row 0 stays tappable: the span stops at its bottom.
        val s = span(100, 146, limitAbove = 92, limitBelow = 152, contentTop = 92, contentBottom = 314)
        assertTrue("span must not reach above the label", s.top >= 92)
        assertTrue("span must not reach past the next row", s.bottom <= 152)
    }

    // --- minimum enforcement when the row is far under 48dp ---

    @Test fun undersizedRowGrowsToTheMinimumHalfUpHalfDown() {
        val s = span(100, 130, limitAbove = 100, limitBelow = 130) // zero-gap 30dp row
        assertEquals(91, s.top) // deficit 18 → 9 up
        assertEquals(139, s.bottom) // …and 9 down → 30+9+9 = 48
        assertEquals(TouchTargets.MIN_TARGET_DP, s.height)
    }

    @Test fun growthClampsToContentAndGivesTheRemainderToTheOtherSide() {
        val s = span(100, 130, limitAbove = 100, limitBelow = 130,
            contentTop = 95, contentBottom = 1000)
        assertEquals(95, s.top)
        assertEquals(143, s.bottom) // 5 up (content clamp) + 13 down
        assertEquals(TouchTargets.MIN_TARGET_DP, s.height)
    }

    @Test fun growthNeverExceedsTheAvailableContentSpace() {
        val s = span(100, 130, limitAbove = 100, limitBelow = 130,
            contentTop = 95, contentBottom = 140)
        assertEquals(95, s.top) // only 5px of room above
        assertEquals(140, s.bottom) // 13px deficit but only 10px of room below
        assertEquals(45, s.height)
    }

    // --- the span always keeps the row's own bounds covered ---

    @Test fun rowBoundsAlwaysStayInsideTheSpan() {
        val rows = listOf(0 to 30, 30 to 60)
        for (index in rows.indices) {
            val s = span(rows[index].first, rows[index].second,
                limitAbove = if (index == 0) 0 else rows[index - 1].second,
                limitBelow = if (index == rows.lastIndex) 60 else rows[index + 1].first)
            assertTrue(s.top <= rows[index].first && s.bottom >= rows[index].second)
        }
    }
}
