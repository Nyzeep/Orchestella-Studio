package io.github.nyzeep.orchestella.runtime.distro;

import java.net.URI;

public final class OfficialRockyLinuxCatalogTest {
    public static void main(String[] arguments) {
        DistroManifest rocky = OfficialRockyLinuxCatalog.rocky();
        assertEquals("rocky", rocky.id());
        assertEquals("9.6", rocky.version());
        assertEquals("aarch64", rocky.architecture());
        assertEquals(
                URI.create("https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/Rocky-9-Container-Base-9.6-20250531.0.aarch64.tar.xz"),
                rocky.source());
        assertEquals("Rocky-9-Container-Base-9.6-20250531.0.aarch64.tar.xz", rocky.rootfsFileName());
        assertEquals("a68ba3a9ac9da45a69b3b3b6c420aa2b74d446548813adcbfdb46fc238f33d1b", rocky.sha256());
        assertEquals(103_658_208L, rocky.sizeBytes());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("pinned Rocky Linux identity changed: expected=" + expected + ", actual=" + actual);
        }
    }
}
