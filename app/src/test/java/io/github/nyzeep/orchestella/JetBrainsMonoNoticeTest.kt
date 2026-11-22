package io.github.nyzeep.orchestella

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W1-06 provenance and metrics contract for the shipped JetBrains Mono NL
 * v2.304 (terminal + every OeTypeRole.Mono surface).
 *
 * Three failure modes are pinned here instead of shipping: (1) a font file or
 * licence text drifts from the exact bytes pulled from the official v2.304
 * release; (2) the NOTICE stops matching the shipped artifacts; (3) a weight
 * loses the uniform-advance property the terminal's per-cell indexing depends
 * on. Everything is parsed straight from the shipped TTF bytes, so no
 * Robolectric graphics stack is involved.
 */
class JetBrainsMonoNoticeTest {

    // --- pinned provenance (mirrors NOTICE-jetbrains-mono.md; keep in sync) ---

    private val zipSha256 = "6f6376c6ed2960ea8a963cd7387ec9d76e3f629125bc33d1fdcd7eb7012f7bbf"

    private val pinnedFonts: Map<String, String> = mapOf(
        "jetbrains_mono_regular.ttf" to
            "fb3b2575d7b0657359707993288f12a7360344d39387bb26050e276d61f6bd2a",
        "jetbrains_mono_bold.ttf" to
            "0198e841824025f8876e5c297f0b9b497ee8d6eb9969710a3328e1303f996ec3",
        "jetbrains_mono_italic.ttf" to
            "c7392a134293e1af1d36fbf04940dd844f632afbf97f8325d1591d3af5096cb8",
        "jetbrains_mono_bold_italic.ttf" to
            "6c2716f2e85101f4109a8c16916061eb38176b3b10342834858d6221ec2fda1b",
    )

    private val licenseSha256 =
        "30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad"

    private val upstreamVersion = "2.304"
    private val fontFamilyName = "JetBrains Mono NL"

    /**
     * Latin cells plus the box-drawing / block characters the terminal actually
     * draws; every one of them must resolve to a real glyph with the same
     * advance in all four weights.
     */
    private val cellCharacters: IntArray = intArrayOf(
        'M'.code, 'W'.code, 'i'.code, '0'.code, ' '.code,
        0x2500, 0x2502, 0x250C, 0x2510, 0x2514, 0x2518, 0x251C, 0x2524,
        0x252C, 0x2534, 0x253C, 0x2550, 0x2551, 0x2554, 0x2557, 0x255A,
        0x255D, 0x2560, 0x2563, 0x2566, 0x2569, 0x256C, 0x2580, 0x2588,
        0x2591, 0x2592, 0x2593,
    )

    // --- provenance tests ----------------------------------------------------

    @Test
    fun shippedFontFilesMatchPinnedSha256() {
        for ((name, expected) in pinnedFonts) {
            val file = repoFile("app/src/main/res/font/$name")
            assertTrue("missing font asset: $name", file.isFile)
            assertEquals(
                "$name must be the pinned official v2.304 NL bytes",
                expected,
                file.sha256(),
            )
        }
    }

    @Test
    fun licenseFileIsThePinnedOfficialOflText() {
        val file = repoFile("app/src/main/assets/licenses/JetBrainsMono-OFL-1.1.txt")
        assertTrue("missing bundled OFL licence text", file.isFile)
        assertEquals(
            "bundled licence must be the official upstream OFL.txt bytes",
            licenseSha256,
            file.sha256(),
        )
        val text = file.readText()
        assertTrue(text.contains("SIL Open Font License, Version 1.1"))
        assertTrue(text.contains("Copyright 2020 The JetBrains Mono Project Authors"))
    }

    @Test
    fun noticePinsUpstreamProvenanceForEveryShippedFile() {
        val notice = repoFile("NOTICE-jetbrains-mono.md").readText()
        assertTrue(notice.contains(upstreamVersion))
        assertTrue(notice.contains("github.com/JetBrains/JetBrainsMono"))
        assertTrue("NOTICE must pin the release zip digest", notice.contains(zipSha256))
        assertTrue(notice.contains("SIL Open Font License"))
        assertTrue(notice.contains(fontFamilyName))
        for ((name, sha) in pinnedFonts) {
            assertTrue("NOTICE must list $name", notice.contains(name))
            assertTrue("NOTICE must pin the digest of $name", notice.contains(sha))
        }
    }

    @Test
    fun fontFamilyXmlMapsAllFourRealWeights() {
        val xml = repoFile("app/src/main/res/font/jetbrains_mono.xml").readText()
        for (name in pinnedFonts.keys) {
            val resource = name.removeSuffix(".ttf")
            assertTrue("font-family must reference $resource", xml.contains("@font/$resource"))
        }
        assertTrue("must expose both framework and AppCompat attributes", xml.contains("app:font"))
        assertTrue(xml.contains("android:font"))
    }

    // --- binary metrics tests -------------------------------------------------

    @Test
    fun fontsIdentifyAsJetBrainsMonoNlVersion2304() {
        for (name in pinnedFonts.keys) {
            val font = ShippedFont.load(repoFile("app/src/main/res/font/$name"))
            assertEquals(
                "$name family must be the NL variant (no-ligatures)",
                fontFamilyName,
                font.familyName,
            )
            assertTrue(
                "$name version string must pin $upstreamVersion, got ${font.versionString}",
                font.versionString.contains(upstreamVersion),
            )
        }
    }

    @Test
    fun terminalCellCharactersResolveToRealGlyphsInAllWeights() {
        for (name in pinnedFonts.keys) {
            val font = ShippedFont.load(repoFile("app/src/main/res/font/$name"))
            for (code in cellCharacters) {
                assertNotEquals(
                    "U+%04X must not fall back to .notdef in $name".format(code),
                    0,
                    font.glyphId(code),
                )
            }
        }
    }

    @Test
    fun allFourWeightsShareIdenticalAdvancesPerCharacter() {
        val perFont: Map<String, Map<Int, Int>> = pinnedFonts.keys.associateWith { name ->
            val font = ShippedFont.load(repoFile("app/src/main/res/font/$name"))
            cellCharacters.associateWith { code -> font.advanceOf(font.glyphId(code)) }
        }
        for ((name, advances) in perFont) {
            assertEquals(
                "$name must be monospace: every cell character shares one advance",
                1,
                advances.values.toSet().size,
            )
            assertTrue("advance must be non-zero in $name", advances.values.first() > 0)
        }
        // Cross-weight identity is what keeps bold rendering from shifting cells.
        val reference = perFont.getValue("jetbrains_mono_regular.ttf")
        for ((name, advances) in perFont) {
            assertEquals(
                "$name must share the regular weight's advances (no cell drift on bold paths)",
                reference,
                advances,
            )
        }
    }

    // --- shipped TTF reader ----------------------------------------------------

    /** Minimal sfnt reader: exactly the tables the provenance/metrics contract needs. */
    private class ShippedFont private constructor(
        val familyName: String,
        val versionString: String,
        private val charToGlyph: Map<Int, Int>,
        private val advances: List<Int>,
        private val lastAdvance: Int,
    ) {
        fun glyphId(code: Int): Int = charToGlyph[code] ?: 0

        fun advanceOf(glyph: Int): Int = if (glyph < advances.size) advances[glyph] else lastAdvance

        companion object {
            fun load(file: File): ShippedFont {
                val data = file.readBytes()
                val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                buf.position(4)
                val numTables = buf.short.toInt() and 0xFFFF
                val tables = HashMap<String, Int>()
                buf.position(12)
                repeat(numTables) {
                    val tag = String(CharArray(4) { buf.get().toInt().toChar() })
                    buf.position(buf.position() + 4) // checksum
                    tables[tag] = buf.int
                    buf.position(buf.position() + 4) // length
                }
                val head = tables.getValue("head")
                // Sanity: the advance contract below assumes upstream metrics.
                assertEquals(
                    "${file.name} must keep the upstream 1000 units/em",
                    1000,
                    u16(data, head + 18),
                )
                val advances = parseHmtx(data, tables)
                return ShippedFont(
                    familyName = name(data, tables, intArrayOf(1, 16)),
                    versionString = name(data, tables, intArrayOf(5)),
                    charToGlyph = parseCmap(data, tables.getValue("cmap")),
                    advances = advances,
                    lastAdvance = advances.last(),
                )
            }

            /** Windows (3,x) records win over Mac (1,x); nameID 16 falls back to 1. */
            private fun name(data: ByteArray, tables: Map<String, Int>, wantedIds: IntArray): String {
                val base = tables.getValue("name")
                val count = u16(data, base + 2)
                val stringOffset = base + u16(data, base + 4)
                var best: Pair<Int, String>? = null
                for (i in 0 until count) {
                    val rec = base + 6 + 12 * i
                    val platform = u16(data, rec)
                    val id = u16(data, rec + 6)
                    if (id !in wantedIds) continue
                    val length = u16(data, rec + 8)
                    val offset = u16(data, rec + 10)
                    val raw = data.copyOfRange(stringOffset + offset, stringOffset + offset + length)
                    val value = if (platform == 3) String(raw, Charsets.UTF_16BE) else String(raw, Charsets.ISO_8859_1)
                    val rank = if (platform == 3) 0 else 1
                    if (best == null || rank < best.first) best = rank to value
                }
                return checkNotNull(best?.second) { "name table lacks nameID ${wantedIds.contentToString()}" }
            }

            private fun parseCmap(data: ByteArray, cmapBase: Int): Map<Int, Int> {
                val count = u16(data, cmapBase + 2)
                data class Sub(val pid: Int, val eid: Int, val format: Int, val offset: Int)
                val subtables = ArrayList<Sub>(count)
                for (i in 0 until count) {
                    val rec = cmapBase + 4 + 8 * i
                    val offset = cmapBase + i32(data, rec + 4)
                    subtables.add(
                        Sub(u16(data, rec), u16(data, rec + 2), u16(data, offset), offset),
                    )
                }
                val rank = mapOf(
                    (3 to 10) to 0, (0 to 4) to 1, (0 to 6) to 2,
                    (3 to 1) to 3, (0 to 3) to 4, (0 to 1) to 5,
                )
                val pick = subtables.filter { rank.containsKey(it.pid to it.eid) }
                    .minByOrNull { rank.getValue(it.pid to it.eid) }
                    ?: throw AssertionError("no supported cmap subtable in shipped font")
                val map = HashMap<Int, Int>()
                when (pick.format) {
                    4 -> {
                        val segCount = u16(data, pick.offset + 6) / 2
                        val endsOff = pick.offset + 14
                        val startsOff = endsOff + 2 * segCount + 2
                        val deltasOff = startsOff + 2 * segCount
                        val rangesOff = deltasOff + 2 * segCount
                        for (s in 0 until segCount) {
                            val start = u16(data, startsOff + 2 * s)
                            val end = u16(data, endsOff + 2 * s)
                            val delta = i16(data, deltasOff + 2 * s)
                            val rangeOffset = u16(data, rangesOff + 2 * s)
                            for (c in start..end) {
                                if (c == 0xFFFF) continue
                                val glyph = if (rangeOffset == 0) {
                                    (c + delta) and 0xFFFF
                                } else {
                                    val idx = rangesOff + 2 * s + rangeOffset + 2 * (c - start)
                                    val g = u16(data, idx)
                                    if (g == 0) 0 else (g + delta) and 0xFFFF
                                }
                                if (glyph != 0 && c !in map) map[c] = glyph
                            }
                        }
                    }
                    12 -> {
                        val groups = i32(data, pick.offset + 12)
                        val groupsOff = pick.offset + 16
                        for (g in 0 until groups) {
                            val rec = groupsOff + 12 * g
                            val start = i32(data, rec)
                            val end = i32(data, rec + 4)
                            val startGlyph = i32(data, rec + 8)
                            for (c in start..end) map[c] = startGlyph + (c - start)
                        }
                    }
                    else -> throw AssertionError("unsupported cmap format ${pick.format} in shipped font")
                }
                return map
            }

            private fun parseHmtx(data: ByteArray, tables: Map<String, Int>): List<Int> {
                val numHMetrics = u16(data, tables.getValue("hhea") + 34)
                val hmtx = tables.getValue("hmtx")
                val advances = ArrayList<Int>(numHMetrics)
                for (i in 0 until numHMetrics) advances.add(u16(data, hmtx + 4 * i))
                return advances
            }

            private fun u16(data: ByteArray, off: Int): Int =
                (data[off].toInt() and 0xFF).shl(8) or (data[off + 1].toInt() and 0xFF)

            private fun i16(data: ByteArray, off: Int): Int =
                u16(data, off).let { if (it >= 0x8000) it - 0x10000 else it }

            private fun i32(data: ByteArray, off: Int): Int =
                ByteBuffer.wrap(data, off, 4).order(ByteOrder.BIG_ENDIAN).int
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun File.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(readBytes())
            .joinToString("") { "%02x".format(it) }

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
