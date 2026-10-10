package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TDD test suite for {@link InstancePowerController} and {@link InstanceLifecycle.State#SUSPENDED}.
 * Covers external manual shutdown, timeout guard, force power-off, suspend, and resume contracts.
 */
public final class InstancePowerControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    public static void main(String[] args) throws Exception {
        Path tempDir;
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            tempDir = Path.of(args[0]).toAbsolutePath().normalize();
            if (Files.exists(tempDir)) {
                deleteRecursively(tempDir);
            }
            Files.createDirectories(tempDir);
        } else {
            tempDir = Files.createTempDirectory("power-controller-test-");
        }

        try {
            InstancePowerControllerTest suite = new InstancePowerControllerTest();
            suite.testNormalSuspendAndResumeLifecycle(tempDir);
            suite.testForcePowerOffFromSuspendedState(tempDir);
            suite.testForcePowerOffFromRunningState(tempDir);
            suite.testGracefulShutdownSuccess(tempDir);
            suite.testGracefulShutdownTimeoutGuard(tempDir);
            suite.testResumeRejectsWhenAnotherInstanceIsAlreadyRunning(tempDir);
            suite.testInvalidStateTransitionsRejected(tempDir);
            suite.testPowerOffForceIsIdempotentOnStoppedInstance(tempDir);
            System.out.println("INSTANCE_POWER_CONTROLLER_TEST_OK");
        } finally {
            if (args == null || args.length == 0) {
                deleteRecursively(tempDir);
            }
        }
    }

    @Test
    public void testAllCases() throws Exception {
        Path tempDir = Files.createTempDirectory("power-controller-junit-");
        try {
            testNormalSuspendAndResumeLifecycle(tempDir);
            testForcePowerOffFromSuspendedState(tempDir);
            testForcePowerOffFromRunningState(tempDir);
            testGracefulShutdownSuccess(tempDir);
            testGracefulShutdownTimeoutGuard(tempDir);
            testResumeRejectsWhenAnotherInstanceIsAlreadyRunning(tempDir);
            testInvalidStateTransitionsRejected(tempDir);
            testPowerOffForceIsIdempotentOnStoppedInstance(tempDir);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public void testNormalSuspendAndResumeLifecycle(Path root) throws Exception {
        Path fixtureDir = root.resolve("suspend-resume");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                fixtureDir.resolve("alpine.raw"));
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());

        TestPowerSeam seam = new TestPowerSeam();
        InstancePowerController powerController = new InstancePowerController(controller, store, seam);

        // 1. Suspend: RUNNING -> SUSPENDED
        boolean suspendOk = powerController.suspend(instance.id());
        assertTrue("suspend should succeed", suspendOk);
        assertTrue("seam freezeCpu should have been invoked", seam.cpuFrozen.get());
        assertEquals(InstanceLifecycle.State.SUSPENDED, store.load(instance.id()).state());

        // 2. Resume: SUSPENDED -> RUNNING
        boolean resumeOk = powerController.resume(instance.id());
        assertTrue("resume should succeed", resumeOk);
        assertFalse("seam cpuFrozen should now be false", seam.cpuFrozen.get());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
    }

    public void testForcePowerOffFromSuspendedState(Path root) throws Exception {
        Path fixtureDir = root.resolve("force-stop-suspended");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                fixtureDir.resolve("alpine.raw"));
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        TestPowerSeam seam = new TestPowerSeam();
        InstancePowerController powerController = new InstancePowerController(controller, store, seam);

        assertTrue(powerController.suspend(instance.id()));
        assertEquals(InstanceLifecycle.State.SUSPENDED, store.load(instance.id()).state());

        // Force power off from SUSPENDED -> STOPPED
        boolean killOk = powerController.powerOffForce(instance.id());
        assertTrue("powerOffForce should succeed", killOk);
        assertTrue("seam killProcess should have been called", seam.processKilled.get());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());
    }

    public void testForcePowerOffFromRunningState(Path root) throws Exception {
        Path fixtureDir = root.resolve("force-stop-running");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                fixtureDir.resolve("alpine.raw"));
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        TestPowerSeam seam = new TestPowerSeam();
        InstancePowerController powerController = new InstancePowerController(controller, store, seam);

        boolean killOk = powerController.powerOffForce(instance.id());
        assertTrue(killOk);
        assertTrue(seam.processKilled.get());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());
    }

    public void testGracefulShutdownSuccess(Path root) throws Exception {
        Path fixtureDir = root.resolve("graceful-shutdown-success");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                fixtureDir.resolve("alpine.raw"));
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        TestPowerSeam seam = new TestPowerSeam();
        seam.simulateNormalGuestExitAfterSignal = true;

        InstancePowerController powerController = new InstancePowerController(controller, store, seam);
        CompletableFuture<Boolean> future = powerController.shutdownGraceful(instance.id(), 3000);

        Boolean result = future.get(5, TimeUnit.SECONDS);
        assertTrue("Graceful shutdown should complete with true", result);
        assertTrue("Shutdown signal should have been sent", seam.shutdownSignalSent.get());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());
    }

    public void testGracefulShutdownTimeoutGuard(Path root) throws Exception {
        Path fixtureDir = root.resolve("graceful-shutdown-timeout");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                fixtureDir.resolve("alpine.raw"));
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        TestPowerSeam seam = new TestPowerSeam();
        // Simulate unresponsive guest: guest never exits despite signal
        seam.simulateNormalGuestExitAfterSignal = false;
        seam.vmAlive.set(true);

        InstancePowerController powerController = new InstancePowerController(controller, store, seam);
        CompletableFuture<Boolean> future = powerController.shutdownGraceful(instance.id(), 200);

        Boolean result = future.get(2, TimeUnit.SECONDS);
        assertFalse("Timeout must resolve to false without locking up", result);
        // State remains RUNNING so user can decide to force-kill
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());

        // Now user can fall back to force power-off
        boolean forceOk = powerController.powerOffForce(instance.id());
        assertTrue("Fallback to force power-off should succeed", forceOk);
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());
    }

    public void testResumeRejectsWhenAnotherInstanceIsAlreadyRunning(Path root) throws Exception {
        Path fixtureDir = root.resolve("single-running-constraint");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        InstanceLifecycle.Instance inst1 = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                fixtureDir.resolve("alpine-1.raw"));
        controller.beginProvisioning(inst1.id());
        controller.completeProvisioning(inst1.id());
        controller.start(inst1.id());

        InstanceLifecycle.Instance inst2 = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                fixtureDir.resolve("alpine-2.raw"));
        controller.beginProvisioning(inst2.id());
        controller.completeProvisioning(inst2.id());

        TestPowerSeam seam = new TestPowerSeam();
        InstancePowerController powerController = new InstancePowerController(controller, store, seam);

        // Suspend inst1
        assertTrue(powerController.suspend(inst1.id()));
        assertEquals(InstanceLifecycle.State.SUSPENDED, store.load(inst1.id()).state());

        // Start inst2 -> inst2 is RUNNING
        controller.start(inst2.id());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(inst2.id()).state());

        // Attempting to resume inst1 while inst2 is RUNNING must fail due to single-running constraint
        try {
            powerController.resume(inst1.id());
            fail("Expected SingleRunningInstanceException when resuming with another VM running");
        } catch (InstanceLifecycle.SingleRunningInstanceException expected) {
            // Expected
        }

        // inst1 remains SUSPENDED
        assertEquals(InstanceLifecycle.State.SUSPENDED, store.load(inst1.id()).state());
    }

    public void testInvalidStateTransitionsRejected(Path root) throws Exception {
        Path fixtureDir = root.resolve("invalid-transitions");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                fixtureDir.resolve("alpine.raw"));

        TestPowerSeam seam = new TestPowerSeam();
        InstancePowerController powerController = new InstancePowerController(controller, store, seam);

        // Cannot suspend CREATED instance
        try {
            powerController.suspend(instance.id());
            fail("Cannot suspend CREATED instance");
        } catch (InstanceLifecycle.InvalidTransitionException expected) {}

        // Cannot resume CREATED instance
        try {
            powerController.resume(instance.id());
            fail("Cannot resume CREATED instance");
        } catch (InstanceLifecycle.InvalidTransitionException expected) {}
    }

    public void testPowerOffForceIsIdempotentOnStoppedInstance(Path root) throws Exception {
        Path fixtureDir = root.resolve("idempotent-stop");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                fixtureDir.resolve("alpine.raw"));
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());

        TestPowerSeam seam = new TestPowerSeam();
        InstancePowerController powerController = new InstancePowerController(controller, store, seam);

        // Force stop on already stopped instance should return true idempotently
        assertTrue(powerController.powerOffForce(instance.id()));
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        }
    }

    /** Mock implementation of {@link InstancePowerController.PowerOperationSeam}. */
    private static final class TestPowerSeam implements InstancePowerController.PowerOperationSeam {
        final AtomicBoolean shutdownSignalSent = new AtomicBoolean(false);
        final AtomicBoolean processKilled = new AtomicBoolean(false);
        final AtomicBoolean cpuFrozen = new AtomicBoolean(false);
        final AtomicBoolean vmAlive = new AtomicBoolean(true);
        boolean simulateNormalGuestExitAfterSignal = false;

        @Override
        public boolean sendGracefulShutdown(String instanceId) throws IOException {
            shutdownSignalSent.set(true);
            if (simulateNormalGuestExitAfterSignal) {
                // Background simulated exit after a short delay
                new Thread(() -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {}
                    vmAlive.set(false);
                }).start();
            }
            return true;
        }

        @Override
        public boolean killProcess(String instanceId) throws IOException {
            processKilled.set(true);
            vmAlive.set(false);
            return true;
        }

        @Override
        public boolean freezeCpu(String instanceId) throws IOException {
            cpuFrozen.set(true);
            return true;
        }

        @Override
        public boolean unfreezeCpu(String instanceId) throws IOException {
            cpuFrozen.set(false);
            return true;
        }

        @Override
        public boolean isVmAlive(String instanceId) {
            return vmAlive.get();
        }
    }
}
