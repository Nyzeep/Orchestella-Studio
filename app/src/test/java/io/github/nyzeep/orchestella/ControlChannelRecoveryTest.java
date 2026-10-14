package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Pure JVM cache/lifecycle tests; no Android socket or VM is opened. */
public final class ControlChannelRecoveryTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final String TOKEN = "a".repeat(64);

    @Test
    public void unsupportedSessionRecoveryHasNoSideEffects() {
        CountingSession session = new CountingSession();
        assertFalse(session.recoverControlChannel());
        assertEquals(0, session.calls);
    }

    @Test
    public void avfDoesNotPretendItsCachedControlChannelCanReopen() throws Exception {
        AvfInstanceLauncher.AvfSession session = new AvfInstanceLauncher.AvfSession(null);
        BridgeClient failed = failedClient();
        setField(session, "bridgeClient", failed);
        assertTrue(session.markBridgeServing());
        assertFalse(session.recoverControlChannel());
        assertSame(failed, session.bridgeClient());
        assertFalse(session.markBridgeServing());
    }

    @Test
    public void failedLiveClientIsRetiredWithoutStoppingTerminalOrVm() throws Exception {
        String id = nextId();
        FakeHandle handle = new FakeHandle();
        QemuInstanceLauncher.Session session = session(id, handle);
        assertTrue(session.markBridgeServing());
        BridgeClient failed = failedClient();
        setField(session, "bridgeClient", failed);
        assertSame(failed, session.bridgeClient());
        withLiveHolder(id, session, holder -> {
            assertTrue(session.recoverControlChannel());
            assertNull(getField(session, "bridgeClient"));
            assertTrue(session.markBridgeServing());
            assertFalse(session.markBridgeServing());
            assertFalse(session.recoverControlChannel());
            assertTrue(holder.isAlive());
            assertSame(session, holder.rawSession());
            assertEquals(0, handle.destroyed);
        });
    }

    @Test
    public void healthyAndHandshakingClientsKeepTheirCacheAndServingOwner() throws Exception {
        String id = nextId();
        QemuInstanceLauncher.Session session = session(id, new FakeHandle());
        BridgeClient healthy = new BridgeClient(
                new ByteArrayInputStream("ORCHESTELLA/READY 1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                new ByteArrayOutputStream());
        assertTrue(healthy.handshake(TOKEN).accepted());
        assertTrue(session.markBridgeServing());
        setField(session, "bridgeClient", healthy);
        withLiveHolder(id, session, holder -> {
            assertFalse(session.recoverControlChannel());
            assertSame(healthy, session.bridgeClient());
            assertFalse(session.markBridgeServing());
            HeldTransport transport = new HeldTransport();
            BridgeClient handshaking = new BridgeClient(transport, new ByteArrayOutputStream());
            handshaking.startResponsePump(null, 1000L);
            Thread hello = new Thread(() -> {
                try { handshaking.handshake(TOKEN); } catch (IOException ignored) { }
            });
            hello.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (!"handshaking".equals(handshaking.readinessState()) && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertEquals("handshaking", handshaking.readinessState());
                setField(session, "bridgeClient", handshaking);
                assertFalse(session.recoverControlChannel());
                assertSame(handshaking, session.bridgeClient());
            } finally {
                transport.release.countDown();
                hello.join(2000L);
                assertFalse(hello.isAlive());
                assertTrue(handshaking.awaitDisconnectedReader(2000L));
            }
        });
    }

    @Test
    public void missingOrForeignTerminalHolderRejectsRecovery() throws Exception {
        String id = nextId();
        FakeHandle handle = new FakeHandle();
        QemuInstanceLauncher.Session session = session(id, handle);
        BridgeClient failed = failedClient();
        setField(session, "bridgeClient", failed);
        try {
            assertFalse(session.recoverControlChannel());
            PersistentTerminalSessionPool.getInstance().register(id, new CountingSession());
            assertFalse(session.recoverControlChannel());
            assertSame(failed, session.bridgeClient());
            assertEquals(0, handle.destroyed);
        } finally {
            PersistentTerminalSessionPool.getInstance().unregister(id);
            session.close();
        }
    }

    @Test
    public void legacySessionWithUnknownIdentityRejectsRecovery() throws Exception {
        QemuInstanceLauncher.Session session = session(null, new FakeHandle());
        BridgeClient failed = failedClient();
        setField(session, "bridgeClient", failed);
        try {
            assertFalse(session.recoverControlChannel());
            assertSame(failed, session.bridgeClient());
        } finally {
            session.close();
        }
    }

    @Test(timeout = 9000L)
    public void activeFailedReaderCannotBeReplacedAfterBoundedWait() throws Exception {
        String id = nextId();
        FakeHandle handle = new FakeHandle();
        QemuInstanceLauncher.Session session = session(id, handle);
        HeldTransport transport = new HeldTransport();
        BridgeClient failed = failedClient(transport);
        setField(session, "bridgeClient", failed);
        try {
            withLiveHolder(id, session, holder -> {
                assertFalse(session.recoverControlChannel());
                assertSame(failed, session.bridgeClient());
                assertFalse(session.markBridgeServing());
                assertTrue(holder.isAlive());
                assertEquals(0, handle.destroyed);
            });
        } finally {
            transport.release.countDown();
            assertTrue(failed.awaitDisconnectedReader(2000L));
        }
    }

    @Test
    public void interruptedReaderWaitPreservesCacheAndInterrupt() throws Exception {
        String id = nextId();
        QemuInstanceLauncher.Session session = session(id, new FakeHandle());
        HeldTransport transport = new HeldTransport();
        BridgeClient failed = failedClient(transport);
        setField(session, "bridgeClient", failed);
        try {
            withLiveHolder(id, session, holder -> {
                Thread.currentThread().interrupt();
                try {
                    assertFalse(session.recoverControlChannel());
                    assertTrue(Thread.currentThread().isInterrupted());
                    assertSame(failed, session.bridgeClient());
                } finally {
                    Thread.interrupted();
                }
            });
        } finally {
            transport.release.countDown();
            assertTrue(failed.awaitDisconnectedReader(2000L));
        }
    }

    @Test
    public void closedSessionCannotRecoverOrClaimServing() throws Exception {
        String id = nextId();
        QemuInstanceLauncher.Session session = session(id, new FakeHandle());
        BridgeClient failed = failedClient();
        setField(session, "bridgeClient", failed);
        withLiveHolder(id, session, holder -> {
            session.close();
            assertFalse(session.recoverControlChannel());
            assertFalse(session.markBridgeServing());
            assertNull(session.bridgeClient());
            assertSame(failed, getField(session, "bridgeClient"));
        });
    }

    private static String nextId() {
        return "control-recovery-test-" + IDS.incrementAndGet();
    }

    private static QemuInstanceLauncher.Session session(String id, FakeHandle handle) {
        return new QemuInstanceLauncher.Session("test-control", "test-terminal", null, handle, id);
    }

    private static BridgeClient failedClient() throws Exception {
        BridgeClient client = new BridgeClient(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        client.startResponsePump(null, 10L);
        try { client.awaitResponsePump(); } catch (IOException expected) { }
        assertEquals("failed", client.readinessState());
        return client;
    }

    private static BridgeClient failedClient(HeldTransport transport) throws Exception {
        BridgeClient client = new BridgeClient(transport, new ByteArrayOutputStream());
        client.startResponsePump(null, 10L);
        assertTrue(transport.entered.await(1L, TimeUnit.SECONDS));
        try {
            client.handshake(TOKEN);
            throw new AssertionError("Silent transport must fail the bounded handshake");
        } catch (IOException expected) {
            assertEquals("failed", client.readinessState());
        }
        return client;
    }

    private interface HolderCheck {
        void run(PersistentTerminalSessionPool.SessionHolder holder) throws Exception;
    }

    private static void withLiveHolder(String id, QemuInstanceLauncher.Session session, HolderCheck check)
            throws Exception {
        // Hold the terminal's existing lock so its pump remains live without
        // entering Android socket code. Recovery must not need this lock.
        synchronized (getField(session, "terminalLock")) {
            PersistentTerminalSessionPool pool = PersistentTerminalSessionPool.getInstance();
            PersistentTerminalSessionPool.SessionHolder holder = pool.register(id, session);
            try {
                assertTrue(holder.isAlive());
                check.run(holder);
            } finally {
                pool.unregister(id);
            }
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** Simulates a transport whose closed reader has not exited yet. */
    private static final class HeldTransport extends InputStream {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override public int read() throws IOException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Synthetic interrupted read", interrupted);
            }
            throw new IOException("Synthetic disconnected transport");
        }

        @Override public void close() { }
    }

    private static final class FakeHandle implements QemuProcessLauncher.ProcessHandle {
        int destroyed;
        @Override public int waitFor() { return 0; }
        @Override public void destroy() { destroyed++; }
    }

    private static final class CountingSession implements ExecutionSession {
        int calls;
        @Override public int waitFor() { calls++; return 0; }
        @Override public OutputStream terminalInput() { calls++; return new ByteArrayOutputStream(); }
        @Override public InputStream terminalOutput() { calls++; return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream controlInput() { calls++; return new ByteArrayOutputStream(); }
        @Override public InputStream controlOutput() { calls++; return new ByteArrayInputStream(new byte[0]); }
        @Override public void close() { calls++; }
        @Override public void terminate() { calls++; }
    }
}
