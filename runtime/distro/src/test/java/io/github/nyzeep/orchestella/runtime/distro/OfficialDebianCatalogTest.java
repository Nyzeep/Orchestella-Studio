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
        assertEquals("e4d504e13e43a384227639b74391ef164a67d916aa8f8a9871ecd73dc577db91", debian.sha256());
        assertEquals(90_346_224L, debian.sizeBytes());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("pinned Debian identity changed: expected=" + expected + ", actual=" + actual);
        }
    }
}
