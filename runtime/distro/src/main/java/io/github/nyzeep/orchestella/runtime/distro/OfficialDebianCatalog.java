package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Debian 13 (trixie) arm64 rootfs.
 *
 * <p>Previous releases referenced a GitHub release asset that was never uploaded (404 in
 * the field). The pinned artifact is now the versioned linuxcontainers.org distrobuilder
 * build {@code 20261010_05:24} (upstream SHA256SUMS verified) self-hosted on the
 * project's {@code distro-catalog-v1} release, with the TUNA lxc-images mirror of the
 * same build leading the mirror chain for CN networks.
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
                "e4d504e13e43a384227639b74391ef164a67d916aa8f8a9871ecd73dc577db91",
                90_346_224L);
    }
}
