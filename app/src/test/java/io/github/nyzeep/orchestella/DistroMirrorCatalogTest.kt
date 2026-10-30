package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.distro.OfficialDebianCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistroMirrorCatalogTest {

    private val proxies = listOf("ghfast.top", "gh-proxy.com", "ghproxy.net", "hk.gh-proxy.com", "mirror.ghproxy.com")

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
    fun debianIsSelfHostedBecauseEveryPublisherArtifactIsARawDiskImage() {
        val manifest = OfficialDebianCatalog.debian()
        val pinned = manifest.source().toString()
        val expected = listOf(pinned) + proxies.map { "https://$it/$pinned" }

        assertEquals(expected, DistroMirrorCatalog.mirrorsFor(manifest))
    }

    @Test
    fun openSuseMirrorsLeadWithTunaThenThePublisher() {
        val manifest = io.github.nyzeep.orchestella.runtime.distro.OfficialOpenSuseCatalog.opensuse()
        val expected = listOf(
            DistroMirrorCatalog.OPENSUSE_TUNA_MIRROR,
            "https://download.opensuse.org/distribution/leap/15.6/appliances/opensuse-leap-image.aarch64-15.6.0-lxc-Build19.146.tar.xz",
        )

        assertEquals(expected, DistroMirrorCatalog.mirrorsFor(manifest))
    }

    @Test
    fun selfHostedDistrosLeadWithTheCatalogUrlAndAddProxies() {
        listOf(
            io.github.nyzeep.orchestella.runtime.distro.OfficialFedoraCatalog.fedora(),
            io.github.nyzeep.orchestella.runtime.distro.OfficialArchCatalog.arch(),
            io.github.nyzeep.orchestella.runtime.distro.OfficialAlmaLinuxCatalog.almalinux(),
            io.github.nyzeep.orchestella.runtime.distro.OfficialRockyLinuxCatalog.rocky(),
        ).forEach { manifest ->
            val pinned = manifest.source().toString()
            val expected = listOf(pinned) + proxies.map { "https://$it/$pinned" }
            assertEquals("mirror chain for ${manifest.id()}", expected, DistroMirrorCatalog.mirrorsFor(manifest))
        }
    }

    @Test
    fun noChainPointsAtAPrunedLinuxcontainersSnapshot() {
        io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry.createDefault()
            .listAll()
            .forEach { manifest ->
                val chain = DistroMirrorCatalog.mirrorsFor(manifest)
                assertTrue(
                    "${manifest.id()} still lists an LXC snapshot: $chain",
                    chain.none { it.contains("lxc-images") || it.contains("linuxcontainers.org") },
                )
            }
    }

    @Test
    fun everyChainKeepsAtLeastOneCandidate() {
        io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry.createDefault()
            .listAll()
            .forEach { manifest ->
                assertTrue("empty chain for ${manifest.id()}", DistroMirrorCatalog.mirrorsFor(manifest).isNotEmpty())
            }
    }

    @Test
    fun mirrorsFollowCatalogVersionBumpWithoutManualMirrorEdits() {
        val bumped = DistroManifest(
            "fedora",
            "44",
            "aarch64",
            "https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/fedora-44-minimal-rootfs.tar.gz",
            "fedora-44-minimal-rootfs.tar.gz",
            "aa".repeat(32),
            95_000_000L,
        )

        val mirrors = DistroMirrorCatalog.mirrorsFor(bumped)

        assertEquals(
            "https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/fedora-44-minimal-rootfs.tar.gz",
            mirrors[0],
        )
        assertEquals(
            "https://ghfast.top/https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/fedora-44-minimal-rootfs.tar.gz",
            mirrors[1],
        )
        assertEquals(
            "https://gh-proxy.com/https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/fedora-44-minimal-rootfs.tar.gz",
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
