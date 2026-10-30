package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Rocky Linux 9 Container Base rootfs (aarch64).
 *
 * <p>Rocky now ships its container base as {@code .oci.tar.xz} plus a mutable
 * {@code ...-latest.aarch64.tar.xz} name; neither can be pinned by digest. The dated
 * {@code 9.6-20250531.0} rootfs snapshot on the project's immutable {@code distro-catalog-v1}
 * release is the pinned artifact, and its SHA-256 / byte count are the uploaded ones.
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
