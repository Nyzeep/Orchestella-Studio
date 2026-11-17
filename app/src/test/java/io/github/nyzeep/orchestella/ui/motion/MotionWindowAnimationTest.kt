package io.github.nyzeep.orchestella.ui.motion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-07: floating layers were the last motion surface the app theme did not
 * own. `MaterialAlertDialogBuilder` and `BottomSheetDialog` resolved their
 * window animation from Material defaults (300/400 ms, library curves), so the
 * documented floating-layer token never reached them.
 *
 * These assertions pin the wiring: the app theme routes both dialog families
 * to Orchestella overlays, and those overlays point at `res/anim` resources
 * whose durations come from `oe_motion_duration_dialog_*`. The motion *shape*
 * is intentionally the one the theme already showed (a fade for alerts, a
 * 20% slide + fade for bottom sheets) — only the clock becomes token-driven.
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
    fun appThemeRoutesBothDialogFamiliesThroughOrchestellaOverlays() {
        assertEquals(
            "@style/ThemeOverlay.Orchestella.MaterialAlertDialog",
            itemValue("Theme.Orchestella", "materialAlertDialogTheme"),
        )
        assertEquals(
            "@style/ThemeOverlay.Orchestella.BottomSheetDialog",
            itemValue("Theme.Orchestella", "bottomSheetDialogTheme"),
        )
    }

    private fun styleParent(name: String): String? = Regex(
        """<style\s+name="${Regex.escape(name)}"\s+parent="([^"]+)"""",
    ).find(listOf("values/themes.xml", "values/styles.xml").joinToString("\n") { xml(it) })
        ?.groupValues?.get(1)

    @Test
    fun dialogOverlaysOverrideOnlyTheWindowAnimationStyle() {
        listOf(
            "ThemeOverlay.Orchestella.MaterialAlertDialog" to
                ("Animation.Orchestella.Dialog" to "ThemeOverlay.Material3.MaterialAlertDialog"),
            "ThemeOverlay.Orchestella.BottomSheetDialog" to
                ("Animation.Orchestella.BottomSheetDialog" to "ThemeOverlay.Material3.BottomSheetDialog"),
        ).forEach { (overlay, expected) ->
            val (animation, parent) = expected
            assertEquals("$overlay must keep the Material overlay parent", parent, styleParent(overlay))
            assertEquals(
                "@style/$animation",
                itemValue(overlay, "android:windowAnimationStyle"),
            )
        }
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
    fun bottomSheetWindowAnimationsKeepTheSlideShape() {
        assertEquals(
            "@anim/oe_bottom_sheet_slide_enter",
            itemValue("Animation.Orchestella.BottomSheetDialog", "android:windowEnterAnimation"),
        )
        assertEquals(
            "@anim/oe_bottom_sheet_slide_exit",
            itemValue("Animation.Orchestella.BottomSheetDialog", "android:windowExitAnimation"),
        )

        val enter = xml("anim/oe_bottom_sheet_slide_enter.xml")
        assertTrue("bottom sheets still slide up 20%", enter.contains("""android:fromYDelta="20%p""""))
        assertTrue("bottom sheets still fade in", enter.contains("<alpha"))
        assertTrue(
            "slide duration comes from the dialog token",
            enter.contains("""android:duration="@integer/oe_motion_duration_dialog_enter""""),
        )

        val exit = xml("anim/oe_bottom_sheet_slide_exit.xml")
        assertTrue("bottom sheets still slide down 20%", exit.contains("""android:toYDelta="20%p""""))
        assertTrue(
            "slide-out duration comes from the dialog exit token",
            exit.contains("""android:duration="@integer/oe_motion_duration_dialog_exit""""),
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
