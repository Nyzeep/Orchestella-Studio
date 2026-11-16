package io.github.nyzeep.orchestella.ui.oe3.screenshot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structure gate for the Compose migration.
 *
 * The ticket's rule is source-level on purpose: a screenshot cannot prove a
 * card has no outline, and code review misses one `strokeWidth` in a large
 * page diff. The scan covers the Oe3 primitives plus the shell and screen
 * trees as soon as they exist; only `ui/oe3/icons` is exempt from the stroke
 * rule, because vector glyphs are strokes by design.
 */
class Oe3StructureGateTest {

    private val uiRoot = File("src/main/java/io/github/nyzeep/orchestella/ui")

    @Test
    fun cardsAndPagesUseToneInsteadOfOutlineOrMaterialCards() {
        assertTrue("missing Oe3 source root: ${uiRoot.absolutePath}", File(uiRoot, "oe3").isDirectory)
        val files = sourceFiles()
        assertTrue("expected Oe3 sources under ${uiRoot.absolutePath}", files.isNotEmpty())

        files.forEach { file ->
            val text = file.readText()
            assertFalse(
                "${file.path} must not use MaterialCardView",
                text.contains("MaterialCardView", ignoreCase = true),
            )
            if (!file.normalizedPath().contains("/ui/oe3/icons/")) {
                assertFalse(
                    "${file.path} must not use strokeWidth (only ui/oe3/icons may stroke)",
                    text.contains("strokeWidth", ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun cardFilesDoNotImportHaze() {
        val cardFiles = sourceFiles().filter { file ->
            val text = file.readText()
            // Case-sensitive: "Discard.kt" is not a card.
            file.name.contains("Card") ||
                text.contains("fun Oe3Card(") ||
                text.contains("fun Oe3GridCard(")
        }
        assertTrue("expected at least one card source", cardFiles.isNotEmpty())

        cardFiles.forEach { file ->
            assertFalse(
                "${file.path} is a card and must not import Haze",
                file.readText().contains("dev.chrisbanes.haze"),
            )
        }
    }

    private fun sourceFiles(): List<File> {
        val roots = listOf(
            File(uiRoot, "oe3"),
            File(uiRoot, "shell"),
            File(uiRoot, "screens"),
        ).filter { it.isDirectory }

        return roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        }
    }

    private fun File.normalizedPath(): String = path.replace('\\', '/')
}
