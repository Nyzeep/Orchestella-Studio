package io.github.nyzeep.orchestella.runtime.contract.display;

import io.github.nyzeep.orchestella.runtime.contract.InputEvent;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authenticated private CPU-copy display transport for a Wayland compositor's wayvnc service.
 *
 * <p>Only use a dedicated per-instance virtio-serial or vsock connection. The token preamble is
 * mandatory before RFB 3.8 None security. This class does not open network endpoints or share the
 * control channel's reader. CONNECTED means a complete legal pixel buffer arrived from the
 * authenticated service; wayvnc can generate placeholder pixels, so this is NOT proof of compositor
 * capture, desktop readiness, guest GPU acceleration, or host presentation.
 *
 * <p>Callbacks run synchronously on the reader/transition thread and should return promptly. There
 * is no frame queue: a slow consumer backpressures the next request. Each complete update causes
 * exactly one following request. All writes, including input, go through one bounded writer queue.
 * Closing the supplied transport MUST unblock both stream operations. A connector must honor
 * interruption or impose its own connection timeout; cancellation closes a late returned transport.
 */
public final class RfbDisplayChannel implements WaylandChannel {
    private static final int HISTORY_LIMIT = 64;
    private final Object lifecycle = new Object();
    private final StreamConnector connector;
    private final String token;
    private final InputCapabilities capabilities;
    private final Options options;
    private final CopyOnWriteArrayList<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<FrameListener> frameListeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<StateChange> history = new ArrayDeque<>();
    private final AtomicLong frameIds = new AtomicLong();
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile String message = "display disconnected";
    private volatile Session session;

    @FunctionalInterface
    public interface StreamConnector {
        /** Returns fresh streams belonging to a new private display connection, never control I/O. */
        Streams open() throws IOException;
    }

    public static final class Streams implements AutoCloseable {
        private final InputStream input;
        private final OutputStream output;
        private final AutoCloseable transport;
        private final AtomicBoolean closed = new AtomicBoolean();

        public Streams(InputStream input, OutputStream output) {
            this(input, output, null);
        }

        /** transport is closed first, e.g. the owning socket, to unblock a pending read/write. */
        public Streams(InputStream input, OutputStream output, AutoCloseable transport) {
            this.input = Objects.requireNonNull(input, "input");
            this.output = Objects.requireNonNull(output, "output");
            this.transport = transport;
        }

        public InputStream input() { return input; }
        public OutputStream output() { return output; }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closeResource(transport);
            closeResource(input);
            closeResource(output);
        }

        private static void closeResource(AutoCloseable resource) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                    // Never expose connector exceptions (which may contain paths/tokens) to status.
                }
            }
        }
    }

    /** Inject only after a guest probe confirms virtual keyboard/Unicode/pointer capability. */
    public record InputCapabilities(boolean keyboard, boolean unicodeText, boolean pointer) {
        public static final InputCapabilities NONE = new InputCapabilities(false, false, false);

        public InputCapabilities {
            if (unicodeText && !keyboard) {
                throw new IllegalArgumentException("Unicode input requires keyboard capability");
            }
        }
    }

    /** Fixed protocol quotas. firstFrameTimeoutMillis also bounds resize, partial reads and writes. */
    public record Options(int inputQueueCapacity, long handshakeTimeoutMillis, long firstFrameTimeoutMillis) {
        public static final Options DEFAULT = new Options(64, 15_000, 30_000);

        public Options {
            if (inputQueueCapacity < 1 || inputQueueCapacity > 1024
                    || handshakeTimeoutMillis < 1 || handshakeTimeoutMillis > 300_000
                    || firstFrameTimeoutMillis < 1 || firstFrameTimeoutMillis > 300_000) {
                throw new IllegalArgumentException("invalid display queue or timeout options");
            }
        }
    }

    public record StateChange(ConnectionState state, String message, long timestampNs) {}

    /** Per-connection observed counters. Timestamps are host receive completion, not capture times. */
    public record Statistics(long frames, long updates, long rectangles, long pixelBytes,
            long receivedBytes, long sentBytes, long inputEvents, long rejectedInputEvents,
            long listenerFailures, long firstFrameNs, long lastFrameNs) {
        public OptionalDouble averageReceiveFps() {
            return frames < 2 || lastFrameNs <= firstFrameNs ? OptionalDouble.empty()
                    : OptionalDouble.of((frames - 1) * 1_000_000_000.0 / (lastFrameNs - firstFrameNs));
        }
    }

    public RfbDisplayChannel(StreamConnector connector, String token) {
        this(connector, token, InputCapabilities.NONE, Options.DEFAULT);
    }

    public RfbDisplayChannel(StreamConnector connector, String token, InputCapabilities capabilities) {
        this(connector, token, capabilities, Options.DEFAULT);
    }

    public RfbDisplayChannel(StreamConnector connector, String token, InputCapabilities capabilities, Options options) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.token = Objects.requireNonNull(token, "token");
        if (!token.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("display token must be 64 lowercase hex characters");
        }
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.options = Objects.requireNonNull(options, "options");
        history.add(new StateChange(state, message, System.nanoTime()));
    }

    /** Starts asynchronous establishment; false if an establishment/connection is already active. */
    public boolean connect() {
        Session next;
        synchronized (lifecycle) {
            if (session != null && !session.stopped.get()) {
                return false;
            }
            next = new Session();
            session = next;
            setStatus(ConnectionState.DISCONNECTED, "connecting authenticated display; awaiting pixels");
        }
        notifyState(next, ConnectionState.DISCONNECTED, message);
        next.start();
        return true;
    }

    /** Explicitly closes the current generation then opens fresh streams. No automatic retry. */
    public boolean reconnect() {
        close();
        return connect();
    }

    @Override public ConnectionState state() { return state; }
    public String message() { return message; }
    public InputCapabilities inputCapabilities() { return capabilities; }

    public List<StateChange> history() {
        synchronized (lifecycle) {
            return List.copyOf(history);
        }
    }

    public Statistics statistics() {
        Session current = session;
        return current == null ? new Statistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) : current.statistics();
    }

    @Override public void addStateListener(StateListener listener) { stateListeners.addIfAbsent(Objects.requireNonNull(listener)); }
    @Override public void removeStateListener(StateListener listener) { stateListeners.remove(listener); }
    @Override public void registerFrameListener(FrameListener listener) { frameListeners.addIfAbsent(Objects.requireNonNull(listener)); }
    @Override public void unregisterFrameListener(FrameListener listener) { frameListeners.remove(listener); }

    @Override
    public boolean sendInputEvent(InputEvent event) {
        Session current = session;
        return current != null && current.accept(event);
    }

    @Override
    public void close() {
        Session current;
        synchronized (lifecycle) {
            current = session;
            if ((current == null || current.stopped.get())
                    && state == ConnectionState.DISCONNECTED && "display closed".equals(message)) {
                return;
            }
            if (current != null) {
                current.stopped.set(true);
            }
            setStatus(ConnectionState.DISCONNECTED, "display closed");
        }
        if (current != null) {
            current.cancel();
        }
        notifyState(current, ConnectionState.DISCONNECTED, "display closed");
    }

    private void setStatus(ConnectionState next, String detail) {
        state = next;
        message = detail;
        history.addLast(new StateChange(next, detail, System.nanoTime()));
        while (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
    }

    private boolean current(Session candidate) {
        return session == candidate && !candidate.stopped.get();
    }

    private void status(Session candidate, ConnectionState next, String detail) {
        synchronized (lifecycle) {
            if (!current(candidate)) {
                return;
            }
            setStatus(next, detail);
        }
        notifyState(candidate, next, detail);
    }

    private void notifyState(Session candidate, ConnectionState next, String detail) {
        for (StateListener listener : stateListeners) {
            if (session != candidate || state != next || !message.equals(detail)) {
                return;
            }
            try {
                listener.onStateChanged(next, detail);
            } catch (RuntimeException ignored) {
                if (candidate != null) {
                    candidate.listenerFailures.incrementAndGet();
                }
            }
        }
    }

    private void failure(Session candidate, String detail) {
        synchronized (lifecycle) {
            if (!current(candidate)) {
                return;
            }
            candidate.stopped.set(true);
            setStatus(ConnectionState.ERROR, detail);
        }
        candidate.cancel();
        notifyState(candidate, ConnectionState.ERROR, detail);
    }

    private static final class Command {
        final byte[] bytes;
        final InputEvent event;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean succeeded;

        Command(byte[] bytes, InputEvent event) { this.bytes = bytes; this.event = event; }
    }

    private final class Session {
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicLong received = new AtomicLong();
        final AtomicLong sent = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong inputEvents = new AtomicLong();
        final AtomicLong listenerFailures = new AtomicLong();
        final ArrayBlockingQueue<Command> queue = new ArrayBlockingQueue<>(options.inputQueueCapacity() + 1);
        final Object queueLock = new Object();
        final Object statsLock = new Object();
        final RfbInputEncoder encoder = new RfbInputEncoder();
        int queuedInputs;
        volatile int width;
        volatile int height;
        final ScheduledThreadPoolExecutor timer = deadlineTimer();
        final Thread reader = daemon(this::read, "display-reader");
        final Thread writer = daemon(this::write, "display-writer");
        volatile Streams streams;
        volatile Command writing;
        volatile ScheduledFuture<?> deadline;
        volatile ScheduledFuture<?> updateDeadline;
        long frames;
        long updates;
        long rectangles;
        long pixelBytes;
        long firstFrameNs;
        long lastFrameNs;

        synchronized void start() {
            if (stopped.get()) { return; }
            deadline = timer.schedule(() -> failure(this, "display handshake timed out"), options.handshakeTimeoutMillis(), TimeUnit.MILLISECONDS);
            reader.start();
        }

        void read() {
            RfbProtocol protocol = null;
            try {
                Streams opened = Objects.requireNonNull(connector.open(), "connector streams");
                synchronized (this) {
                    if (stopped.get()) {
                        opened.close();
                        return;
                    }
                    streams = opened;
                    writer.start();
                }
                DataInputStream input = new DataInputStream(new FilterInputStream(opened.input()) {
                    @Override public int read() throws IOException {
                        int result = in.read();
                        if (result >= 0) { received.incrementAndGet(); }
                        return result;
                    }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        int result = in.read(bytes, offset, length);
                        if (result > 0) { received.addAndGet(result); }
                        return result;
                    }
                });
                protocol = RfbProtocol.negotiate(input, this::send, token);
                width = protocol.width();
                height = protocol.height();
                if (stopped.get()) { return; }
                deadline.cancel(false);
                deadline = timer.schedule(() -> failure(this, "complete display pixel frame timed out"), options.firstFrameTimeoutMillis(), TimeUnit.MILLISECONDS);
                status(this, ConnectionState.DISCONNECTED, "authenticated RFB; awaiting complete pixels; capture not verified");
                send(protocol.request());
                long completedUpdates = 0;
                long sizeGeneration = protocol.sizeGeneration();
                while (current(this)) {
                    boolean publish;
                    try {
                        publish = protocol.readMessage(this::beginUpdate);
                    } finally {
                        ScheduledFuture<?> pending = updateDeadline;
                        if (pending != null) { pending.cancel(false); }
                    }
                    width = protocol.width();
                    height = protocol.height();
                    if (sizeGeneration != protocol.sizeGeneration()) {
                        sizeGeneration = protocol.sizeGeneration();
                        deadline.cancel(false);
                        if (stopped.get()) { return; }
                        deadline = timer.schedule(() -> failure(this, "complete resized pixel frame timed out"), options.firstFrameTimeoutMillis(), TimeUnit.MILLISECONDS);
                        status(this, ConnectionState.DISCONNECTED, "display resized; awaiting complete pixels; capture not verified");
                    }
                    recordDecoder(protocol);
                    if (publish && current(this)) {
                        long timestamp = System.nanoTime();
                        FrameData frame = protocol.frame(frameIds.incrementAndGet(), timestamp);
                        synchronized (statsLock) {
                            frames++;
                            if (firstFrameNs == 0) { firstFrameNs = timestamp; }
                            lastFrameNs = timestamp;
                        }
                        deadline.cancel(false);
                        if (state != ConnectionState.CONNECTED) {
                            status(this, ConnectionState.CONNECTED, "authenticated pixel transport; compositor capture not verified");
                        }
                        for (FrameListener listener : frameListeners) {
                            if (!current(this)) { break; }
                            try {
                                listener.onFrameAvailable(frame);
                            } catch (RuntimeException ignored) {
                                listenerFailures.incrementAndGet();
                            }
                        }
                    }
                    if (protocol.updates() != completedUpdates) {
                        completedUpdates = protocol.updates();
                        send(protocol.request());
                    }
                }
            } catch (IOException failed) {
                recordDecoder(protocol);
                String detail = failed instanceof RfbProtocol.Rejected ? failed.getMessage()
                        : failed instanceof EOFException ? "display stream ended" : "display stream or connector failed";
                failure(this, detail);
            } catch (RuntimeException failed) {
                recordDecoder(protocol);
                failure(this, "display stream or connector failed");
            }
        }

        void beginUpdate() {
            if (!stopped.get()) {
                updateDeadline = timer.schedule(() -> failure(this, "display update timed out"), options.firstFrameTimeoutMillis(), TimeUnit.MILLISECONDS);
            }
        }

        void recordDecoder(RfbProtocol protocol) {
            if (protocol != null) {
                synchronized (statsLock) {
                    updates = protocol.updates();
                    rectangles = protocol.rectangles();
                    pixelBytes = protocol.pixelBytes();
                }
            }
        }

        boolean accept(InputEvent event) {
            synchronized (queueLock) {
                if (!current(this) || state != ConnectionState.CONNECTED
                        || !RfbInputEncoder.supports(event, capabilities)
                        || queuedInputs >= options.inputQueueCapacity()
                        || !queue.offer(new Command(null, event))) {
                    rejected.incrementAndGet();
                    return false;
                }
                queuedInputs++;
                return true;
            }
        }

        void send(byte[] bytes) throws IOException {
            Command command = new Command(bytes, null);
            synchronized (queueLock) {
                if (stopped.get() || !queue.offer(command)) {
                    throw new IOException("display writer unavailable");
                }
            }
            try {
                command.done.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("display writer cancelled", interrupted);
            }
            if (!command.succeeded) {
                throw new IOException("display write failed");
            }
        }

        void write() {
            try {
                while (!stopped.get()) {
                    Command command = queue.take();
                    writing = command;
                    synchronized (queueLock) {
                        if (command.event != null) { queuedInputs--; }
                    }
                    if (!stopped.get()) {
                        byte[] bytes = command.event == null ? command.bytes : encoder.encode(command.event, width, height);
                        if (bytes.length != 0) {
                            ScheduledFuture<?> writeDeadline = timer.schedule(() -> failure(this, "display write timed out"), options.firstFrameTimeoutMillis(), TimeUnit.MILLISECONDS);
                            try {
                                streams.output().write(bytes);
                                sent.addAndGet(bytes.length);
                                streams.output().flush();
                            } finally {
                                writeDeadline.cancel(false);
                            }
                        }
                        if (command.event != null) { inputEvents.incrementAndGet(); }
                        command.succeeded = true;
                    }
                    command.done.countDown();
                    writing = null;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException failed) {
                failure(this, "display writer failed");
            } finally {
                Command command = writing;
                if (command != null) { command.done.countDown(); }
                drain();
            }
        }

        void cancel() {
            timer.shutdownNow();
            reader.interrupt();
            writer.interrupt();
            Streams opened;
            synchronized (this) { opened = streams; }
            if (opened != null) { opened.close(); }
            Command command = writing;
            if (command != null) { command.done.countDown(); }
            drain();
        }

        private void drain() {
            synchronized (queueLock) {
                Command command;
                while ((command = queue.poll()) != null) {
                    if (command.event != null) { queuedInputs--; }
                    command.done.countDown();
                }
            }
        }

        Statistics statistics() {
            synchronized (statsLock) {
                return new Statistics(frames, updates, rectangles, pixelBytes, received.get(), sent.get(),
                        inputEvents.get(), rejected.get(), listenerFailures.get(), firstFrameNs, lastFrameNs);
            }
        }
    }

    private static ScheduledThreadPoolExecutor deadlineTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> daemon(task, "display-deadline"));
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return timer;
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }
}
