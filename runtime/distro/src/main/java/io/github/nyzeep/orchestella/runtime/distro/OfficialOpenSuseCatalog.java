package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for openSUSE Leap 16.0 rootfs (aarch64, networkd flavor).
 *
 * <p>Switched from the Tumbleweed JeOS appliance (mutable rolling file, upstream now
 * prunes it) to the versioned, immutable Leap 16.0 Build18.75 rootfs tarball whose
 * upstream sha256 file matches the pinned digest; self-hosted on the project's
 * {@code distro-catalog-v1} release.
 */
public final class OfficialOpenSuseCatalog {
    private OfficialOpenSuseCatalog() {}

    public static DistroManifest opensuse() {
        return new DistroManifest(
                "opensuse",
                "16.0",
                "aarch64",
                "https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/opensuse-leap-image.aarch64-1.0.0-networkd-Build18.75.tar.xz",
                "opensuse-leap-image.aarch64-1.0.0-networkd-Build18.75.tar.xz",
                "d43d3e63203c3e08dbd5164a904fbf92df69ff21713ff18c82da179e00639ef6",
                31_426_856L);
    }
}
