package io.github.nyzeep.orchestella.ui

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup

/**
 * Min-touch-target support (WCAG 2.5.5 / Material 48dp rule) for rows that are
 * visually smaller than 48dp, without changing their visual size.
 *
 * 2026-11-11 U1 / W3: the theme picker rows on the dashboard preferences tab are
 * 46dp tall with 6dp gaps, and taps landing in the gaps (or on the short text
 * node) used to die. The fix is a touch delegate on the row container: each
 * row's hit area is grown into the adjacent gaps (adjacent rows tile the whole
 * stack — no dead zone) and, where the geometry allows, to [MIN_TARGET_DP].
 * Visual rhythm is untouched: heights, margins and fills in XML are unchanged.
 */
object TouchTargets {
    /** Material/WCAG minimum interactive target size. */
    const val MIN_TARGET_DP = 48

    /** Vertical hit span [top, bottom) in container coordinates. */
    data class Span(val top: Int, val bottom: Int) {
        val height: Int get() = bottom - top
    }

    /**
     * Vertical hit span for one row inside a vertically stacked container.
     *
     * [rowTop]/[rowBottom] are the row's own bounds; [limitAbove]/[limitBelow]
     * are the nearest geometry around it (previous/next sibling edge or the
     * container content edge). The row first claims half of each adjacent gap —
     * so stacked rows meet exactly at the gap midpoints (zero dead zone, zero
     * overlap) — and then grows symmetrically until it reaches [minTargetPx],
     * clamped to [contentTop, contentBottom]. The row's own bounds are always
     * fully inside the span, so the visible control never loses coverage.
     */
    fun verticalSpan(
        rowTop: Int,
        rowBottom: Int,
        limitAbove: Int,
        limitBelow: Int,
        contentTop: Int,
        contentBottom: Int,
        minTargetPx: Int,
    ): Span {
        require(rowTop < rowBottom) { "row bounds inverted: $rowTop..$rowBottom" }
        var top = rowTop - (rowTop - limitAbove).coerceAtLeast(0) / 2
        var bottom = rowBottom + (limitBelow - rowBottom).coerceAtLeast(0) / 2
        var deficit = minTargetPx - (bottom - top)
        if (deficit > 0) {
            val up = (deficit + 1) / 2
            top -= up.coerceAtMost(top - contentTop).coerceAtLeast(0)
            deficit = minTargetPx - (bottom - top)
            if (deficit > 0) {
                bottom += deficit.coerceAtMost(contentBottom - bottom).coerceAtLeast(0)
            }
        }
        return Span(top, bottom)
    }

    /**
     * Computes hit spans for [rows] (ordered top → bottom, all direct children of
     * [container]) from their current layout, then installs one [TouchDelegate]
     * on the container that routes taps in the gaps to the nearest row. Edge rows
     * borrow from their nearest non-row sibling (e.g. the section label above),
     * never past it, so tapping the label never activates a row. Must run after
     * layout (call from a layout pass or `post {}`); returns false when the
     * inputs are not laid out yet — callers may retry on the next layout.
     */
    fun installStackedRowTargets(container: ViewGroup, rows: List<View>, density: Float): Boolean {
        if (rows.size < 2 || rows.any { it.height <= 0 || it.parent !== container }) return false
        if (rows.zipWithNext { a, b -> b.top <= a.bottom }.any { it }) return false
        val childIndices = rows.map { container.indexOfChild(it) }
        if (childIndices != childIndices.indices.toList()) return false // not top-down siblings
        val contentTop = container.paddingTop
        val contentBottom = container.height - container.paddingBottom
        val minTargetPx = (MIN_TARGET_DP * density).toInt().coerceAtLeast(1)
        val spans = rows.mapIndexed { index, row ->
            val childIndex = childIndices[index]
            val limitAbove = if (childIndex == 0) contentTop else container.getChildAt(childIndex - 1).bottom
            val limitBelow = if (childIndex == container.childCount - 1) contentBottom else container.getChildAt(childIndex + 1).top
            verticalSpan(row.top, row.bottom, limitAbove, limitBelow,
                contentTop, contentBottom, minTargetPx)
        }
        container.touchDelegate = StackedRowsTouchDelegate(rows, spans)
        return true
    }
}

/**
 * Routes container-level touches (the gaps between rows) to the row whose grown
 * hit span contains the touch point, offsetting the event into that row's
 * coordinate space. Touches outside every span fall through to the container,
 * exactly like the pre-delegate behavior for card padding.
 */
internal class StackedRowsTouchDelegate(
    private val rows: List<View>,
    spans: List<TouchTargets.Span>,
) : TouchDelegate(Rect(), rows.first()) {
    private val rects: List<Rect> = rows.mapIndexed { index, row ->
        Rect(row.left, spans[index].top, row.right, spans[index].bottom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val y = event.y
        val index = rects.indexOfFirst { it.top <= y && y < it.bottom }
        if (index < 0) return false
        val row = rows[index]
        val rect = rects[index]
        val offset = MotionEvent.obtain(event)
        offset.setLocation(event.x - rect.left, y - row.top)
        val handled = try {
            row.dispatchTouchEvent(offset)
        } finally {
            offset.recycle()
        }
        return handled
    }
}
