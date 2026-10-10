package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Fedora 41 Minimal Container rootfs.
 */
public final class OfficialFedoraCatalog {
    private OfficialFedoraCatalog() {}

    public static DistroManifest fedora() {
        return new DistroManifest(
                "fedora",
                "41",
                "aarch64",
                "https://download.fedoraproject.org/pub/fedora/linux/releases/41/Container/aarch64/images/Fedora-Container-Minimal-Base-41-aarch64.tar.xz",
                "Fedora-Container-Minimal-Base-41-aarch64.tar.xz",
                "b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1",
                125_829_120L);
    }
}
