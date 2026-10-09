package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.distro.OfficialAlpineCatalog
import io.github.nyzeep.orchestella.runtime.distro.OfficialDebianCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class DistroMirrorCatalogTest {

    @Test
    fun alpineMirrorsLeadWithPinnedSourceAndAddDomesticAcceleration() {
        val manifest = OfficialAlpineCatalog.alpine()
        val expected = listOf(
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz",
            "https://mirrors.ustc.edu.cn/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz",
        )

        assertEquals(expected, DistroMirrorCatalog.mirrorsFor(manifest))
    }

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
            "alpine",
            "3.25.0",
            "aarch64",
            "https://dl-cdn.alpinelinux.org/alpine/v3.25/releases/aarch64/alpine-minirootfs-3.25.0-aarch64.tar.gz",
            "alpine-minirootfs-3.25.0-aarch64.tar.gz",
            "aa".repeat(32),
            4_000_000L,
        )

        val mirrors = DistroMirrorCatalog.mirrorsFor(bumped)

        assertEquals(
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.25/releases/aarch64/alpine-minirootfs-3.25.0-aarch64.tar.gz",
            mirrors[1],
        )
        assertEquals(
            "https://mirrors.ustc.edu.cn/alpine/v3.25/releases/aarch64/alpine-minirootfs-3.25.0-aarch64.tar.gz",
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
