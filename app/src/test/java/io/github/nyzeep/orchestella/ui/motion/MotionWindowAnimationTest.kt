package io.github.nyzeep.orchestella.ui.motion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-07: floating layers were the last motion surface the app theme did not
 * own. `MaterialAlertDialogBuilder` resolved its window animation from Material
 * defaults (300/400 ms, library curves), so the documented floating-layer token
 * never reached it.
 *
 * These assertions pin the wiring: the app theme routes the alert family to an
 * Orchestella overlay, and that overlay points at `res/anim` resources whose
 * durations come from `oe_motion_duration_dialog_*`.
 *
 * UI-13 (2026-11-18): the AppCompat `BottomSheetDialog` family is gone — the
 * three remaining sheets became Compose pages — so its overlay, animation and
 * animation resources were deleted. The bottom-sheet half of this test now
 * pins that absence instead of a slide shape.
 */
class MotionWindowAnimationTest {

    private val res: File = listOf(File("src/main/res"), File("app/src/main/res"))
        .firstOrNull { it.isDirectory }
        ?: error("cannot locate res/ from ${File(".").absolutePath}")

    private fun xml(relative: String): String = File(res, relative).readText()

    private fun styleBody(name: String): String {
        val text = listOf("values/themes.xml", "values/styles.xml")
            .joinToString("\n") { xml(it) }
        val match = Regex(
            """<style\s+name="${Regex.escape(name)}"[^>]*>(.*?)</style>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(text) ?: error("style $name is not defined")
        return match.groupValues[1]
    }

    private fun itemValue(style: String, item: String): String? = Regex(
        """<item\s+name="${Regex.escape(item)}"\s*>\s*([^<]+?)\s*</item>""",
    ).find(styleBody(style))?.groupValues?.get(1)

    @Test
    fun appThemeRoutesTheAlertDialogFamilyThroughItsOverlay() {
        assertEquals(
            "@style/ThemeOverlay.Orchestella.MaterialAlertDialog",
            itemValue("Theme.Orchestella", "materialAlertDialogTheme"),
        )
        assertNull(
            "no BottomSheetDialog remains, so the theme must not keep its overlay hook",
            itemValue("Theme.Orchestella", "bottomSheetDialogTheme"),
        )
    }

    private fun styleParent(name: String): String? = Regex(
        """<style\s+name="${Regex.escape(name)}"\s+parent="([^"]+)"""",
    ).find(listOf("values/themes.xml", "values/styles.xml").joinToString("\n") { xml(it) })
        ?.groupValues?.get(1)

    @Test
    fun dialogOverlayOverridesOnlyTheWindowAnimationStyle() {
        val overlay = "ThemeOverlay.Orchestella.MaterialAlertDialog"
        assertEquals(
            "Material overlay parent must be kept",
            "ThemeOverlay.Material3.MaterialAlertDialog",
            styleParent(overlay),
        )
        assertEquals(
            "@style/Animation.Orchestella.Dialog",
            itemValue(overlay, "android:windowAnimationStyle"),
        )
    }

    @Test
    fun dialogWindowAnimationsAreAlphaFadesOnTokenDurations() {
        assertEquals(
            "@anim/oe_dialog_fade_enter",
            itemValue("Animation.Orchestella.Dialog", "android:windowEnterAnimation"),
        )
        assertEquals(
            "@anim/oe_dialog_fade_exit",
            itemValue("Animation.Orchestella.Dialog", "android:windowExitAnimation"),
        )

        val enter = xml("anim/oe_dialog_fade_enter.xml")
        assertEquals(
            "@integer/oe_motion_duration_dialog_enter",
            Regex("""android:duration="([^"]+)"""").find(enter)!!.groupValues[1],
        )
        assertTrue("alert dialogs still fade in", enter.contains("<alpha"))
        assertTrue(
            "alert dialogs keep the platform 0.8 scale-from",
            enter.contains("""android:fromXScale="0.8""""),
        )

        val exit = xml("anim/oe_dialog_fade_exit.xml")
        assertEquals(
            "@integer/oe_motion_duration_dialog_exit",
            Regex("""android:duration="([^"]+)"""").find(exit)!!.groupValues[1],
        )
    }

    @Test
    fun bottomSheetDialogFamilyIsGone() {
        val styles = listOf("values/themes.xml", "values/styles.xml")
            .joinToString("\n") { xml(it) }
        assertFalse(
            "UI-13 removed every AppCompat BottomSheetDialog; no style may survive",
            styles.contains("BottomSheetDialog"),
        )
    }

    @Test
    fun standardEasingResourceExistsAgain() {
        // OrchestellaMotion documents res/anim/oe_standard.xml as the mirror of
        // the (0.2, 0, 0, 1) curve; the file had gone missing, so the floating
        // layer would have had no XML curve to reference.
        val standard = xml("anim/oe_standard.xml")
        assertTrue(standard.contains("""android:controlX1="0.2""""))
        assertTrue(standard.contains("""android:controlY1="0.0""""))
        assertTrue(standard.contains("""android:controlX2="0.0""""))
        assertTrue(standard.contains("""android:controlY2="1.0""""))
    }
}
