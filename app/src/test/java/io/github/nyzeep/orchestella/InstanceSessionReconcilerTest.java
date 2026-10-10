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
    public void testInstanceRunningInStoreAutoReattachSucceeds() throws Exception {
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

        assertEquals(InstanceSessionReconciler.Status.ATTACHED, result.status());
        assertNotNull(result.sessionHolder());
        assertTrue(pool.hasActiveSession(instance.id()));
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

        // Verify that the instance state in store transitioned to CRASHED with diagnostic
        InstanceLifecycle.Instance loaded = store.load(instance.id());
        assertEquals(InstanceLifecycle.State.CRASHED, loaded.state());
        assertNotNull(loaded.crashDiagnostic());
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
    public void testInstanceNotFoundReturnsNotFound() throws Exception {
        InstanceSessionReconciler reconciler = new InstanceSessionReconciler(
                pool, store, controller, inst -> null);

        InstanceSessionReconciler.ReconcileResult result =
                reconciler.ensureSessionAliveOrRecover("non-existent-uuid");

        assertEquals(InstanceSessionReconciler.Status.NOT_FOUND, result.status());
        assertNull(result.instance());
    }
}
