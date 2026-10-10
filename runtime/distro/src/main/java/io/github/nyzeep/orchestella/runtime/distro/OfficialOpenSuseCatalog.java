package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for openSUSE Tumbleweed JeOS rootfs.
 */
public final class OfficialOpenSuseCatalog {
    private OfficialOpenSuseCatalog() {}

    public static DistroManifest opensuse() {
        return new DistroManifest(
                "opensuse",
                "tumbleweed",
                "aarch64",
                "https://download.opensuse.org/ports/aarch64/tumbleweed/appliances/openSUSE-Tumbleweed-ARM-JeOS-rootfs.aarch64.tar.xz",
                "openSUSE-Tumbleweed-ARM-JeOS-rootfs.aarch64.tar.xz",
                "c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2",
                115_343_360L);
    }
}
