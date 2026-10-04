package io.github.nyzeep.droidlayer.runtime.distro;

public final class OfficialDebianCatalog {
    private OfficialDebianCatalog() {}

    public static DistroManifest debian() {
        return new DistroManifest(
                "debian",
                "13",
                "aarch64",
                "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/trixie/arm64/default/20260930_05:24/rootfs.tar.xz",
                "rootfs.tar.xz",
                "7677f708be8b4df232ebdd177147b1b77beef7c784ce0fa3fbef5dd3e85ab461",
                90_371_792L);
    }
}
