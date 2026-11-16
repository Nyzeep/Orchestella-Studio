package io.github.nyzeep.orchestella.ui.oe3.screenshot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Performance/ownership gate for the glass layer (ticket UI3-10).
 *
 * The ticket's discipline is source-level on purpose: only the floating
 * chrome may blur, so Haze imports must stay inside the `ui/oe3/glass` tree
 * (the draw-path owner) and the `ui/shell/nav` tree (the bar). Card and page
 * code must never reference Haze or `Modifier.blur`, the backdrop blur stays
 * a single layer (exactly one `haze` source marker and one `hazeChild`
 * consumer, and none of the 2.x-only entry points), and the blur surface may
 * only be composed by the nav bar so the blurred region can never exceed
 * the bar.
 */
class Oe3GlassGateTest {

    private val uiRoot = File("src/main/java/io/github/nyzeep/orchestella/ui")

    private fun sourceFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun allUiFiles(): List<File> {
        assertTrue("missing Oe3 source root: ${uiRoot.absolutePath}", uiRoot.isDirectory)
        return sourceFiles(uiRoot)
    }

    @Test
    fun screensTreeDoesNotImportHaze() {
        val screens = File(uiRoot, "screens")
        assertTrue("missing screens root: ${screens.absolutePath}", screens.isDirectory)
        sourceFiles(screens).forEach { file ->
            assertFalse(
                "${file.path} is page content and must not import Haze",
                file.readText().contains("dev.chrisbanes.haze"),
            )
        }
    }

    @Test
    fun hazeImportsLiveOnlyInTheGlassAndNavZones() {
        val files = allUiFiles()
        assertTrue("expected Oe3 sources under ${uiRoot.absolutePath}", files.isNotEmpty())
        files.forEach { file ->
            val path = file.invariantSeparatorsPath
            val inGlassZone = "/ui/oe3/glass/" in path
            val inNavZone = "/ui/shell/nav/" in path
            if (file.readText().contains("dev.chrisbanes.haze")) {
                assertTrue(
                    "$path imports Haze; only ui/oe3/glass/** and ui/shell/nav/** may",
                    inGlassZone || inNavZone,
                )
            }
        }
    }

    @Test
    fun cardsAndPagesNeverUseModifierBlur() {
        allUiFiles().forEach { file ->
            val text = file.readText()
            val isPage = "/ui/screens/" in file.invariantSeparatorsPath
            val isCard = file.name.contains("Card") || text.contains("fun Oe3Card(")
            if (isPage || isCard) {
                assertFalse(
                    "${file.path} must not self-blur; glass belongs to the floating chrome",
                    text.contains("Modifier.blur(") || text.contains(".blur("),
                )
            }
        }
    }

    @Test
    fun backdropBlurStaysASingleLayerOnTheOnePointOhApi() {
        val text = allUiFiles().joinToString("\n") { it.readText() }
        assertEquals(
            "exactly one Haze source marker may exist (the glass source helper)",
            1,
            COUNT.compile(text, ".haze("),
        )
        assertEquals(
            "exactly one hazeChild consumer may exist (Oe3GlassSurface)",
            1,
            COUNT.compile(text, "hazeChild("),
        )
        // Haze 1.x API only: the 2.x entry points and any nested source marker
        // are structural defects, not style issues.
        assertEquals(0, COUNT.compile(text, "hazeSource("))
        assertEquals(0, COUNT.compile(text, "hazeEffect("))
        assertEquals(0, COUNT.compile(text, "Modifier.blur("))
    }

    @Test
    fun theBlurSurfaceIsComposedOnlyByTheNavChrome() {
        // Zone whitelist: the blur region is bounded by whoever composes
        // Oe3GlassSurface; only the nav bar (bar-height bounds) may do so.
        val allowed = setOf(
            "oe3/glass/Oe3GlassSurface.kt",
            "shell/nav/Oe3NavBar.kt",
        )
        allUiFiles()
            .filter { it.readText().contains("Oe3GlassSurface(") }
            .forEach { file ->
                val path = file.invariantSeparatorsPath
                val suffix = path.substringAfterLast("/ui/")
                assertTrue(
                    "$path composes Oe3GlassSurface; only $allowed may",
                    suffix in allowed,
                )
            }
    }

    private object COUNT {
        fun compile(text: String, needle: String): Int = text.split(needle).size - 1
    }
}
