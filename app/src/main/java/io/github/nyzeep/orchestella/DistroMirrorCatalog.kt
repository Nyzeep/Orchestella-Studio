package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.network.download.MirrorPriorityList

/**
 * Multi-source mirror lists for pinned distro rootfs downloads (ADR-0006, ADR-0019, Ticket 51).
 *
 * <p>Every list leads with the manifest's pinned HTTPS source; the remaining entries are
 * host-substituted acceleration mirrors derived from that same URL, so a catalog version
 * bump propagates to all mirrors without editing mirror paths. The dashboard
 * ([MainActivity.createDistro]) downloads through these lists; SetupWizardActivity still
 * carries its own hardcoded list — migrating it here is a follow-up.
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
            "arch" -> listOf(
                substituteHost(source, "archlinuxarm.org", "mirrors.tuna.tsinghua.edu.cn/archlinuxarm"),
                substituteHost(source, "archlinuxarm.org", "mirrors.ustc.edu.cn/archlinuxarm"),
                source,
            )
            "debian" -> listOf(
                source,
                substitutePrefix(
                    source,
                    "https://github.com/",
                    "https://ghproxy.net/https://github.com/",
                ),
                substitutePrefix(
                    source,
                    "https://github.com/",
                    "https://mirror.ghproxy.com/https://github.com/",
                ),
            )
            else -> listOf(source)
        }
        return MirrorPriorityList.of(*candidates.distinct().toTypedArray())
    }

    private fun substituteHost(url: String, fromHost: String, toHost: String): String =
        if (url.contains("://$fromHost/")) url.replace("://$fromHost/", "://$toHost/") else url

    private fun substitutePrefix(url: String, fromPrefix: String, toPrefix: String): String =
        if (url.startsWith(fromPrefix)) toPrefix + url.removePrefix(fromPrefix) else url
}
