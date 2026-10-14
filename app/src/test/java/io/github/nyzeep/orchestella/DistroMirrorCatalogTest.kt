package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.distro.OfficialDebianCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class DistroMirrorCatalogTest {

    @Test
    fun ubuntuMirrorsLeadWithTunaAndUstcAndOfficialUpstream() {
        val manifest = io.github.nyzeep.orchestella.runtime.distro.OfficialUbuntuCatalog.ubuntu()
        val expected = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
        )

        assertEquals(expected, DistroMirrorCatalog.mirrorsFor(manifest))
    }

    @Test
    fun debianMirrorsLeadWithTunaLxcImagesThenPinnedSourceAndProxies() {
        val manifest = OfficialDebianCatalog.debian()
        val expected = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/trixie/arm64/default/20261013_05%3A24/rootfs.tar.xz",
            "https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/debian-13-rootfs.tar.xz",
            "https://ghproxy.net/https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/debian-13-rootfs.tar.xz",
            "https://mirror.ghproxy.com/https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/debian-13-rootfs.tar.xz",
        )

        assertEquals(expected, DistroMirrorCatalog.mirrorsFor(manifest))
    }

    @Test
    fun archMirrorsLeadWithTunaAndUstcThenPinnedSourceAndProxies() {
        val manifest = io.github.nyzeep.orchestella.runtime.distro.OfficialArchCatalog.arch()
        val mirrors = DistroMirrorCatalog.mirrorsFor(manifest)
        assertEquals(DistroMirrorCatalog.ARCH_TUNA_MIRROR, mirrors[0])
        assertEquals(DistroMirrorCatalog.ARCH_USTC_MIRROR, mirrors[1])
        assertEquals(manifest.source().toString(), mirrors[2])
    }

    @Test
    fun openSuseMirrorsLeadWithTunaAndUpstreamThenPinnedSourceAndProxies() {
        val manifest = io.github.nyzeep.orchestella.runtime.distro.OfficialOpenSuseCatalog.opensuse()
        val mirrors = DistroMirrorCatalog.mirrorsFor(manifest)
        assertEquals(DistroMirrorCatalog.OPENSUSE_TUNA_MIRROR, mirrors[0])
        assertEquals(DistroMirrorCatalog.OPENSUSE_UPSTREAM_MIRROR, mirrors[1])
        assertEquals(manifest.source().toString(), mirrors[2])
    }

    @Test
    fun fedoraMirrorsLeadWithTunaLxcImages() {
        val manifest = io.github.nyzeep.orchestella.runtime.distro.OfficialFedoraCatalog.fedora()
        val mirrors = DistroMirrorCatalog.mirrorsFor(manifest)
        assertEquals(DistroMirrorCatalog.FEDORA_TUNA_LXC_IMAGES_MIRROR, mirrors[0])
    }

    @Test
    fun almaLinuxMirrorsLeadWithTunaLxcImages() {
        val manifest = io.github.nyzeep.orchestella.runtime.distro.OfficialAlmaLinuxCatalog.almalinux()
        val mirrors = DistroMirrorCatalog.mirrorsFor(manifest)
        assertEquals(DistroMirrorCatalog.ALMALINUX_TUNA_LXC_IMAGES_MIRROR, mirrors[0])
    }

    @Test
    fun rockyMirrorsLeadWithTunaLxcImages() {
        val manifest = io.github.nyzeep.orchestella.runtime.distro.OfficialRockyLinuxCatalog.rocky()
        val mirrors = DistroMirrorCatalog.mirrorsFor(manifest)
        assertEquals(DistroMirrorCatalog.ROCKY_TUNA_LXC_IMAGES_MIRROR, mirrors[0])
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
            "https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.5.0/debian-14-rootfs.tar.xz",
            mirrors[1],
        )
        assertEquals(
            "https://ghproxy.net/https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.5.0/debian-14-rootfs.tar.xz",
            mirrors[2],
        )
        assertEquals(
            "https://mirror.ghproxy.com/https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.5.0/debian-14-rootfs.tar.xz",
            mirrors[3],
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
