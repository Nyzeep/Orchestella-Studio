package io.github.nyzeep.droidlayer.runtime.distro;

import java.net.URI;

public final class OfficialDebianCatalogTest {
    public static void main(String[] arguments) {
        DistroManifest debian = OfficialDebianCatalog.debian();
        assertEquals("debian", debian.id());
        assertEquals("13", debian.version());
        assertEquals("aarch64", debian.architecture());
        assertEquals(
                URI.create("https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/trixie/arm64/default/20260930_05:24/rootfs.tar.xz"),
                debian.source());
        assertEquals("rootfs.tar.xz", debian.rootfsFileName());
        assertEquals("7677f708be8b4df232ebdd177147b1b77beef7c784ce0fa3fbef5dd3e85ab461", debian.sha256());
        assertEquals(90_371_792L, debian.sizeBytes());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("pinned Debian identity changed: expected=" + expected + ", actual=" + actual);
        }
    }
}
