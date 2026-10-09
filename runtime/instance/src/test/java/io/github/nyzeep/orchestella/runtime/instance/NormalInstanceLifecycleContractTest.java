package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Normal-lifecycle contract only: it intentionally excludes process death, recovery, and host power.
 */
public final class NormalInstanceLifecycleContractTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected one approved test directory");
        }
        Path testDirectory = Path.of(arguments[0]).toAbsolutePath().normalize();
        resetFixture(testDirectory);

        persistsNormalProvisioningStartAndGuestStopAcrossStoreReconstruction(testDirectory);
        permitsOnlyOneRunningInstance(testDirectory);
        generatesAndPersistsAUniqueBridgeTokenPerInstance(testDirectory);
        generatesAndPersistsRandomCredentialsPerInstance(testDirectory);
    }

    private static void persistsNormalProvisioningStartAndGuestStopAcrossStoreReconstruction(
            Path testDirectory) throws Exception {
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-a"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path rootVolume = testDirectory.resolve("alpine-instance.raw");

        InstanceLifecycle.Instance instance =
                controller.create(new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        assertEquals(InstanceLifecycle.State.CREATED, instance.state(), "new instance state");
        assertEquals(
                FullLinuxContract.RuntimeClass.FULL_LINUX,
                instance.runtimeClass(),
                "instance Runtime Class");
        assertEquals(rootVolume, instance.rootVolume(), "root volume reference");

        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        InstanceLifecycle.Instance provisioned = store.load(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, provisioned.state(), "provisioning completes stopped");

        controller.start(instance.id());
        InstanceLifecycle.FileStore reconstructedStore =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-a"));
        InstanceLifecycle.Instance reconstructed = reconstructedStore.load(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING, reconstructed.state(), "reconstructed visible state");
        assertEquals(rootVolume, reconstructed.rootVolume(), "restart uses the same root volume");
        assertEquals("alpine", reconstructed.distro().id(), "distro remains a separate reference");

        controller.recordNormalGuestStop(instance.id());
        InstanceLifecycle.Instance stopped = reconstructedStore.load(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, stopped.state(), "normal guest stop is persisted");

        assertThrows(
                InstanceLifecycle.InvalidTransitionException.class,
                () -> controller.completeProvisioning(instance.id()),
                "a stopped instance is not provisioned again");
    }

    private static void permitsOnlyOneRunningInstance(Path testDirectory) throws Exception {
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-b"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        InstanceLifecycle.Instance first =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("first.raw"));
        InstanceLifecycle.Instance second =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("second.raw"));

        provisionToStopped(controller, first.id());
        provisionToStopped(controller, second.id());
        controller.start(first.id());

        assertThrows(
                InstanceLifecycle.SingleRunningInstanceException.class,
                () -> controller.start(second.id()),
                "a second instance cannot run concurrently");
        controller.recordNormalGuestStop(first.id());
        controller.start(second.id());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(second.id()).state(), "second starts after stop");
    }

    private static void generatesAndPersistsAUniqueBridgeTokenPerInstance(Path testDirectory)
            throws Exception {
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-c"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        InstanceLifecycle.Instance first =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("first-token.raw"));
        InstanceLifecycle.Instance second =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("second-token.raw"));

        assertCanonicalToken(first.bridgeToken(), "first Instance token");
        assertCanonicalToken(second.bridgeToken(), "second Instance token");
        assertFalse(
                first.bridgeToken().equals(second.bridgeToken()),
                "Instances must not share a Bridge token");

        InstanceLifecycle.FileStore reconstructedStore =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-c"));
        assertEquals(
                first.bridgeToken(),
                reconstructedStore.load(first.id()).bridgeToken(),
                "first Instance token persists without disclosure");
    }

    private static void generatesAndPersistsRandomCredentialsPerInstance(Path testDirectory)
            throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-cred"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        InstanceLifecycle.Instance first =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("first-cred.raw"));
        InstanceLifecycle.Instance second =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("second-cred.raw"));

        assertNotNull(first.guestPassword(), "first guest password must not be null");
        assertNotNull(second.guestPassword(), "second guest password must not be null");
        assertTrue(first.guestPassword().length() >= 12, "guest password must be at least 12 chars");
        assertFalse(
                first.guestPassword().equals(second.guestPassword()),
                "instances must have distinct random passwords");

        // Persists across reconstructed store
        InstanceLifecycle.FileStore reconstructed =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-cred"));
        assertEquals(
                first.guestPassword(),
                reconstructed.load(first.id()).guestPassword(),
                "guest password persists");

        // Update password
        InstanceLifecycle.Instance updated =
                controller.updateGuestPassword(first.id(), "NewSecurePass2026");
        assertEquals("NewSecurePass2026", updated.guestPassword(), "updated password in memory");
        assertEquals(
                "NewSecurePass2026",
                reconstructed.load(first.id()).guestPassword(),
                "updated password in store");

        // Invalid update rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.updateGuestPassword(first.id(), "short"),
                "short password rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.updateGuestPassword(first.id(), "pass with spaces"),
                "spaces rejected");
    }

    private static void provisionToStopped(InstanceLifecycle.Controller controller, String id) throws Exception {
        controller.beginProvisioning(id);
        controller.completeProvisioning(id);
    }

    /**
     * Each run must observe a virgin fixture: a previous run legitimately leaves its last instance
     * RUNNING, and stale store state would otherwise surface as SingleRunningInstanceException.
     */
    private static void resetFixture(Path testDirectory) throws Exception {
        if (Files.isDirectory(testDirectory)) {
            try (var paths = Files.walk(testDirectory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(testDirectory);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertCanonicalToken(String token, String description) {
        if (token == null || !token.matches("[0-9a-f]{64}")) {
            throw new AssertionError(description + " must be canonical without logging its value");
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertNotNull(Object value, String description) {
        if (value == null) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        if (condition) {
            throw new AssertionError(description);
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
