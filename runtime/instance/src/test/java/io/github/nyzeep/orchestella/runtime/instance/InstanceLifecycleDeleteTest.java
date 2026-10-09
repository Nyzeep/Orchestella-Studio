package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * JVM contract test for physical deletion, safety circuit breaker, and hardware configuration (Ticket 49).
 */
public final class InstanceLifecycleDeleteTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected one approved test directory");
        }
        Path testDirectory = Path.of(arguments[0]).toAbsolutePath().normalize();
        resetFixture(testDirectory);

        deletesStoppedInstanceAndPhysicallyPurgesFiles(testDirectory);
        deletesCreatedInstanceImmediately(testDirectory);
        deletesCrashedInstance(testDirectory);
        rejectsDeletionOfRunningInstanceWithSafetyCircuitBreaker(testDirectory);
        rejectsDeletionOfProvisioningInstance(testDirectory);
        throwsNoSuchFileExceptionWhenDeletingNonexistentInstance(testDirectory);
        persistsAndLoadsVmHardwareConfig(testDirectory);
        backwardCompatibleWithLegacyPropertiesWithoutHardwareConfig(testDirectory);
        updatesHardwareConfigInStoppedStateAndRejectsInRunningState(testDirectory);

        System.out.println("INSTANCE_LIFECYCLE_DELETE_TEST_OK");
    }

    private static void deletesStoppedInstanceAndPhysicallyPurgesFiles(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("delete-stopped-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("instance-to-delete.raw");
        writeText(rootVolume, "raw-disk-dummy-data");

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        // Create swap and temporary files in storeDir matching instance id
        Path swapFile = storeDir.resolve(instance.id() + ".swap");
        writeText(swapFile, "dummy-swap");
        Path tmpFile = storeDir.resolve(instance.id() + ".tmp");
        writeText(tmpFile, "dummy-tmp");
        Path rawFileInStore = storeDir.resolve(instance.id() + ".raw");
        writeText(rawFileInStore, "dummy-raw");

        Path metaFile = storeDir.resolve(instance.id() + ".properties");
        assertTrue(Files.exists(metaFile), "metadata file must exist before delete");
        assertTrue(Files.exists(rootVolume), "root volume must exist before delete");
        assertTrue(Files.exists(swapFile), "swap file must exist before delete");

        // Execute physical delete
        store.delete(instance.id());

        // Verify physical deletion
        assertFalse(Files.exists(metaFile), "metadata file must be purged");
        assertFalse(Files.exists(rootVolume), "root volume must be purged");
        assertFalse(Files.exists(swapFile), "swap file must be purged");
        assertFalse(Files.exists(tmpFile), "temporary file must be purged");
        assertFalse(Files.exists(rawFileInStore), "raw file in store dir must be purged");

        // Verify store state
        assertFalse(store.list().stream().anyMatch(i -> i.id().equals(instance.id())),
                "instance must not be present in list() after delete");
        assertThrows(IOException.class, () -> store.load(instance.id()),
                "load() after delete must throw IOException/NoSuchFileException");
    }

    private static void deletesCreatedInstanceImmediately(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("delete-created-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("created-instance.raw");
        writeText(rootVolume, "dummy");

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        assertEquals(InstanceLifecycle.State.CREATED, instance.state(), "state is CREATED");

        store.delete(instance.id());

        assertFalse(Files.exists(rootVolume), "root volume must be deleted");
        assertFalse(Files.exists(storeDir.resolve(instance.id() + ".properties")), "properties must be deleted");
        assertFalse(store.list().stream().anyMatch(i -> i.id().equals(instance.id())), "removed from list");
    }

    private static void deletesCrashedInstance(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("delete-crashed-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("crashed-instance.raw");
        writeText(rootVolume, "dummy");

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        Path logPath = testDirectory.resolve("crash.log");
        writeText(logPath, "fatal guest crash");

        controller.recordCrash(instance.id(), 139, logPath, "SIGSEGV in QEMU");
        InstanceLifecycle.Instance crashed = store.load(instance.id());
        assertEquals(InstanceLifecycle.State.CRASHED, crashed.state(), "state must be CRASHED");

        store.delete(instance.id());

        assertFalse(Files.exists(rootVolume), "root volume must be deleted on crashed delete");
        assertFalse(Files.exists(storeDir.resolve(instance.id() + ".properties")), "metadata purged");
        assertFalse(store.list().stream().anyMatch(i -> i.id().equals(instance.id())), "not in list");
    }

    private static void rejectsDeletionOfRunningInstanceWithSafetyCircuitBreaker(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("running-safety-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("running-instance.raw");
        writeText(rootVolume, "running-disk-data");

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        Path metaFile = storeDir.resolve(instance.id() + ".properties");
        assertTrue(Files.exists(metaFile), "meta exists");
        assertTrue(Files.exists(rootVolume), "root volume exists");

        // Attempt delete while RUNNING -> Must throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> store.delete(instance.id()),
                "deleting RUNNING instance must throw IllegalStateException");

        // Ensure files are intact
        assertTrue(Files.exists(metaFile), "meta file must not be modified or deleted");
        assertTrue(Files.exists(rootVolume), "root volume must not be deleted");
        InstanceLifecycle.Instance stillRunning = store.load(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING, stillRunning.state(), "instance is still RUNNING");
    }

    private static void rejectsDeletionOfProvisioningInstance(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("provisioning-safety-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("provisioning-instance.raw");
        writeText(rootVolume, "prov");

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());

        assertThrows(IllegalStateException.class, () -> store.delete(instance.id()),
                "deleting PROVISIONING instance must throw IllegalStateException");
    }

    private static void throwsNoSuchFileExceptionWhenDeletingNonexistentInstance(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("nonexistent-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);

        String randomId = UUID.randomUUID().toString();
        assertThrows(IOException.class, () -> store.delete(randomId),
                "deleting nonexistent instance must throw NoSuchFileException or IOException");
    }

    private static void persistsAndLoadsVmHardwareConfig(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("hw-config-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("hw-instance.raw");
        writeText(rootVolume, "hw");

        VmHardwareConfig customConfig = new VmHardwareConfig(
                4,
                4096,
                16L * 1024 * 1024 * 1024,
                false,
                "console=ttyS0");

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                rootVolume,
                customConfig);

        assertEquals(customConfig, instance.hardwareConfig(), "instance holds hardwareConfig");

        // Reload from disk
        InstanceLifecycle.FileStore reconstructedStore = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Instance loaded = reconstructedStore.load(instance.id());
        assertEquals(customConfig, loaded.hardwareConfig(), "reloaded instance matches custom hardwareConfig");
        assertEquals(4, loaded.hardwareConfig().cpuCores(), "reloaded cpuCores");
        assertEquals(4096, loaded.hardwareConfig().ramMegabytes(), "reloaded ramMegabytes");
        assertEquals(16L * 1024 * 1024 * 1024, loaded.hardwareConfig().diskCapacityBytes(), "reloaded disk");
        assertFalse(loaded.hardwareConfig().gpuAccelerationEnabled(), "reloaded gpuAccelerationEnabled");
        assertEquals("console=ttyS0", loaded.hardwareConfig().extraKernelArguments(), "reloaded args");
    }

    private static void backwardCompatibleWithLegacyPropertiesWithoutHardwareConfig(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("legacy-prop-store");
        Files.createDirectories(storeDir);
        String legacyId = UUID.randomUUID().toString();
        Path propFile = storeDir.resolve(legacyId + ".properties");

        String legacyProperties = "id=" + legacyId + "\n"
                + "distroId=alpine\n"
                + "distroVersion=3.24.1\n"
                + "rootVolume=" + testDirectory.resolve("legacy.raw").toString().replace("\\", "\\\\") + "\n"
                + "bridgeToken=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n"
                + "runtimeClass=FULL_LINUX\n"
                + "state=STOPPED\n"
                + "createdAtMillis=1789000000000\n"
                + "updatedAtMillis=1789000000000\n";
        writeText(propFile, legacyProperties);

        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Instance loaded = store.load(legacyId);

        // Must default to VmHardwareConfig.defaultConfig()
        assertEquals(VmHardwareConfig.defaultConfig(), loaded.hardwareConfig(),
                "legacy instance must fallback to VmHardwareConfig.defaultConfig()");
    }

    private static void updatesHardwareConfigInStoppedStateAndRejectsInRunningState(Path testDirectory) throws Exception {
        Path storeDir = testDirectory.resolve("hw-update-store");
        Files.createDirectories(storeDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = testDirectory.resolve("hw-update.raw");
        writeText(rootVolume, "update");

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        assertEquals(VmHardwareConfig.defaultConfig(), instance.hardwareConfig(), "default hardwareConfig");

        VmHardwareConfig upgraded = instance.hardwareConfig()
                .withCpuCores(6)
                .withRamMegabytes(6144);

        // Update in CREATED state -> Allowed
        InstanceLifecycle.Instance updated = controller.updateHardwareConfig(instance.id(), upgraded);
        assertEquals(upgraded, updated.hardwareConfig(), "updated in CREATED state");

        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state(), "state is STOPPED");

        // Update in STOPPED state -> Allowed
        VmHardwareConfig finalConfig = upgraded.withGpuAcceleration(false);
        InstanceLifecycle.Instance updatedStopped = controller.updateHardwareConfig(instance.id(), finalConfig);
        assertEquals(finalConfig, updatedStopped.hardwareConfig(), "updated in STOPPED state");

        // Start instance -> RUNNING state
        controller.start(instance.id());

        // Update in RUNNING state -> Must reject
        assertThrows(IllegalStateException.class,
                () -> controller.updateHardwareConfig(instance.id(), VmHardwareConfig.defaultConfig()),
                "updating hardware config while RUNNING must throw IllegalStateException");
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
