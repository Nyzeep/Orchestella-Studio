package io.github.nyzeep.orchestella.ui.oe3

import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3DividerSpec
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3RingSpec
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM contract for the primitives whose math must not live in a Canvas. */
class Oe3ComponentsTest {

    @Test
    fun ringSweepsZeroHalfAndFull() {
        assertEquals(0f, Oe3RingSpec.sweep(0f), 0.0001f)
        assertEquals(180f, Oe3RingSpec.sweep(0.5f), 0.0001f)
        assertEquals(360f, Oe3RingSpec.sweep(1f), 0.0001f)
    }

    @Test
    fun ringClampsOutOfRangeAndNonFiniteProgress() {
        assertEquals(0f, Oe3RingSpec.sweep(-0.4f), 0.0001f)
        assertEquals(360f, Oe3RingSpec.sweep(1.7f), 0.0001f)
        assertEquals(0f, Oe3RingSpec.clamp(Float.NaN), 0.0001f)
        assertEquals(0f, Oe3RingSpec.clamp(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(-90f, Oe3RingSpec.START_ANGLE, 0.0001f)
    }

    @Test
    fun dividerStartsAtTheTextEdgeWhenALeadingIconExists() {
        assertEquals(Oe3Sizes.dividerStart, Oe3DividerSpec.startIndent(hasLeading = true))
        assertEquals(56.dp, Oe3DividerSpec.startIndent(hasLeading = true))
        assertEquals(16.dp, Oe3DividerSpec.startIndent(hasLeading = false))
        assertEquals(14.dp, Oe3Sizes.dividerEnd)
    }

    @Test
    fun chipKeepsACompactVisualInsideA48dpTarget() {
        assertEquals(32.dp, Oe3Sizes.chip)
        assertEquals(48.dp, Oe3Sizes.interactiveMinHeight(Oe3Sizes.chip))
        assertEquals(48.dp, Oe3Sizes.button)
    }
}
