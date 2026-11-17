package io.github.nyzeep.orchestella.ui.oe3

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural gate from the ticket: the new design language expresses hierarchy
 * with tone only. Any outline or depth primitive anywhere under ui/oe3 fails
 * the build instead of being caught by eye in review.
 */
class Oe3StructureTest {

    @Test
    fun oe3SourcesUseNoOutlineOrDepthPrimitives() {
        val base = File("src/main/java/io/github/nyzeep/orchestella/ui/oe3")
        assertTrue("missing Oe3 source root: ${base.absolutePath}", base.isDirectory)

        // Scope to the directories UI3-01 owns; ui/oe3/icons belongs to UI3-03.
        val files = listOf("tokens", "theme", "components")
            .map { File(base, it) }
            .flatMap { dir ->
                assertTrue("missing Oe3 source dir: ${dir.absolutePath}", dir.isDirectory)
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
        assertTrue("expected the Oe3 primitives to exist", files.size >= 10)

        val forbidden = listOf("strokeWidth", "border", "shadowElevation")
        files.forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse(
                    "${file.name} must not reference '$token'",
                    text.contains(token, ignoreCase = true),
                )
            }
        }
    }
}
