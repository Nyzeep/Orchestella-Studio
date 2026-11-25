package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.DisplayChannel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * G-03: the desktop launch negotiation state machine must behave exactly like the
 * pre-split activity — desktopLaunch → STATUS polling → applyDesktopEvidence →
 * displayMode — with the displayGeneration/isCurrentDisplay guard semantics intact.
 *
 * <p>Robolectric runner: the coordinator parses guest STATUS payloads with
 * {@code org.json.JSONObject}, which the plain JVM android.jar stub does not carry.
 * The control client itself is a real {@link BridgeClient} over scripted reply lines,
 * so result classification and the outbound request stream stay production-faithful.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class DesktopSessionCoordinatorTest {

    /** Canonical synthetic token (same shape discipline as QemuReadinessProbeTest). */
    private static final String TOKEN =
            "0123abcd" + "4567ef01" + "2345abcd" + "6789ef01"
                    + "0123abcd" + "4567ef01" + "2345abcd" + "6789ef01";

    private static final String GENERATION_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String GENERATION_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static final String INSTANCE_ID = "coordinator-test-inst";

    /**
     * Unique registry id per test: the session pool refuses a second owner while the
     * previous runtime "may still be alive", and a fake session never confirms a stop,
     * so each test claims its own id instead.
     */
    private final String instanceId = INSTANCE_ID + "-" + IDS.incrementAndGet();
    private static final java.util.concurrent.atomic.AtomicInteger IDS =
            new java.util.concurrent.atomic.AtomicInteger();

    // ---------------------------------------------------------------- fakes

    /** Records the guard's host-liveness inputs. */
    private static final class FakeHost implements DesktopSessionCoordinator.Host {
        boolean finishing;
        boolean destroyed;

        @Override public boolean isFinishing() {
            return finishing;
        }

        @Override public boolean isDestroyed() {
            return destroyed;
        }
    }

    /** ExecutionSession fake: only the display paths the coordinator touches are live. */
    private static final class FakeSession implements ExecutionSession {
        DisplayChannel channel;
        BridgeClient client;
        boolean reconnectReturnValue = true;
        final List<boolean[]> reconnectCalls = new ArrayList<>();

        @Override public int waitFor() {
            return 0;
        }

        @Override public OutputStream terminalInput() {
            return new ByteArrayOutputStream();
        }

        @Override public InputStream terminalOutput() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override public OutputStream controlInput() {
            return new ByteArrayOutputStream();
        }

        @Override public InputStream controlOutput() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override public void close() {
        }

        @Override public DisplayChannel displayChannel() {
            return channel;
        }

        @Override public BridgeClient bridgeClient() {
            return client;
        }

        @Override public boolean reconnectDisplay(boolean keyboard, boolean unicodeText, boolean pointer) {
            reconnectCalls.add(new boolean[] {keyboard, unicodeText, pointer});
            return reconnectReturnValue;
        }
    }

    /** Channel fake whose connection state the negotiation probes. */
    private static final class FakeChannel implements DisplayChannel {
        boolean connected;

        @Override public ConnectionState state() {
            return connected ? ConnectionState.CONNECTED : ConnectionState.DISCONNECTED;
        }

        @Override public void addStateListener(StateListener listener) {
        }

        @Override public void removeStateListener(StateListener listener) {
        }

        @Override public void registerFrameListener(FrameListener listener) {
        }

        @Override public void unregisterFrameListener(FrameListener listener) {
        }

        @Override public boolean sendInputEvent(io.github.nyzeep.orchestella.runtime.contract.InputEvent event) {
            return true;
        }

        @Override public void close() {
        }
    }

    /** Failure sink recording the (target, client, lifecycle, message) tuple verbatim. */
    private static final class RecordingFailureSink implements DesktopSessionCoordinator.NegotiationFailureSink {
        ExecutionSession target;
        DesktopControlClient client;
        long lifecycle = -1;
        String message;

        @Override public void onNegotiationFailed(ExecutionSession target, DesktopControlClient client,
                long lifecycle, String message) {
            this.target = target;
            this.client = client;
            this.lifecycle = lifecycle;
            this.message = message;
        }
    }

    /**
     * Direct executor for confirm dispatch; optionally rejects submissions to exercise
     * the shutdown race path.
     */
    private static final class DirectExecutor implements java.util.concurrent.ScheduledExecutorService {
        boolean rejectSubmissions;

        private void checkReject() {
            if (rejectSubmissions) throw new RejectedExecutionException("executor already shut down");
        }

        @Override public void execute(Runnable command) {
            checkReject();
            command.run();
        }

        @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay,
                TimeUnit unit) {
            checkReject();
            command.run();
            return null;
        }

        @Override public <V> java.util.concurrent.ScheduledFuture<V> schedule(
                java.util.concurrent.Callable<V> callable, long delay, TimeUnit unit) {
            checkReject();
            try {
                callable.call();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
            return null;
        }

        @Override public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException("not used by the coordinator tests");
        }

        @Override public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("not used by the coordinator tests");
        }

        @Override public void shutdown() {
        }

        @Override public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override public boolean isShutdown() {
            return false;
        }

        @Override public boolean isTerminated() {
            return false;
        }

        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override public java.util.concurrent.Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout,
                TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }

    // ---------------------------------------------------------------- builders

    /** Scripted reply client: each exchange consumes its reply line synchronously (no pump). */
    private static final class ScriptedClient {
        final BridgeClient client;
        private final ByteArrayOutputStream outbound = new ByteArrayOutputStream();

        ScriptedClient(String... replies) {
            StringBuilder script = new StringBuilder("ORCHESTELLA/READY 1\n");
            for (String reply : replies) {
                script.append(reply).append('\n');
            }
            this.client = new BridgeClient(
                    new ByteArrayInputStream(script.toString().getBytes(StandardCharsets.US_ASCII)),
                    outbound);
        }

        String requests() {
            return outbound.toString(StandardCharsets.US_ASCII);
        }
    }

    private static String evidencePayload(String generation, String state) {
        return "{\"state\":\"" + state + "\",\"generation\":\"" + generation
                + "\",\"capture_generation\":\"" + generation + "\",\"capture_width\":1920,\"capture_height\":1080,"
                + "\"input\":{\"keyboard\":true,\"unicodeText\":false,\"pointer\":true}}";
    }

    private FakeSession registeredSession() throws java.io.IOException {
        FakeSession session = new FakeSession();
        ExecutionSessionRegistry.register(instanceId, session);
        return session;
    }

    @After
    public void releaseRegistryEntry() {
        ExecutionSessionRegistry.unregister(instanceId);
    }

    private DesktopSessionCoordinator coordinator(FakeSession session, FakeHost host,
            RecordingFailureSink sink, java.util.concurrent.ScheduledExecutorService executor) {
        return new DesktopSessionCoordinator(session, instanceId, session.channel, 2560, 1440, 1.25, host,
                sink, executor);
    }

    // ---------------------------------------------------------------- negotiation

    @Test
    public void negotiationRunsLaunchStatusEvidenceThenDisplayMode() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        session.channel = channel;
        ScriptedClient client = new ScriptedClient(
                "ORCHESTELLA/OK display",
                "ORCHESTELLA/OK features " + evidencePayload(GENERATION_A, "awaiting-frame"),
                "ORCHESTELLA/OK display");
        session.client = client.client;
        assertTrue(client.client.handshake(TOKEN).accepted());

        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        coordinator.onForeground();
        coordinator.negotiateOnce(session, 1L);

        assertNull(sink.message);
        String requests = client.requests();
        assertTrue("desktopLaunch must precede STATUS polling",
                requests.contains("ORCHESTELLA/DISPLAY LAUNCH"));
        assertTrue(requests.contains("ORCHESTELLA/FEATURES 1 STATUS desktop-labwc"));
        assertTrue("displayMode uses DisplayViewportBudget without clamping 2560x1440 down to 1920x1080",
                requests.contains("ORCHESTELLA/DISPLAY MODE 2560 1440 1.25"));
        assertEquals(1, session.reconnectCalls.size());
        assertTrue(session.reconnectCalls.get(0)[0]);
        assertFalse(session.reconnectCalls.get(0)[1]);
        assertTrue(session.reconnectCalls.get(0)[2]);
    }

    @Test
    public void negotiationSkipsLaunchWhenChannelAlreadyConnected() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        channel.connected = true;
        session.channel = channel;
        ScriptedClient client = new ScriptedClient("ORCHESTELLA/OK display");
        session.client = client.client;
        assertTrue(client.client.handshake(TOKEN).accepted());

        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        coordinator.onForeground();
        coordinator.negotiateOnce(session, 1L);

        assertNull(sink.message);
        String requests = client.requests();
        assertFalse("connected channel must not re-launch the compositor",
                requests.contains("ORCHESTELLA/DISPLAY LAUNCH"));
        assertTrue(requests.contains("ORCHESTELLA/DISPLAY MODE 2560 1440 1.25"));
    }

    @Test
    public void negotiationPreservesPortraitAspectRatioAndFits2kPlusWithinBudget() throws Exception {
        FakeSession portraitSession = registeredSession();
        FakeChannel portraitChannel = new FakeChannel();
        portraitChannel.connected = true;
        portraitSession.channel = portraitChannel;
        ScriptedClient portraitClient = new ScriptedClient("ORCHESTELLA/OK display");
        portraitSession.client = portraitClient.client;
        assertTrue(portraitClient.client.handshake(TOKEN).accepted());

        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator portraitCoordinator = new DesktopSessionCoordinator(
                portraitSession, instanceId, portraitChannel, 1080, 2400, 2.0, host, sink, new DirectExecutor());
        portraitCoordinator.onForeground();
        portraitCoordinator.negotiateOnce(portraitSession, 1L);

        assertNull(sink.message);
        assertTrue("portrait 1080x2400 must not be squashed to 1080x1080",
                portraitClient.requests().contains("ORCHESTELLA/DISPLAY MODE 1080 2400 2.0"));

        FakeSession qhdSession = new FakeSession();
        String qhdId = instanceId + "-qhd";
        ExecutionSessionRegistry.register(qhdId, qhdSession);
        try {
            FakeChannel qhdChannel = new FakeChannel();
            qhdChannel.connected = true;
            qhdSession.channel = qhdChannel;
            ScriptedClient qhdClient = new ScriptedClient("ORCHESTELLA/OK display");
            qhdSession.client = qhdClient.client;
            assertTrue(qhdClient.client.handshake(TOKEN).accepted());

            DesktopSessionCoordinator qhdCoordinator = new DesktopSessionCoordinator(
                    qhdSession, qhdId, qhdChannel, 1440, 3120, 2.0, host, sink, new DirectExecutor());
            qhdCoordinator.onForeground();
            qhdCoordinator.negotiateOnce(qhdSession, 1L);

            assertNull(sink.message);
            assertTrue("1440x3120 must fit within 4_194_304 budget preserving aspect ratio",
                    qhdClient.requests().contains("ORCHESTELLA/DISPLAY MODE 1390 3014 2.0"));

            try (RfbDisplaySession rfbSession = new RfbDisplaySession(
                    () -> { throw new java.io.IOException("unused"); }, TOKEN, () -> qhdClient.client)) {
                assertFalse("raw 1440x3120 exceeds pixel budget and must be rejected",
                        rfbSession.setMode(1440, 3120, 2.0));
                assertTrue("budgeted 1390x3014 must be accepted by RfbDisplaySession",
                        rfbSession.setMode(1390, 3014, 2.0));
            }
        } finally {
            ExecutionSessionRegistry.unregister(qhdId);
        }
    }

    @Test
    public void negotiationFailureCarriesGuestReasonAndHumanizedCopy() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        session.channel = channel;
        ScriptedClient client = new ScriptedClient(
                "ORCHESTELLA/OK display",
                "ORCHESTELLA/OK features {\"state\":\"failed\",\"reason\":\"disk-full\"}");
        session.client = client.client;
        assertTrue(client.client.handshake(TOKEN).accepted());

        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        coordinator.onForeground();
        coordinator.negotiateOnce(session, 1L);

        assertEquals("桌面连接失败：disk-full。可返回终端查看日志。", sink.message);
        assertEquals(session, sink.target);
        assertEquals(1L, sink.lifecycle);
        assertFalse("no displayMode after a failed negotiation",
                client.requests().contains("ORCHESTELLA/DISPLAY MODE"));
    }

    @Test
    public void negotiationWithoutWaylandChannelFailsWithChannelCopy() throws Exception {
        FakeSession session = registeredSession();
        session.channel = null;
        ScriptedClient client = new ScriptedClient();
        session.client = client.client;
        assertTrue(client.client.handshake(TOKEN).accepted());

        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        coordinator.onForeground();
        coordinator.negotiateOnce(session, 1L);

        assertEquals("桌面连接失败：显示通道在当前实例不可用。可返回终端查看日志。", sink.message);
    }

    @Test
    public void negotiationAbortsWhenGuardDisarmed() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        session.channel = channel;
        ScriptedClient client = new ScriptedClient("ORCHESTELLA/OK display", "ORCHESTELLA/OK display");
        session.client = client.client;
        assertTrue(client.client.handshake(TOKEN).accepted());

        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        // No onForeground(): displayForeground stays false.
        coordinator.negotiateOnce(session, coordinator.generationForTest());

        assertNull(sink.message);
        assertFalse(client.requests().contains("ORCHESTELLA/DISPLAY LAUNCH"));
        assertFalse(client.requests().contains("ORCHESTELLA/DISPLAY MODE"));
        assertEquals(0, session.reconnectCalls.size());
    }

    @Test
    public void negotiationAbortsWhenStaleGeneration() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        session.channel = channel;
        ScriptedClient client = new ScriptedClient("ORCHESTELLA/OK display", "ORCHESTELLA/OK display");
        session.client = client.client;
        assertTrue(client.client.handshake(TOKEN).accepted());

        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        coordinator.onForeground();
        coordinator.onBackground(); // generation bumped, foreground disarmed
        coordinator.negotiateOnce(session, 1L); // stale lifecycle captured before onBackground

        assertNull(sink.message);
        assertFalse(client.requests().contains("ORCHESTELLA/DISPLAY MODE"));
    }

    @Test
    public void guardRejectsReplacedSessionClient() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        session.channel = channel;
        ScriptedClient original = new ScriptedClient();
        session.client = original.client;
        assertTrue(original.client.handshake(TOKEN).accepted());

        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        coordinator.onForeground();
        DesktopControlClient originalSeam = DesktopControlClient.of(original.client);
        assertTrue(coordinator.isCurrentDisplay(session, originalSeam, coordinator.generationForTest()));

        // A recovered control channel swaps in a fresh client mid-flight; in-flight
        // operations bound to the retired client must abort.
        ScriptedClient replacement = new ScriptedClient();
        assertTrue(replacement.client.handshake(TOKEN).accepted());
        session.client = replacement.client;
        assertFalse("retired client identity must fail the guard",
                coordinator.isCurrentDisplay(session, originalSeam, coordinator.generationForTest()));
        assertTrue(coordinator.isCurrentDisplay(session, DesktopControlClient.of(replacement.client),
                coordinator.generationForTest()));
    }

    @Test
    public void negotiationWithoutClientIsANoOp() throws Exception {
        FakeSession session = registeredSession();
        session.channel = new FakeChannel();
        session.client = null;
        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        coordinator.onForeground();
        coordinator.negotiateOnce(session, 1L);
        assertNull(sink.message);
    }

    @Test
    public void negotiationAbortsWhenHostIsDestroyed() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        session.channel = channel;
        ScriptedClient client = new ScriptedClient("ORCHESTELLA/OK display", "ORCHESTELLA/OK display");
        session.client = client.client;
        assertTrue(client.client.handshake(TOKEN).accepted());

        FakeHost host = new FakeHost();
        host.destroyed = true;
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        coordinator.onForeground();
        coordinator.negotiateOnce(session, 1L);

        assertNull(sink.message);
        assertFalse(client.requests().contains("ORCHESTELLA/DISPLAY LAUNCH"));
    }

    // ---------------------------------------------------------------- evidence polling

    @Test
    public void pollOnceAppliesFreshEvidenceAndReconnects() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        session.channel = channel;
        ScriptedClient client = new ScriptedClient(
                "ORCHESTELLA/OK features " + evidencePayload(GENERATION_A, "awaiting-frame"));
        session.client = client.client;
        assertTrue(client.client.handshake(TOKEN).accepted());

        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        coordinator.onForeground();
        coordinator.pollOnce();

        assertEquals(1, session.reconnectCalls.size());
        assertEquals(GENERATION_A, coordinator.observedGenerationForTest());
        assertEquals(GENERATION_A, coordinator.observedGenerationForTest());

        // Same evidence again: the gate sees an unchanged generation, no reconnect.
        ScriptedClient repeat = new ScriptedClient(
                "ORCHESTELLA/OK features " + evidencePayload(GENERATION_A, "awaiting-frame"));
        session.client = repeat.client;
        assertTrue(repeat.client.handshake(TOKEN).accepted());
        coordinator.pollOnce();
        assertEquals(1, session.reconnectCalls.size());

        // A new guest generation re-opens the gate and re-connects the display.
        ScriptedClient next = new ScriptedClient(
                "ORCHESTELLA/OK features " + evidencePayload(GENERATION_B, "ready"));
        session.client = next.client;
        assertTrue(next.client.handshake(TOKEN).accepted());
        coordinator.pollOnce();
        assertEquals(2, session.reconnectCalls.size());
    }

    @Test
    public void pollOnceSkipsUnreadyOrDisarmedClients() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        session.channel = channel;
        ScriptedClient client = new ScriptedClient(
                "ORCHESTELLA/OK features {\"state\":\"running\"}");
        session.client = client.client;
        // No handshake: the client is not ready.

        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        coordinator.onForeground();
        coordinator.pollOnce();
        assertFalse(client.requests().contains("ORCHESTELLA/FEATURES"));

        host.finishing = true;
        assertTrue(client.client.handshake(TOKEN).accepted());
        coordinator.pollOnce();
        assertFalse("a finishing host must not poll",
                client.requests().contains("ORCHESTELLA/FEATURES 1 STATUS"));
    }

    @Test
    public void pollOnceSurvivesRejectedAndNonAttachRounds() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        session.channel = channel;
        ScriptedClient rejected = new ScriptedClient("ORCHESTELLA/ERROR busy");
        session.client = rejected.client;
        assertTrue(rejected.client.handshake(TOKEN).accepted());
        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        coordinator.onForeground();
        coordinator.pollOnce(); // rejected STATUS: silent skip, no state reset
        assertEquals(0, session.reconnectCalls.size());
        assertNull(sink.message);

        ScriptedClient running = new ScriptedClient(
                "ORCHESTELLA/OK features {\"state\":\"running\"}");
        session.client = running.client;
        assertTrue(running.client.handshake(TOKEN).accepted());
        coordinator.pollOnce(); // neither awaiting-frame nor ready: gate invalidated, no reconnect
        assertEquals(0, session.reconnectCalls.size());
        assertNull(coordinator.observedGenerationForTest());
    }

    // ---------------------------------------------------------------- frame confirmation

    @Test
    public void confirmFrameRequiresFreshEvidence() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        session.channel = channel;
        ScriptedClient client = new ScriptedClient(
                "ORCHESTELLA/OK features " + evidencePayload(GENERATION_A, "awaiting-frame"),
                "ORCHESTELLA/OK features " + evidencePayload(GENERATION_A, "awaiting-frame"));
        session.client = client.client;
        assertTrue(client.client.handshake(TOKEN).accepted());
        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DirectExecutor executor = new DirectExecutor();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, executor);
        coordinator.onForeground();

        coordinator.confirmFrame(frameOf(1920, 1080)); // no evidence observed yet: begin() returns null
        assertFalse(client.requests().contains("CONFIRM-FRAME"));

        coordinator.pollOnce(); // observe generation A
        // The frame must postdate the observation: the gate stamps notBeforeNs at observe.
        coordinator.confirmFrame(frameOf(1920, 1080)); // now the ticket flows through to the bridge
        assertTrue(client.requests()
                .contains("ORCHESTELLA/FEATURES 1 CONFIRM-FRAME desktop-labwc " + GENERATION_A));
    }

    @Test
    public void confirmFrameRejectedWhenExecutorShutDown() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        session.channel = channel;
        ScriptedClient client = new ScriptedClient(
                "ORCHESTELLA/OK features " + evidencePayload(GENERATION_A, "awaiting-frame"));
        session.client = client.client;
        assertTrue(client.client.handshake(TOKEN).accepted());
        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DirectExecutor executor = new DirectExecutor();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, executor);
        coordinator.onForeground();
        coordinator.pollOnce();

        executor.rejectSubmissions = true; // mirrors displayEvidence.shutdown() on a live render
        coordinator.confirmFrame(frameOf(1920, 1080));
        assertFalse(client.requests().contains("CONFIRM-FRAME"));

        // The gate dropped the rejected ticket: a later, healthy round may try again.
        executor.rejectSubmissions = false;
        coordinator.confirmFrame(frameOf(1920, 1080));
        assertTrue(client.requests().contains("CONFIRM-FRAME"));
    }

    @Test
    public void confirmFrameAbortsWhenForegroundLost() throws Exception {
        FakeSession session = registeredSession();
        FakeChannel channel = new FakeChannel();
        session.channel = channel;
        ScriptedClient client = new ScriptedClient(
                "ORCHESTELLA/OK features " + evidencePayload(GENERATION_A, "awaiting-frame"));
        session.client = client.client;
        assertTrue(client.client.handshake(TOKEN).accepted());
        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        coordinator.onForeground();
        coordinator.pollOnce();
        coordinator.onBackground();
        coordinator.confirmFrame(frameOf(1920, 1080));
        assertFalse(client.requests().contains("CONFIRM-FRAME"));
    }

    // ---------------------------------------------------------------- lifecycle + headless

    @Test
    public void headlessJudgmentMatchesSessionChannelPresence() {
        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();

        DesktopSessionCoordinator withoutSession = new DesktopSessionCoordinator(null, "inst-1", null,
                1920, 1080, 1.0, host, sink, new DirectExecutor());
        assertTrue(withoutSession.isHeadlessInstance());

        FakeSession channelless = new FakeSession();
        DesktopSessionCoordinator noChannel = new DesktopSessionCoordinator(channelless, "inst-1", null,
                1920, 1080, 1.0, host, sink, new DirectExecutor());
        assertTrue(noChannel.isHeadlessInstance());

        FakeSession live = new FakeSession();
        live.channel = new FakeChannel();
        DesktopSessionCoordinator graphical = new DesktopSessionCoordinator(live, "inst-1", live.channel,
                1920, 1080, 1.0, host, sink, new DirectExecutor());
        assertFalse(graphical.isHeadlessInstance());
    }

    @Test
    public void foregroundFlipBumpsGenerationEachTime() {
        FakeSession session = new FakeSession();
        FakeHost host = new FakeHost();
        RecordingFailureSink sink = new RecordingFailureSink();
        DesktopSessionCoordinator coordinator = coordinator(session, host, sink, new DirectExecutor());
        long first = coordinator.generationForTest();
        coordinator.onForeground();
        long afterForeground = coordinator.generationForTest();
        coordinator.onBackground();
        coordinator.onForeground();
        assertTrue(afterForeground > first);
        assertTrue(coordinator.generationForTest() > afterForeground);
    }

    // ---------------------------------------------------------------- helpers

    private static DisplayChannel.FrameData frameOf(int width, int height) {
        int stride = width * 4;
        byte[] pixels = new byte[stride * height];
        // Two distinct colors so the presentation gate's uniform-color check passes.
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int offset = row * stride + column * 4;
                boolean alternate = ((row / 8) + (column / 8)) % 2 == 0;
                pixels[offset] = (byte) (alternate ? 20 : 200);
                pixels[offset + 1] = (byte) (alternate ? 40 : 180);
                pixels[offset + 2] = (byte) (alternate ? 60 : 160);
                pixels[offset + 3] = (byte) 255;
            }
        }
        return new DisplayChannel.FrameData(width, height, stride, DisplayChannel.FrameData.FORMAT_RGBA_8888,
                pixels, 1L, System.nanoTime());
    }
}
