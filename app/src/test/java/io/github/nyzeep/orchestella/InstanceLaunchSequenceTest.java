package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class InstanceLaunchSequenceTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void firstLaunchThenGuestStopThenRelaunchFollowTheNormalLifecycle() throws Exception {
        Path dir = temporaryFolder.newFolder("instances").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                dir.resolve("root-volume.raw"));

        InstanceLaunchSequence.beforeLaunch(controller, created);
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(created.id()).state());

        InstanceLaunchSequence.afterGuestExit(controller, created.id());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(created.id()).state());

        InstanceLaunchSequence.beforeLaunch(controller, store.load(created.id()));
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(created.id()).state());
    }

    private static final class UnconfirmedExitSession extends ExitSession implements RuntimeOwnedSession {
        int stopCalls;
        volatile boolean confirmed;
        UnconfirmedExitSession() { super(0); }
        @Override public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            stopCalls++;
            return confirmed ? RuntimeProcessSupervisor.StopResult.CONFIRMED
                    : RuntimeProcessSupervisor.StopResult.UNCONFIRMED;
        }
        @Override public boolean isRuntimeAlive() { return !confirmed; }
    }

    @Test
    public void unconfirmedStopKeepsRunningMetadataOwnerAndGuard() throws Exception {
        Path dir = temporaryFolder.newFolder("stop-unconfirmed").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        InstanceLaunchGuard.begin(instance.id());
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        UnconfirmedExitSession session = new UnconfirmedExitSession();
        ExecutionSessionRegistry.register(instance.id(), session);
        java.util.concurrent.atomic.AtomicInteger feedback = new java.util.concurrent.atomic.AtomicInteger();
        try {
            InstanceLaunchSequence.ExitResult result = InstanceLaunchSequence.awaitGuestExit(
                    controller, instance.id(), session, feedback::incrementAndGet);
            assertEquals(InstanceLaunchSequence.ExitStatus.STOP_UNCONFIRMED, result.status());
            assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
            org.junit.Assert.assertSame("unconfirmed stop must keep the owner",
                    session, ExecutionSessionRegistry.get(instance.id()));
            org.junit.Assert.assertTrue(InstanceLaunchGuard.isInFlight(instance.id()));
            assertEquals("no confirmed-exit feedback may run", 0, feedback.get());
            assertEquals("channels stay with the retained owner", 0, session.closes);
            assertEquals(1, session.stopCalls);
        } finally {
            session.confirmed = true;
            ExecutionSessionRegistry.unregister(instance.id());
            InstanceLaunchGuard.end(instance.id());
        }
    }

    private static class ExitSession implements ExecutionSession {
        private final int code;
        int closes;
        int terminations;
        ExitSession(int code) { this.code = code; }
        public int waitFor() throws InterruptedException { return code; }
        public java.io.InputStream terminalOutput() { return new java.io.ByteArrayInputStream(new byte[0]); }
        public java.io.OutputStream terminalInput() { return new java.io.ByteArrayOutputStream(); }
        public java.io.InputStream controlOutput() { return new java.io.ByteArrayInputStream(new byte[0]); }
        public java.io.OutputStream controlInput() { return new java.io.ByteArrayOutputStream(); }
        public void close() { closes++; }
        public void terminate() { terminations++; }
    }

    @Test
    public void waitFailureDoesNotClaimVmDeathOrStopForegroundBinding() throws Exception {
        Path dir = temporaryFolder.newFolder("wait-failure").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        ExitSession session = new ExitSession(0) {
            public int waitFor() { throw new IllegalStateException("synthetic wait failed"); }
        };
        InstanceLaunchGuard.begin(instance.id());
        ExecutionSessionRegistry.register(instance.id(), session);
        java.util.concurrent.atomic.AtomicInteger stopped = new java.util.concurrent.atomic.AtomicInteger();
        try {
            InstanceLaunchSequence.ExitResult result = InstanceLaunchSequence.awaitGuestExit(
                    controller, instance.id(), session, stopped::incrementAndGet);
            assertEquals(InstanceLaunchSequence.ExitStatus.WAIT_FAILED, result.status());
            assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
            org.junit.Assert.assertNull(store.load(instance.id()).crashDiagnostic());
            org.junit.Assert.assertSame(session, ExecutionSessionRegistry.get(instance.id()));
            org.junit.Assert.assertTrue(InstanceLaunchGuard.isInFlight(instance.id()));
            assertEquals(0, stopped.get());
            assertEquals(0, session.closes);
            assertEquals(0, session.terminations);
        } finally {
            ExecutionSessionRegistry.unregister(instance.id());
            InstanceLaunchGuard.end(instance.id());
        }
    }

    @Test
    public void acceptedForceStopMakesItsConfirmedNonzeroExitIntentional() throws Exception {
        Path dir = temporaryFolder.newFolder("force-stop").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        ExitSession session = new ExitSession(23);
        ExecutionSessionRegistry.register(instance.id(), session);
        java.util.concurrent.atomic.AtomicInteger stopped = new java.util.concurrent.atomic.AtomicInteger();
        try {
            ExecutionSessionRegistry.requestStop(instance.id(), session,
                    PersistentTerminalSessionPool.StopIntent.FORCE, session::terminate);
            assertEquals("An accepted stop request must not predict exit", InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
            InstanceLaunchSequence.ExitResult result = InstanceLaunchSequence.awaitGuestExit(
                    controller, instance.id(), session, stopped::incrementAndGet);
            assertEquals(InstanceLaunchSequence.ExitStatus.NORMAL, result.status());
            assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());
            org.junit.Assert.assertNull(store.load(instance.id()).crashDiagnostic());
            assertEquals(1, session.terminations);
            assertEquals(1, stopped.get());
        } finally {
            ExecutionSessionRegistry.unregister(instance.id());
        }
    }

    @Test
    public void failedOrStaleStopRequestsDoNotHideAbnormalExitOrTouchReplacement() throws Exception {
        Path dir = temporaryFolder.newFolder("rejected-stop").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        ExitSession old = new ExitSession(23);
        ExitSession session = new ExitSession(23);
        ExecutionSessionRegistry.register(instance.id(), old);
        ExecutionSessionRegistry.register(instance.id(), session);
        try {
            try {
                ExecutionSessionRegistry.requestStop(instance.id(), old,
                        PersistentTerminalSessionPool.StopIntent.FORCE, old::terminate);
                fail("stale action accepted");
            } catch (java.io.IOException expected) { }
            assertEquals(0, old.terminations);
            try {
                ExecutionSessionRegistry.requestStop(instance.id(), session,
                        PersistentTerminalSessionPool.StopIntent.FORCE,
                        () -> { throw new java.io.IOException("synthetic rejection"); });
                fail("rejected action accepted");
            } catch (java.io.IOException expected) { }
            assertEquals(PersistentTerminalSessionPool.StopIntent.NONE,
                    ExecutionSessionRegistry.stopIntent(instance.id(), session));
            InstanceLaunchSequence.ExitResult result = InstanceLaunchSequence.awaitGuestExit(
                    controller, instance.id(), session, () -> {});
            assertEquals(InstanceLaunchSequence.ExitStatus.CRASHED, result.status());
            assertEquals(23, store.load(instance.id()).crashDiagnostic().exitCode());
            assertEquals(0, session.terminations);
        } finally { ExecutionSessionRegistry.unregister(instance.id()); }
    }

    @Test
    public void gracefulIntentWaitsForExitAndDoesNotMaskNonzeroFailure() throws Exception {
        Path dir = temporaryFolder.newFolder("graceful-stop").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        ExitSession session = new ExitSession(23);
        ExecutionSessionRegistry.register(instance.id(), session);
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        try {
            ExecutionSessionRegistry.requestStop(instance.id(), session,
                    PersistentTerminalSessionPool.StopIntent.GRACEFUL, requests::incrementAndGet);
            ExecutionSessionRegistry.requestStop(instance.id(), session,
                    PersistentTerminalSessionPool.StopIntent.GRACEFUL, requests::incrementAndGet);
            assertEquals(1, requests.get());
            assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
            InstanceLaunchSequence.ExitResult result = InstanceLaunchSequence.awaitGuestExit(
                    controller, instance.id(), session, () -> {});
            assertEquals(InstanceLaunchSequence.ExitStatus.CRASHED, result.status());
            assertEquals(23, store.load(instance.id()).crashDiagnostic().exitCode());
        } finally { ExecutionSessionRegistry.unregister(instance.id()); }
    }

    @Test
    public void interruptedWaitKeepsOwnerAndPreservesThreadInterrupt() throws Exception {
        Path dir = temporaryFolder.newFolder("interrupted-wait").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        ExitSession session = new ExitSession(0) {
            public int waitFor() throws InterruptedException { throw new InterruptedException("synthetic interruption"); }
        };
        ExecutionSessionRegistry.register(instance.id(), session);
        try {
            InstanceLaunchSequence.ExitResult result = InstanceLaunchSequence.awaitGuestExit(
                    controller, instance.id(), session, () -> { throw new AssertionError("cannot stop foreground"); });
            assertEquals(InstanceLaunchSequence.ExitStatus.WAIT_FAILED, result.status());
            org.junit.Assert.assertTrue(Thread.currentThread().isInterrupted());
            org.junit.Assert.assertSame(session, ExecutionSessionRegistry.get(instance.id()));
            assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
        } finally { Thread.interrupted(); ExecutionSessionRegistry.unregister(instance.id()); }
    }

    @Test
    public void currentExit0CompletesOnceAndDuplicateWatcherHasNoSideEffects() throws Exception {
        Path dir = temporaryFolder.newFolder("current-exit0").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), dir.resolve("never-opened.raw"));
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        ExitSession session = new ExitSession(0);
        ExecutionSessionRegistry.register(instance.id(), session);
        java.util.concurrent.atomic.AtomicInteger feedback = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(InstanceLaunchSequence.ExitStatus.NORMAL, InstanceLaunchSequence.awaitGuestExit(
                controller, instance.id(), session, feedback::incrementAndGet).status());
        assertEquals(InstanceLaunchSequence.ExitStatus.STALE, InstanceLaunchSequence.awaitGuestExit(
                controller, instance.id(), session, feedback::incrementAndGet).status());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());
        org.junit.Assert.assertNull(store.load(instance.id()).crashDiagnostic());
        assertEquals(1, feedback.get());
        assertEquals(1, session.closes);
        assertEquals(0, session.terminations);
    }

    @Test
    public void confirmedOwnerExit23RecordsCrashWithItsCodeAndStopsFeedbackOnce() throws Exception {
        Path dir = temporaryFolder.newFolder("exit23").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        ExecutionSession session = new ExitSession(23);
        InstanceLaunchGuard.begin(instance.id());
        ExecutionSessionRegistry.register(instance.id(), session);
        java.util.concurrent.atomic.AtomicInteger stopped = new java.util.concurrent.atomic.AtomicInteger();
        try {
            InstanceLaunchSequence.ExitResult result = InstanceLaunchSequence.awaitGuestExit(
                    controller, instance.id(), session, stopped::incrementAndGet);
            assertEquals(InstanceLaunchSequence.ExitStatus.CRASHED, result.status());
            InstanceLifecycle.Instance crashed = store.load(instance.id());
            assertEquals(InstanceLifecycle.State.CRASHED, crashed.state());
            assertEquals(23, crashed.crashDiagnostic().exitCode());
            assertEquals(1, stopped.get());
            org.junit.Assert.assertNull(ExecutionSessionRegistry.get(instance.id()));
            org.junit.Assert.assertFalse(InstanceLaunchGuard.isInFlight(instance.id()));
            assertEquals(instance.bridgeToken(), crashed.bridgeToken());
            assertEquals(instance.guestPassword(), crashed.guestPassword());
            assertEquals(instance.rootVolume(), crashed.rootVolume());
            controller.coldRestore(instance.id());
            assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
        } finally {
            ExecutionSessionRegistry.unregister(instance.id());
            InstanceLaunchGuard.end(instance.id());
        }
    }

    @Test
    public void staleOwnerExitCannotClearReplacementMetadataSessionGuardOrNotification() throws Exception {
        Path dir = temporaryFolder.newFolder("stale-owner").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        java.util.concurrent.CountDownLatch waiting = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finishOld = new java.util.concurrent.CountDownLatch(1);
        ExitSession old = new ExitSession(0) {
            public int waitFor() throws InterruptedException {
                waiting.countDown();
                if (!finishOld.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
                return 0;
            }
        };
        ExecutionSessionRegistry.register(instance.id(), old);
        java.util.concurrent.atomic.AtomicInteger stopped = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CompletableFuture<InstanceLaunchSequence.ExitResult> observed = new java.util.concurrent.CompletableFuture<>();
        Thread watcher = new Thread(() -> {
            try { observed.complete(InstanceLaunchSequence.awaitGuestExit(controller, instance.id(), old, stopped::incrementAndGet)); }
            catch (Throwable failure) { observed.completeExceptionally(failure); }
        });
        watcher.setDaemon(true);
        watcher.start();
        ExitSession replacement = new ExitSession(0);
        try {
            org.junit.Assert.assertTrue(waiting.await(2, java.util.concurrent.TimeUnit.SECONDS));
            controller.recordGracefulStop(instance.id());
            InstanceLaunchSequence.beforeLaunch(controller, store.load(instance.id()));
            ExecutionSessionRegistry.register(instance.id(), replacement);
            InstanceLaunchGuard.begin(instance.id());
            finishOld.countDown();
            assertEquals(InstanceLaunchSequence.ExitStatus.STALE, observed.get(2, java.util.concurrent.TimeUnit.SECONDS).status());
            assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
            org.junit.Assert.assertNull(store.load(instance.id()).crashDiagnostic());
            org.junit.Assert.assertSame(replacement, ExecutionSessionRegistry.get(instance.id()));
            org.junit.Assert.assertTrue(InstanceLaunchGuard.isInFlight(instance.id()));
            assertEquals(0, stopped.get());
            assertEquals(0, replacement.closes);
            assertEquals(0, replacement.terminations);
        } finally {
            finishOld.countDown();
            watcher.join(2000);
            ExecutionSessionRegistry.unregister(instance.id());
            InstanceLaunchGuard.end(instance.id());
        }
    }

    @Test
    public void coldRelaunchFromCrashOrInterruptionPreservesInstanceIdentityAndSingleRunningSlot() throws Exception {
        Path dir = temporaryFolder.newFolder("cold-relaunch").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        controller.recordCrash(instance.id(), 23, null, "synthetic diagnostic");
        InstanceLaunchSequence.beforeLaunch(controller, store.load(instance.id()));
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
        org.junit.Assert.assertNull(store.load(instance.id()).crashDiagnostic());
        controller.reconcileAfterHostDeath(instance.id());
        assertEquals(InstanceLifecycle.State.INTERRUPTED, store.load(instance.id()).state());
        InstanceLifecycle.Instance other = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), dir.resolve("other.raw"));
        InstanceLaunchSequence.beforeLaunch(controller, other);
        try {
            InstanceLaunchSequence.beforeLaunch(controller, store.load(instance.id()));
            fail("cold restore stole the single RUNNING slot");
        } catch (InstanceLifecycle.SingleRunningInstanceException expected) { }
        assertEquals(InstanceLifecycle.State.INTERRUPTED, store.load(instance.id()).state());
        controller.recordGracefulStop(other.id());
        InstanceLaunchSequence.beforeLaunch(controller, store.load(instance.id()));
        InstanceLifecycle.Instance restored = store.load(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING, restored.state());
        assertEquals(instance.rootVolume(), restored.rootVolume());
        assertEquals(instance.bridgeToken(), restored.bridgeToken());
        assertEquals(instance.guestPassword(), restored.guestPassword());
        org.junit.Assert.assertFalse(java.nio.file.Files.exists(instance.rootVolume()));
    }

    @Test
    public void olderCompletionCannotEndANewerLaunchBeforeItsOwnerRegisters() throws Exception {
        Path dir = temporaryFolder.newFolder("replacement-launching").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        String oldGeneration = InstanceLaunchGuard.begin(instance.id());
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        ExitSession old = new ExitSession(0);
        ExecutionSessionRegistry.register(instance.id(), old);
        controller.recordGracefulStop(instance.id());
        org.junit.Assert.assertTrue(InstanceLaunchGuard.end(instance.id(), oldGeneration));
        String newGeneration = InstanceLaunchGuard.begin(instance.id());
        InstanceLaunchSequence.beforeLaunch(controller, store.load(instance.id()));
        try {
            assertEquals(InstanceLaunchSequence.ExitStatus.STALE, InstanceLaunchSequence.awaitGuestExit(
                    controller, instance.id(), old, () -> { throw new AssertionError("old notification stop"); }).status());
            assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
            org.junit.Assert.assertTrue(InstanceLaunchGuard.isLatest(instance.id(), newGeneration));
            org.junit.Assert.assertFalse(InstanceLaunchGuard.end(instance.id(), oldGeneration));
            org.junit.Assert.assertTrue(InstanceLaunchGuard.isInFlight(instance.id()));
        } finally { ExecutionSessionRegistry.unregister(instance.id()); InstanceLaunchGuard.end(instance.id()); }
    }

    @Test
    public void failedLaunchOnlyReleasesItsOwnUnregisteredSlot() throws Exception {
        Path dir = temporaryFolder.newFolder("failed-launch").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        String generation = InstanceLaunchGuard.begin(instance.id());
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        java.util.concurrent.atomic.AtomicInteger stops = new java.util.concurrent.atomic.AtomicInteger();
        org.junit.Assert.assertTrue(InstanceLaunchSequence.afterLaunchFailure(controller, instance.id(), generation, true, stops::incrementAndGet));
        assertEquals(InstanceLifecycle.State.CRASHED, store.load(instance.id()).state());
        assertEquals(-1, store.load(instance.id()).crashDiagnostic().exitCode());
        String replacementGeneration = InstanceLaunchGuard.begin(instance.id());
        InstanceLaunchSequence.beforeLaunch(controller, store.load(instance.id()));
        ExitSession replacement = new ExitSession(0);
        ExecutionSessionRegistry.register(instance.id(), replacement);
        try {
            org.junit.Assert.assertFalse(InstanceLaunchSequence.afterLaunchFailure(controller, instance.id(), generation, true, stops::incrementAndGet));
            org.junit.Assert.assertFalse(InstanceLaunchSequence.afterLaunchFailure(controller, instance.id(), replacementGeneration, true, stops::incrementAndGet));
            assertEquals(1, stops.get());
            assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
            assertEquals(0, replacement.closes);
        } finally { ExecutionSessionRegistry.unregister(instance.id()); InstanceLaunchGuard.end(instance.id()); }
    }

    @Test
    public void notificationFailureCannotKeepAConfirmedExitedOwnerRegistered() throws Exception {
        Path dir = temporaryFolder.newFolder("notification-failure").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        InstanceLaunchGuard.begin(instance.id());
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        ExitSession session = new ExitSession(0);
        ExecutionSessionRegistry.register(instance.id(), session);
        try {
            try {
                InstanceLaunchSequence.awaitGuestExit(controller, instance.id(), session,
                        () -> { throw new IllegalStateException("synthetic Android notification failure"); });
                fail("feedback error must be surfaced");
            } catch (IllegalStateException expected) { }
            assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());
            org.junit.Assert.assertNull(ExecutionSessionRegistry.get(instance.id()));
            org.junit.Assert.assertFalse(InstanceLaunchGuard.isInFlight(instance.id()));
            assertEquals(1, session.closes);
            assertEquals(0, session.terminations);
        } finally { ExecutionSessionRegistry.unregister(instance.id()); InstanceLaunchGuard.end(instance.id()); }
    }

    @Test
    public void rejectedConcurrentLaunchCannotReplaceUnregisteredOwnersGeneration() throws Exception {
        Path dir = temporaryFolder.newFolder("exclusive-launch-admission").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("never-opened.raw"));
        String generationA = InstanceLaunchGuard.begin(instance.id());
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        org.junit.Assert.assertNull(ExecutionSessionRegistry.get(instance.id()));
        java.util.concurrent.atomic.AtomicInteger feedback = new java.util.concurrent.atomic.AtomicInteger();
        try {
            String rejectedGeneration = InstanceLaunchGuard.begin(instance.id());
            org.junit.Assert.assertNull("B must be refused while A owns launch admission", rejectedGeneration);
            org.junit.Assert.assertFalse(InstanceLaunchSequence.afterLaunchFailure(
                    controller, instance.id(), rejectedGeneration, false, feedback::incrementAndGet));
            org.junit.Assert.assertFalse(InstanceLaunchGuard.end(instance.id(), rejectedGeneration));
            org.junit.Assert.assertFalse(InstanceLaunchGuard.end(instance.id(), "old-generation"));
            org.junit.Assert.assertTrue(InstanceLaunchGuard.isLatest(instance.id(), generationA));
            assertEquals(generationA, InstanceLaunchGuard.generation(instance.id()));
            assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
            ExitSession ownerA = new ExitSession(0);
            ExecutionSessionRegistry.register(instance.id(), ownerA);
            assertEquals(generationA, ExecutionSessionRegistry.generation(instance.id(), ownerA));
            assertEquals(InstanceLaunchSequence.ExitStatus.NORMAL, InstanceLaunchSequence.awaitGuestExit(
                    controller, instance.id(), ownerA, feedback::incrementAndGet).status());
            assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());
            org.junit.Assert.assertNull(ExecutionSessionRegistry.get(instance.id()));
            org.junit.Assert.assertFalse(InstanceLaunchGuard.isInFlight(instance.id()));
            org.junit.Assert.assertFalse(InstanceLaunchGuard.end(instance.id(), generationA));
            assertEquals(1, ownerA.closes);
            assertEquals(1, feedback.get());
        } finally { ExecutionSessionRegistry.unregister(instance.id()); InstanceLaunchGuard.end(instance.id()); }
    }

    @Test
    public void beforeLaunchKeepsTheSingleRunningInstanceConstraint() throws Exception {
        Path dir = temporaryFolder.newFolder("single").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance first = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                dir.resolve("first.raw"));
        InstanceLifecycle.Instance second = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                dir.resolve("second.raw"));

        InstanceLaunchSequence.beforeLaunch(controller, first);
        try {
            InstanceLaunchSequence.beforeLaunch(controller, second);
            fail("a second RUNNING instance must be rejected");
        } catch (InstanceLifecycle.SingleRunningInstanceException expected) {
            assertEquals(InstanceLifecycle.State.RUNNING, store.load(first.id()).state());
            assertEquals(InstanceLifecycle.State.STOPPED, store.load(second.id()).state());
        }
    }
}
