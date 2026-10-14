package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for AlmaLinux 9 Base rootfs (aarch64).
 *
 * <p>Uses the versioned linuxcontainers.org distrobuilder build {@code 20261012_23:08}
 * (upstream SHA256SUMS verified) with the TUNA lxc-images mirror leading the mirror chain.
 */
public final class OfficialAlmaLinuxCatalog {
    private OfficialAlmaLinuxCatalog() {}

    public static DistroManifest almalinux() {
        return new DistroManifest(
                "almalinux",
                "9",
                "aarch64",
                "https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/AlmaLinux-9-Base-rootfs.tar.gz",
                "AlmaLinux-9-Base-rootfs.tar.gz",
                "543b8e13d355d4143cdda20c55bcfce32467d72211bc8cbe8c2fac1088834443",
                103_733_984L);
    }
}
