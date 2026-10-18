package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class QemuLaunchCleanupTest {

    @Test(timeout = 5_000)
    public void failedLaunchWithoutSessionStopsProcessAndGateway() {
        FakeProcess process = new FakeProcess();
        FakeGateway gateway = new FakeGateway(true);
        QemuInstanceLauncher.cleanupFailedLaunch(null, process, gateway);

        assertFalse("a failed launch must not leave QEMU alive", process.isAlive());
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
        volatile boolean alive = true;

        @Override public int waitFor() throws InterruptedException {
            exited.await();
            return 0;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }
        @Override public void destroy() {
            destroys.incrementAndGet();
            alive = false;
            exited.countDown();
        }
        @Override public void destroyForcibly() { destroy(); }
        @Override public boolean isAlive() { return alive; }
    }

    private static final class FakeGateway implements RuntimeGateway {
        final AtomicInteger stops = new AtomicInteger();
        volatile boolean alive;
        FakeGateway(boolean alive) { this.alive = alive; }
        @Override public boolean isAlive() { return alive; }
        @Override public boolean stop(long graceMs) {
            stops.incrementAndGet();
            alive = false;
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
