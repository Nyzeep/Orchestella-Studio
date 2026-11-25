package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.nyzeep.orchestella.audio.LocalSocketAudioSource;
import io.github.nyzeep.orchestella.audio.StreamConnection;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;

/**
 * G-05 JVM closure test: engine-side host listener ({@link AudioHostRelay}) to source
 * supply ({@link LocalSocketAudioSource#attach(StreamConnection)}) over real pipes.
 * The guest transport is simulated by pipe peers that exchange the token handshake
 * exactly like the real audio relay and then stream synthetic PCM.
 *
 * <p>This proves the host-side stream plumbing only. The real guest relay, chardev
 * socket and PCM playback remain unverified on devices (see G-05 notes).
 */
public final class AudioHostRelayTest {

    private static final String TOKEN = "0123456789abcdef".repeat(4);
    private static final byte[] PCM_A = new byte[256];
    private static final byte[] PCM_B = {(byte) 0xAA, 0x55, (byte) 0xAA, 0x55};

    static {
        for (int i = 0; i < PCM_A.length; i++) {
            PCM_A[i] = (byte) (i % 251);
        }
    }

    /** Event recorder with count-based polling await. */
    private static final class RecordingSink implements AudioHostRelay.EventSink {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(String event, String detail) {
            events.add(event + "|" + detail);
        }

        boolean awaitEvent(String name, int minimumCount, long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                if (count(name) >= minimumCount) {
                    return true;
                }
                Thread.sleep(20);
            }
            return count(name) >= minimumCount;
        }

        int count(String name) {
            int total = 0;
            for (String event : events) {
                if (event.startsWith(name + "|")) {
                    total++;
                }
            }
            return total;
        }
    }

    /**
     * Blocking consumer acceptor fake: {@link #offer} plays the role of a source
     * client connecting to the engine listener.
     */
    private static final class FakeAcceptor implements AudioHostRelay.ConsumerAcceptor {
        private final LinkedBlockingQueue<StreamConnection> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean open = new AtomicBoolean(true);

        void offer(StreamConnection link) {
            queue.offer(link);
        }

        @Override
        public StreamConnection accept() throws IOException {
            while (open.get()) {
                try {
                    StreamConnection link = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (link != null) {
                        return link;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("acceptor interrupted");
                }
            }
            throw new IOException("acceptor closed");
        }

        @Override
        public void close() {
            open.set(false);
        }
    }

    /** One fresh guest-transport pipe pair per relay connect attempt (like real connections). */
    private static final class TransportPipes {
        final PipedInputStream relayInput;
        final PipedOutputStream guestWrites;
        final PipedInputStream peerReads;
        final PipedOutputStream relayOutput;

        TransportPipes() throws IOException {
            this.relayInput = new PipedInputStream(8192);
            this.guestWrites = new PipedOutputStream(relayInput);
            this.relayOutput = new PipedOutputStream();
            this.peerReads = new PipedInputStream(relayOutput, 8192);
        }
    }

    /**
     * Reader for the relay's handshake preamble (the host -> guest direction over the
     * newest pipe pair).
     */
    private static final class PreamblePeer extends Thread {
        private final AtomicReference<TransportPipes> pipesRef;
        final StringBuilder line = new StringBuilder();
        final CountDownLatch gotLine = new CountDownLatch(1);

        PreamblePeer(AtomicReference<TransportPipes> pipesRef) {
            this.pipesRef = pipesRef;
            setDaemon(true);
            start();
        }

        @Override
        public void run() {
            try {
                TransportPipes pipes = currentPipes();
                int datum;
                while ((datum = pipes.peerReads.read()) >= 0) {
                    line.append((char) datum);
                    if (datum == '\n') {
                        break;
                    }
                }
            } catch (IOException | InterruptedException ignored) {
                // The peer ends with the pipe or the harness deadline.
            } finally {
                gotLine.countDown();
            }
        }

        private TransportPipes currentPipes() throws InterruptedException, IOException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                TransportPipes pipes = pipesRef.get();
                if (pipes != null) {
                    return pipes;
                }
                Thread.sleep(10);
            }
            throw new IOException("transport pipes never opened");
        }
    }

    /**
     * A consumer link the relay writes PCM into. The relay's write side is
     * {@link #outputStream()}; the consumer (attached source) reads the same bytes from
     * {@link #inputStream()} — one duplex pipe, exactly like a real socket pair.
     */
    private static final class PipeConnection implements StreamConnection {
        private final PipedInputStream input;
        private final PipedOutputStream output;

        PipeConnection() throws IOException {
            this.output = new PipedOutputStream();
            this.input = new PipedInputStream(output, 8192);
        }

        PipedInputStream readEnd() {
            return input;
        }

        @Override
        public InputStream inputStream() {
            return input;
        }

        @Override
        public OutputStream outputStream() {
            return output;
        }

        @Override
        public void close() throws IOException {
            input.close();
            output.close();
        }
    }

    private static void assertEqualsBytes(byte[] expected, InputStream actual, String label) throws IOException {
        byte[] read = new byte[expected.length];
        int offset = 0;
        while (offset < read.length) {
            int count = actual.read(read, offset, read.length - offset);
            assertTrue(label + ": stream ended after " + offset + " bytes", count > 0);
            offset += count;
        }
        for (int i = 0; i < expected.length; i++) {
            assertEquals(label + ": byte " + i, expected[i], read[i]);
        }
    }

    /** Shared harness: fresh-per-attempt transport pipes plus a started relay. */
    private static final class RelayHarness {
        final RecordingSink sink = new RecordingSink();
        final FakeAcceptor acceptor = new FakeAcceptor();
        final AtomicReference<TransportPipes> pipesRef = new AtomicReference<>();
        final AudioHostRelay relay;

        RelayHarness(String token) {
            this.relay = AudioHostRelay.forTesting(acceptor, () -> {
                TransportPipes pipes = new TransportPipes();
                pipesRef.set(pipes);
                return new StreamConnection() {
                    @Override public InputStream inputStream() { return pipes.relayInput; }
                    @Override public OutputStream outputStream() { return pipes.relayOutput; }
                    @Override public void close() throws IOException {
                        pipes.relayInput.close();
                        pipes.relayOutput.close();
                    }
                };
            }, token, sink);
            relay.start();
        }

        /** Awaits the relay's preamble on the live pipe pair, answers READY, awaits transport-ready. */
        void completeHandshake() throws Exception {
            PreamblePeer peer = new PreamblePeer(pipesRef);
            assertTrue("relay must send the audio preamble", peer.gotLine.await(5, TimeUnit.SECONDS));
            assertTrue("preamble must carry the token: " + peer.line,
                    peer.line.toString().startsWith("ORCHESTELLA/AUDIO 1 " + TOKEN));
            TransportPipes pipes = pipesRef.get();
            assertNotNull("handshake pipes must exist", pipes);
            pipes.guestWrites.write("ORCHESTELLA/AUDIO READY 1\n".getBytes("US-ASCII"));
            pipes.guestWrites.flush();
            assertTrue("relay must reach transport-ready",
                    sink.awaitEvent("transport-ready", 1, 5_000));
        }

        void writeGuest(byte[] data) throws IOException {
            TransportPipes pipes = pipesRef.get();
            assertNotNull("guest write side must exist", pipes);
            pipes.guestWrites.write(data);
            pipes.guestWrites.flush();
        }
    }

    @Test(timeout = 20_000)
    public void hostListenerSuppliesAnAttachedSourceAcrossPipes() throws Exception {
        RelayHarness harness = new RelayHarness(TOKEN);
        harness.completeHandshake();

        PipeConnection link = new PipeConnection();
        harness.acceptor.offer(link);
        assertTrue("the engine listener must accept the consumer link",
                harness.sink.awaitEvent("consumer-attached", 1, 5_000));

        // The closure under test: engine-accepted link feeds the audio source attach path.
        LocalSocketAudioSource source = new LocalSocketAudioSource();
        source.attach(link);
        InputStream pcm = source.openStream();
        assertTrue(source.isConnected());

        harness.writeGuest(PCM_A);
        assertEqualsBytes(PCM_A, pcm, "guest PCM must reach the attached source");

        harness.writeGuest(PCM_B);
        assertEqualsBytes(PCM_B, pcm, "a second guest burst must flow through the same link");

        source.close();
        harness.relay.close();
        assertEquals(AudioHostRelay.State.CLOSED, harness.relay.state());
    }

    @Test(timeout = 20_000)
    public void pcmWithoutConsumerIsDrainedAndTheNextConsumerTakesOver() throws Exception {
        RelayHarness harness = new RelayHarness(TOKEN);
        harness.completeHandshake();

        // PCM arrives while no consumer exists: drained (never stalled), nothing delivered.
        harness.writeGuest(PCM_A);
        Thread.sleep(300);
        assertEquals("no consumer link may exist before the offer",
                0, harness.sink.count("consumer-attached"));

        PipeConnection link = new PipeConnection();
        harness.acceptor.offer(link);
        assertTrue(harness.sink.awaitEvent("consumer-attached", 1, 5_000));

        LocalSocketAudioSource source = new LocalSocketAudioSource();
        source.attach(link);
        InputStream pcm = source.openStream();

        harness.writeGuest(PCM_B);
        assertEqualsBytes(PCM_B, pcm, "post-attach PCM is delivered, pre-attach PCM was drained");

        source.close();
        harness.relay.close();
    }

    @Test(timeout = 20_000)
    public void deadConsumerLinkIsReplacedByTheNextAcceptedOne() throws Exception {
        RelayHarness harness = new RelayHarness(TOKEN);
        harness.completeHandshake();

        PipeConnection first = new PipeConnection();
        harness.acceptor.offer(first);
        assertTrue(harness.sink.awaitEvent("consumer-attached", 1, 5_000));

        harness.writeGuest(PCM_A);
        assertEqualsBytes(PCM_A, first.readEnd(), "first consumer must receive PCM");
        // The player side dies: close the read end like a crashed consumer, then feed
        // one burst so the relay discovers the broken link on its next write.
        first.readEnd().close();
        harness.writeGuest(PCM_B);
        assertTrue("a broken consumer link must be detached on the failed write",
                harness.sink.awaitEvent("consumer-detached", 1, 5_000));

        PipeConnection second = new PipeConnection();
        harness.acceptor.offer(second);
        assertTrue("the replacement link must be accepted",
                harness.sink.awaitEvent("consumer-attached", 2, 5_000));

        harness.writeGuest(PCM_B);
        assertEqualsBytes(PCM_B, second.readEnd(), "the replacement consumer takes over the stream");

        harness.relay.close();
    }

    @Test(timeout = 20_000)
    public void closedGuestSideDuringHandshakeFailsTheAttempt() throws Exception {
        RelayHarness harness = new RelayHarness(TOKEN);
        // The guest relay closes the connection without answering READY (refused token
        // or restart cycle): the handshake must fail the attempt, not hang.
        PreamblePeer peer = new PreamblePeer(harness.pipesRef);
        assertTrue("relay must send the audio preamble", peer.gotLine.await(5, TimeUnit.SECONDS));
        TransportPipes pipes = harness.pipesRef.get();
        assertNotNull(pipes);
        pipes.guestWrites.close();
        assertTrue("a closed guest side must surface as a transport failure",
                harness.sink.awaitEvent("transport-failed", 1, 5_000));

        harness.relay.close();
        assertEquals(AudioHostRelay.State.CLOSED, harness.relay.state());
    }

    @Test(timeout = 20_000)
    public void invalidTokenFailsTheRelayFastAndClosesTheAcceptor() throws Exception {
        RecordingSink sink = new RecordingSink();
        FakeAcceptor acceptor = new FakeAcceptor();
        AudioHostRelay relay = AudioHostRelay.forTesting(acceptor, () -> {
            throw new IOException("transport must never be opened without a token");
        }, "not-a-token", sink);
        relay.start();

        assertTrue("an unusable token must fail the relay instead of half-serving",
                sink.awaitEvent("relay-failed", 1, 5_000));
        assertEquals(AudioHostRelay.State.FAILED, relay.state());
        assertTrue(sink.events.stream().anyMatch(event -> event.startsWith("token-unavailable|")));

        relay.close();
        assertEquals(AudioHostRelay.State.CLOSED, relay.state());
    }

    @Test(timeout = 20_000)
    public void sessionTeardownClosesTheAttachedAudioRelay() throws Exception {
        QemuProcessLauncher.ProcessHandle handle = new QemuProcessLauncher.ProcessHandle() {
            @Override public int waitFor() { return 0; }
            @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
            @Override public void destroy() { }
            @Override public void destroyForcibly() { }
            @Override public boolean isAlive() { return false; }
            @Override public long pid() { return -1L; }
        };
        QemuInstanceLauncher.Session session = new QemuInstanceLauncher.Session(
                "audio-control", "audio-terminal", handle, "audio-instance");
        RecordingSink sink = new RecordingSink();
        AudioHostRelay relay = AudioHostRelay.forTesting(
                new FakeAcceptor(),
                () -> {
                    throw new IOException("no transport in the lifecycle test");
                },
                null,
                sink);
        relay.start();
        session.attachAudioRelay(relay);

        session.stopRuntime(500L);
        assertTrue("session teardown must close the engine audio relay",
                sink.awaitEvent("relay-closed", 1, 5_000));
        assertEquals(AudioHostRelay.State.CLOSED, relay.state());
    }

    @Test(timeout = 20_000)
    public void unhandledTransportErrorEntersStandbyWithoutDestroyingListener() throws Exception {
        RecordingSink sink = new RecordingSink();
        FakeAcceptor acceptor = new FakeAcceptor();
        AtomicInteger attempts = new AtomicInteger();
        AudioHostRelay relay = AudioHostRelay.forTesting(acceptor, () -> {
            attempts.incrementAndGet();
            throw new IOException("chardev never comes up");
        }, TOKEN, sink, 10L, 200L, 5_000L);
        relay.start();

        assertTrue("five attempts must settle into standby without marking FAILED",
                sink.awaitEvent("transport-standby", 1, 5_000));
        assertTrue("each attempt in the window must be visible",
                sink.count("transport-failed") >= AudioHostRelay.MAX_TRANSPORT_ATTEMPTS);
        assertEquals(0, sink.count("relay-failed"));
        assertEquals(AudioHostRelay.State.IDLE, relay.state());
        assertTrue("consumer acceptor must stay alive during standby", acceptor.open.get());

        relay.close();
        assertEquals(AudioHostRelay.State.CLOSED, relay.state());
    }

    @Test(timeout = 20_000)
    public void standbyRelayWakesAndStreamsPcmWhenConsumerAttaches() throws Exception {
        RecordingSink sink = new RecordingSink();
        FakeAcceptor acceptor = new FakeAcceptor();
        AtomicInteger openCount = new AtomicInteger();
        AtomicReference<TransportPipes> pipesRef = new AtomicReference<>();
        AudioHostRelay relay = AudioHostRelay.forTesting(acceptor, () -> {
            if (openCount.incrementAndGet() <= AudioHostRelay.MAX_TRANSPORT_ATTEMPTS) {
                throw new IOException("desktop audio not ready yet");
            }
            TransportPipes pipes = new TransportPipes();
            pipesRef.set(pipes);
            return new StreamConnection() {
                @Override public InputStream inputStream() { return pipes.relayInput; }
                @Override public OutputStream outputStream() { return pipes.relayOutput; }
                @Override public void close() throws IOException {
                    pipes.relayInput.close();
                    pipes.relayOutput.close();
                }
            };
        }, TOKEN, sink, 10L, 500L, 30_000L);
        relay.start();

        assertTrue("relay must enter standby after initial window",
                sink.awaitEvent("transport-standby", 1, 5_000));
        int attemptsBeforeConsumer = openCount.get();

        // Attaching a consumer must wake standby immediately and reset the retry window.
        PipeConnection link = new PipeConnection();
        acceptor.offer(link);
        assertTrue(sink.awaitEvent("consumer-attached", 1, 5_000));

        PreamblePeer peer = new PreamblePeer(pipesRef);
        assertTrue("woken relay must open transport and send preamble",
                peer.gotLine.await(5, TimeUnit.SECONDS));
        assertTrue(peer.line.toString().startsWith("ORCHESTELLA/AUDIO 1 " + TOKEN));
        TransportPipes pipes = pipesRef.get();
        assertNotNull(pipes);
        pipes.guestWrites.write("ORCHESTELLA/AUDIO READY 1\n".getBytes("US-ASCII"));
        pipes.guestWrites.flush();
        assertTrue(sink.awaitEvent("transport-ready", 1, 5_000));
        assertTrue(openCount.get() > attemptsBeforeConsumer);

        LocalSocketAudioSource source = new LocalSocketAudioSource();
        source.attach(link);
        InputStream pcm = source.openStream();
        pipes.guestWrites.write(PCM_A);
        pipes.guestWrites.flush();
        assertEqualsBytes(PCM_A, pcm, "woken relay must pump PCM to attached consumer");
        assertEquals(AudioHostRelay.State.PUMPING, relay.state());

        source.close();
        relay.close();
    }

    @Test(timeout = 10_000)
    public void closeInterruptsActiveBlockedPumpReadAndExitsThreadsBoundedly() throws Exception {
        RecordingSink sink = new RecordingSink();
        FakeAcceptor acceptor = new FakeAcceptor();
        CountDownLatch inBlockedRead = new CountDownLatch(1);
        CountDownLatch transportClosed = new CountDownLatch(1);
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        ByteArrayOutputStream guestOut = new ByteArrayOutputStream();

        InputStream blockingStream = new InputStream() {
            private final byte[] readyBytes = "ORCHESTELLA/AUDIO READY 1\n".getBytes("US-ASCII");
            private int idx = 0;

            @Override
            public synchronized int read() throws IOException {
                if (idx < readyBytes.length) {
                    return readyBytes[idx++] & 0xFF;
                }
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                inBlockedRead.countDown();
                synchronized (this) {
                    while (!streamClosed.get()) {
                        try {
                            wait(200);
                        } catch (InterruptedException interrupted) {
                            throw new IOException("interrupted", interrupted);
                        }
                    }
                }
                throw new IOException("transport closed");
            }

            @Override
            public synchronized void close() {
                streamClosed.set(true);
                notifyAll();
            }
        };

        AudioHostRelay relay = AudioHostRelay.forTesting(acceptor, () -> new StreamConnection() {
            @Override public InputStream inputStream() { return blockingStream; }
            @Override public OutputStream outputStream() { return guestOut; }
            @Override public void close() throws IOException {
                transportClosed.countDown();
                blockingStream.close();
            }
        }, TOKEN, sink, 10L, 1_000L, 5_000L);
        relay.start();

        assertTrue("pump must enter blocking input.read()", inBlockedRead.await(5, TimeUnit.SECONDS));
        relay.close();

        assertTrue("close() must close activeTransport immediately",
                transportClosed.await(2, TimeUnit.SECONDS));
        assertTrue("relay worker threads must exit boundedly on close()",
                relay.awaitTerminationForTest(2_000L));
        assertEquals(AudioHostRelay.State.CLOSED, relay.state());
    }

    @Test(timeout = 20_000)
    public void concurrentInstancesIsolateAudioSocketsAndPumpIndependently() throws Exception {
        String socketA = QemuInstanceLauncher.audioConsumerSocketName(
                "0f1e2d3c-4b5a-6978-8796-aabbccddeeff", "gen-a111");
        String socketB = QemuInstanceLauncher.audioConsumerSocketName(
                "99887766-5544-3322-1100-ffeeddccbbaa", "gen-b222");
        assertNotEquals(socketA, socketB);

        RelayHarness instanceA = new RelayHarness(TOKEN);
        RelayHarness instanceB = new RelayHarness(TOKEN);
        instanceA.completeHandshake();
        instanceB.completeHandshake();

        PipeConnection linkA = new PipeConnection();
        PipeConnection linkB = new PipeConnection();
        instanceA.acceptor.offer(linkA);
        instanceB.acceptor.offer(linkB);
        assertTrue(instanceA.sink.awaitEvent("consumer-attached", 1, 5_000));
        assertTrue(instanceB.sink.awaitEvent("consumer-attached", 1, 5_000));

        LocalSocketAudioSource sourceA = new LocalSocketAudioSource(socketA);
        LocalSocketAudioSource sourceB = new LocalSocketAudioSource(socketB);
        sourceA.attach(linkA);
        sourceB.attach(linkB);

        instanceA.writeGuest(PCM_A);
        instanceB.writeGuest(PCM_B);
        assertEqualsBytes(PCM_A, sourceA.openStream(), "instance A must receive only its PCM");
        assertEqualsBytes(PCM_B, sourceB.openStream(), "instance B must receive only its PCM");

        sourceA.close();
        sourceB.close();
        instanceA.relay.close();
        instanceB.relay.close();
    }

    @Test(timeout = 5_000)
    public void relayRejectsDoubleCloseQuietly() throws Exception {
        RelayHarness harness = new RelayHarness(TOKEN);
        harness.relay.close();
        harness.relay.close();
        assertEquals(AudioHostRelay.State.CLOSED, harness.relay.state());
    }

    @Test(timeout = 5_000)
    public void streamConnectionContractRejectsNullAttach() {
        LocalSocketAudioSource source = new LocalSocketAudioSource();
        try {
            source.attach(null);
            fail("null attach is a programming error");
        } catch (NullPointerException | IOException expected) {
            // Only the NPE branch is reachable; IOException keeps the checked contract honest.
            if (!(expected instanceof NullPointerException)) {
                fail("null attach must fail as NPE, not " + expected);
            }
        }
    }
}
