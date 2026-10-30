package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.network.download.MirrorPriorityList

/**
 * Multi-source mirror lists for pinned distro rootfs downloads (ADR-0006, Ticket 51).
 *
 * <p>Two rules shape every list here, and both come from a failure this catalog has already
 * paid for:
 *
 * <ol>
 *   <li><b>Candidates must be byte-identical.</b> The engine treats a SHA-256 mismatch as
 *       tampering and fails closed instead of failing over, so a mirror serving a different
 *       build is strictly worse than no mirror at all.</li>
 *   <li><b>Artifacts must outlive the week.</b> linuxcontainers.org distrobuilder snapshots
 *       ({@code .../default/20261013_05:24/rootfs.tar.xz}) are pruned upstream within days and
 *       the TUNA/USTC {@code lxc-images} mirrors keep only the most recent three; every LXC
 *       path pinned by the previous catalog answered 404 by 2026-10-30. Mutable {@code -latest}
 *       names are just as unusable, because the pinned digest changes underneath them.</li>
 * </ol>
 *
 * <p>Where a publisher keeps a pinned, bootable rootfs for the life of a release, the catalog
 * points at that publisher and leads with its domestic mirror: Ubuntu's {@code ubuntu-base} on
 * TUNA/USTC, openSUSE's Leap appliance on TUNA. Fedora, AlmaLinux, Rocky Linux and Arch offer
 * only OCI layouts or mutable {@code -latest} tarballs, and Debian's cloud tarball is a raw
 * disk image rather than a root filesystem, so those five are snapshotted onto the project's
 * immutable {@code distro-catalog-v1} release and reached through the GitHub proxies below.
 */
object DistroMirrorCatalog {

    /**
     * GitHub release proxies, in the order they are tried. A CN network often cannot reach
     * github.com release assets directly and individual proxies come and go, so several are
     * listed; the health probe ranks them and the engine skips a dead one on the first 4xx
     * instead of retrying it.
     */
    private val GITHUB_PROXIES = listOf(
        "https://ghfast.top/https://github.com/",
        "https://gh-proxy.com/https://github.com/",
        "https://ghproxy.net/https://github.com/",
        "https://hk.gh-proxy.com/https://github.com/",
        "https://mirror.ghproxy.com/https://github.com/",
    )

    /** Proxy variants of a GitHub-hosted artifact; empty for any other host. */
    private fun githubMirrors(source: String): List<String> =
        if (!source.startsWith("https://github.com/")) {
            emptyList()
        } else {
            GITHUB_PROXIES.map { prefix -> substitutePrefix(source, "https://github.com/", prefix) }
        }

    fun mirrorsFor(manifest: DistroManifest): List<String> {
        val source = manifest.source().toString()
        val candidates = when (manifest.id()) {
            "ubuntu" -> listOf(
                substituteHost(source, "cdimage.ubuntu.com", "mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage"),
                substituteHost(source, "cdimage.ubuntu.com", "mirrors.ustc.edu.cn/ubuntu-cdimage"),
                source,
            )
            "opensuse" -> listOf(
                OPENSUSE_TUNA_MIRROR,
                source,
            )
            // Fedora, AlmaLinux, Rocky Linux, Arch and Debian are immutable snapshots on the
            // project's own catalog release: the publishers offer only OCI layouts, raw disk
            // images (Debian's genericcloud tar is a single 3 GiB disk.raw) or mutable -latest
            // tarballs, none of which can be pinned by digest.
            else -> listOf(source) + githubMirrors(source)
        }
        return MirrorPriorityList.of(*candidates.distinct().toTypedArray())
    }

    /** TUNA mirror of the pinned openSUSE Leap 15.6 appliance (byte-identical to upstream). */
    const val OPENSUSE_TUNA_MIRROR =
        "https://mirrors.tuna.tsinghua.edu.cn/opensuse/distribution/leap/15.6/appliances/opensuse-leap-image.aarch64-15.6.0-lxc-Build19.146.tar.xz"

    private fun substituteHost(url: String, fromHost: String, toHost: String): String =
        if (url.contains("://$fromHost/")) url.replace("://$fromHost/", "://$toHost/") else url

    private fun substitutePrefix(url: String, fromPrefix: String, toPrefix: String): String =
        if (url.startsWith(fromPrefix)) toPrefix + url.removePrefix(fromPrefix) else url
}
