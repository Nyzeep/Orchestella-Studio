package io.github.nyzeep.orchestella.runtime.distro;

public final class DistroFlavorTest {
    public static void main(String[] arguments) {
        testsDebianFlavor();
        testsUbuntuFlavor();
        testsFedoraFlavor();
        testsArchFlavor();
        testsOpenSuseFlavor();
        testsAlmaLinuxFlavor();
        testsRockyLinuxFlavor();
        rejectsUnsupportedDistro();
    }

    private static void testsDebianFlavor() {
        DistroFlavor debian = DistroFlavor.forDistroId("debian");
        assertEquals("debian", debian.distroId());
        assertEquals("Debian GNU/Linux", debian.displayName());
        assertEquals(DistroFlavor.InitSystem.SYSTEMD, debian.initSystem());
        assertEquals("apt", debian.packageManager());
        assertEquals("debian-13", debian.provisionedDistroTag());
        assertEquals("/etc/systemd/system/orchestella-guest-agent.service", debian.serviceUnitPath());
        assertEquals(
                "/etc/systemd/system/multi-user.target.wants/orchestella-guest-agent.service",
                debian.serviceEnableSymlink());

        String unit = debian.systemdUnitContent();
        assertTrue(unit.contains("Description=Orchestella Guest Agent"));
        assertTrue(unit.contains("ExecStart=/usr/sbin/orchestella-guest-agent-launcher"));
        assertTrue(unit.contains("WantedBy=multi-user.target"));

        String resolv = debian.resolvConfContent();
        assertTrue(resolv.contains("nameserver 10.0.2.3"));
        assertTrue(resolv.contains("nameserver 8.8.8.8"));

        String launcher = debian.guestAgentLauncherScript();
        assertTrue(launcher.contains("#!/bin/sh"));
        assertTrue(launcher.contains("exec /usr/sbin/orchestella-guest-agent"));
        assertTrue(launcher.contains("--token-file /etc/orchestella/bridge.token"));
        assertTrue(launcher.contains("--terminal-device /dev/orchestella-terminal"));
    }

    private static void testsUbuntuFlavor() {
        DistroFlavor ubuntu = DistroFlavor.forManifest(OfficialUbuntuCatalog.ubuntu());
        assertEquals("ubuntu", ubuntu.distroId());
        assertEquals("Ubuntu Linux", ubuntu.displayName());
        assertEquals(DistroFlavor.InitSystem.SYSTEMD, ubuntu.initSystem());
        assertEquals("apt", ubuntu.packageManager());
        assertEquals("ubuntu-24.04", ubuntu.provisionedDistroTag());
        assertEquals("/etc/systemd/system/orchestella-guest-agent.service", ubuntu.serviceUnitPath());
        assertEquals("/etc/systemd/system/multi-user.target.wants/orchestella-guest-agent.service", ubuntu.serviceEnableSymlink());
    }

    private static void testsFedoraFlavor() {
        DistroFlavor fedora = DistroFlavor.forManifest(OfficialFedoraCatalog.fedora());
        assertEquals("fedora", fedora.distroId());
        assertEquals("Fedora Linux", fedora.displayName());
        assertEquals(DistroFlavor.InitSystem.SYSTEMD, fedora.initSystem());
        assertEquals("dnf", fedora.packageManager());
        assertEquals("fedora-41", fedora.provisionedDistroTag());
    }

    private static void testsArchFlavor() {
        DistroFlavor arch = DistroFlavor.forManifest(OfficialArchCatalog.arch());
        assertEquals("arch", arch.distroId());
        assertEquals("Arch Linux ARM", arch.displayName());
        assertEquals(DistroFlavor.InitSystem.SYSTEMD, arch.initSystem());
        assertEquals("pacman", arch.packageManager());
        assertEquals("archlinux", arch.provisionedDistroTag());
    }

    private static void testsOpenSuseFlavor() {
        DistroFlavor opensuse = DistroFlavor.forManifest(OfficialOpenSuseCatalog.opensuse());
        assertEquals("opensuse", opensuse.distroId());
        assertEquals("openSUSE Tumbleweed", opensuse.displayName());
        assertEquals(DistroFlavor.InitSystem.SYSTEMD, opensuse.initSystem());
        assertEquals("zypper", opensuse.packageManager());
        assertEquals("opensuse-tumbleweed", opensuse.provisionedDistroTag());
    }

    private static void testsAlmaLinuxFlavor() {
        DistroFlavor almalinux = DistroFlavor.forManifest(OfficialAlmaLinuxCatalog.almalinux());
        assertEquals("almalinux", almalinux.distroId());
        assertEquals("AlmaLinux", almalinux.displayName());
        assertEquals(DistroFlavor.InitSystem.SYSTEMD, almalinux.initSystem());
        assertEquals("dnf", almalinux.packageManager());
        assertEquals("almalinux-9", almalinux.provisionedDistroTag());
    }

    private static void testsRockyLinuxFlavor() {
        DistroFlavor rocky = DistroFlavor.forManifest(OfficialRockyLinuxCatalog.rocky());
        assertEquals("rocky", rocky.distroId());
        assertEquals("Rocky Linux", rocky.displayName());
        assertEquals(DistroFlavor.InitSystem.SYSTEMD, rocky.initSystem());
        assertEquals("dnf", rocky.packageManager());
        assertEquals("rocky-9", rocky.provisionedDistroTag());
    }

    private static void rejectsUnsupportedDistro() {
        try {
            DistroFlavor.forDistroId("archlinux");
            throw new AssertionError("archlinux should be unsupported");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("archlinux"));
        }

        try {
            DistroFlavor.forDistroId("alpine");
            throw new AssertionError("alpine should be unsupported");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("alpine"));
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
