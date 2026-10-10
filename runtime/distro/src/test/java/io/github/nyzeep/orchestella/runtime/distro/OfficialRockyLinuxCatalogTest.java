package io.github.nyzeep.orchestella.runtime.distro;

import java.net.URI;

public final class OfficialRockyLinuxCatalogTest {
    public static void main(String[] arguments) {
        DistroManifest rocky = OfficialRockyLinuxCatalog.rocky();
        assertEquals("rocky", rocky.id());
        assertEquals("9.5", rocky.version());
        assertEquals("aarch64", rocky.architecture());
        assertEquals(
                URI.create("https://download.rockylinux.org/pub/rocky/9.5/images/aarch64/Rocky-9-Container-Base-9.5-aarch64.tar.xz"),
                rocky.source());
        assertEquals("Rocky-9-Container-Base-9.5-aarch64.tar.xz", rocky.rootfsFileName());
        assertEquals("f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5", rocky.sha256());
        assertEquals(146_800_640L, rocky.sizeBytes());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("pinned Rocky Linux identity changed: expected=" + expected + ", actual=" + actual);
        }
    }
}
