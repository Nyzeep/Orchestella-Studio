package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Arch Linux ARM aarch64 rootfs.
 *
 * <p>Upstream publishes only a mutable {@code -latest} artifact, so the pinned copy is
 * self-hosted on the project's {@code distro-catalog-v1} release (snapshot 2026-10);
 * firstboot applies {@code pacman -Syu} on top of it.
 */
public final class OfficialArchCatalog {
    private OfficialArchCatalog() {}

    public static DistroManifest arch() {
        return new DistroManifest(
                "arch",
                "rolling",
                "aarch64",
                "https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/ArchLinuxARM-aarch64-latest.tar.gz",
                "ArchLinuxARM-aarch64-latest.tar.gz",
                "42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319",
                829_367_415L);
    }
}
