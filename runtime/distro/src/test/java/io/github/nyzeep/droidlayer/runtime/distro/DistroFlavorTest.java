package io.github.nyzeep.droidlayer.runtime.distro;

public final class DistroFlavorTest {
    public static void main(String[] arguments) {
        testsDebianFlavor();
        testsAlpineFlavor();
        rejectsUnsupportedDistro();
    }

    private static void testsDebianFlavor() {
        DistroFlavor debian = DistroFlavor.forDistroId("debian");
        assertEquals("debian", debian.distroId());
        assertEquals("Debian GNU/Linux", debian.displayName());
        assertEquals(DistroFlavor.InitSystem.SYSTEMD, debian.initSystem());
        assertEquals("apt", debian.packageManager());
        assertEquals("debian-13", debian.provisionedDistroTag());
        assertEquals("/etc/systemd/system/droidlayer-guest-agent.service", debian.serviceUnitPath());
        assertEquals(
                "/etc/systemd/system/multi-user.target.wants/droidlayer-guest-agent.service",
                debian.serviceEnableSymlink());

        String unit = debian.systemdUnitContent();
        assertTrue(unit.contains("Description=Orchestella Guest Agent"));
        assertTrue(unit.contains("ExecStart=/usr/sbin/droidlayer-guest-agent-launcher"));
        assertTrue(unit.contains("WantedBy=multi-user.target"));

        String resolv = debian.resolvConfContent();
        assertTrue(resolv.contains("nameserver 10.0.2.3"));
        assertTrue(resolv.contains("nameserver 8.8.8.8"));

        String launcher = debian.guestAgentLauncherScript();
        assertTrue(launcher.contains("#!/bin/sh"));
        assertTrue(launcher.contains("exec /usr/sbin/droidlayer-guest-agent"));
        assertTrue(launcher.contains("--token-file /etc/droidlayer/bridge.token"));
        assertTrue(launcher.contains("--terminal-device /dev/droidlayer-terminal"));
    }

    private static void testsAlpineFlavor() {
        DistroFlavor alpine = DistroFlavor.forManifest(OfficialAlpineCatalog.alpine());
        assertEquals("alpine", alpine.distroId());
        assertEquals("Alpine Linux", alpine.displayName());
        assertEquals(DistroFlavor.InitSystem.OPENRC, alpine.initSystem());
        assertEquals("apk", alpine.packageManager());
        assertEquals("alpine-3.24.1", alpine.provisionedDistroTag());
        assertEquals("/etc/init.d/droidlayer-guest-agent", alpine.serviceUnitPath());
        assertEquals("/etc/runlevels/default/droidlayer-guest-agent", alpine.serviceEnableSymlink());
    }

    private static void rejectsUnsupportedDistro() {
        try {
            DistroFlavor.forDistroId("archlinux");
            throw new AssertionError("archlinux should be unsupported");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("archlinux"));
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("condition must be true");
        }
    }
}
