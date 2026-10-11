package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.network.download.MirrorPriorityList

/**
 * Multi-source mirror lists for pinned distro rootfs downloads (ADR-0006, ADR-0019, Ticket 51).
 *
 * <p>Every list leads with the manifest's pinned HTTPS source; remaining entries are
 * host-substituted acceleration mirrors derived from that same URL, so a catalog version
 * bump propagates to all mirrors without editing mirror paths.
 *
 * <p>Since the 2026-10 catalog refresh, five of the seven pinned artifacts are self-hosted
 * on the project's immutable {@code distro-catalog-v1} GitHub release (upstream had 404'd
 * them: Debian release asset missing, Fedora 41 EOL, openSUSE Tumbleweed file pruned,
 * AlmaLinux/Rocky point releases pulled). Those chains therefore use GitHub acceleration
 * proxies for CN networks; Ubuntu/Arch keep their education-net mirror leads because the
 * mirrored files are byte-identical to the pinned sources.
 */
object DistroMirrorCatalog {

    fun mirrorsFor(manifest: DistroManifest): List<String> {
        val source = manifest.source().toString()
        val candidates = when (manifest.id()) {
            "ubuntu" -> listOf(
                substituteHost(source, "cdimage.ubuntu.com", "mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage"),
                substituteHost(source, "cdimage.ubuntu.com", "mirrors.ustc.edu.cn/ubuntu-cdimage"),
                source,
            )
            "debian" -> listOf(
                // CN-fast lead: TUNA's lxc-images mirror of the exact pinned distrobuilder
                // build (20261010_05:24), byte-identical to the self-hosted release asset.
                DEBIAN_TUNA_LXC_IMAGES_MIRROR,
                source,
                substitutePrefix(source, "https://github.com/", "https://ghproxy.net/https://github.com/"),
                substitutePrefix(source, "https://github.com/", "https://mirror.ghproxy.com/https://github.com/"),
            )
            "fedora", "opensuse", "almalinux", "rocky", "arch" -> listOf(
                source,
                substitutePrefix(source, "https://github.com/", "https://ghproxy.net/https://github.com/"),
                substitutePrefix(source, "https://github.com/", "https://mirror.ghproxy.com/https://github.com/"),
            )
            else -> listOf(source)
        }
        return MirrorPriorityList.of(*candidates.distinct().toTypedArray())
    }

    /** TUNA mirror of the pinned Debian 13 rootfs (linuxcontainers.org build 20261010_05:24). */
    private const val DEBIAN_TUNA_LXC_IMAGES_MIRROR =
        "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/trixie/arm64/default/20261010_05%3A24/rootfs.tar.xz"

    private fun substituteHost(url: String, fromHost: String, toHost: String): String =
        if (url.contains("://$fromHost/")) url.replace("://$fromHost/", "://$toHost/") else url

    private fun substitutePrefix(url: String, fromPrefix: String, toPrefix: String): String =
        if (url.startsWith(fromPrefix)) toPrefix + url.removePrefix(fromPrefix) else url
}
