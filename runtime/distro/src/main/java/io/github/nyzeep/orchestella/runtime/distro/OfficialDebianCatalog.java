package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Debian 13 (trixie) arm64 rootfs.
 *
 * <p>Uses the versioned linuxcontainers.org distrobuilder build {@code 20261013_05:24}
 * (upstream SHA256SUMS verified) with the TUNA lxc-images mirror leading the mirror chain.
 */
public final class OfficialDebianCatalog {
    private OfficialDebianCatalog() {}

    public static DistroManifest debian() {
        return new DistroManifest(
                "debian",
                "13",
                "aarch64",
                "https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/debian-13-rootfs.tar.xz",
                "debian-13-rootfs.tar.xz",
                "000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907",
                90_351_092L);
    }
}
