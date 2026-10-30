package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for AlmaLinux 9 Base rootfs (aarch64).
 *
 * <p>AlmaLinux publishes its container/cloud builds as OCI layouts and qcow2 images only, and the
 * linuxcontainers.org snapshots this entry used to pin are pruned after a few days. The snapshot
 * on the project's immutable {@code distro-catalog-v1} release is the pinned artifact; its
 * SHA-256 and byte count are the ones recorded when it was uploaded.
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
