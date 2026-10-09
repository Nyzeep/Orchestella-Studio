package io.github.nyzeep.orchestella.runtime.distro;

public final class OfficialDebianCatalog {
    private OfficialDebianCatalog() {}

    public static DistroManifest debian() {
        return new DistroManifest(
                "debian",
                "13",
                "aarch64",
                "https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.4.0/debian-13-rootfs.tar.xz",
                "debian-13-rootfs.tar.xz",
                "8f4465c7c72332cc5d34912a049aa7f48131a440ffade0e057a7dd960ccc0035",
                90_332_396L);
    }
}
