package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Ubuntu 24.04 LTS (Noble Numbat) minimal rootfs.
 */
public final class OfficialUbuntuCatalog {
    private OfficialUbuntuCatalog() {}

    public static DistroManifest ubuntu() {
        return new DistroManifest(
                "ubuntu",
                "24.04",
                "aarch64",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                "ubuntu-base-24.04.5-base-arm64.tar.gz",
                "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
                29_936_675L);
    }
}
