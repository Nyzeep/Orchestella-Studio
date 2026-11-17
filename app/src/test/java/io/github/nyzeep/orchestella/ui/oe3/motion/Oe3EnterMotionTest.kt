package io.github.nyzeep.orchestella.ui.oe3.motion

import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entrance-motion contract (spec §2.5, ticket ZD-W1): the cardEnter and
 * dialog tokens are consumed through the pure spec objects, keep their
 * documented duration/easing/scale in the standard scale, and collapse to
 * 0ms / scale 1.0 under reduced motion. The floating-layer hosts are pinned
 * to the shared composable so a call site cannot silently drop the token.
 */
class Oe3EnterMotionTest {

    @Test
    fun cardEnterUsesTheDocumentedScaleAndDuration() {
        val motion = Oe3Motion.standard()
        assertEquals(300, Oe3CardEnterSpec.fade(motion).durationMillis)
        assertEquals(300, Oe3CardEnterSpec.scale(motion).durationMillis)
        assertEquals(motion.cardEnter.easing, Oe3CardEnterSpec.fade(motion).easing)
        assertEquals(0.96f, Oe3CardEnterSpec.scaleFrom(motion), 0.0001f)
    }

    @Test
    fun cardEnterCollapsesToInstantUnderReducedMotion() {
        val reduced = Oe3Motion.reduced()
        assertEquals(0, Oe3CardEnterSpec.fade(reduced).durationMillis)
        assertEquals(0, Oe3CardEnterSpec.scale(reduced).durationMillis)
        assertEquals(1f, Oe3CardEnterSpec.scaleFrom(reduced), 0.0001f)
    }

    @Test
    fun dialogUsesTheDocumentedEaseOutCubicCurve() {
        val motion = Oe3Motion.standard()
        assertEquals(250, Oe3DialogEnterSpec.fade(motion).durationMillis)
        assertEquals(250, Oe3DialogEnterSpec.scale(motion).durationMillis)
        assertEquals(motion.dialog.easing, Oe3DialogEnterSpec.fade(motion).easing)
        assertEquals(motion.dialog.easing, Oe3DialogEnterSpec.scale(motion).easing)
        // The floating layer scales from the tokens' shared 0.96 scale-from.
        assertEquals(0.96f, Oe3DialogEnterSpec.scaleFrom(motion), 0.0001f)
    }

    @Test
    fun dialogCollapsesToInstantUnderReducedMotion() {
        val reduced = Oe3Motion.reduced()
        assertEquals(0, Oe3DialogEnterSpec.fade(reduced).durationMillis)
        assertEquals(0, Oe3DialogEnterSpec.scale(reduced).durationMillis)
        assertEquals(1f, Oe3DialogEnterSpec.scaleFrom(reduced), 0.0001f)
    }

    @Test
    fun everyFloatingLayerHostConsumesTheDialogEnterMotion() {
        listOf(
            "src/main/java/io/github/nyzeep/orchestella/ui/screens/theme/CustomSlotEditorDialog.kt",
            "src/main/java/io/github/nyzeep/orchestella/ui/screens/hardware/HardwareScreen.kt",
            "src/main/java/io/github/nyzeep/orchestella/ui/screens/instances/InstancesScreen.kt",
        ).forEach { path ->
            assertTrue(
                "dialog host must consume Oe3DialogEnterMotion: $path",
                File(path).readText().contains("Oe3DialogEnterMotion"),
            )
        }
    }
}
