package io.github.nyzeep.orchestella;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.nyzeep.orchestella.audio.StreamConnection;

/**
 * Engine-side audio loop (G-05). Bridges the guest's virtio-serial audio port —
 * surfacing on the host as the QEMU-served per-instance chardev socket — to local
 * consumer links accepted on the abstract {@code @droidlayer-audio} socket, where
 * {@link io.github.nyzeep.orchestella.audio.LocalSocketAudioSource} (client connect
 * or attach path) picks the PCM stream up for {@code LowLatencyAudioPlayer}.
 *
 * <p>Wire protocol to the guest relay (mirror of the display plane): the host sends
 * {@code ORCHESTELLA/AUDIO 1 <bridge-token>\n} on the chardev connection and waits
 * for {@code ORCHESTELLA/AUDIO READY 1\n} before any PCM flows. Without the token
 * handshake the relay stays idle; the guest supervisor never forwards an
 * unauthenticated stream.
 *
 * <p>Operational shape: while no consumer is attached, incoming PCM is drained and
 * discarded so the guest relay never stalls on virtio-serial backpressure. A dead
 * consumer link is closed and the next accepted one takes over. Transport
 * failures reconnect with a bounded budget; an exhausted budget tears the relay
 * down (state {@code FAILED}) so consumer connects fail fast and the
 * {@code FallbackAudioSource} probe walk moves on honestly.
 *
 * <p>Honesty boundary: this loop is code-complete and JVM-tested with pipe-backed
 * transports; the real PCM closure is NOT verified on a device yet.
 */
public final class AudioHostRelay implements AutoCloseable {

    /** Abstract socket name consumers connect to (LocalSocketAudioSource.DEFAULT_SOCKET_NAME). */
    public static final String DEFAULT_CONSUMER_SOCKET_NAME = "droidlayer-audio";

    static final String PREAMBLE = "ORCHESTELLA/AUDIO 1 ";
    /** Line content without the terminating newline, as the handshake reader accumulates it. */
    static final String READY_TEXT = "ORCHESTELLA/AUDIO READY 1";
    static final int MAX_READY_LINE = 64;

    /** Relay states, observable via {@link EventSink}; never used for external control flow. */
    public enum State { IDLE, CONNECTING, HANDSHAKING, PUMPING, DRAINING, FAILED, CLOSED }

    /** Consumer-link acceptor seam. Production uses the abstract LocalServerSocket; tests use pipes. */
    public interface ConsumerAcceptor extends java.io.Closeable {
        /** Blocks until a consumer connects. Throws when closed or the listener dies. */
        StreamConnection accept() throws IOException;
    }

    /** Guest-transport factory seam: one fresh chardev connection per call. */
    public interface TransportFactory {
        StreamConnection open() throws IOException;
    }

    /** JVM-safe event observation; production wires android Log via the engine assembly. */
    public interface EventSink {
        void onEvent(String event, String detail);
    }

    private static final int PUMP_BUFFER_BYTES = 8192;
    static final int MAX_TRANSPORT_ATTEMPTS = 5;
    private static final long TRANSPORT_RETRY_DELAY_MS = 2_000L;
    private static final long HANDSHAKE_TIMEOUT_MS = 15_000L;
    private static final long HANDSHAKE_POLL_MS = 50L;

    private final ConsumerAcceptor acceptor;
    private final TransportFactory transportFactory;
    private final String token;
    private final EventSink sink;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicReference<StreamConnection> consumer = new AtomicReference<>();
    private volatile State state = State.IDLE;

    private AudioHostRelay(ConsumerAcceptor acceptor, TransportFactory transportFactory,
            String token, EventSink sink) {
        this.acceptor = Objects.requireNonNull(acceptor, "acceptor");
        this.transportFactory = Objects.requireNonNull(transportFactory, "transportFactory");
        this.token = token;
        this.sink = sink;
    }

    /** Full-shape relay for tests: inject any acceptor/transport/sink. */
    public static AudioHostRelay forTesting(ConsumerAcceptor acceptor, TransportFactory transportFactory,
            String token, EventSink sink) {
        return new AudioHostRelay(acceptor, transportFactory, token, sink);
    }

    /**
     * Production shape: abstract-socket consumer listener plus filesystem chardev
     * connection. Called only from the engine assembly layer (QemuInstanceLauncher).
     */
    public static AudioHostRelay forLocalSockets(String consumerSocketName, String chardevSocketPath,
            String bridgeToken, EventSink sink) {
        return new AudioHostRelay(new LocalSocketAcceptor(consumerSocketName, sink),
                new ChardevTransportFactory(chardevSocketPath, sink), bridgeToken, sink);
    }

    /** Spawns the accept and transport daemon threads; does no blocking work on the caller. */
    public AudioHostRelay start() {
        Thread acceptorThread = new Thread(this::acceptLoop, "orchestella-audio-accept");
        acceptorThread.setDaemon(true);
        acceptorThread.start();
        Thread transportThread = new Thread(this::transportLoop, "orchestella-audio-transport");
        transportThread.setDaemon(true);
        transportThread.start();
        return this;
    }

    private void acceptLoop() {
        while (!closed.get() && !failed.get()) {
            StreamConnection link;
            try {
                link = acceptor.accept();
            } catch (IOException | RuntimeException | Error dead) {
                if (!closed.get() && !failed.get()) {
                    emit("accept-failed", dead.getClass().getSimpleName());
                }
                return;
            }
            if (closed.get() || failed.get()) {
                closeQuietly(link);
                return;
            }
            StreamConnection previous = consumer.getAndSet(link);
            if (previous != null) {
                // One consumer at a time: a newer client link replaces a dead/slow one.
                closeQuietly(previous);
                emit("consumer-detached", "replaced-by-newer-consumer");
            }
            emit("consumer-attached", "replaced=" + (previous != null));
        }
    }

    private void transportLoop() {
        if (token == null || !token.matches("[0-9a-f]{64}")) {
            emit("token-unavailable", "audio loop disabled; bridge token absent or not 64-hex");
            markFailed();
            return;
        }
        for (int attempt = 1; attempt <= MAX_TRANSPORT_ATTEMPTS && !closed.get() && !failed.get(); attempt++) {
            StreamConnection transport = null;
            try {
                state = State.CONNECTING;
                transport = transportFactory.open();
                state = State.HANDSHAKING;
                handshake(transport);
                emit("transport-ready", "attempt=" + attempt);
                pump(transport);
                emit("transport-ended", "guest-side EOF after " + attempt + " attempt(s)");
                if (!closed.get() && !failed.get()) {
                    // The guest relay session ended (restart cycle). Give it a beat, reconnect.
                    sleepQuietly(TRANSPORT_RETRY_DELAY_MS);
                    continue;
                }
                return;
            } catch (IOException | RuntimeException | Error failure) {
                closeQuietly(transport);
                emit("transport-failed", "attempt=" + attempt + " " + failure.getClass().getSimpleName());
                if (attempt < MAX_TRANSPORT_ATTEMPTS && !closed.get() && !failed.get()) {
                    sleepQuietly(TRANSPORT_RETRY_DELAY_MS);
                }
            }
        }
        if (!closed.get()) {
            markFailed();
        }
    }

    /**
     * Token preamble then a bounded READY wait. The read blocks; the deadline is
     * enforced by a watchdog that closes the transport (the only way to unblock a
     * stream read), so both silence and guest-side closure surface as failures.
     */
    private void handshake(StreamConnection transport) throws IOException {
        AtomicBoolean settled = new AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            long end = System.nanoTime() + HANDSHAKE_TIMEOUT_MS * 1_000_000L;
            while (!settled.get() && System.nanoTime() < end) {
                try {
                    Thread.sleep(HANDSHAKE_POLL_MS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
            if (!settled.get()) {
                emit("handshake-timeout", "closing transport to unblock the READY read");
                closeQuietly(transport);
            }
        }, "orchestella-audio-handshake");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            OutputStream output = transport.outputStream();
            output.write((PREAMBLE + token + "\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            InputStream input = transport.inputStream();
            StringBuilder line = new StringBuilder();
            while (line.length() < MAX_READY_LINE) {
                int datum = input.read();
                if (datum < 0) {
                    throw new IOException("audio relay closed during handshake");
                }
                if (datum == '\n') {
                    if (!READY_TEXT.contentEquals(line)) {
                        throw new IOException("audio relay rejected the token handshake");
                    }
                    return;
                }
                line.append((char) datum);
            }
            throw new IOException("audio relay handshake line overflow");
        } finally {
            settled.set(true);
        }
    }

    /**
     * Guest -> host copy only. With no consumer attached, PCM is read and discarded:
     * stopping the drain would backpressure the guest relay until its stall guard
     * kills the session. A consumer write failure detaches that consumer; the accept
     * loop supplies the next one.
     */
    private void pump(StreamConnection transport) throws IOException {
        InputStream input = transport.inputStream();
        byte[] buffer = new byte[PUMP_BUFFER_BYTES];
        while (!closed.get() && !failed.get()) {
            int count = input.read(buffer);
            if (count < 0) {
                return;
            }
            StreamConnection current = consumer.get();
            if (current == null) {
                state = State.DRAINING;
                continue;
            }
            try {
                OutputStream output = current.outputStream();
                output.write(buffer, 0, count);
                output.flush();
                state = State.PUMPING;
            } catch (IOException | RuntimeException | Error consumerDead) {
                consumer.compareAndSet(current, null);
                closeQuietly(current);
                emit("consumer-detached", consumerDead.getClass().getSimpleName());
            }
        }
    }

    private void markFailed() {
        if (failed.compareAndSet(false, true)) {
            state = State.FAILED;
            emit("relay-failed", "transport budget exhausted; consumer connects will fail fast");
            closeQuietly(consumer.getAndSet(null));
            try {
                acceptor.close();
            } catch (IOException | RuntimeException | Error ignored) {
                // Best-effort teardown; the state already says FAILED.
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state = State.CLOSED;
        emit("relay-closed", "session teardown");
        closeQuietly(consumer.getAndSet(null));
        try {
            acceptor.close();
        } catch (IOException | RuntimeException | Error ignored) {
            // Teardown is best-effort; threads observe the closed flag.
        }
    }

    State state() {
        return state;
    }

    private void emit(String event, String detail) {
        if (sink != null) {
            sink.onEvent(event, detail);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(StreamConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (IOException | RuntimeException | Error ignored) {
            // Relay teardown never surfaces consumer/transport close failures.
        }
    }

    /** Android-backed consumer listener on the abstract namespace socket. */
    private static final class LocalSocketAcceptor implements ConsumerAcceptor {
        private final LocalServerSocket server;
        private final EventSink sink;

        LocalSocketAcceptor(String socketName, EventSink sink) {
            this.sink = sink;
            LocalServerSocket created = null;
            try {
                // LocalServerSocket(String) binds the ABSTRACT namespace: @droidlayer-audio.
                created = new LocalServerSocket(socketName);
            } catch (IOException | RuntimeException | Error failure) {
                if (sink != null) {
                    sink.onEvent("listen-failed", failure.getClass().getSimpleName());
                }
            }
            this.server = created;
        }

        @Override
        public StreamConnection accept() throws IOException {
            LocalServerSocket current = server;
            if (current == null) {
                throw new IOException("audio consumer listener unavailable");
            }
            LocalSocket socket = current.accept();
            return new LocalSocketConnection(socket);
        }

        @Override
        public void close() throws IOException {
            LocalServerSocket current = server;
            if (current != null) {
                current.close();
            }
        }
    }

    /** Android-backed guest transport: client of the QEMU-served filesystem chardev socket. */
    private static final class ChardevTransportFactory implements TransportFactory {
        private final String socketPath;
        private final EventSink sink;

        ChardevTransportFactory(String socketPath, EventSink sink) {
            this.socketPath = socketPath;
            this.sink = sink;
        }

        @Override
        public StreamConnection open() throws IOException {
            LocalSocket socket = new LocalSocket();
            try {
                socket.connect(new LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // The connect failure is the real error surface; half-open teardown is best-effort.
                }
                if (sink != null) {
                    sink.onEvent("chardev-connect-failed", failure.getClass().getSimpleName());
                }
                throw failure;
            }
            return new LocalSocketConnection(socket);
        }
    }

    private static final class LocalSocketConnection implements StreamConnection {
        private final LocalSocket socket;

        LocalSocketConnection(LocalSocket socket) {
            this.socket = socket;
        }

        @Override
        public InputStream inputStream() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream outputStream() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
