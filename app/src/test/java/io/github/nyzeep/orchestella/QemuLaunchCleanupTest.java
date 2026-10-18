package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class QemuLaunchCleanupTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test(timeout = 8_000)
    public void failedLaunchWithoutSessionStopsProcessAndGatewaySocket() throws Exception {
        FakeProcess process = new FakeProcess(false);
        File socket = temporaryFolder.newFile("api.sock");
        FakeGateway gateway = new FakeGateway(true, socket);

        QemuInstanceLauncher.cleanupFailedLaunch(null, process, gateway);

        assertFalse("a failed launch must not leave QEMU alive", process.isAlive());
        assertEquals(1, gateway.stops.get());
        assertFalse("a failed launch must delete the gateway API socket", socket.exists());
    }

    @Test(timeout = 10_000)
    public void failedLaunchForcesAProcessThatIgnoresDestroy() {
        FakeProcess process = new FakeProcess(true);
        FakeGateway gateway = new FakeGateway(true, null);

        QemuInstanceLauncher.cleanupFailedLaunch(null, process, gateway);

        assertEquals(1, process.destroys.get());
        assertEquals("cleanup must escalate to destroyForcibly", 1, process.forces.get());
        assertFalse(process.isAlive());
        assertEquals(1, gateway.stops.get());
    }

    @Test(timeout = 5_000)
    public void failedLaunchWithSessionUsesTheSupervisor() {
        FakeOwnedSession session = new FakeOwnedSession();
        QemuInstanceLauncher.cleanupFailedLaunch(session, null, null);

        assertEquals("registration failure must retire the session-owned runtime",
                1, session.stops.get());
    }

    private static final class FakeProcess implements QemuProcessLauncher.ProcessHandle {
        final CountDownLatch exited = new CountDownLatch(1);
        final AtomicInteger destroys = new AtomicInteger();
        final AtomicInteger forces = new AtomicInteger();
        final boolean ignoreDestroy;
        volatile boolean alive = true;

        FakeProcess(boolean ignoreDestroy) { this.ignoreDestroy = ignoreDestroy; }

        @Override public int waitFor() throws InterruptedException {
            exited.await();
            return 0;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }
        @Override public void destroy() {
            destroys.incrementAndGet();
            if (!ignoreDestroy) die();
        }
        @Override public void destroyForcibly() {
            forces.incrementAndGet();
            die();
        }
        @Override public boolean isAlive() { return alive; }
        private void die() { alive = false; exited.countDown(); }
    }

    private static final class FakeGateway implements RuntimeGateway {
        final AtomicInteger stops = new AtomicInteger();
        final File socket;
        volatile boolean alive;
        FakeGateway(boolean alive, File socket) { this.alive = alive; this.socket = socket; }
        @Override public boolean isAlive() { return alive; }
        @Override public boolean stop(long graceMs) {
            stops.incrementAndGet();
            alive = false;
            if (socket != null) socket.delete();
            return true;
        }
    }

    private static final class FakeOwnedSession implements RuntimeOwnedSession {
        final AtomicInteger stops = new AtomicInteger();
        @Override public int waitFor() { return 0; }
        @Override public OutputStream terminalInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream terminalOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream controlInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream controlOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public void close() { }
        @Override public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            stops.incrementAndGet();
            return RuntimeProcessSupervisor.StopResult.CONFIRMED;
        }
        @Override public boolean isRuntimeAlive() { return false; }
    }
}
