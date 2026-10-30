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

    /**
     * GitHub release proxies, in the order they are tried. A CN network cannot reach
     * github.com release assets directly and individual proxies come and go, so several
     * are listed; the health probe ranks them and the engine skips a dead one on the first
     * 4xx instead of retrying it.
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
            // Debian publishes the rootfs itself and keeps dated builds for the release's
            // lifetime; the old LXC pin was pruned upstream (404 everywhere) so it is gone.
            "debian" -> listOf(source)
            "fedora" -> listOf(
                FEDORA_TUNA_LXC_IMAGES_MIRROR,
                source,
            ) + githubMirrors(source)
            "arch" -> listOf(
                ARCH_TUNA_MIRROR,
                ARCH_USTC_MIRROR,
                source,
            ) + githubMirrors(source)
            "opensuse" -> listOf(
                OPENSUSE_TUNA_MIRROR,
                OPENSUSE_UPSTREAM_MIRROR,
                source,
            ) + githubMirrors(source)
            "almalinux" -> listOf(
                ALMALINUX_TUNA_LXC_IMAGES_MIRROR,
                source,
            ) + githubMirrors(source)
            "rocky" -> listOf(
                ROCKY_TUNA_LXC_IMAGES_MIRROR,
                source,
            ) + githubMirrors(source)
            else -> listOf(source)
        }
        return MirrorPriorityList.of(*candidates.distinct().toTypedArray())
    }

    /** TUNA mirror of the pinned Debian 13 rootfs (linuxcontainers.org build 20261013_05:24). */
    const val DEBIAN_TUNA_LXC_IMAGES_MIRROR =
        "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/trixie/arm64/default/20261013_05%3A24/rootfs.tar.xz"

    /** TUNA mirror of the pinned Fedora 43 rootfs (linuxcontainers.org build 20261013_20:33). */
    const val FEDORA_TUNA_LXC_IMAGES_MIRROR =
        "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/fedora/43/arm64/default/20261013_20%3A33/rootfs.tar.xz"

    /** TUNA mirror of the pinned Arch Linux ARM aarch64 rootfs. */
    const val ARCH_TUNA_MIRROR =
        "https://mirrors.tuna.tsinghua.edu.cn/archlinuxarm/os/ArchLinuxARM-aarch64-latest.tar.gz"

    /** USTC mirror of the pinned Arch Linux ARM aarch64 rootfs. */
    const val ARCH_USTC_MIRROR =
        "https://mirrors.ustc.edu.cn/archlinuxarm/os/ArchLinuxARM-aarch64-latest.tar.gz"

    /** TUNA mirror of the pinned openSUSE Leap 15.6 appliance rootfs. */
    const val OPENSUSE_TUNA_MIRROR =
        "https://mirrors.tuna.tsinghua.edu.cn/opensuse/distribution/leap/15.6/appliances/opensuse-leap-image.aarch64-15.6.0-lxc-Build19.146.tar.xz"

    /** Upstream mirror of the pinned openSUSE Leap 15.6 appliance rootfs. */
    const val OPENSUSE_UPSTREAM_MIRROR =
        "https://download.opensuse.org/distribution/leap/15.6/appliances/opensuse-leap-image.aarch64-15.6.0-lxc-Build19.146.tar.xz"

    /** TUNA mirror of the pinned AlmaLinux 9 rootfs (linuxcontainers.org build 20261012_23:08). */
    const val ALMALINUX_TUNA_LXC_IMAGES_MIRROR =
        "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/almalinux/9/arm64/default/20261012_23%3A08/rootfs.tar.xz"

    /** TUNA mirror of the pinned Rocky Linux 9 rootfs (linuxcontainers.org build 20261013_03:17). */
    const val ROCKY_TUNA_LXC_IMAGES_MIRROR =
        "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/rockylinux/9/arm64/default/20261013_03%3A17/rootfs.tar.xz"

    private fun substituteHost(url: String, fromHost: String, toHost: String): String =
        if (url.contains("://$fromHost/")) url.replace("://$fromHost/", "://$toHost/") else url

    private fun substitutePrefix(url: String, fromPrefix: String, toPrefix: String): String =
        if (url.startsWith(fromPrefix)) toPrefix + url.removePrefix(fromPrefix) else url
}
