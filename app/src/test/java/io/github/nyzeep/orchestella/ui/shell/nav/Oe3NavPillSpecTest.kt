package io.github.nyzeep.orchestella.ui.shell.nav

import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Geometry and motion contract of the floating bar's selected pill
 * (spec §2.5, ticket UI3-10): the pill keeps the per-cell geometry the
 * previous shell drew (centered, bleeding 4dp over each cell edge) and
 * springs between cells via [Oe3NavPillSpec.travel], so resting frames stay
 * pixel-identical to the shipped bar.
 */
class Oe3NavPillSpecTest {

    private val cellWidth = 77.2.dp

    @Test
    fun thePillOverhangsItsCellByTheTokenAmount() {
        assertEquals(Oe3Sizes.navPillOverhang, Oe3NavPillSpec.overhang)
        assertEquals(cellWidth + Oe3Sizes.navPillOverhang, Oe3NavPillSpec.width(cellWidth))
    }

    @Test
    fun noSelectionChangeMeansNoTravel() {
        assertEquals(0.dp, Oe3NavPillSpec.travel(cellWidth, 2, 2))
        assertEquals(0.dp, Oe3NavPillSpec.travel(cellWidth, 0, 0))
    }

    @Test
    fun theTravelSpansOneCellPerSkippedTabTowardsTheOldCell() {
        assertEquals(-cellWidth, Oe3NavPillSpec.travel(cellWidth, 0, 1))
        assertEquals(cellWidth * 2, Oe3NavPillSpec.travel(cellWidth, 3, 1))
        assertEquals(cellWidth * 4, Oe3NavPillSpec.travel(cellWidth, 4, 0))
    }

    @Test
    fun theSelectionSpringIsTheTokenSpring() {
        val motion = Oe3Motion.standard()
        val spring = Oe3NavPillSpec.selectionSpring(motion)!!
        assertEquals(0.85f, spring.dampingRatio, 0.0001f)
        assertEquals(Spring.StiffnessMediumLow, spring.stiffness, 0.0001f)
    }

    @Test
    fun reducedMotionWithdrawsTheSelectionSpring() {
        assertNull(Oe3NavPillSpec.selectionSpring(Oe3Motion.reduced()))
    }

    @Test
    fun thePressSpringKeepsTheDocumentedDampingAndTheNominalQuarterSecond() {
        val spring = Oe3NavPillSpec.pressSpring(Oe3Motion.standard())
        assertEquals(0.85f, spring.dampingRatio, 0.0001f)
        // The ≈250ms nominal maps to StiffnessMedium; anchor the choice so a
        // silent stiffness change cannot drift the press feel away from spec.
        assertEquals(Spring.StiffnessMedium, spring.stiffness, 0.0001f)
        // ≈250ms nominal; the tokens' press duration stays the design source.
        assertEquals(250, Oe3Motion.standard().press.durationMillis)
    }

    @Test
    fun theLabelColorTransitionIsTheThemeTweenColourCurve() {
        val tokens = Oe3Motion.standard()
        val transition = Oe3NavPillSpec.colorTransition(tokens)
        assertEquals(300, transition.durationMillis)
        assertEquals(tokens.themeTween.durationMillis, transition.durationMillis)
        assertEquals(tokens.themeTween.easing, transition.easing)
        // Reduced motion: the same spec collapses to 0ms.
        assertEquals(0, Oe3NavPillSpec.colorTransition(Oe3Motion.reduced()).durationMillis)
    }

    @Test
    fun thePressScaleIsASubtleDipFromFullScale() {
        assertEquals(0.96f, Oe3NavPillSpec.PRESS_SCALE, 0.0001f)
        assertEquals(1f, Oe3NavPillSpec.REST_SCALE, 0.0001f)
    }

    @Test
    fun theTravelStretchPeaksAtOneCellAndRelaxesAtRest() {
        val motion = Oe3Motion.standard()
        val cellWidthPx = cellWidth.value
        // Rest: no travel, no stretch.
        assertEquals(1f, Oe3NavPillSpec.stretchFor(0f, cellWidthPx, motion), 0.0001f)
        // Half a cell of travel stretches half of the max.
        assertEquals(
            1f + 0.02f,
            Oe3NavPillSpec.stretchFor(cellWidthPx / 2f, cellWidthPx, motion),
            0.0001f,
        )
        // One cell — and beyond — holds the spec ceiling of scaleX 1.04.
        assertEquals(
            1.04f,
            Oe3NavPillSpec.stretchFor(cellWidthPx, cellWidthPx, motion),
            0.0001f,
        )
        assertEquals(
            1.04f,
            Oe3NavPillSpec.stretchFor(cellWidthPx * 3f, cellWidthPx, motion),
            0.0001f,
        )
        assertEquals(0.04f, OeMotion.NAV_PILL_STRETCH_MAX, 0.0001f)
    }

    @Test
    fun reducedMotionAndDegenerateCellsNeverStretch() {
        assertEquals(
            1f,
            Oe3NavPillSpec.stretchFor(100f, cellWidth.value, Oe3Motion.reduced()),
            0.0001f,
        )
        // A zero-width cell cannot normalise travel; stretch stays neutral.
        assertEquals(
            1f,
            Oe3NavPillSpec.stretchFor(100f, 0f, Oe3Motion.standard()),
            0.0001f,
        )
    }

    @Test
    fun theSettleVelocityIsClampedToALegalBound() {
        val bound = OeMotion.NAV_PILL_SETTLE_MAX_VELOCITY_PX_PER_SEC
        assertEquals(bound, Oe3NavPillSpec.clampSettleVelocity(50_000f), 0.0001f)
        assertEquals(-bound, Oe3NavPillSpec.clampSettleVelocity(-50_000f), 0.0001f)
        assertEquals(bound, Oe3NavPillSpec.clampSettleVelocity(bound), 0.0001f)
        // A human-speed release passes through untouched.
        assertEquals(1234f, Oe3NavPillSpec.clampSettleVelocity(1234f), 0.0001f)
        assertEquals(0f, Oe3NavPillSpec.clampSettleVelocity(0f), 0.0001f)
    }
}
