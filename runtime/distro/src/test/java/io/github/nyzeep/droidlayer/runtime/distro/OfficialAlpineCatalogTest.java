package io.github.nyzeep.droidlayer.runtime.distro;

import java.net.URI;

public final class OfficialAlpineCatalogTest {
    public static void main(String[] arguments) {
        DistroManifest alpine = OfficialAlpineCatalog.alpine();
        assertEquals("alpine", alpine.id());
        assertEquals("3.24.1", alpine.version());
        assertEquals("aarch64", alpine.architecture());
        assertEquals(
                URI.create("https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz"),
                alpine.source());
        assertEquals("alpine-minirootfs-3.24.1-aarch64.tar.gz", alpine.rootfsFileName());
        assertEquals("f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259", alpine.sha256());
        assertEquals(4_023_732L, alpine.sizeBytes());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("pinned Alpine identity changed");
        }
    }
}
