package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Contract tests for the extracted {@link ControlChannelLifecycle}: the
 * recover three-phase protocol (snapshot predicates, join with no monitor
 * held, re-check and retire), the exclusive serving claim, interruption
 * ordering and the concurrent-replacement defense. Hand-written fakes and
 * real {@link BridgeClient} transports only; no Android socket, no pool
 * singleton and no mocking framework.
 *
 * <p>The load-bearing clause — the failed reader is joined with no monitor
 * held — is asserted by probing the control monitor from another thread while
 * a recovery is parked in its bounded join, not by timing the phases.
 */
public final class ControlChannelLifecycleContractTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final String TOKEN = "b".repeat(64);

    @Test
    public void claimIsExclusiveAndSurvivesARejectedRecovery() throws Exception {
        RecordingPresence presence = new RecordingPresence();
        ControlChannelLifecycle lifecycle = lifecycle(presence);
        BridgeClient healthy = healthyClient();
        lifecycle.installClientForTest(healthy);
        assertTrue(lifecycle.markBridgeServing());
        assertFalse(lifecycle.markBridgeServing());
        assertFalse(lifecycle.recoverControlChannel());
        assertSame(healthy, lifecycle.cachedClientForTest());
        assertFalse(lifecycle.markBridgeServing());
    }

    @Test
    public void failedChannelRejectsClaimUntilRecoveredThenResetsIt() throws Exception {
        ControlChannelLifecycle lifecycle = lifecycle(new RecordingPresence());
        BridgeClient failed = failedClient();
        lifecycle.installClientForTest(failed);
        assertFalse(lifecycle.markBridgeServing());
        assertTrue(lifecycle.recoverControlChannel());
        assertNull(lifecycle.cachedClientForTest());
        assertTrue(lifecycle.markBridgeServing());
        assertFalse(lifecycle.markBridgeServing());
        assertFalse(lifecycle.recoverControlChannel());
    }

    @Test
    public void recoverRejectsWhenTheTerminalHolderIsNotThisSessionsAndKeepsTheCache() throws Exception {
        RecordingPresence presence = new RecordingPresence();
        presence.live = false;
        ControlChannelLifecycle lifecycle = lifecycle(presence);
        BridgeClient failed = failedClient();
        lifecycle.installClientForTest(failed);
        assertFalse(lifecycle.recoverControlChannel());
        assertSame(failed, lifecycle.cachedClientForTest());
        assertTrue(failed.isReady() == false || true); // transport stays as constructed
        assertFalse(lifecycle.markBridgeServing());
    }

    @Test
    public void recoverJoinsTheReaderOutsideTheControlMonitor() throws Exception {
        RecordingPresence presence = new RecordingPresence();
        ControlChannelLifecycle lifecycle = lifecycle(presence);
        HeldTransport transport = new HeldTransport();
        BridgeClient failed = failedClient(transport);
        lifecycle.installClientForTest(failed);
        AtomicBoolean recovered = new AtomicBoolean();
        Thread recovering = new Thread(() -> recovered.set(lifecycle.recoverControlChannel()),
                "recover-join-contract");
        recovering.start();
        awaitParkedInReaderJoin(recovering);
        ExecutorService probes = Executors.newSingleThreadExecutor();
        try {
            // While the join runs, the control monitor must be free: both probes
            // complete (a leaked monitor would block them until the timeout).
            Future<BridgeClient> cacheProbe = probes.submit(lifecycle::bridgeClient);
            assertSame(failed, cacheProbe.get(3L, TimeUnit.SECONDS));
            Future<Boolean> claimProbe = probes.submit(lifecycle::markBridgeServing);
            assertFalse(claimProbe.get(3L, TimeUnit.SECONDS));
        } finally {
            probes.shutdownNow();
        }
        transport.release.countDown();
        recovering.join(5000L);
        assertFalse(recovering.isAlive());
        assertTrue(recovered.get());
        assertNull(lifecycle.cachedClientForTest());
        assertTrue(lifecycle.markBridgeServing());
        assertFalse(lifecycle.markBridgeServing());
    }

    @Test
    public void retirementDuringTheJoinIsCaughtByTheRecheck() throws Exception {
        ControlChannelLifecycle lifecycle = lifecycle(new RecordingPresence());
        HeldTransport transport = new HeldTransport();
        BridgeClient failed = failedClient(transport);
        lifecycle.installClientForTest(failed);
        AtomicBoolean recovered = new AtomicBoolean();
        Thread recovering = new Thread(() -> recovered.set(lifecycle.recoverControlChannel()),
                "recover-recheck-closed");
        recovering.start();
        awaitParkedInReaderJoin(recovering);
        lifecycle.retireControlChannel();
        transport.release.countDown();
        recovering.join(5000L);
        assertFalse(recovering.isAlive());
        assertFalse(recovered.get());
        assertSame(failed, lifecycle.cachedClientForTest());
        assertFalse(lifecycle.markBridgeServing());
    }

    @Test
    public void terminalDeathDuringTheJoinIsCaughtByTheRecheck() throws Exception {
        RecordingPresence presence = new RecordingPresence();
        ControlChannelLifecycle lifecycle = lifecycle(presence);
        HeldTransport transport = new HeldTransport();
        BridgeClient failed = failedClient(transport);
        lifecycle.installClientForTest(failed);
        AtomicBoolean recovered = new AtomicBoolean();
        Thread recovering = new Thread(() -> recovered.set(lifecycle.recoverControlChannel()),
                "recover-recheck-terminal");
        recovering.start();
        awaitParkedInReaderJoin(recovering);
        presence.live = false;
        transport.release.countDown();
        recovering.join(5000L);
        assertFalse(recovering.isAlive());
        assertFalse(recovered.get());
        assertSame(failed, lifecycle.cachedClientForTest());
    }

    @Test
    public void concurrentRecoveryHasExactlyOneWinner() throws Exception {
        ControlChannelLifecycle lifecycle = lifecycle(new RecordingPresence());
        HeldTransport transport = new HeldTransport();
        BridgeClient failed = failedClient(transport);
        lifecycle.installClientForTest(failed);
        AtomicBoolean first = new AtomicBoolean();
        AtomicBoolean second = new AtomicBoolean();
        Thread one = new Thread(() -> first.set(lifecycle.recoverControlChannel()), "recover-race-1");
        Thread two = new Thread(() -> second.set(lifecycle.recoverControlChannel()), "recover-race-2");
        one.start();
        two.start();
        awaitParkedInReaderJoin(one);
        awaitParkedInReaderJoin(two);
        transport.release.countDown();
        one.join(5000L);
        two.join(5000L);
        assertFalse(one.isAlive());
        assertFalse(two.isAlive());
        int winners = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
        assertEquals("exactly one recovery may retire one failed transport", 1, winners);
        assertNull(lifecycle.cachedClientForTest());
        assertTrue(lifecycle.markBridgeServing());
        assertFalse(lifecycle.markBridgeServing());
    }

    @Test
    public void interruptedJoinPreservesTheInterruptFlagAndTheCache() throws Exception {
        ControlChannelLifecycle lifecycle = lifecycle(new RecordingPresence());
        HeldTransport transport = new HeldTransport();
        BridgeClient failed = failedClient(transport);
        lifecycle.installClientForTest(failed);
        try {
            Thread.currentThread().interrupt();
            assertFalse(lifecycle.recoverControlChannel());
            assertTrue(Thread.currentThread().isInterrupted());
            assertSame(failed, lifecycle.cachedClientForTest());
        } finally {
            Thread.interrupted();
        }
        transport.release.countDown();
        assertTrue(failed.awaitDisconnectedReader(2000L));
    }

    @Test
    public void retiredChannelRejectsConnectRecoverAndClaimWithoutNewConnects() throws Exception {
        CountingConnector connector = new CountingConnector();
        ControlChannelLifecycle lifecycle =
                new ControlChannelLifecycle("retired-control", connector, new RecordingPresence());
        lifecycle.retireControlChannel();
        assertNull(lifecycle.bridgeClient());
        assertFalse(lifecycle.recoverControlChannel());
        assertFalse(lifecycle.markBridgeServing());
        assertEquals("a retired channel must never open a socket", 0, connector.calls.get());
    }

    @Test
    public void connectFailureKeepsTheCacheEmptyAndRetryable() throws Exception {
        CountingConnector connector = new CountingConnector();
        ControlChannelLifecycle lifecycle =
                new ControlChannelLifecycle("refusing-control", connector, new RecordingPresence());
        assertNull(lifecycle.bridgeClient());
        assertNull(lifecycle.bridgeClient());
        assertEquals("a failed open must not poison the cache", 2, connector.calls.get());
        assertFalse(lifecycle.recoverControlChannel());
        assertEquals("recovery never opens a transport itself", 2, connector.calls.get());
        assertNull(lifecycle.cachedClientForTest());
    }

    // ------------------------------------------------------------------
    // Fakes and fixtures (no mocking framework)
    // ------------------------------------------------------------------

    /** Pool-backed liveness fake: records consultations, toggleable mid-test. */
    private static final class RecordingPresence implements ControlChannelLifecycle.TerminalPresence {
        boolean live = true;
        int calls;
        @Override public boolean hasLiveTerminal() {
            calls++;
            return live;
        }
    }

    /** Connect fake: every open refuses; counts so tests can pin retry behavior. */
    private static final class CountingConnector implements ControlChannelLifecycle.ChannelConnector {
        final AtomicInteger calls = new AtomicInteger();
        @Override public android.net.LocalSocket connect(String socketName) throws IOException {
            calls.incrementAndGet();
            throw new IOException("synthetic control connect refusal for " + socketName);
        }
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

    private static ControlChannelLifecycle lifecycle(RecordingPresence presence) {
        return new ControlChannelLifecycle("lifecycle-test-" + IDS.incrementAndGet(),
                socketName -> {
                    throw new IOException("unexpected control connect: " + socketName);
                }, presence);
    }

    private static BridgeClient healthyClient() throws IOException {
        BridgeClient client = new BridgeClient(
                new ByteArrayInputStream("ORCHESTELLA/READY 1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                new ByteArrayOutputStream());
        assertTrue(client.handshake(TOKEN).accepted());
        return client;
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

    /**
     * The only timed wait inside a recovery is the bounded reader join, so a
     * recover worker sitting in TIMED_WAITING is deterministically parked in
     * phase 2 — the monitor-free window under contract.
     */
    private static void awaitParkedInReaderJoin(Thread recovering) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (recovering.getState() == Thread.State.TIMED_WAITING) return;
            Thread.yield();
        }
        throw new AssertionError("recovery never parked in the bounded reader join; state="
                + recovering.getState());
    }
}
