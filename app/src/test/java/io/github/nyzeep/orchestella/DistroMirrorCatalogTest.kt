package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.distro.OfficialDebianCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class DistroMirrorCatalogTest {

    @Test
    fun debianMirrorsLeadWithPinnedSourceAndAddUpstreamAndUstc() {
        val manifest = OfficialDebianCatalog.debian()
        val expected = listOf(
            "https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.4.0/debian-13-rootfs.tar.xz",
            "https://ghproxy.net/https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.4.0/debian-13-rootfs.tar.xz",
            "https://mirror.ghproxy.com/https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.4.0/debian-13-rootfs.tar.xz",
        )

        assertEquals(expected, DistroMirrorCatalog.mirrorsFor(manifest))
    }

    @Test
    fun mirrorsFollowCatalogVersionBumpWithoutManualMirrorEdits() {
        val bumped = DistroManifest(
            "debian",
            "14",
            "aarch64",
            "https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.5.0/debian-14-rootfs.tar.xz",
            "debian-14-rootfs.tar.xz",
            "aa".repeat(32),
            95_000_000L,
        )

        val mirrors = DistroMirrorCatalog.mirrorsFor(bumped)

        assertEquals(
            "https://ghproxy.net/https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.5.0/debian-14-rootfs.tar.xz",
            mirrors[1],
        )
        assertEquals(
            "https://mirror.ghproxy.com/https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.5.0/debian-14-rootfs.tar.xz",
            mirrors[2],
        )
    }

    @Test
    fun unknownDistroFallsBackToPinnedSourceOnly() {
        val manifest = DistroManifest(
            "testlinux",
            "1.0",
            "aarch64",
            "https://example.com/path/file.tar.gz",
            "file.tar.gz",
            "bb".repeat(32),
            1_000L,
        )

        assertEquals(
            listOf("https://example.com/path/file.tar.gz"),
            DistroMirrorCatalog.mirrorsFor(manifest),
        )
    }
}
