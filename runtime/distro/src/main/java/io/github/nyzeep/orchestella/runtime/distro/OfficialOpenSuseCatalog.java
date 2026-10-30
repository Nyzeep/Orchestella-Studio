package io.github.nyzeep.orchestella.runtime.distro;

/**
 * Pinned official catalog entry for openSUSE Leap 15.6 rootfs (aarch64).
 *
 * <p>Points at the publisher's own versioned Leap 15.6 Build19.146 appliance tarball. The build
 * number is part of the file name, so the artifact is immutable for the life of the release and
 * the digest is openSUSE's published {@code .sha256} for it. TUNA carries a byte-identical copy
 * and leads the mirror chain.
 */
public final class OfficialOpenSuseCatalog {
    private OfficialOpenSuseCatalog() {}

    public static DistroManifest opensuse() {
        return new DistroManifest(
                "opensuse",
                "15.6",
                "aarch64",
                "https://download.opensuse.org/distribution/leap/15.6/appliances/opensuse-leap-image.aarch64-15.6.0-lxc-Build19.146.tar.xz",
                "opensuse-leap-image.aarch64-15.6.0-lxc-Build19.146.tar.xz",
                "19d113ae23948bdd499e1e4ea140694a674a578c4812636b9452b6b33d47ecad",
                34_208_284L);
    }
}
