package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Rocky Linux 9.6 Container Base rootfs.
 *
 * <p>The 9.5 artifact the previous catalog pointed at was pulled from upstream CDN, so
 * the pinned copy is the immutable 9.6 vault artifact (official CHECKSUM sha256
 * 257534a5...) self-hosted on the project's {@code distro-catalog-v1} release.
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
                "257534a55c3422b91cf1bf2dfcd02a752d3b2661902b76350b7a9ca39cc27edb",
                50_342_584L);
    }
}
