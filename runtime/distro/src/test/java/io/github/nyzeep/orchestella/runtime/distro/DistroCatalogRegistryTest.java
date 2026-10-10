package io.github.nyzeep.orchestella.runtime.distro;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Pure JVM tests for DistroCatalogRegistry registration, query, and manifest integrity.
 */
public final class DistroCatalogRegistryTest {

    private static final List<String> EXPECTED_7_DISTROS = Arrays.asList(
            "ubuntu",
            "debian",
            "fedora",
            "arch",
            "opensuse",
            "almalinux",
            "rocky"
    );

    public static void main(String[] args) {
        testAllSevenDistrosRegistered();
        testEachManifestContractAndIntegrity();
        testPackageManagerMappingForSevenDistros();
        testListAllMatchesSupportedIds();
        testCustomRegistration();
        testNonExistentDistroReturnsNull();
    }

    private static void testAllSevenDistrosRegistered() {
        DistroCatalogRegistry registry = DistroCatalogRegistry.getInstance();
        Set<String> supportedIds = registry.supportedDistroIds();

        for (String expectedId : EXPECTED_7_DISTROS) {
            assertTrue("Registry must support " + expectedId, supportedIds.contains(expectedId));
            DistroManifest manifest = registry.get(expectedId);
            assertNotNull("Manifest for " + expectedId + " must not be null", manifest);
            assertEquals(expectedId, manifest.id());
        }
    }

    private static void testEachManifestContractAndIntegrity() {
        DistroCatalogRegistry registry = DistroCatalogRegistry.getInstance();

        for (String id : EXPECTED_7_DISTROS) {
            DistroManifest manifest = registry.get(id);
            assertEquals(id, manifest.id());
            assertNotNull("version must not be null for " + id, manifest.version());
            assertFalse("version must not be blank for " + id, manifest.version().trim().isEmpty());
            assertEquals("architecture must be aarch64", "aarch64", manifest.architecture());

            assertNotNull("source must not be null", manifest.source());
            assertEquals("https", manifest.source().getScheme());
            assertTrue("source must end with rootfsFileName",
                    manifest.source().getPath().endsWith("/" + manifest.rootfsFileName()));

            assertNotNull("sha256 must not be null", manifest.sha256());
            assertEquals("sha256 must be 64 characters", 64, manifest.sha256().length());
            assertTrue("sizeBytes must be positive", manifest.sizeBytes() > 0);
        }
    }

    private static void testPackageManagerMappingForSevenDistros() {
        DistroCatalogRegistry registry = DistroCatalogRegistry.getInstance();

        assertEquals(PackageManagerType.APT, registry.getPackageManager("ubuntu"));
        assertEquals(PackageManagerType.APT, registry.getPackageManager("debian"));
        assertEquals(PackageManagerType.DNF, registry.getPackageManager("fedora"));
        assertEquals(PackageManagerType.PACMAN, registry.getPackageManager("arch"));
        assertEquals(PackageManagerType.ZYPPER, registry.getPackageManager("opensuse"));
        assertEquals(PackageManagerType.DNF, registry.getPackageManager("almalinux"));
        assertEquals(PackageManagerType.DNF, registry.getPackageManager("rocky"));
    }

    private static void testListAllMatchesSupportedIds() {
        DistroCatalogRegistry registry = DistroCatalogRegistry.getInstance();
        List<DistroManifest> all = registry.listAll();
        assertTrue("Must have at least 7 distros", all.size() >= 7);

        for (DistroManifest manifest : all) {
            assertTrue("All distros in listAll must be in supportedDistroIds",
                    registry.supportedDistroIds().contains(manifest.id()));
        }
    }

    private static void testCustomRegistration() {
        DistroCatalogRegistry registry = DistroCatalogRegistry.createNewRegistry();
        DistroManifest custom = new DistroManifest(
                "custom",
                "1.0",
                "aarch64",
                "https://example.com/custom-rootfs.tar.gz",
                "custom-rootfs.tar.gz",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                1024L
        );
        registry.register(custom);

        assertTrue(registry.supportedDistroIds().contains("custom"));
        assertEquals(custom, registry.get("custom"));
    }

    private static void testNonExistentDistroReturnsNull() {
        DistroCatalogRegistry registry = DistroCatalogRegistry.getInstance();
        DistroManifest nonExistent = registry.get("nonexistent");
        if (nonExistent != null) {
            throw new AssertionError("nonexistent distro must return null");
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(String message, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("condition must be true");
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(String message, boolean condition) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertNotNull(String message, Object obj) {
        if (obj == null) {
            throw new AssertionError(message);
        }
    }
}
