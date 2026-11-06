package io.github.nyzeep.orchestella.runtime.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Locks the extracted {@link InstanceLifecycleController} boundary through the unchanged
 * public {@link InstanceLifecycle.Controller} shell: every coordinator responsibility
 * (create, provisioning, transitions, crash recording, reconciliation, cold restore,
 * hardware/credential updates, clone, diagnostic export) still routes through the inherited
 * implementation with identical semantics.
 */
public final class InstanceLifecycleControllerContractTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected one approved test directory");
        }
        Path testDirectory = Path.of(arguments[0]).toAbsolutePath().normalize();
        resetFixture(testDirectory);

        drivesProvisioningStartStopAndSingleRunningConstraint(testDirectory);
        drivesSuspendResumeForceStopAndCrashRecording(testDirectory);
        drivesReconciliationColdRestoreAndResetToStopped(testDirectory);
        drivesCloneHardwarePasswordAndDiagnostics(testDirectory);
        enforcesInvalidTransitions(testDirectory);
    }

    private static void drivesProvisioningStartStopAndSingleRunningConstraint(Path testDirectory)
            throws IOException {
        InstanceLifecycle.Controller controller = controllerFor(testDirectory, "controller-provisioning");
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), emptyVolume("prov"));
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        InstanceLifecycle.Instance second = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), emptyVolume("prov-b"));
        controller.beginProvisioning(second.id());
        controller.completeProvisioning(second.id());
        assertThrows(InstanceLifecycle.SingleRunningInstanceException.class,
                () -> controller.start(second.id()),
                "a second RUNNING instance must be refused");

        controller.recordNormalGuestStop(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, storeFor(testDirectory, "controller-provisioning")
                .load(instance.id()).state(), "guest stop persists STOPPED");
    }

    private static void drivesSuspendResumeForceStopAndCrashRecording(Path testDirectory)
            throws IOException {
        InstanceLifecycle.Controller controller = controllerFor(testDirectory, "controller-transitions");
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), emptyVolume("transitions"));
        provisionAndStop(controller, instance.id());
        controller.start(instance.id());
        controller.suspend(instance.id());
        assertEquals(InstanceLifecycle.State.SUSPENDED,
                storeFor(testDirectory, "controller-transitions").load(instance.id()).state(),
                "suspend persists SUSPENDED");
        controller.resume(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING,
                storeFor(testDirectory, "controller-transitions").load(instance.id()).state(),
                "resume persists RUNNING");
        controller.recordGracefulStop(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED,
                storeFor(testDirectory, "controller-transitions").load(instance.id()).state(),
                "graceful stop persists STOPPED");

        controller.start(instance.id());
        controller.recordCrash(instance.id(), 7, null, "vmm crash");
        InstanceLifecycle.Instance crashed =
                storeFor(testDirectory, "controller-transitions").load(instance.id());
        assertEquals(InstanceLifecycle.State.CRASHED, crashed.state(), "crash persists CRASHED");
        assertEquals(7, crashed.crashDiagnostic().exitCode(), "crash exit code persists");

        controller.forceStop(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED,
                storeFor(testDirectory, "controller-transitions").load(instance.id()).state(),
                "force stop from CRASHED persists STOPPED");
    }

    private static void drivesReconciliationColdRestoreAndResetToStopped(Path testDirectory)
            throws IOException {
        InstanceLifecycle.Controller controller = controllerFor(testDirectory, "controller-recovery");
        InstanceLifecycle.Instance stale = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), emptyVolume("recovery"));
        provisionAndStop(controller, stale.id());
        controller.start(stale.id());

        List<InstanceLifecycle.Instance> reconciled = controller.reconcileAfterHostDeath();
        assertEquals(1, reconciled.size(), "one stale instance reconciled");
        assertEquals(InstanceLifecycle.State.INTERRUPTED, reconciled.get(0).state(),
                "reconciliation targets INTERRUPTED");

        controller.coldRestore(stale.id());
        assertEquals(InstanceLifecycle.State.RUNNING,
                storeFor(testDirectory, "controller-recovery").load(stale.id()).state(),
                "cold restore returns the instance to RUNNING");
        controller.recordCrash(stale.id(), 1, null, "recovery crash");
        controller.resetToStopped(stale.id());
        assertEquals(InstanceLifecycle.State.STOPPED,
                storeFor(testDirectory, "controller-recovery").load(stale.id()).state(),
                "reset to stopped persists STOPPED");
    }

    private static void drivesCloneHardwarePasswordAndDiagnostics(Path testDirectory)
            throws IOException {
        InstanceLifecycle.Controller controller = controllerFor(testDirectory, "controller-maintenance");
        InstanceLifecycle.Instance source = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), emptyVolume("maintenance"));
        controller.updateHardwareConfig(source.id(),
                new io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig(
                        6, 8192, 17_179_869_184L, false, null, false));
        controller.updateGuestPassword(source.id(), "rotated-password-9");
        InstanceLifecycle.Instance updated = storeFor(testDirectory, "controller-maintenance").load(source.id());
        assertEquals(6, updated.hardwareConfig().cpuCores(), "hardware update persists cpu cores");
        assertFalse(updated.guestPassword().isEmpty(), "rotated credential persists");

        InstanceLifecycle.Instance clone = controller.cloneInstance(source.id());
        assertEquals(InstanceLifecycle.State.CREATED, clone.state(), "clone starts CREATED");
        assertEquals(source.distro().id(), clone.distro().id(), "clone copies distro identity");
        assertFalse(clone.id().equals(source.id()), "clone gets a fresh id");

        provisionAndStop(controller, source.id());
        controller.start(source.id());
        controller.recordCrash(source.id(), 3, null, "diagnostic export crash");
        Path report = testDirectory.resolve("controller-maintenance")
                .resolve(source.id() + "-diagnostic.txt");
        controller.exportDiagnostic(source.id(), report);
        assertTrue(Files.isRegularFile(report), "diagnostic report file written");
    }

    private static void enforcesInvalidTransitions(Path testDirectory) throws IOException {
        InstanceLifecycle.Controller controller = controllerFor(testDirectory, "controller-invalid");
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), emptyVolume("invalid"));
        assertThrows(InstanceLifecycle.InvalidTransitionException.class,
                () -> controller.suspend(instance.id()),
                "CREATED -> SUSPENDED must be refused");
        assertThrows(InstanceLifecycle.InvalidTransitionException.class,
                () -> controller.start(instance.id()),
                "CREATED -> RUNNING must be refused");
        assertThrows(IllegalArgumentException.class,
                () -> controller.reconcileAfterHostDeath(InstanceLifecycle.State.SUSPENDED),
                "reconciliation must refuse non-recovery target states");
    }

    private static void provisionAndStop(InstanceLifecycle.Controller controller, String id)
            throws IOException {
        controller.beginProvisioning(id);
        controller.completeProvisioning(id);
    }

    private static InstanceLifecycle.Controller controllerFor(Path testDirectory, String name)
            throws IOException {
        return new InstanceLifecycle.Controller(
                new InstanceLifecycle.FileStore(testDirectory.resolve(name)), CLOCK);
    }

    private static InstanceLifecycle.Store storeFor(Path testDirectory, String name) throws IOException {
        return new InstanceLifecycle.FileStore(testDirectory.resolve(name));
    }

    private static Path emptyVolume(String name) throws IOException {
        return Files.createFile(Files.createTempDirectory("controller-volumes").resolve(name + ".raw"));
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
