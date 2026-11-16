package io.github.nyzeep.orchestella.ui.oe3.screenshot

import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Compile-closure smoke for the Haze pin.
 *
 * Haze is added by UI3-11 for UI3-10's glass chrome. A class literal proves the
 * artifact's Kotlin metadata is readable by this toolchain (Haze 2.1.0 is not:
 * it is compiled with metadata 2.4 and pulls Compose 1.12 / AGP 9.1), without
 * bringing the blur API into production code before UI3-10 lands.
 */
class HazeDependencySmokeTest {

    @Test
    fun hazeStateIsReadableFromThisToolchain() {
        assertEquals("dev.chrisbanes.haze.HazeState", HazeState::class.qualifiedName)
    }
}
