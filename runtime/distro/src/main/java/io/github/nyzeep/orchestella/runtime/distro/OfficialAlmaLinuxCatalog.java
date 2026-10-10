package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for AlmaLinux 9.5 Minimal Container rootfs.
 */
public final class OfficialAlmaLinuxCatalog {
    private OfficialAlmaLinuxCatalog() {}

    public static DistroManifest almalinux() {
        return new DistroManifest(
                "almalinux",
                "9.5",
                "aarch64",
                "https://repo.almalinux.org/almalinux/9.5/cloud/aarch64/images/AlmaLinux-9-Container-Minimal-9.5-aarch64.tar.xz",
                "AlmaLinux-9-Container-Minimal-9.5-aarch64.tar.xz",
                "e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4",
                142_606_336L);
    }
}
