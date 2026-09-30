package io.github.nyzeep.droidlayer.runtime.distro;

public final class OfficialAlpineCatalog {
    private OfficialAlpineCatalog() {}

    public static DistroManifest alpine() {
        return new DistroManifest(
                "alpine",
                "3.24.1",
                "aarch64",
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz",
                "alpine-minirootfs-3.24.1-aarch64.tar.gz",
                "f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259",
                4_023_732L);
    }
}
