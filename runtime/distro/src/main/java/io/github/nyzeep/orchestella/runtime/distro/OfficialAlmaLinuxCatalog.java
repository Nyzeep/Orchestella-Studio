package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for AlmaLinux 9 Base rootfs (aarch64).
 *
 * <p>The pinned asset is the official {@code quay.io/almalinuxorg/9-base} arm64 layer
 * (digest-verified during capture) self-hosted on the project's
 * {@code distro-catalog-v1} release.
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
                "ae65b206007f1635dfb045e9ac0e67dbdd9820c536115298b7e89cc078895d37",
                66_985_382L);
    }
}
