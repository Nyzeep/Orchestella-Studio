package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Debian 13 (trixie) arm64 rootfs.
 *
 * <p>Points at Debian's own dated cloud-image build {@code 20261026-2643} rather than a
 * linuxcontainers.org snapshot: the project's mirrors keep only a few days of distrobuilder
 * builds, so the previous pin had already been pruned upstream (404 on every LXC mirror) and
 * the surviving copy lived only in this repository's GitHub release, which is unreachable
 * from mainland networks. cloud.debian.org keeps its dated directories for the life of the
 * release and the host is reachable from the test device (206 on a ranged GET, 2.8s).
 */
public final class OfficialDebianCatalog {
    private OfficialDebianCatalog() {}

    public static DistroManifest debian() {
        return new DistroManifest(
                "debian",
                "13",
                "aarch64",
                "https://cloud.debian.org/images/cloud/trixie/20261026-2643"
                        + "/debian-13-genericcloud-arm64-20261026-2643.tar.xz",
                "debian-13-genericcloud-arm64-20261026-2643.tar.xz",
                "61ac9c6500688d04dfb52b7761f4e8eac891c97272b7da8867250b9e94df0645",
                214_878_388L);
    }
}
