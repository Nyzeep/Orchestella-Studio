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
                "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz",
                "ubuntu-base-24.04-base-arm64.tar.gz",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                89_128_960L);
    }
}
