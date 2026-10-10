package io.github.nyzeep.orchestella.runtime.distro;

import java.net.URI;

public final class OfficialAlmaLinuxCatalogTest {
    public static void main(String[] arguments) {
        DistroManifest almalinux = OfficialAlmaLinuxCatalog.almalinux();
        assertEquals("almalinux", almalinux.id());
        assertEquals("9.5", almalinux.version());
        assertEquals("aarch64", almalinux.architecture());
        assertEquals(
                URI.create("https://repo.almalinux.org/almalinux/9.5/cloud/aarch64/images/AlmaLinux-9-Container-Minimal-9.5-aarch64.tar.xz"),
                almalinux.source());
        assertEquals("AlmaLinux-9-Container-Minimal-9.5-aarch64.tar.xz", almalinux.rootfsFileName());
        assertEquals("e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4", almalinux.sha256());
        assertEquals(142_606_336L, almalinux.sizeBytes());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("pinned AlmaLinux identity changed: expected=" + expected + ", actual=" + actual);
        }
    }
}
