package io.github.nyzeep.orchestella.runtime.distro;

import java.net.URI;

public final class OfficialDebianCatalogTest {
    public static void main(String[] arguments) {
        DistroManifest debian = OfficialDebianCatalog.debian();
        assertEquals("debian", debian.id());
        assertEquals("13", debian.version());
        assertEquals("aarch64", debian.architecture());
        assertEquals(
                URI.create("https://cloud.debian.org/images/cloud/trixie/20261026-2643"
                        + "/debian-13-genericcloud-arm64-20261026-2643.tar.xz"),
                debian.source());
        assertEquals("debian-13-genericcloud-arm64-20261026-2643.tar.xz", debian.rootfsFileName());
        assertEquals("61ac9c6500688d04dfb52b7761f4e8eac891c97272b7da8867250b9e94df0645", debian.sha256());
        assertEquals(214_878_388L, debian.sizeBytes());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("pinned Debian identity changed: expected=" + expected + ", actual=" + actual);
        }
    }
}
