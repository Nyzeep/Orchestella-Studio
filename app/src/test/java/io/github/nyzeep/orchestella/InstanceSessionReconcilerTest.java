package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class InstanceSessionReconcilerTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private PersistentTerminalSessionPool pool;
    private InstanceLifecycle.FileStore store;
    private InstanceLifecycle.Controller controller;

    @Before
    public void setUp() throws Exception {
        pool = new PersistentTerminalSessionPool();
        Path dir = temporaryFolder.newFolder("reconcile_instances").toPath();
        store = new InstanceLifecycle.FileStore(dir);
        controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
    }

    @After
    public void tearDown() {
        if (pool != null) {
            pool.clear();
        }
    }

    private static final class FakeSession implements ExecutionSession {
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final java.io.PipedOutputStream pipeOut;
        private final java.io.PipedInputStream pipeIn;

        FakeSession() {
            try {
                this.pipeOut = new java.io.PipedOutputStream();
                this.pipeIn = new java.io.PipedInputStream(pipeOut);
                this.pipeOut.write("booting...\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                this.pipeOut.flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public OutputStream terminalInput() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream terminalOutput() {
            return pipeIn;
        }

        @Override
        public OutputStream controlInput() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream controlOutput() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void close() {
            closed.set(true);
            try {
                pipeOut.close();
                pipeIn.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void testSessionAlreadyActiveInPoolReturnsAttached() throws Exception {
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"),
                temporaryFolder.newFile("root.raw").toPath());

        FakeSession session = new FakeSession();
        pool.register(instance.id(), session);

        InstanceSessionReconciler reconciler = new InstanceSessionReconciler(
                pool, store, controller, inst -> null);

        InstanceSessionReconciler.ReconcileResult result =
                reconciler.ensureSessionAliveOrRecover(instance.id());

        assertEquals(InstanceSessionReconciler.Status.ATTACHED, result.status());
        assertNotNull(result.sessionHolder());
        assertEquals(instance.id(), result.sessionHolder().instanceId());
    }

    @Test
    public void missingOwnerRequiresColdRestoreEvenIfSocketProbeCouldSucceed() throws Exception {
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"),
                temporaryFolder.newFile("root-running.raw").toPath());

        // Put instance into RUNNING state
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());

        // Ensure pool has NO live session (simulating app killed / activity recreated)
        assertNull(pool.getHolder(instance.id()));

        FakeSession recoveredSession = new FakeSession();
        InstanceSessionReconciler.SessionRecoverer recoverer = inst -> recoveredSession;

        InstanceSessionReconciler reconciler = new InstanceSessionReconciler(
                pool, store, controller, recoverer);

        InstanceSessionReconciler.ReconcileResult result =
                reconciler.ensureSessionAliveOrRecover(instance.id());

        assertEquals(InstanceSessionReconciler.Status.CRASHED, result.status());
        assertNull(result.sessionHolder());
        assertEquals(InstanceLifecycle.State.INTERRUPTED, store.load(instance.id()).state());
        org.junit.Assert.assertFalse(pool.hasActiveSession(instance.id()));
        recoveredSession.close();
    }

    @Test
    public void testInstanceRunningInStoreAutoReattachFailsTransitionsToCrashed() throws Exception {
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                temporaryFolder.newFile("root-dead.raw").toPath());

        InstanceLaunchSequence.beforeLaunch(controller, instance);
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());

        // Recovery throws or returns null (simulating dead socket / kernel exit)
        InstanceSessionReconciler.SessionRecoverer failingRecoverer = inst -> {
            throw new java.net.ConnectException("Connection refused (vsock down)");
        };

        InstanceSessionReconciler reconciler = new InstanceSessionReconciler(
                pool, store, controller, failingRecoverer);

        InstanceSessionReconciler.ReconcileResult result =
                reconciler.ensureSessionAliveOrRecover(instance.id());

        assertEquals(InstanceSessionReconciler.Status.CRASHED, result.status());
        assertNull(result.sessionHolder());

        // Without a VM owner there is no observed exit code. Persist interruption,
        // retain all instance data, and let an explicit cold restore rebuild runtime.
        InstanceLifecycle.Instance loaded = store.load(instance.id());
        assertEquals(InstanceLifecycle.State.INTERRUPTED, loaded.state());
        assertNull(loaded.crashDiagnostic());
    }

    @Test
    public void testInstanceStoppedOrCreatedReturnsOffline() throws Exception {
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                temporaryFolder.newFile("root-stopped.raw").toPath());

        InstanceSessionReconciler reconciler = new InstanceSessionReconciler(
                pool, store, controller, inst -> null);

        InstanceSessionReconciler.ReconcileResult result =
                reconciler.ensureSessionAliveOrRecover(instance.id());

        assertEquals(InstanceSessionReconciler.Status.OFFLINE, result.status());
        assertEquals(InstanceLifecycle.State.CREATED, result.instance().state());
    }

    @Test
    public void terminalEofDoesNotReplaceTheOwnedRunningSessionOrWriteCrashMetadata() throws Exception {
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"),
                temporaryFolder.getRoot().toPath().resolve("never-opened.raw"));
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        FakeSession owner = new FakeSession();
        PersistentTerminalSessionPool.SessionHolder holder = pool.register(instance.id(), owner);
        java.util.concurrent.CountDownLatch eof = new java.util.concurrent.CountDownLatch(1);
        holder.setSessionEndListener(id -> eof.countDown());
        owner.pipeOut.close();
        assertTrue(eof.await(2, java.util.concurrent.TimeUnit.SECONDS));
        java.util.concurrent.atomic.AtomicInteger recoveryCalls = new java.util.concurrent.atomic.AtomicInteger();
        InstanceSessionReconciler reconciler = new InstanceSessionReconciler(
                pool, store, controller, inst -> { recoveryCalls.incrementAndGet(); return null; });

        InstanceSessionReconciler.ReconcileResult result = reconciler.ensureSessionAliveOrRecover(instance.id());

        assertEquals("Terminal EOF does not prove VM death", InstanceSessionReconciler.Status.ATTACHED, result.status());
        assertTrue("The supervised owner must be retained", result.sessionHolder() == holder);
        assertEquals(0, recoveryCalls.get());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
        assertNull(store.load(instance.id()).crashDiagnostic());
        assertTrue(!owner.closed.get());
    }

    @Test
    public void socketRecoveryCannotInventAReplacementForAMissingSupervisedOwner() throws Exception {
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"),
                temporaryFolder.getRoot().toPath().resolve("orphan-never-opened.raw"));
        InstanceLaunchSequence.beforeLaunch(controller, instance);
        java.util.concurrent.atomic.AtomicInteger probes = new java.util.concurrent.atomic.AtomicInteger();
        InstanceSessionReconciler reconciler = new InstanceSessionReconciler(
                pool, store, controller, inst -> { probes.incrementAndGet(); return new FakeSession(); });
        InstanceSessionReconciler.ReconcileResult result = reconciler.ensureSessionAliveOrRecover(instance.id());
        assertEquals(InstanceSessionReconciler.Status.CRASHED, result.status());
        assertEquals(InstanceLifecycle.State.INTERRUPTED, store.load(instance.id()).state());
        assertEquals(0, probes.get());
        assertNull(pool.getSession(instance.id()));
        assertEquals(instance.bridgeToken(), store.load(instance.id()).bridgeToken());
        assertEquals(instance.guestPassword(), store.load(instance.id()).guestPassword());
        assertEquals(instance.rootVolume(), store.load(instance.id()).rootVolume());
    }

    @Test
    public void testInstanceNotFoundReturnsNotFound() throws Exception {
        InstanceSessionReconciler reconciler = new InstanceSessionReconciler(
                pool, store, controller, inst -> null);

        InstanceSessionReconciler.ReconcileResult result =
                reconciler.ensureSessionAliveOrRecover("non-existent-uuid");

        assertEquals(InstanceSessionReconciler.Status.NOT_FOUND, result.status());
        assertNull(result.instance());
    }
}
