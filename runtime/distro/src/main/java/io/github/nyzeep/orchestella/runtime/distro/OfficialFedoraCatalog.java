package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Fedora 43 minimal rootfs (aarch64).
 *
 * <p>Fedora publishes its container base only as an OCI layout
 * ({@code Fedora-Container-Base-Generic-Minimal-43-1.6.aarch64.oci.tar.xz}), which this runtime
 * cannot unpack as a root filesystem, and the linuxcontainers.org builds are pruned within days.
 * The snapshot on the project's immutable {@code distro-catalog-v1} release is therefore the
 * pinned artifact; its SHA-256 and byte count are the ones recorded when it was uploaded.
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
