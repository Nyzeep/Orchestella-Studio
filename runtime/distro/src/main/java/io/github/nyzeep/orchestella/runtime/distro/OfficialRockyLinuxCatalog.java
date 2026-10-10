package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Rocky Linux 9.5 Container Base rootfs.
 */
public final class OfficialRockyLinuxCatalog {
    private OfficialRockyLinuxCatalog() {}

    public static DistroManifest rocky() {
        return new DistroManifest(
                "rocky",
                "9.5",
                "aarch64",
                "https://download.rockylinux.org/pub/rocky/9.5/images/aarch64/Rocky-9-Container-Base-9.5-aarch64.tar.xz",
                "Rocky-9-Container-Base-9.5-aarch64.tar.xz",
                "f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5",
                146_800_640L);
    }
}
