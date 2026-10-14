package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Rocky Linux 9 Container Base rootfs.
 *
 * <p>Uses the versioned linuxcontainers.org distrobuilder build {@code 20261013_03:17}
 * (upstream SHA256SUMS verified) with the TUNA lxc-images mirror leading the mirror chain.
 */
public final class OfficialRockyLinuxCatalog {
    private OfficialRockyLinuxCatalog() {}

    public static DistroManifest rocky() {
        return new DistroManifest(
                "rocky",
                "9.6",
                "aarch64",
                "https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/Rocky-9-Container-Base-9.6-20250531.0.aarch64.tar.xz",
                "Rocky-9-Container-Base-9.6-20250531.0.aarch64.tar.xz",
                "a68ba3a9ac9da45a69b3b3b6c420aa2b74d446548813adcbfdb46fc238f33d1b",
                103_658_208L);
    }
}
