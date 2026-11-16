package io.github.nyzeep.orchestella.ui.oe3

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM contract for the Oe3 geometry tokens (spec §2.2–2.3). */
class Oe3GeometryTest {

    @Test
    fun shapeScaleIsTheDocumentedSixStepLadder() {
        assertEquals(8.dp, Oe3Shapes.xs)
        assertEquals(12.dp, Oe3Shapes.sm)
        assertEquals(16.dp, Oe3Shapes.md)
        assertEquals(20.dp, Oe3Shapes.lg)
        assertEquals(24.dp, Oe3Shapes.xl)
        assertEquals(24.dp, Oe3Shapes.groupedOuter)
        assertEquals(0.dp, Oe3Shapes.groupedInner)
    }

    @Test
    fun pillIsAFullCapsuleAndRoundedBuildsFromTheToken() {
        assertEquals(RoundedCornerShape(percent = 50), Oe3Shapes.pill)
        assertEquals(RoundedCornerShape(16.dp), Oe3Shapes.rounded(Oe3Shapes.md))
    }

    @Test
    fun spacingRhythmUsesTheDocumentedSteps() {
        assertEquals(4.dp, Oe3Spacing.xxs)
        assertEquals(8.dp, Oe3Spacing.xs)
        assertEquals(12.dp, Oe3Spacing.sm)
        assertEquals(16.dp, Oe3Spacing.md)
        assertEquals(20.dp, Oe3Spacing.lg)
        assertEquals(24.dp, Oe3Spacing.xl)
        assertEquals(32.dp, Oe3Spacing.xxl)
    }

    @Test
    fun pageAndCardInsetsMatchTheDesignLanguage() {
        assertEquals(20.dp, Oe3Spacing.pageInset)
        assertEquals(83.dp, Oe3Spacing.contentBottom)
        assertEquals(20.dp, Oe3Spacing.cardPadding)
        assertEquals(56.dp, Oe3Spacing.rowHeight)
        assertEquals(16.dp, Oe3Spacing.rowHorizontal)
        assertEquals(16.dp, Oe3Spacing.iconTextGap)
        assertEquals(24.dp, Oe3Spacing.sectionTop)
        assertEquals(8.dp, Oe3Spacing.sectionBottom)
        assertEquals(8.dp, Oe3Spacing.gridGap)
    }

    @Test
    fun chromeSizesFollowTheReferenceMeasurements() {
        assertEquals(72.dp, Oe3Sizes.topBar)
        assertEquals(62.dp, Oe3Sizes.navBar)
        assertEquals(21.dp, Oe3Sizes.navSideMargin)
        assertEquals(21.dp, Oe3Sizes.navBottomMargin)
        assertEquals(4.dp, Oe3Sizes.navPadding)
        assertEquals(54.dp, Oe3Sizes.navPill)
        assertEquals(8.dp, Oe3Sizes.navPillOverhang)
        assertEquals(24.dp, Oe3Sizes.navIcon)
    }

    @Test
    fun everyInteractivePrimitiveHasA48dpFloor() {
        assertTrue(
            "minimum touch target must be at least 48dp",
            Oe3Sizes.minTouchTarget >= 48.dp,
        )
        assertTrue("list rows exceed the floor", Oe3Spacing.rowHeight >= Oe3Sizes.minTouchTarget)
        assertTrue("buttons exceed the floor", Oe3Sizes.button >= Oe3Sizes.minTouchTarget)
        assertTrue("the nav pill exceeds the floor", Oe3Sizes.navPill >= Oe3Sizes.minTouchTarget)
    }

    @Test
    fun dividerUsesTheDocumentedThicknessAndInsets() {
        assertEquals(0.75.dp, Oe3Sizes.dividerThickness)
        assertEquals(56.dp, Oe3Sizes.dividerStart)
        assertEquals(14.dp, Oe3Sizes.dividerEnd)
        assertEquals(
            Oe3Spacing.rowHorizontal + Oe3Sizes.icon + Oe3Spacing.iconTextGap,
            Oe3Sizes.dividerStart,
        )
    }
}
