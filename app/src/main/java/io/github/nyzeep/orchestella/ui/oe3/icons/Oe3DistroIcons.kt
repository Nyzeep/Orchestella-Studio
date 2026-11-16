package io.github.nyzeep.orchestella.ui.oe3.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import java.util.Locale

/**
 * Licence that a distro mark arrives under. The simple-icons package is CC0,
 * but individual marks may carry their own terms; [entries] records the real
 * one per slug and NOTICE-icons.md must agree with it.
 */
enum class Oe3IconLicense(val label: String, val url: String) {
    Cc0("CC0-1.0", "https://creativecommons.org/publicdomain/zero/1.0/"),
    CcBySa30("CC-BY-SA-3.0", "https://creativecommons.org/licenses/by-sa/3.0/"),
    CcBySa40("CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/"),
    FedoraBrand("Fedora brand terms", "https://docs.fedoraproject.org/en-US/project/brand/"),
}

/**
 * One distro mark: a single filled path from simple-icons, rendered on the
 * same 24dp grid as the line glyphs.
 *
 * [id] is the id used by `DistroCatalogRegistry`; [aliases] covers the other
 * spellings that reach the UI (for example `archlinux` from older records).
 */
class Oe3BrandIcon internal constructor(
    val id: String,
    val slug: String,
    val displayName: String,
    val license: Oe3IconLicense,
    val aliases: List<String>,
    val pathData: String,
) {
    /** Built once: distro grids ask for the same mark on every recomposition. */
    val vector: ImageVector by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ImageVector.Builder(
            name = "Oe3Distro.$slug",
            defaultWidth = Oe3IconStyle.size,
            defaultHeight = Oe3IconStyle.size,
            viewportWidth = Oe3IconStyle.viewport,
            viewportHeight = Oe3IconStyle.viewport,
        ).addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
        ).build()
    }
}

/**
 * Distro marks for the five-pane shell.
 *
 * The catalogue is the source of truth for ids: look at
 * `DistroCatalogRegistry.createDefault()`. Every id it returns resolves to a
 * brand vector; anything else (null, blank, a future distro) gets the generic
 * Linux mark instead of crashing or drawing nothing.
 *
 * The vectors carry no colour. Paint them with [Oe3Icon] or `Icon(..., tint=)`
 * so the mark follows `onSurfaceVariant` / `primary` like every other glyph.
 * The brand outlines are not redrawn: each vector keeps the upstream geometry
 * and only drops the colour, as required by the trademark and licence notes in
 * NOTICE-icons.md.
 */
object Oe3DistroIcons {

    /** Brand entries, one per distinct mark. Aliases are not repeated here. */
    val entries: List<Oe3BrandIcon> = listOf(
        Oe3BrandIcon(
            id = "ubuntu",
            slug = "ubuntu",
            displayName = "Ubuntu",
            license = Oe3IconLicense.Cc0,
            aliases = emptyList(),
            pathData = Oe3BrandPathsCc0.Oe3UbuntuPath,
        ),
        Oe3BrandIcon(
            id = "debian",
            slug = "debian",
            displayName = "Debian",
            license = Oe3IconLicense.CcBySa30,
            aliases = emptyList(),
            pathData = Oe3BrandPathsShareAlike.Oe3DebianPath,
        ),
        Oe3BrandIcon(
            id = "fedora",
            slug = "fedora",
            displayName = "Fedora",
            license = Oe3IconLicense.FedoraBrand,
            aliases = emptyList(),
            pathData = Oe3BrandPathsShareAlike.Oe3FedoraPath,
        ),
        Oe3BrandIcon(
            id = "arch",
            slug = "archlinux",
            displayName = "Arch Linux",
            license = Oe3IconLicense.Cc0,
            aliases = listOf("archlinux"),
            pathData = Oe3BrandPathsCc0.Oe3ArchlinuxPath,
        ),
        Oe3BrandIcon(
            id = "opensuse",
            slug = "opensuse",
            displayName = "openSUSE",
            license = Oe3IconLicense.Cc0,
            aliases = emptyList(),
            pathData = Oe3BrandPathsCc0.Oe3OpensusePath,
        ),
        Oe3BrandIcon(
            id = "almalinux",
            slug = "almalinux",
            displayName = "AlmaLinux",
            license = Oe3IconLicense.Cc0,
            aliases = emptyList(),
            pathData = Oe3BrandPathsCc0.Oe3AlmalinuxPath,
        ),
        Oe3BrandIcon(
            id = "rocky",
            slug = "rockylinux",
            displayName = "Rocky Linux",
            license = Oe3IconLicense.CcBySa40,
            aliases = listOf("rockylinux"),
            pathData = Oe3BrandPathsShareAlike.Oe3RockylinuxPath,
        ),
        // Not in the current catalogue, but old instances and the setup wizard
        // still carry these ids; they must not fall back while such a record
        // is on screen.
        Oe3BrandIcon(
            id = "alpine",
            slug = "alpinelinux",
            displayName = "Alpine Linux",
            license = Oe3IconLicense.Cc0,
            aliases = listOf("alpinelinux"),
            pathData = Oe3BrandPathsCc0.Oe3AlpinelinuxPath,
        ),
        Oe3BrandIcon(
            id = "kali",
            slug = "kalilinux",
            displayName = "Kali Linux",
            license = Oe3IconLicense.Cc0,
            aliases = listOf("kalilinux"),
            pathData = Oe3BrandPathsCc0.Oe3KalilinuxPath,
        ),
        Oe3BrandIcon(
            id = "linux",
            slug = "linux",
            displayName = "Linux",
            license = Oe3IconLicense.Cc0,
            aliases = emptyList(),
            pathData = Oe3BrandPathsCc0.Oe3LinuxPath,
        ),
    )

    private val byId: Map<String, Oe3BrandIcon> = buildMap {
        for (icon in Oe3DistroIcons.entries) {
            put(icon.id.lowercase(Locale.ROOT), icon)
            for (alias in icon.aliases) {
                put(alias.lowercase(Locale.ROOT), icon)
            }
        }
    }

    /** Fallback for unknown, blank or missing distro ids. */
    val generic: ImageVector = entries.first { it.id == "linux" }.vector

    /** Brand record for [id], or null when the id is not a known distro. */
    fun brandOf(id: String?): Oe3BrandIcon? {
        val key = id?.trim()?.lowercase(Locale.ROOT)
        if (key.isNullOrEmpty()) {
            return null
        }
        return byId[key]
    }

    /** Mark for [id]; unknown ids resolve to [generic] instead of failing. */
    fun of(id: String?): ImageVector = brandOf(id)?.vector ?: generic
}
