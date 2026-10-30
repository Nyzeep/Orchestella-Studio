package io.github.nyzeep.orchestella.runtime.distro;

import java.net.URI;

public final class OfficialDebianCatalogTest {
    public static void main(String[] arguments) {
        DistroManifest debian = OfficialDebianCatalog.debian();
        assertEquals("debian", debian.id());
        assertEquals("13", debian.version());
        assertEquals("aarch64", debian.architecture());
        assertEquals(
                URI.create("https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/debian-13-rootfs.tar.xz"),
                debian.source());
        assertEquals("debian-13-rootfs.tar.xz", debian.rootfsFileName());
        assertEquals("000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907", debian.sha256());
        assertEquals(90_351_092L, debian.sizeBytes());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("pinned Debian identity changed: expected=" + expected + ", actual=" + actual);
        }
    }
}
