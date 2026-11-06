package io.github.nyzeep.orchestella.runtime.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Locks the extracted {@link InstanceFileStore} boundary: atomic metadata persistence, sorted
 * listing, the delete-state circuit breaker and the directory-wide shared state monitor.
 * Public behavior is unchanged; this test only pins the extracted collaborator through the
 * unchanged {@link InstanceLifecycle.Store} contract.
 */
public final class InstanceFileStoreContractTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected one approved test directory");
        }
        Path testDirectory = Path.of(arguments[0]).toAbsolutePath().normalize();
        resetFixture(testDirectory);

        savesLoadsAndListsSortedByInstanceId(testDirectory);
        refusesToDeleteLiveInstances(testDirectory);
        sharesOneStateMonitorPerDirectory(testDirectory);
    }

    private static void savesLoadsAndListsSortedByInstanceId(Path testDirectory) throws IOException {
        Path directory = testDirectory.resolve("filestore-basic");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(directory);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        InstanceLifecycle.Instance first = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), emptyVolume("basic-a"));
        InstanceLifecycle.Instance second = controller.create(
                new InstanceLifecycle.DistroReference("debian", "12.5.0"), emptyVolume("basic-b"));

        List<InstanceLifecycle.Instance> listed = store.list();
        assertEquals(2, listed.size(), "listed instance count");
        assertTrue(listed.get(0).id().compareTo(listed.get(1).id()) < 0,
                "list order is instance id sorted");
        assertTrue(listed.stream().anyMatch(entry -> entry.id().equals(first.id())),
                "both created instances are listed");
        assertTrue(listed.stream().anyMatch(entry -> entry.id().equals(second.id())),
                "both created instances are listed");

        InstanceLifecycle.Instance loaded = store.load(first.id());
        assertEquals(first.state(), loaded.state(), "persisted state survives a round trip");
        assertEquals(first.distro().id(), loaded.distro().id(), "persisted distro survives a round trip");

        // Metadata is written as .properties next to the state directory root.
        assertTrue(Files.isRegularFile(directory.resolve(first.id() + ".properties")),
                "metadata file uses the .properties name");
        assertTrue(Files.isRegularFile(second.rootVolume()), "root volume file exists");
    }

    private static void refusesToDeleteLiveInstances(Path testDirectory) throws IOException {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(testDirectory.resolve("filestore-guard"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), emptyVolume("guard"));

        controller.beginProvisioning(instance.id());
        assertThrows(IllegalStateException.class, () -> store.delete(instance.id()),
                "delete must refuse a PROVISIONING instance");
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());
        assertThrows(IllegalStateException.class, () -> store.delete(instance.id()),
                "delete must refuse a RUNNING instance");
        controller.forceStop(instance.id());
        store.delete(instance.id());
        assertThrows(IOException.class, () -> store.load(instance.id()),
                "load after delete must fail");
    }

    private static void sharesOneStateMonitorPerDirectory(Path testDirectory) throws IOException {
        Path directory = testDirectory.resolve("filestore-monitor");
        InstanceLifecycle.FileStore first = new InstanceLifecycle.FileStore(directory);
        InstanceLifecycle.FileStore second = new InstanceLifecycle.FileStore(directory);
        InstanceLifecycle.FileStore elsewhere =
                new InstanceLifecycle.FileStore(testDirectory.resolve("filestore-monitor-2"));
        assertEquals(first.stateMonitor(), second.stateMonitor(),
                "stores on one directory share the state monitor");
        assertFalse(first.stateMonitor() == elsewhere.stateMonitor(),
                "different directories use different monitors");
    }

    private static Path emptyVolume(String name) throws IOException {
        return Files.createFile(testRoot().resolve(name + ".raw"));
    }

    private static Path testRoot() throws IOException {
        return Files.createTempDirectory("filestore-volumes");
    }

    private static void resetFixture(Path testDirectory) throws IOException {
        if (Files.exists(testDirectory)) {
            try (var paths = Files.list(testDirectory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    if (Files.isDirectory(path)) {
                        try (var nested = Files.list(path)) {
                            for (Path child : nested.toList()) {
                                Files.deleteIfExists(child);
                            }
                        }
                    }
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(testDirectory);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": values differ (redacted)");
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        if (condition) {
            throw new AssertionError(description);
        }
    }

    private static <T extends Throwable> void assertThrows(
            Class<T> type, ThrowingRunnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return;
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
