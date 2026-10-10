package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for Kali Linux Rolling arm64 rootfs.
 */
public final class OfficialKaliCatalog {
    private OfficialKaliCatalog() {}

    public static DistroManifest kali() {
        return new DistroManifest(
                "kali",
                "rolling",
                "aarch64",
                "https://kali.download/arm-images/kali-rolling/kali-linux-rolling-arm64-rootfs.tar.xz",
                "kali-linux-rolling-arm64-rootfs.tar.xz",
                "d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3",
                136_314_880L);
    }
}
