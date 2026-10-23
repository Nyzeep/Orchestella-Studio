package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One authenticated AXP1 connection with framing, a bounded receive window and
 * half-close/reset semantics.
 *
 * <p>The handshake is the only authenticated step: HELLO carries the role, a
 * 16-byte nonce, the 32-hex-character token and the local capabilities;
 * HELLO_ACK carries the peer role, nonce and negotiated view. The token compare
 * is constant-time and a rejected peer only receives the numeric
 * {@code AUTH_FAILED} code, never the expected value.
 *
 * <p>After the handshake a daemon reader turns DATA frames into a bounded
 * in-memory window ({@code windowBytes}, 4 MiB by default). When the window is
 * full the reader stops reading the transport, so a slow consumer applies
 * backpressure instead of growing heap. {@code EOF} closes only the peer's
 * sending direction; {@code RST} closes both directions immediately.
 *
 * <p>AXP1 is independent of FLN1 and does not parse the distcc protocol.
 */
public final class Axp1Connection implements Closeable {
    private static final int NONCE_BYTES = 16;
    private static final int TOKEN_CHARS = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Axp1Transport transport;
    private final Axp1Role localRole;
    private final Axp1Capabilities negotiated;
    private final Axp1Capabilities peerCapabilities;
    private final int maxFrame;
    private final Axp1Window inbound;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicBoolean writeClosed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<Axp1Error> terminal = new AtomicReference<>();
    private final long startedNanos = System.nanoTime();
    private final AtomicLong dataBytesIn = new AtomicLong();
    private final AtomicLong dataBytesOut = new AtomicLong();
    private final AtomicLong framesRead = new AtomicLong();
    private final AtomicLong framesWritten = new AtomicLong();
    private final AtomicLong pings = new AtomicLong();
    private final AtomicLong pongs = new AtomicLong();
    private final Thread reader;

    private Axp1Connection(
            Axp1Transport transport,
            Axp1Role localRole,
            Axp1Capabilities negotiated,
            Axp1Capabilities peerCapabilities,
            Axp1Limits limits) {
        this.transport = transport;
        this.localRole = localRole;
        this.negotiated = negotiated;
        this.peerCapabilities = peerCapabilities;
        this.maxFrame = negotiated.maxFrame();
        this.inbound = new Axp1Window(limits.windowBytes());
        this.reader = new Thread(this::readLoop, "axp1-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    /** Client side: sends HELLO and waits for HELLO_ACK. */
    public static Axp1Connection connect(
            Axp1Transport transport,
            Axp1Role role,
            String token,
            Axp1Capabilities localCapabilities,
            Axp1Limits limits) throws Axp1Exception {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(localCapabilities, "localCapabilities");
        Objects.requireNonNull(limits, "limits");
        requireToken(token);
        try {
            writeRaw(transport.output(), Axp1Frame.Type.HELLO,
                    helloPayload(role, token.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                            localCapabilities),
                    limits.maxFrame());
            transport.output().flush();
            Axp1Frame reply = readWithTimeout(transport.input(), limits);
            if (reply == null) {
                throw new Axp1Exception(Axp1Error.TIMEOUT, "peer closed during the handshake");
            }
            if (reply.type() == Axp1Frame.Type.RST) {
                throw rstFailure(reply);
            }
            if (reply.type() != Axp1Frame.Type.HELLO_ACK) {
                throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "expected HELLO_ACK");
            }
            ParsedHandshake ack = parseAck(reply.payload());
            if (ack.role == role) {
                throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "peer role matches local role");
            }
            return new Axp1Connection(transport, role,
                    Axp1Capabilities.negotiate(localCapabilities, ack.capabilities),
                    ack.capabilities,
                    limits);
        } catch (Axp1Exception failure) {
            closeQuietly(transport);
            throw failure;
        } catch (IOException failure) {
            closeQuietly(transport);
            throw new Axp1Exception(Axp1Error.CLOSED, "transport failed during the handshake", failure);
        }
    }

    /** Server side: validates HELLO, then sends HELLO_ACK. */
    public static Axp1Connection accept(
            Axp1Transport transport,
            Axp1Role role,
            String expectedToken,
            Axp1Capabilities localCapabilities,
            Axp1Limits limits) throws Axp1Exception {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(localCapabilities, "localCapabilities");
        Objects.requireNonNull(limits, "limits");
        requireToken(expectedToken);
        try {
            Axp1Frame hello = readWithTimeout(transport.input(), limits);
            if (hello == null) {
                throw new Axp1Exception(Axp1Error.TIMEOUT, "peer closed during the handshake");
            }
            if (hello.type() != Axp1Frame.Type.HELLO) {
                throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "expected HELLO");
            }
            ParsedHandshake request = parseHello(hello.payload());
            if (request.role == role) {
                throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "peer role matches local role");
            }
            if (!tokenEquals(expectedToken, request.token)) {
                sendReset(transport, Axp1Error.AUTH_FAILED);
                throw new Axp1Exception(Axp1Error.AUTH_FAILED, "authentication failed");
            }
            byte[] ack = ackPayload(role, localCapabilities);
            writeRaw(transport.output(), Axp1Frame.Type.HELLO_ACK, ack, limits.maxFrame());
            transport.output().flush();
            return new Axp1Connection(transport, role,
                    Axp1Capabilities.negotiate(localCapabilities, request.capabilities),
                    request.capabilities,
                    limits);
        } catch (Axp1Exception failure) {
            if (failure.code() == Axp1Error.VERSION_MISMATCH
                    || failure.code() == Axp1Error.FRAME_TOO_LARGE
                    || failure.code() == Axp1Error.PROTOCOL_ERROR) {
                sendReset(transport, failure.code());
            }
            closeQuietly(transport);
            throw failure;
        } catch (IOException failure) {
            closeQuietly(transport);
            throw new Axp1Exception(Axp1Error.CLOSED, "transport failed during the handshake", failure);
        }
    }

    public Axp1Role localRole() {
        return localRole;
    }

    public Axp1Capabilities negotiated() {
        return negotiated;
    }

    /** Capabilities exactly as advertised by the peer, before negotiation. */
    public Axp1Capabilities peerCapabilities() {
        return peerCapabilities;
    }

    public InputStream dataInput() {
        return inbound.stream();
    }

    /** Framed byte stream; writes are split into DATA frames of at most maxFrame. */
    public OutputStream dataOutput() {
        return new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                int written = 0;
                while (written < length) {
                    int chunk = Math.min(maxFrame, length - written);
                    writeFrame(Axp1Frame.Type.DATA, Arrays.copyOfRange(
                            bytes, offset + written, offset + written + chunk));
                    written += chunk;
                }
            }

            @Override
            public void flush() throws IOException {
                transport.output().flush();
            }

            @Override
            public void close() throws IOException {
                closeWrite();
            }
        };
    }

    /** Half-close: sends EOF once; the read direction stays open. Idempotent. */
    public void closeWrite() throws IOException {
        if (writeClosed.get()) {
            return;
        }
        if (closed.get()) {
            throw new Axp1Exception(Axp1Error.CLOSED, "connection is closed");
        }
        if (!writeClosed.compareAndSet(false, true)) {
            return;
        }
        writeRawLocked(Axp1Frame.empty(Axp1Frame.Type.EOF));
        transport.output().flush();
    }

    /** Sends RST and closes both directions. Idempotent. */
    public void reset(Axp1Error error) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        terminal.compareAndSet(null, error);
        try {
            writeRawLocked(Axp1Frame.of(Axp1Frame.Type.RST, codeBytes(error)));
            transport.output().flush();
        } catch (IOException ignored) {
            // The peer is already gone; the local state is what matters.
        }
        inbound.fail(new Axp1Exception(error, "connection reset"));
        closeQuietly(transport);
    }

    /** Full close: sends EOF best-effort, then releases both directions. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        terminal.compareAndSet(null, Axp1Error.CLOSED);
        try {
            if (writeClosed.compareAndSet(false, true)) {
                writeRawLocked(Axp1Frame.empty(Axp1Frame.Type.EOF));
                transport.output().flush();
            }
        } catch (IOException ignored) {
            // Best effort; closing the transport is authoritative.
        }
        inbound.fail(new Axp1Exception(Axp1Error.CLOSED, "connection closed"));
        closeQuietly(transport);
    }

    public void ping() throws IOException {
        byte[] nonce = new byte[8];
        RANDOM.nextBytes(nonce);
        writeFrame(Axp1Frame.Type.PING, nonce);
        pings.incrementAndGet();
        transport.output().flush();
    }

    public boolean isClosed() {
        return closed.get() || terminal.get() != null;
    }

    public long bufferedBytes() {
        return inbound.bufferedBytes();
    }

    public Axp1Stats stats() {
        return new Axp1Stats(
                dataBytesIn.get(),
                dataBytesOut.get(),
                framesRead.get(),
                framesWritten.get(),
                pings.get(),
                pongs.get(),
                inbound.bufferedBytes(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
                terminal.get());
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                Axp1Frame frame = Axp1Codec.read(transport.input(), maxFrame);
                if (frame == null) {
                    terminate(Axp1Error.CLOSED, new Axp1Exception(
                            Axp1Error.CLOSED, "peer transport closed"));
                    return;
                }
                framesRead.incrementAndGet();
                switch (frame.type()) {
                    case DATA -> {
                        dataBytesIn.addAndGet(frame.payloadLength());
                        inbound.offer(frame.payload());
                    }
                    case EOF -> {
                        inbound.finish();
                        return;
                    }
                    case PING -> writePong(frame.payload());
                    case PONG -> pongs.incrementAndGet();
                    case RST -> {
                        Axp1Error code = rstCode(frame.payload());
                        terminate(code, new Axp1Exception(code, "peer reset"));
                        return;
                    }
                    case HELLO, HELLO_ACK -> {
                        sendReset(transport, Axp1Error.PROTOCOL_ERROR);
                        terminate(Axp1Error.PROTOCOL_ERROR,
                                new Axp1Exception(Axp1Error.PROTOCOL_ERROR,
                                        "handshake frame after the handshake"));
                        return;
                    }
                }
            }
        } catch (Axp1Exception failure) {
            terminate(failure.code(), failure);
        } catch (IOException failure) {
            terminate(Axp1Error.CLOSED,
                    new Axp1Exception(Axp1Error.CLOSED, "transport read failed", failure));
        }
    }

    private void terminate(Axp1Error error, Axp1Exception failure) {
        terminal.compareAndSet(null, error);
        closed.set(true);
        inbound.fail(failure);
        closeQuietly(transport);
    }

    private void writePong(byte[] payload) {
        try {
            writeFrame(Axp1Frame.Type.PONG, payload);
            transport.output().flush();
        } catch (IOException ignored) {
            // A failed PONG is not a second close owner.
        }
    }

    private void writeFrame(Axp1Frame.Type type, byte[] payload)
            throws IOException {
        if (closed.get() || writeClosed.get()) {
            throw new Axp1Exception(Axp1Error.CLOSED, "connection is closed");
        }
        writeRawLocked(Axp1Frame.of(type, payload));
    }

    private void writeRawLocked(Axp1Frame frame) throws IOException {
        writeLock.lock();
        try {
            writeRaw(transport.output(), frame.type(), frame.payload(), maxFrame);
            framesWritten.incrementAndGet();
            if (frame.type() == Axp1Frame.Type.DATA) {
                dataBytesOut.addAndGet(frame.payloadLength());
            }
        } finally {
            writeLock.unlock();
        }
    }

    private static void writeRaw(OutputStream output, Axp1Frame.Type type, byte[] payload, int maxFrame)
            throws IOException {
        output.write(Axp1Codec.encode(Axp1Frame.of(type, payload), maxFrame));
    }

    private static Axp1Frame readWithTimeout(InputStream input, Axp1Limits limits)
            throws Axp1Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "axp1-handshake-read");
            thread.setDaemon(true);
            return thread;
        });
        Future<Axp1Frame> pending = executor.submit(
                () -> Axp1Codec.read(input, limits.maxFrame()));
        try {
            return pending.get(limits.handshakeTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            throw new Axp1Exception(Axp1Error.TIMEOUT, "handshake timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Axp1Exception(Axp1Error.CLOSED, "handshake interrupted");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Axp1Exception typed) {
                throw typed;
            }
            if (cause instanceof IOException io) {
                throw new Axp1Exception(Axp1Error.CLOSED, "handshake transport failed", io);
            }
            throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "handshake failed", cause);
        } finally {
            pending.cancel(true);
            executor.shutdownNow();
        }
    }

    private static byte[] helloPayload(Axp1Role role, byte[] token, Axp1Capabilities capabilities) {
        return handshakePayload(role, token, capabilities);
    }

    private static byte[] ackPayload(Axp1Role role, Axp1Capabilities capabilities) {
        return handshakePayload(role, null, capabilities);
    }

    private static byte[] handshakePayload(
            Axp1Role role, byte[] token, Axp1Capabilities capabilities) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(role.wireCode());
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            out.writeByte(nonce.length);
            out.write(nonce);
            if (token != null) {
                out.writeByte(token.length);
                out.write(token);
            }
            out.write(capabilities.encode());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory handshake encoding failed", impossible);
        }
    }

    private static ParsedHandshake parseHello(byte[] payload) throws Axp1Exception {
        ParsedHandshake parsed = parseHandshake(payload, true);
        if (parsed.token == null || parsed.token.length != TOKEN_CHARS) {
            throw new Axp1Exception(Axp1Error.AUTH_FAILED, "authentication failed");
        }
        return parsed;
    }

    private static ParsedHandshake parseAck(byte[] payload) throws Axp1Exception {
        return parseHandshake(payload, false);
    }

    private static ParsedHandshake parseHandshake(byte[] payload, boolean withToken)
            throws Axp1Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int roleCode = in.readUnsignedByte();
            Axp1Role role = Axp1Role.fromWire(roleCode);
            if (role == null) {
                throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "invalid role");
            }
            int nonceLength = in.readUnsignedByte();
            if (nonceLength != NONCE_BYTES) {
                throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "invalid nonce length");
            }
            byte[] nonce = new byte[nonceLength];
            in.readFully(nonce);
            byte[] token = null;
            if (withToken) {
                int tokenLength = in.readUnsignedByte();
                if (tokenLength < 0 || tokenLength > 64) {
                    throw new Axp1Exception(Axp1Error.AUTH_FAILED, "authentication failed");
                }
                token = new byte[tokenLength];
                in.readFully(token);
            }
            byte[] capabilities = in.readAllBytes();
            return new ParsedHandshake(role, nonce, token, Axp1Capabilities.decode(capabilities));
        } catch (IOException failure) {
            throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "truncated handshake", failure);
        }
    }

    private static boolean tokenEquals(String expected, byte[] actual) {
        byte[] expectedBytes = expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] expectedDigest = sha256(expectedBytes);
        byte[] actualDigest = sha256(actual == null ? new byte[0] : actual);
        return actual != null && MessageDigest.isEqual(expectedDigest, actualDigest);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    private static void requireToken(String token) {
        if (token == null || !token.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("token must be 32 lowercase hex characters");
        }
    }

    private static Axp1Exception rstFailure(Axp1Frame frame) {
        Axp1Error code = rstCode(frame.payload());
        return new Axp1Exception(code, "peer rejected the connection");
    }

    private static Axp1Error rstCode(byte[] payload) {
        if (payload.length < 2) {
            return Axp1Error.PROTOCOL_ERROR;
        }
        return Axp1Error.fromWire(((payload[0] & 0xff) << 8) | (payload[1] & 0xff));
    }

    private static byte[] codeBytes(Axp1Error error) {
        return new byte[] {(byte) (error.wireCode() >>> 8), (byte) error.wireCode()};
    }

    private static void sendReset(Axp1Transport transport, Axp1Error error) {
        try {
            writeRaw(transport.output(), Axp1Frame.Type.RST, codeBytes(error),
                    Axp1Codec.DEFAULT_MAX_FRAME);
            transport.output().flush();
        } catch (IOException ignored) {
            // Best effort: the typed local failure is already authoritative.
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException | RuntimeException ignored) {
            // Best effort teardown.
        }
    }

    private record ParsedHandshake(
            Axp1Role role, byte[] nonce, byte[] token, Axp1Capabilities capabilities) {}

    /**
     * Bounded receive buffer. {@link #offer} blocks when the window is full,
     * which stops the reader thread from reading the transport.
     */
    private static final class Axp1Window {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition();
        private final Condition notFull = lock.newCondition();
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private final long windowBytes;
        private long buffered;
        private boolean finished;
        private IOException failure;

        Axp1Window(long windowBytes) {
            this.windowBytes = windowBytes;
        }

        void offer(byte[] chunk) throws IOException {
            lock.lock();
            try {
                int offset = 0;
                while (offset < chunk.length) {
                    while (failure == null && buffered >= windowBytes) {
                        try {
                            notFull.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new Axp1Exception(Axp1Error.CLOSED, "window wait interrupted");
                        }
                    }
                    if (failure != null) {
                        throw failure;
                    }
                    int take = (int) Math.min(chunk.length - offset, windowBytes - buffered);
                    if (take <= 0) {
                        continue;
                    }
                    chunks.add(Arrays.copyOfRange(chunk, offset, offset + take));
                    buffered += take;
                    offset += take;
                    notEmpty.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }

        int read(byte[] target, int offset, int length) throws IOException {
            lock.lock();
            try {
                while (true) {
                    if (failure != null) {
                        throw failure;
                    }
                    if (buffered > 0) {
                        break;
                    }
                    if (finished) {
                        return -1;
                    }
                    try {
                        notEmpty.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new Axp1Exception(Axp1Error.CLOSED, "read interrupted");
                    }
                }
                int copied = 0;
                while (copied < length && !chunks.isEmpty()) {
                    byte[] head = chunks.peek();
                    int take = Math.min(head.length, length - copied);
                    System.arraycopy(head, 0, target, offset + copied, take);
                    copied += take;
                    buffered -= take;
                    if (take == head.length) {
                        chunks.poll();
                    } else {
                        chunks.poll();
                        chunks.addFirst(Arrays.copyOfRange(head, take, head.length));
                    }
                    notFull.signalAll();
                }
                return copied;
            } finally {
                lock.unlock();
            }
        }

        void finish() {
            lock.lock();
            try {
                finished = true;
                notEmpty.signalAll();
                notFull.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void fail(IOException error) {
            lock.lock();
            try {
                failure = error;
                notEmpty.signalAll();
                notFull.signalAll();
            } finally {
                lock.unlock();
            }
        }

        long bufferedBytes() {
            lock.lock();
            try {
                return buffered;
            } finally {
                lock.unlock();
            }
        }

        InputStream stream() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int read = Axp1Window.this.read(one, 0, 1);
                    return read < 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (length == 0) {
                        return 0;
                    }
                    return Axp1Window.this.read(bytes, offset, length);
                }
            };
        }
    }
}
