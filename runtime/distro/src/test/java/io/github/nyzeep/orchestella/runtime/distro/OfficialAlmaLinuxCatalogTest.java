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
        assertEquals("ae65b206007f1635dfb045e9ac0e67dbdd9820c536115298b7e89cc078895d37", almalinux.sha256());
        assertEquals(66_985_382L, almalinux.sizeBytes());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("pinned AlmaLinux identity changed: expected=" + expected + ", actual=" + actual);
        }
    }
}
