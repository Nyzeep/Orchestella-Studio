package io.github.nyzeep.orchestella.runtime.distro;

import java.net.URI;

public final class OfficialAlmaLinuxCatalogTest {
    public static void main(String[] arguments) {
        DistroManifest almalinux = OfficialAlmaLinuxCatalog.almalinux();
        assertEquals("almalinux", almalinux.id());
        assertEquals("9", almalinux.version());
        assertEquals("aarch64", almalinux.architecture());
        assertEquals(
                URI.create("https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/AlmaLinux-9-Base-rootfs.tar.gz"),
                almalinux.source());
        assertEquals("AlmaLinux-9-Base-rootfs.tar.gz", almalinux.rootfsFileName());
        assertEquals("543b8e13d355d4143cdda20c55bcfce32467d72211bc8cbe8c2fac1088834443", almalinux.sha256());
        assertEquals(103_733_984L, almalinux.sizeBytes());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("pinned AlmaLinux identity changed: expected=" + expected + ", actual=" + actual);
        }
    }
}
