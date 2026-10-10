package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Arch Linux ARM aarch64 rootfs.
 */
public final class OfficialArchCatalog {
    private OfficialArchCatalog() {}

    public static DistroManifest arch() {
        return new DistroManifest(
                "arch",
                "rolling",
                "aarch64",
                "https://archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
                "ArchLinuxARM-aarch64-latest.tar.gz",
                "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
                188_743_680L);
    }
}
