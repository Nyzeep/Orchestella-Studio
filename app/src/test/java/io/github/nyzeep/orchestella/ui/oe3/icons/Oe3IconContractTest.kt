package io.github.nyzeep.orchestella.ui.oe3.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM contract for the Oe3 icon family. No Android runtime: the vectors
 * and the catalogue are plain JVM objects, so the geometry rules that every
 * page depends on are pinned here instead of being checked by eye.
 */
class Oe3IconContractTest {

    // --- family geometry ---------------------------------------------------

    @Test
    fun lineGlyphsAreOneStrokedPathOnThe24Grid() {
        assertTrue("navigation + actions must not be empty", Oe3Icons.all.size >= 14)
        for (icon in Oe3Icons.all) {
            val paths = pathsOf(icon)
            assertEquals("${icon.name} must be one path", 1, paths.size)
            val path = paths.single()
            assertNull("${icon.name} must not fill", path.fill)
            assertTrue("${icon.name} must stroke", path.stroke is SolidColor)
            assertEquals(
                "${icon.name} must be paint-any-colour black",
                Color.Black,
                (path.stroke as SolidColor).value,
            )
            assertEquals("${icon.name} line weight", 1.75f, path.strokeLineWidth, 0.0001f)
            assertEquals("${icon.name} cap", StrokeCap.Round, path.strokeLineCap)
            assertEquals("${icon.name} join", StrokeJoin.Round, path.strokeLineJoin)
            assertOn24Grid(icon)
        }
    }

    @Test
    fun navigationAndActionOrderIsPinned() {
        assertEquals(
            listOf(
                "Oe3.Workstation",
                "Oe3.Instances",
                "Oe3.Hardware",
                "Oe3.Distros",
                "Oe3.Config",
            ),
            Oe3Icons.navigation.map { it.name },
        )
        assertEquals(
            listOf(
                "Oe3.Terminal",
                "Oe3.Power",
                "Oe3.More",
                "Oe3.Create",
                "Oe3.Import",
                "Oe3.Ssh",
                "Oe3.Theme",
                "Oe3.About",
                "Oe3.Update",
            ),
            Oe3Icons.actions.map { it.name },
        )
        assertEquals(Oe3Icons.navigation + Oe3Icons.actions, Oe3Icons.all)
    }

    @Test
    fun distroMarksAreOneFilledPathWithNoOwnColour() {
        for (brand in Oe3DistroIcons.entries) {
            val paths = pathsOf(brand.vector)
            assertEquals("${brand.slug} must be one path", 1, paths.size)
            val path = paths.single()
            assertNull("${brand.slug} must not stroke", path.stroke)
            assertTrue("${brand.slug} must fill", path.fill is SolidColor)
            assertEquals(
                "${brand.slug} must be paint-any-colour black",
                Color.Black,
                (path.fill as SolidColor).value,
            )
            assertOn24Grid(brand.vector)
        }
    }

    @Test
    fun everyBrandEntryHasADistinctSlugAndPath() {
        assertEquals(
            "slugs must be unique",
            Oe3DistroIcons.entries.size,
            Oe3DistroIcons.entries.map { it.slug }.toSet().size,
        )
        assertEquals(
            "brand marks must not share geometry",
            Oe3DistroIcons.entries.size,
            Oe3DistroIcons.entries.map { it.pathData }.toSet().size,
        )
        for (brand in Oe3DistroIcons.entries) {
            assertTrue("${brand.slug} path data must not be blank", brand.pathData.isNotBlank())
        }
    }

    // --- catalogue coverage ------------------------------------------------

    @Test
    fun everyCatalogueDistroIdResolvesToItsOwnMark() {
        val catalogue = DistroCatalogRegistry.createDefault().listAll()
        assertTrue("catalogue must not be empty", catalogue.isNotEmpty())
        for (manifest in catalogue) {
            val brand = Oe3DistroIcons.brandOf(manifest.id())
            assertNotNull("missing Oe3 icon for distro id ${manifest.id()}", brand)
            assertTrue(
                "distro id ${manifest.id()} fell back to generic",
                Oe3DistroIcons.of(manifest.id()) !== Oe3DistroIcons.generic,
            )
        }
    }

    @Test
    fun onlyCataloguedDistrosAreExemptFromTheFallback() {
        val catalogued = DistroCatalogRegistry.createDefault().supportedDistroIds()
        for (brand in Oe3DistroIcons.entries) {
            if (brand.id !in catalogued) {
                // Alpine, Kali and the generic Linux mark are not in the current
                // catalogue; they exist for legacy records and for fallback.
                assertTrue(
                    "unexpected uncatalogued brand ${brand.id}",
                    brand.id in setOf("alpine", "kali", "linux"),
                )
            }
        }
    }

    @Test
    fun unknownAndBlankIdsFallBackToGeneric() {
        for (id in listOf(null, "", "   ", "plan9", "not-a-distro")) {
            assertSame("id <$id> must fall back", Oe3DistroIcons.generic, Oe3DistroIcons.of(id))
            assertNull("id <$id> must not resolve to a brand", Oe3DistroIcons.brandOf(id))
        }
    }

    @Test
    fun idsAreTrimmedAndCaseInsensitive() {
        val ubuntu = Oe3DistroIcons.brandOf("ubuntu")
        assertNotNull(ubuntu)
        assertSame(ubuntu, Oe3DistroIcons.brandOf("  UBUNTU  "))
        assertSame(ubuntu?.vector, Oe3DistroIcons.of("Ubuntu"))
    }

    @Test
    fun aliasesResolveToTheSameMark() {
        val aliases = mapOf(
            "arch" to "archlinux",
            "rocky" to "rockylinux",
            "alpine" to "alpinelinux",
            "kali" to "kalilinux",
        )
        for ((id, alias) in aliases) {
            val primary = Oe3DistroIcons.brandOf(id)
            assertNotNull("missing $id", primary)
            assertSame("$alias must alias $id", primary, Oe3DistroIcons.brandOf(alias))
        }
    }

    @Test
    fun genericMarkIsTheLinuxBrand() {
        val linux = Oe3DistroIcons.brandOf("linux")
        assertNotNull(linux)
        assertSame(linux?.vector, Oe3DistroIcons.generic)
    }

    // --- helpers -----------------------------------------------------------

    private fun assertOn24Grid(icon: ImageVector) {
        assertEquals("${icon.name} width", 24.dp, icon.defaultWidth)
        assertEquals("${icon.name} height", 24.dp, icon.defaultHeight)
        assertEquals("${icon.name} viewport width", 24f, icon.viewportWidth, 0.0001f)
        assertEquals("${icon.name} viewport height", 24f, icon.viewportHeight, 0.0001f)
    }

    private fun pathsOf(icon: ImageVector): List<VectorPath> = pathsOf(icon.root)

    private fun pathsOf(group: VectorGroup): List<VectorPath> {
        val result = mutableListOf<VectorPath>()
        for (node: VectorNode in group) {
            when (node) {
                is VectorPath -> result += node
                is VectorGroup -> result += pathsOf(node)
            }
        }
        return result
    }
}
