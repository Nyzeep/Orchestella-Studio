package io.github.nyzeep.orchestella.ui.motion

import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-07: the XML half of the motion system.
 *
 * `OeMotion` is the only source of durations in Kotlin, but `res/anim` and the
 * window-animation style can only read resources. This test pins the resource
 * integers to the very same `OeMotion` constants, so the two halves can never
 * drift again, and forbids literal `android:duration` values in `res/anim`.
 */
class MotionXmlTokenTest {

    private val res: File = listOf(File("src/main/res"), File("app/src/main/res"))
        .firstOrNull { it.isDirectory }
        ?: error("cannot locate res/ from ${File(".").absolutePath}")

    private fun xml(relative: String): String = File(res, relative).readText()

    /** name -> value for every `<integer>` in the motion token file. */
    private fun motionIntegers(): Map<String, Int> {
        val text = xml("values/oe_motion_tokens.xml")
        return Regex("""<integer\s+name="([^"]+)"\s*>\s*(-?\d+)\s*</integer>""")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }

    @Test
    fun xmlDurationsMirrorTheSingleMotionSource() {
        val integers = motionIntegers()

        assertEquals(
            "short3 is the View exit duration",
            OrchestellaMotion.DURATION_EXIT_MS,
            integers["oe_motion_duration_short3"],
        )
        assertEquals(
            "emphasized_enter is the activity enter duration",
            OrchestellaMotion.DURATION_ACTIVITY_ENTER_MS,
            integers["oe_motion_duration_emphasized_enter"],
        )
        assertEquals(
            "dialog enter mirrors the Compose dialog token",
            OrchestellaMotion.DURATION_DIALOG_ENTER_MS,
            integers["oe_motion_duration_dialog_enter"],
        )
        assertEquals(
            "dialog exit mirrors the View dialog exit token",
            OrchestellaMotion.DURATION_DIALOG_EXIT_MS,
            integers["oe_motion_duration_dialog_exit"],
        )
    }

    @Test
    fun everyAnimDurationIsAReferenceToAMotionInteger() {
        val literals = mutableListOf<String>()
        val dangling = mutableListOf<String>()
        val integers = motionIntegers()

        File(res, "anim").listFiles { file -> file.extension == "xml" }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                val text = file.readText()
                Regex("""android:duration\s*=\s*"([^"]*)"""")
                    .findAll(text)
                    .forEach { match ->
                        val value = match.groupValues[1]
                        val name = Regex("""@integer/(\w+)""").find(value)?.groupValues?.get(1)
                        when {
                            name == null -> literals += "anim/${file.name}: $value"
                            name !in integers -> dangling += "anim/${file.name}: $value"
                        }
                    }
            }

        assertTrue("res/anim must not hard-code durations: $literals", literals.isEmpty())
        assertTrue("res/anim references undefined motion integers: $dangling", dangling.isEmpty())
    }

    @Test
    fun activityWindowAnimationsStayWiredToOneStyle() {
        val themes = xml("values/themes.xml")
        assertTrue(
            "Theme.Orchestella must route window animations through one style",
            themes.contains(
                """<item name="android:windowAnimationStyle">@style/Animation.Orchestella.Activity</item>""",
            ),
        )
        assertTrue(
            "Animation.Orchestella.Activity must exist",
            themes.contains("""<style name="Animation.Orchestella.Activity""""),
        )
    }

    @Test
    fun motionScaleIsNotRedefinedOutsideTheSingleSource() {
        // A guard against a second duration table creeping into the View layer:
        // OrchestellaMotion may only alias OeMotion constants.
        val source = File("src/main/java/io/github/nyzeep/orchestella/ui/motion/OrchestellaMotion.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/io/github/nyzeep/orchestella/ui/motion/OrchestellaMotion.kt")
        val numericConstants = Regex("""const val (\w+)\s*=\s*(-?\d+(?:\.\d+)?f?)""")
            .findAll(source.readText())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertTrue(
            "OrchestellaMotion must not declare its own durations: $numericConstants",
            numericConstants.isEmpty(),
        )
    }

    @Test
    fun motionSingleSourceKeepsTheViewDurationsTheXmlExpects() {
        // Independent literals from the design spec; if the source is retuned,
        // this fails and the XML integers have to move with it.
        assertEquals(150, OeMotion.VIEW_EXIT_MS)
        assertEquals(210, OeMotion.VIEW_ACTIVITY_ENTER_MS)
        assertEquals(250, OeMotion.VIEW_DIALOG_ENTER_MS)
        assertEquals(150, OeMotion.VIEW_DIALOG_EXIT_MS)
    }
}
