package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Debian 13 (trixie) arm64 rootfs.
 *
 * <p>Debian's cloud images are the obvious publisher-hosted choice, but their
 * {@code debian-13-genericcloud-arm64-*.tar.xz} is a tar holding a single 3 GiB
 * {@code disk.raw}, not a root filesystem: the guest extractor rejects it and the assembly
 * aborts at input verification (measured on device 2026-10-30). The linuxcontainers.org builds
 * that carry a real rootfs tree are pruned after a few days, so the project snapshots one onto
 * its immutable {@code distro-catalog-v1} release and pins that.
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
