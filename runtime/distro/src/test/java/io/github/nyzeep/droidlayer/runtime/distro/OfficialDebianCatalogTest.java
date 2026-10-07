package io.github.nyzeep.droidlayer.runtime.distro;

import java.net.URI;

public final class OfficialDebianCatalogTest {
    public static void main(String[] arguments) {
        DistroManifest debian = OfficialDebianCatalog.debian();
        assertEquals("debian", debian.id());
        assertEquals("13", debian.version());
        assertEquals("aarch64", debian.architecture());
        assertEquals(
                URI.create("https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.4.0/debian-13-rootfs.tar.xz"),
                debian.source());
        assertEquals("debian-13-rootfs.tar.xz", debian.rootfsFileName());
        assertEquals("8f4465c7c72332cc5d34912a049aa7f48131a440ffade0e057a7dd960ccc0035", debian.sha256());
        assertEquals(90_332_396L, debian.sizeBytes());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("pinned Debian identity changed: expected=" + expected + ", actual=" + actual);
        }
    }
}
