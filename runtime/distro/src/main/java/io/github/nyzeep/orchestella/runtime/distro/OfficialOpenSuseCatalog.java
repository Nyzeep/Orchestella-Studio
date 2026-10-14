package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for openSUSE Leap 15.6 rootfs (aarch64).
 *
 * <p>Uses the versioned, immutable Leap 15.6 Build19.146 LXC appliance rootfs tarball whose
 * upstream sha256 matches the pinned digest.
 */
public final class OfficialOpenSuseCatalog {
    private OfficialOpenSuseCatalog() {}

    public static DistroManifest opensuse() {
        return new DistroManifest(
                "opensuse",
                "15.6",
                "aarch64",
                "https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/opensuse-leap-image.aarch64-15.6.0-lxc-Build19.146.tar.xz",
                "opensuse-leap-image.aarch64-15.6.0-lxc-Build19.146.tar.xz",
                "19d113ae23948bdd499e1e4ea140694a674a578c4812636b9452b6b33d47ecad",
                34_208_284L);
    }
}
