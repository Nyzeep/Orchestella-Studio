package io.github.nyzeep.droidlayer

import io.github.nyzeep.droidlayer.runtime.distro.DistroManifest
import io.github.nyzeep.droidlayer.runtime.network.download.MirrorPriorityList

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
            "alpine" -> listOf(
                source,
                substitutePrefix(
                    source,
                    "https://dl-cdn.alpinelinux.org/alpine/",
                    "https://mirrors.tuna.tsinghua.edu.cn/alpine/",
                ),
                substitutePrefix(
                    source,
                    "https://dl-cdn.alpinelinux.org/alpine/",
                    "https://mirrors.ustc.edu.cn/alpine/",
                ),
            )
            "debian" -> listOf(
                source,
                substitutePrefix(
                    source,
                    "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/",
                    "https://images.linuxcontainers.org/",
                ),
                substitutePrefix(
                    source,
                    "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/",
                    "https://mirrors.ustc.edu.cn/lxc-images/",
                ),
            )
            else -> listOf(source)
        }
        return MirrorPriorityList.of(*candidates.distinct().toTypedArray())
    }

    private fun substitutePrefix(url: String, fromPrefix: String, toPrefix: String): String =
        if (url.startsWith(fromPrefix)) toPrefix + url.removePrefix(fromPrefix) else url
}
