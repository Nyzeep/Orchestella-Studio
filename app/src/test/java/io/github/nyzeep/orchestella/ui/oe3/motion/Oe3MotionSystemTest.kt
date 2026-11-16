package io.github.nyzeep.orchestella.ui.oe3.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM contract for the system motion bridge (spec §2.5, ticket UI3-10):
 * `Settings.Global.ANIMATOR_DURATION_SCALE == 0` is the reduced-motion signal
 * and every duration collapses to 0ms through the token layer (`Oe3Motion.reduced()`).
 */
class Oe3MotionSystemTest {

    @Test
    fun zeroScaleMeansReducedMotion() {
        assertTrue(Oe3MotionSystem.isReducedMotion(0f))
        assertTrue(Oe3MotionSystem.isReducedMotion("0"))
        assertTrue(Oe3MotionSystem.isReducedMotion("0.0"))
    }

    @Test
    fun anyPositiveScaleMeansFullMotion() {
        assertFalse(Oe3MotionSystem.isReducedMotion(1f))
        assertFalse(Oe3MotionSystem.isReducedMotion(0.5f))
        assertFalse(Oe3MotionSystem.isReducedMotion("1"))
        assertFalse(Oe3MotionSystem.isReducedMotion("1.5"))
    }

    @Test
    fun aMissingOrCorruptSettingReadsAsTheSystemDefault() {
        assertEquals(1f, Oe3MotionSystem.parseScale(null), 0.0001f)
        assertEquals(1f, Oe3MotionSystem.parseScale(""), 0.0001f)
        assertEquals(1f, Oe3MotionSystem.parseScale("not-a-number"), 0.0001f)
        assertEquals(1f, Oe3MotionSystem.isReducedMotion(null).let { if (it) 0f else 1f }, 0.0001f)
    }

    @Test
    fun parseKeepsFractionalScalesAndRejectsGarbageSigns() {
        assertEquals(0.5f, Oe3MotionSystem.parseScale("0.5"), 0.0001f)
        assertEquals(2f, Oe3MotionSystem.parseScale(" 2 "), 0.0001f)
        // Negative scales are corrupt data, not "slower motion": treat as default.
        assertEquals(1f, Oe3MotionSystem.parseScale("-1"), 0.0001f)
    }
}
