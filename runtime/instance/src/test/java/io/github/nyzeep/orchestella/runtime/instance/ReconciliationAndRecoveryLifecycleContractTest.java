package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Lifecycle contract for Ticket 28: graceful stop, host death reconciliation,
 * cold restore, and crash state recording.
 */
public final class ReconciliationAndRecoveryLifecycleContractTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochMilli(1_789_000_005_000L), ZoneOffset.UTC);

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected one approved test directory");
        }
        Path testDirectory = Path.of(arguments[0]).toAbsolutePath().normalize();
        resetFixture(testDirectory);

        persistsInterruptedStateAcrossStoreReconstruction(testDirectory);
        persistsCrashedStateAndDiagnosticAcrossStoreReconstruction(testDirectory);
        validatesAllowedAndForbiddenTransitions(testDirectory);
        coldRestoreFromInterruptedRestartsInstance(testDirectory);
        coldRestoreFromCrashedRestartsInstanceAndClearsDiagnostic(testDirectory);
        resetToStoppedFromInterruptedAndCrashed(testDirectory);
        reconcileAllAfterHostDeathTargetingStoppedOrInterrupted(testDirectory);
        exportsStructuredDiagnosticReportAndEmbeddedLog(testDirectory);
        recordsGracefulHostStop(testDirectory);
    }

    private static void persistsInterruptedStateAcrossStoreReconstruction(Path testDirectory)
            throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-interrupted"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path rootVolume = testDirectory.resolve("alpine-interrupted.raw");

        InstanceLifecycle.Instance instance =
                controller.create(new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        // Reconcile host death while running
        controller.reconcileAfterHostDeath(instance.id());

        InstanceLifecycle.FileStore reconstructedStore =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-interrupted"));
        InstanceLifecycle.Instance reconstructed = reconstructedStore.load(instance.id());
        assertEquals(
                InstanceLifecycle.State.INTERRUPTED,
                reconstructed.state(),
                "interrupted state persists across store reconstruction");
    }

    private static void persistsCrashedStateAndDiagnosticAcrossStoreReconstruction(Path testDirectory)
            throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-crashed"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path rootVolume = testDirectory.resolve("alpine-crashed.raw");
        Path logPath = testDirectory.resolve("vmm-stderr.log");
        Files.writeString(logPath, "qemu: fatal: emulation error\n");

        InstanceLifecycle.Instance instance =
                controller.create(new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        controller.recordCrash(instance.id(), 137, logPath);

        InstanceLifecycle.FileStore reconstructedStore =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-crashed"));
        InstanceLifecycle.Instance reconstructed = reconstructedStore.load(instance.id());
        assertEquals(
                InstanceLifecycle.State.CRASHED,
                reconstructed.state(),
                "crashed state persists across store reconstruction");

        InstanceLifecycle.CrashDiagnostic diagnostic = reconstructed.crashDiagnostic();
        assertTrue(diagnostic != null, "crash diagnostic must be present");
        assertEquals(137, diagnostic.exitCode(), "exit code");
        assertEquals(CLOCK.millis(), diagnostic.timestampMillis(), "timestamp millis");
        assertEquals(logPath, diagnostic.logPath(), "log path reference");
        assertEquals(logPath, reconstructed.diagnosticLogPath(), "diagnosticLogPath getter");
    }

    private static void validatesAllowedAndForbiddenTransitions(Path testDirectory) throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-transitions"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path rootVolume = testDirectory.resolve("alpine-transitions.raw");

        InstanceLifecycle.Instance instance =
                controller.create(new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);

        // CREATED -> RUNNING is forbidden
        assertThrows(
                InstanceLifecycle.InvalidTransitionException.class,
                () -> controller.start(instance.id()),
                "cannot start unprovisioned instance");

        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state(), "stopped after provisioning");

        // STOPPED -> CRASHED is forbidden
        assertThrows(
                InstanceLifecycle.InvalidTransitionException.class,
                () -> controller.recordCrash(instance.id(), 1, null),
                "cannot crash stopped instance");

        controller.start(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state(), "running");

        // RUNNING -> STOPPED via graceful stop
        controller.recordGracefulStop(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state(), "stopped gracefully");

        // Restart to test crash transition
        controller.start(instance.id());
        controller.recordCrash(instance.id(), 137, null);
        assertEquals(InstanceLifecycle.State.CRASHED, store.load(instance.id()).state(), "crashed");

        // CRASHED -> PROVISIONING is forbidden
        assertThrows(
                InstanceLifecycle.InvalidTransitionException.class,
                () -> controller.beginProvisioning(instance.id()),
                "cannot provision crashed instance");
    }

    private static void coldRestoreFromInterruptedRestartsInstance(Path testDirectory) throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-cold-restore-interrupted"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path rootVolume = testDirectory.resolve("alpine-cold-restore.raw");

        InstanceLifecycle.Instance instance =
                controller.create(new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        // Simulate host death -> stale RUNNING reconciled to INTERRUPTED
        controller.reconcileAfterHostDeath(instance.id());
        assertEquals(InstanceLifecycle.State.INTERRUPTED, store.load(instance.id()).state(), "reconciled interrupted");

        // Normal start should be rejected because state is INTERRUPTED, not STOPPED
        assertThrows(
                InstanceLifecycle.InvalidTransitionException.class,
                () -> controller.start(instance.id()),
                "cannot normal-start an interrupted instance; coldRestore required");

        // Cold restore restarts the interrupted instance
        InstanceLifecycle.Instance restored = controller.coldRestore(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING, restored.state(), "cold restore returns running state");
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state(), "cold restore persisted");

        // Single running instance rule still applies
        InstanceLifecycle.Instance other =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("alpine-other.raw"));
        controller.beginProvisioning(other.id());
        controller.completeProvisioning(other.id());
        assertThrows(
                InstanceLifecycle.SingleRunningInstanceException.class,
                () -> controller.start(other.id()),
                "cannot start second instance while cold-restored instance is running");

        // Guest normal stop completes successfully
        controller.recordNormalGuestStop(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state(), "stopped after normal stop");
    }

    private static void coldRestoreFromCrashedRestartsInstanceAndClearsDiagnostic(Path testDirectory)
            throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-cold-restore-crashed"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path rootVolume = testDirectory.resolve("alpine-crashed-restore.raw");
        Path logPath = testDirectory.resolve("crash.log");
        Files.writeString(logPath, "error: sigkill received\n");

        InstanceLifecycle.Instance instance =
                controller.create(new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        controller.recordCrash(instance.id(), 137, logPath);
        InstanceLifecycle.Instance crashed = store.load(instance.id());
        assertEquals(InstanceLifecycle.State.CRASHED, crashed.state(), "crashed");
        assertTrue(crashed.crashDiagnostic() != null, "diagnostic present before restore");

        // Cold restore restarts from CRASHED and clears prior diagnostic
        InstanceLifecycle.Instance restored = controller.coldRestore(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING, restored.state(), "restored to running");
        assertTrue(restored.crashDiagnostic() == null, "prior crash diagnostic cleared on cold restore");
        assertTrue(store.load(instance.id()).crashDiagnostic() == null, "cleared diagnostic persisted");

        controller.recordNormalGuestStop(instance.id());
    }

    private static void resetToStoppedFromInterruptedAndCrashed(Path testDirectory) throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-reset-stopped"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path rootVolume = testDirectory.resolve("alpine-reset.raw");

        InstanceLifecycle.Instance instance =
                controller.create(new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        // 1. Reset from INTERRUPTED
        controller.reconcileAfterHostDeath(instance.id());
        assertEquals(InstanceLifecycle.State.INTERRUPTED, store.load(instance.id()).state(), "interrupted");
        InstanceLifecycle.Instance resetFromInterrupted = controller.resetToStopped(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, resetFromInterrupted.state(), "reset to stopped from interrupted");
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state(), "reset persisted");

        // Now it can normal-start
        controller.start(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state(), "normal-started from stopped");

        // 2. Reset from CRASHED
        controller.recordCrash(instance.id(), 1, null);
        assertEquals(InstanceLifecycle.State.CRASHED, store.load(instance.id()).state(), "crashed");
        InstanceLifecycle.Instance resetFromCrashed = controller.resetToStopped(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, resetFromCrashed.state(), "reset to stopped from crashed");
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state(), "reset persisted");

        // Cannot reset an already STOPPED instance
        assertThrows(
                InstanceLifecycle.InvalidTransitionException.class,
                () -> controller.resetToStopped(instance.id()),
                "cannot reset instance already in STOPPED");
    }

    private static void reconcileAllAfterHostDeathTargetingStoppedOrInterrupted(Path testDirectory)
            throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-reconcile-batch"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        InstanceLifecycle.Instance inst1 =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("inst1.raw"));
        InstanceLifecycle.Instance inst2 =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("inst2.raw"));

        controller.beginProvisioning(inst1.id());
        controller.completeProvisioning(inst1.id());
        controller.beginProvisioning(inst2.id());
        controller.completeProvisioning(inst2.id());

        // inst1 is running, inst2 is stopped
        controller.start(inst1.id());

        // Batch reconciliation default (target: INTERRUPTED)
        java.util.List<InstanceLifecycle.Instance> reconciledList = controller.reconcileAfterHostDeath();
        assertEquals(1, reconciledList.size(), "reconciled count");
        assertEquals(inst1.id(), reconciledList.get(0).id(), "reconciled instance id");
        assertEquals(InstanceLifecycle.State.INTERRUPTED, store.load(inst1.id()).state(), "inst1 interrupted");
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(inst2.id()).state(), "inst2 remains stopped");

        // Cold restore inst1, then test targeted reconciliation to STOPPED
        controller.coldRestore(inst1.id());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(inst1.id()).state(), "inst1 running again");

        java.util.List<InstanceLifecycle.Instance> reconciledToStopped =
                controller.reconcileAfterHostDeath(InstanceLifecycle.State.STOPPED);
        assertEquals(1, reconciledToStopped.size(), "reconciled to stopped count");
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(inst1.id()).state(), "inst1 reconciled to stopped");

        // Target state validation
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.reconcileAfterHostDeath(InstanceLifecycle.State.CRASHED),
                "cannot reconcile target to CRASHED");
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.reconcileAfterHostDeath(InstanceLifecycle.State.RUNNING),
                "cannot reconcile target to RUNNING");
    }

    private static void exportsStructuredDiagnosticReportAndEmbeddedLog(Path testDirectory) throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-export"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path rootVolume = testDirectory.resolve("alpine-export.raw");
        Path logFile = testDirectory.resolve("crash-stderr.log");
        Files.writeString(logFile, "FATAL: kernel panic - unable to mount root fs\nline 2 of panic log\n");

        InstanceLifecycle.Instance instance =
                controller.create(new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        controller.recordCrash(instance.id(), 139, logFile, "Segmentation fault in vcpu thread");

        Path exportDestination = testDirectory.resolve("exported-reports").resolve("crash-report.txt");
        controller.exportDiagnostic(instance.id(), exportDestination);

        assertTrue(Files.isRegularFile(exportDestination), "export file must be created");
        String content = Files.readString(exportDestination);
        assertTrue(content.contains(instance.id()), "report contains instance id");
        assertTrue(content.contains("alpine 3.24.1"), "report contains distro");
        assertTrue(content.contains("139"), "report contains exit code");
        assertTrue(content.contains("Segmentation fault in vcpu thread"), "report contains message");
        assertTrue(content.contains(logFile.toString()), "report contains log path reference");
        assertTrue(content.contains("FATAL: kernel panic - unable to mount root fs"), "report embeds log content");
        assertTrue(content.contains("line 2 of panic log"), "report embeds full log content");

        // Export on instance without crash diagnostic throws IllegalStateException
        InstanceLifecycle.Instance normalInst =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("alpine-normal.raw"));
        assertThrows(
                IllegalStateException.class,
                () -> controller.exportDiagnostic(normalInst.id(), testDirectory.resolve("dummy.txt")),
                "cannot export diagnostic on instance without crash");
    }

    private static void recordsGracefulHostStop(Path testDirectory) throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-graceful-stop"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path rootVolume = testDirectory.resolve("alpine-graceful.raw");

        InstanceLifecycle.Instance instance =
                controller.create(new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        InstanceLifecycle.Instance stopped = controller.recordGracefulStop(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, stopped.state(), "graceful stop returns STOPPED state");
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state(), "graceful stop persisted");
    }

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

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
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
