package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Fedora 43 Minimal Container rootfs.
 *
 * <p>Fedora ships current container images only as OCI archives, so the pinned asset is
 * the inner rootfs layer extracted from
 * {@code Fedora-Container-Base-Generic-Minimal-43-1.6.aarch64.oci.tar.xz} (layer
 * digest-verified during capture) and self-hosted on the project's
 * {@code distro-catalog-v1} release.
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
                "59327d988a136a185148842cf6625366e4815cb7e85d3d0802453c7b9e4af497",
                51_525_008L);
    }
}
