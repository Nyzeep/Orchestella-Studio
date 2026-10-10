package io.github.nyzeep.orchestella

import androidx.annotation.DrawableRes
import java.util.Locale

/**
 * Helper providing unified vector icon resources and friendly labels for all 7 distros
 * and graceful fallback for legacy distros (ADR-0006, Phase 5.0 R1 / R4).
 */
object DistroIconHelper {

    @DrawableRes
    @JvmStatic
    fun getIconRes(distroId: String?): Int {
        if (distroId == null) {
            return R.drawable.ic_distro_legacy
        }
        return when (distroId.trim().lowercase(Locale.ROOT)) {
            "ubuntu" -> R.drawable.ic_distro_ubuntu
            "debian" -> R.drawable.ic_distro_debian
            "fedora" -> R.drawable.ic_distro_fedora
            "arch" -> R.drawable.ic_distro_arch
            "opensuse" -> R.drawable.ic_distro_opensuse
            "almalinux" -> R.drawable.ic_distro_almalinux
            "rocky" -> R.drawable.ic_distro_rocky
            else -> R.drawable.ic_distro_legacy
        }
    }

    @JvmStatic
    fun getDisplayName(distroId: String?): String {
        if (distroId == null) {
            return "Linux"
        }
        return when (distroId.trim().lowercase(Locale.ROOT)) {
            "ubuntu" -> "Ubuntu"
            "debian" -> "Debian"
            "fedora" -> "Fedora"
            "arch" -> "Arch"
            "opensuse" -> "openSUSE"
            "almalinux" -> "AlmaLinux"
            "rocky" -> "Rocky Linux"
            "alpine" -> "Alpine (Legacy)"
            "kali" -> "Kali (Legacy)"
            else -> distroId.replaceFirstChar { it.uppercase() } + " (Legacy)"
        }
    }
}
