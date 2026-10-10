package io.github.nyzeep.orchestella.runtime.distro;

import java.util.Arrays;
import java.util.List;

/**
 * Pure JVM tests for PackageManagerType enum definitions and command generators.
 */
public final class PackageManagerTypeTest {

    public static void main(String[] args) {
        testEnumValuesAndCommandNames();
        testDefaultUpdateCommands();
        testDefaultInstallCommands();
        testDistroIdMappings();
        testRejectsUnknownDistroId();
    }

    private static void testEnumValuesAndCommandNames() {
        assertEquals("apt", PackageManagerType.APT.commandName());
        assertEquals("pacman", PackageManagerType.PACMAN.commandName());
        assertEquals("dnf", PackageManagerType.DNF.commandName());
        assertEquals("zypper", PackageManagerType.ZYPPER.commandName());
    }

    private static void testDefaultUpdateCommands() {
        assertTrue(PackageManagerType.APT.defaultUpdateCommand().contains("apt"));
        assertTrue(PackageManagerType.PACMAN.defaultUpdateCommand().contains("pacman -Sy"));
        assertTrue(PackageManagerType.DNF.defaultUpdateCommand().contains("dnf"));
        assertTrue(PackageManagerType.ZYPPER.defaultUpdateCommand().contains("zypper refresh"));
    }

    private static void testDefaultInstallCommands() {
        List<String> packages = Arrays.asList("bash", "curl");

        String aptInstall = PackageManagerType.APT.defaultInstallCommand(packages);
        assertTrue(aptInstall.contains("install -y") && aptInstall.contains("bash curl"));

        String pacmanInstall = PackageManagerType.PACMAN.defaultInstallCommand(packages);
        assertTrue(pacmanInstall.contains("pacman -S") && pacmanInstall.contains("bash curl"));

        String dnfInstall = PackageManagerType.DNF.defaultInstallCommand(packages);
        assertTrue(dnfInstall.contains("dnf install -y") && dnfInstall.contains("bash curl"));

        String zypperInstall = PackageManagerType.ZYPPER.defaultInstallCommand(packages);
        assertTrue(zypperInstall.contains("zypper") && zypperInstall.contains("install -y") && zypperInstall.contains("bash curl"));
    }

    private static void testDistroIdMappings() {
        assertEquals(PackageManagerType.APT, PackageManagerType.forDistroId("ubuntu"));
        assertEquals(PackageManagerType.APT, PackageManagerType.forDistroId("debian"));
        assertEquals(PackageManagerType.PACMAN, PackageManagerType.forDistroId("arch"));
        assertEquals(PackageManagerType.DNF, PackageManagerType.forDistroId("fedora"));
        assertEquals(PackageManagerType.DNF, PackageManagerType.forDistroId("almalinux"));
        assertEquals(PackageManagerType.DNF, PackageManagerType.forDistroId("rocky"));
        assertEquals(PackageManagerType.ZYPPER, PackageManagerType.forDistroId("opensuse"));
    }

    private static void testRejectsUnknownDistroId() {
        assertRejects("unknown_distro");
        assertRejects("alpine");
        assertRejects("kali");
    }

    private static void assertRejects(String distroId) {
        try {
            PackageManagerType.forDistroId(distroId);
            throw new AssertionError("Should reject distro id: " + distroId);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(distroId));
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
