package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * JVM contract test for instance cloning and storage duplication (Phase 4.9 Task 3).
 */
public final class InstanceLifecycleCloneTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected one approved test directory");
        }
        Path testDirectory = Path.of(arguments[0]).toAbsolutePath().normalize();
        resetFixture(testDirectory);

        clonesStoppedInstanceAndDuplicatesDisk(testDirectory);
        clonesCreatedInstanceSuccessfully(testDirectory);
        rejectsCloningOfRunningInstance(testDirectory);
        rejectsCloningOfProvisioningInstance(testDirectory);
        clonesWithCustomTargetRootVolume(testDirectory);
        clonedInstanceHasNewCredentialsAndRetainsHardwareConfig(testDirectory);

        System.out.println("INSTANCE_LIFECYCLE_CLONE_TEST_OK");
    }

    private static void clonesStoppedInstanceAndDuplicatesDisk(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("clone-stopped-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("source-stopped.raw");
        writeText(rootVolume, "original-disk-content-12345");

        InstanceLifecycle.Instance source = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(source.id());
        controller.completeProvisioning(source.id());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(source.id()).state(), "source must be STOPPED");

        // Clone instance
        InstanceLifecycle.Instance cloned = controller.cloneInstance(source.id());

        assertNotNull(cloned, "cloned instance must not be null");
        assertFalse(cloned.id().equals(source.id()), "cloned instance ID must be unique");
        assertEquals(InstanceLifecycle.State.CREATED, cloned.state(), "cloned instance state must be CREATED");
        assertEquals(source.distro().id(), cloned.distro().id(), "distro id must match");
        assertEquals(source.distro().version(), cloned.distro().version(), "distro version must match");

        // Verify root volume disk file was copied and exists
        assertTrue(Files.isRegularFile(cloned.rootVolume()), "cloned root volume must exist on disk");
        assertFalse(cloned.rootVolume().equals(source.rootVolume()), "cloned root volume path must differ from source");
        String clonedContent = Files.readString(cloned.rootVolume());
        assertEquals("original-disk-content-12345", clonedContent, "cloned disk contents must match source");

        // Verify loaded from store
        InstanceLifecycle.Instance loaded = store.load(cloned.id());
        assertEquals(cloned.id(), loaded.id(), "loaded cloned instance id");
        assertEquals(InstanceLifecycle.State.CREATED, loaded.state(), "loaded state must be CREATED");
    }

    private static void clonesCreatedInstanceSuccessfully(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("clone-created-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("source-created.raw");
        writeText(rootVolume, "fresh-created-content");

        InstanceLifecycle.Instance source = controller.create(
                new InstanceLifecycle.DistroReference("debian", "12"), rootVolume);
        assertEquals(InstanceLifecycle.State.CREATED, source.state(), "source state is CREATED");

        InstanceLifecycle.Instance cloned = controller.cloneInstance(source.id());
        assertEquals(InstanceLifecycle.State.CREATED, cloned.state(), "cloned state is CREATED");
        assertTrue(Files.exists(cloned.rootVolume()), "cloned disk exists");
        assertEquals("fresh-created-content", Files.readString(cloned.rootVolume()), "cloned disk content matches");
    }

    private static void rejectsCloningOfRunningInstance(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("clone-running-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("source-running.raw");
        writeText(rootVolume, "running-content");

        InstanceLifecycle.Instance source = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(source.id());
        controller.completeProvisioning(source.id());
        controller.start(source.id());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(source.id()).state(), "source must be RUNNING");

        assertThrows(IllegalStateException.class, () -> controller.cloneInstance(source.id()),
                "cloning a RUNNING instance must throw IllegalStateException");
    }

    private static void rejectsCloningOfProvisioningInstance(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("clone-prov-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("source-prov.raw");
        writeText(rootVolume, "prov-content");

        InstanceLifecycle.Instance source = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(source.id());

        assertThrows(IllegalStateException.class, () -> controller.cloneInstance(source.id()),
                "cloning a PROVISIONING instance must throw IllegalStateException");
    }

    private static void clonesWithCustomTargetRootVolume(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("clone-custom-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("source-custom.raw");
        writeText(rootVolume, "custom-content");

        InstanceLifecycle.Instance source = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);

        Path explicitTarget = testDirectory.resolve("explicit-cloned.raw");
        InstanceLifecycle.Instance cloned = controller.cloneInstance(source.id(), explicitTarget);

        assertEquals(explicitTarget.normalize(), cloned.rootVolume().normalize(), "target path matches");
        assertTrue(Files.exists(explicitTarget), "explicit target disk created");
        assertEquals("custom-content", Files.readString(explicitTarget), "explicit disk content matches");
    }

    private static void clonedInstanceHasNewCredentialsAndRetainsHardwareConfig(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("clone-hw-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("source-hw.raw");
        writeText(rootVolume, "hw-content");

        VmHardwareConfig customConfig = new VmHardwareConfig(
                6, 6144, 32L * 1024 * 1024 * 1024, false, "quiet console=ttyS0");

        InstanceLifecycle.Instance source = controller.create(
                new InstanceLifecycle.DistroReference("debian", "12"), rootVolume, customConfig);

        InstanceLifecycle.Instance cloned = controller.cloneInstance(source.id());

        // New bridge token and guest password
        assertFalse(cloned.bridgeToken().equals(source.bridgeToken()), "bridgeToken must be freshly generated");
        assertFalse(cloned.guestPassword().equals(source.guestPassword()), "guestPassword must be freshly generated");
        assertEquals(16, cloned.guestPassword().length(), "guest password valid length");

        // Hardware config is replicated
        assertEquals(customConfig, cloned.hardwareConfig(), "hardwareConfig must be duplicated");
        assertEquals(6, cloned.hardwareConfig().cpuCores(), "cpuCores");
        assertEquals(6144, cloned.hardwareConfig().ramMegabytes(), "ramMegabytes");
        assertEquals(32L * 1024 * 1024 * 1024, cloned.hardwareConfig().diskCapacityBytes(), "diskCapacityBytes");
        assertFalse(cloned.hardwareConfig().gpuAccelerationEnabled(), "gpuAccelerationEnabled");
    }

    private static void writeText(Path path, String text) throws IOException {
        Files.write(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void resetFixture(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new RuntimeException("failed to clean " + path, exception);
                        }
                    });
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertNotNull(Object obj, String description) {
        if (obj == null) {
            throw new AssertionError(description);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static <T extends Throwable> T assertThrows(
            Class<T> type, ThrowingRunnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return type.cast(throwable);
            }
            throw new AssertionError(description + ": unexpected exception", throwable);
        }
        throw new AssertionError(description + ": expected exception " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
