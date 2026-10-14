package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Fedora 43 rootfs.
 *
 * <p>Uses the versioned linuxcontainers.org distrobuilder build {@code 20261013_20:33}
 * (upstream SHA256SUMS verified) with the TUNA lxc-images mirror leading the mirror chain.
 */
public final class OfficialFedoraCatalog {
    private OfficialFedoraCatalog() {}

    public static DistroManifest fedora() {
        return new DistroManifest(
                "fedora",
                "43",
                "aarch64",
                "https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/fedora-43-minimal-rootfs.tar.gz",
                "fedora-43-minimal-rootfs.tar.gz",
                "8974f57e713459753ca460a7900c903c6d376788d665bf907b2f4d7d1cb8638f",
                92_218_952L);
    }
}
