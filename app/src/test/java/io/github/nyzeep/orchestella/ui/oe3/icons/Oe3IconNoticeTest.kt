package io.github.nyzeep.orchestella.ui.oe3.icons

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the provenance contract: exactly the brand marks we ship are listed in
 * NOTICE-icons.md, each one names its real licence (the simple-icons package is
 * CC0 but individual marks are not all CC0), and each slug maps to one matching
 * vector drawable. A missing entry, a stale licence or a drifted path fails
 * here instead of shipping.
 */
class Oe3IconNoticeTest {

    private val notice: String by lazy { repoFile("NOTICE-icons.md").readText() }

    @Test
    fun noticePinsTheUpstreamVersion() {
        assertTrue("NOTICE must pin simple-icons 16.38.0", notice.contains("16.38.0"))
        assertTrue(
            "NOTICE must link the CC0 deed",
            notice.contains("creativecommons.org/publicdomain/zero/1.0"),
        )
    }

    @Test
    fun noticeHasOneRowPerBrandAndNoExtraRows() {
        val rows = noticeRows()
        assertEquals("NOTICE rows must match shipped brand marks", Oe3DistroIcons.entries.size, rows.size)
        assertEquals(
            "NOTICE slugs must match shipped slugs",
            Oe3DistroIcons.entries.map { it.slug }.toSet(),
            rows.map { it.slug }.toSet(),
        )
    }

    @Test
    fun upstreamLicenseExpectationsArePinned() {
        // Evidence: simple-icons 16.38.0 data/simple-icons.json records Debian
        // as CC-BY-SA-3.0, Rocky as CC-BY-SA-4.0 and Fedora as "custom"; the
        // rest carry no separate entry and ship under the package's CC0-1.0.
        // Debian also offers LGPL-3.0-or-later, which this project takes.
        val expected = mapOf(
            "ubuntu" to "CC0-1.0",
            "debian" to "LGPL-3.0-or-later",
            "fedora" to "Fedora brand terms",
            "archlinux" to "CC0-1.0",
            "alpinelinux" to "CC0-1.0",
            "kalilinux" to "CC0-1.0",
            "opensuse" to "CC0-1.0",
            "almalinux" to "CC0-1.0",
            "rockylinux" to "CC-BY-SA-4.0",
            "linux" to "CC0-1.0",
        )
        assertEquals(
            "the shipped licence table must not drift from the pinned upstream facts",
            expected,
            Oe3DistroIcons.entries.associate { it.slug to it.license.label },
        )
    }

    @Test
    fun noticeRecordsTheRealLicensePerBrand() {
        val bySlug = Oe3DistroIcons.entries.associateBy { it.slug }
        for (row in noticeRows()) {
            val brand = bySlug.getValue(row.slug)
            assertEquals(
                "name for ${row.slug} must match the icon table",
                brand.displayName,
                row.name,
            )
            assertEquals(
                "licence for ${row.slug} must match the icon table",
                brand.license.label,
                row.license,
            )
        }
    }

    @Test
    fun everyBrandSlugHasOneMatchingDrawable() {
        for (brand in Oe3DistroIcons.entries) {
            val file = repoFile("app/src/main/res/drawable/oe3_distro_${brand.slug}.xml")
            assertTrue("missing drawable for ${brand.slug}", file.isFile)
            val xml = file.readText()
            assertEquals(
                "oe3_distro_${brand.slug}.xml must contain exactly one <path>",
                1,
                Regex("<path\\b").findAll(xml).count(),
            )
            assertTrue(
                "oe3_distro_${brand.slug}.xml must not wrap the path in a <group>",
                !xml.contains("<group"),
            )
            assertTrue(
                "oe3_distro_${brand.slug}.xml must use one plain black fill",
                xml.contains("android:fillColor=\"#FF000000\""),
            )
            for (forbidden in listOf("strokeColor", "gradient", "android:tint")) {
                assertTrue(
                    "oe3_distro_${brand.slug}.xml must not contain $forbidden",
                    !xml.contains(forbidden),
                )
            }
            val match = Regex("android:pathData=\"([^\"]*)\"").find(xml)
            assertTrue("oe3_distro_${brand.slug}.xml has no pathData", match != null)
            assertEquals(
                "drawable and ImageVector drifted for ${brand.slug}",
                normalize(brand.pathData),
                normalize(match!!.groupValues[1]),
            )
        }
    }

    @Test
    fun noOrphanOrUnlistedDistroDrawableExists() {
        val drawableDir = repoFile("app/src/main/res/drawable")
        val shipped = drawableDir.listFiles().orEmpty()
            .filter { it.isFile }
            .map { it.name }
            .filter { it.startsWith("oe3_distro_") && it.endsWith(".xml") }
            .map { it.removePrefix("oe3_distro_").removeSuffix(".xml") }
            .toSet()
        assertEquals(
            "every oe3_distro_*.xml must have a NOTICE entry",
            Oe3DistroIcons.entries.map { it.slug }.toSet(),
            shipped,
        )
    }

    @Test
    fun noBitmapVariantsWereAdded() {
        val res = repoFile("app/src/main/res")
        val bitmaps = res.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("drawable") }
            .flatMap { it.listFiles().orEmpty().asList() }
            .filter { it.name.startsWith("oe3_") }
            .filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif") }
        assertTrue("Oe3 icons must stay vector-only, found $bitmaps", bitmaps.isEmpty())
    }

    // --- helpers -----------------------------------------------------------

    private data class NoticeRow(val slug: String, val name: String, val license: String)

    private fun noticeRows(): List<NoticeRow> {
        val slug = Regex("^[a-z0-9]+$")
        return notice.lineSequence()
            .filter { it.trimStart().startsWith("|") }
            .map { line -> line.trim().trim('|').split('|').map { it.trim() } }
            .filter { cells -> cells.size >= 3 && slug.matches(cells[0]) }
            .map { cells -> NoticeRow(cells[0], cells[1], cells[2]) }
            .toList()
    }

    /** Collapse the whitespace that SVG path grammar treats as a separator. */
    private fun normalize(pathData: String): String =
        pathData.replace(',', ' ').trim().replace(Regex("\\s+"), " ")

    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) {
                return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${File("").absolutePath}")
    }
}
