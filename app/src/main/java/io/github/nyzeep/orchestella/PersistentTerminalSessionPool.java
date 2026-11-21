package io.github.nyzeep.orchestella;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import io.github.nyzeep.orchestella.RuntimeProcessSupervisor.StopResult;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Thread-safe deep module for managing long-lived Linux terminal sessions.
 *
 * <p>Decouples terminal I/O lifecycles from ephemeral Android Activities. Ensures that
 * Activity destruction and re-entry never interrupt underlying guest shell processes,
 * while multiple UI subscribers can safely attach and detach without stream contention.
 */
public final class PersistentTerminalSessionPool {

    public enum StopIntent { NONE, GRACEFUL, FORCE }

    /** Receives the one-time guest Provisioning marker observed on a session's output stream. */
    @FunctionalInterface
    public interface ProvisioningListener {
        /**
         * Called once on the pump thread after the guest marker was fully observed.
         *
         * @param instanceId the owning instance
         * @param markerVersion the accepted marker version
         */
        void onProvisioningComplete(String instanceId, int markerVersion);
    }

    /**
     * W1-05 (T-14): lightweight monotonic-clock hooks along the write path, for the
     * Wave 2 real-PTY load tests (input submit → write completion → pump/model
     * advance). Every timestamp is a {@link System#nanoTime()} value — the same
     * CLOCK_MONOTONIC family as Android's {@code SystemClock.elapsedRealtimeNanos()},
     * but also legal inside plain JVM unit tests. A probe is optional: with no probe
     * set the hooks cost zero allocations and no extra clock reads.
     */
    public interface IoProbe {
        /** A write (user input or protocol reply) entered the bounded write queue. */
        void onWriteSubmitted(long monotonicNanos, int bytes, int queueDepth);

        /** The writer thread flushed one queued write to the PTY input stream. */
        void onWriteCompleted(long monotonicNanos, int bytes, long writeDurationNanos);

        /** A write was dropped: the queue was saturated or the PTY input stream failed. */
        void onWriteDropped(long monotonicNanos, int bytes, int queueDepth);

        /** The pump thread appended a decoded chunk to the canonical screen model. */
        void onPumpChunkAppended(long monotonicNanos, int chars);
    }

    private static final PersistentTerminalSessionPool INSTANCE = new PersistentTerminalSessionPool();
    private static final long RUNTIME_STOP_GRACE_MS = 2000L;
    /**
     * W1-05 (T-14): bounded async write queue. Capacity bounds the backlog when a
     * guest stops reading its PTY stdin; the dedicated writer thread is the only
     * place that performs blocking {@code out.write()/out.flush()}, so a stalled
     * guest can never deadlock the pump loop, the screen model or the UI.
     */
    private static final int WRITE_QUEUE_CAPACITY = 128;
    /** How long user input waits for queue room before failing with IOException. */
    private static final long WRITE_ENQUEUE_WAIT_MS = 1_000L;
    /** Writer-thread idle poll. Bounds how fast an idle writer notices close(); a writer blocked inside a write is retired only by the stream failing or the process exiting. */
    private static final long WRITE_POLL_MS = 250L;
    /**
     * How long a caller waits for another thread's in-flight stop before
     * treating the runtime as unconfirmed. A supervisor stop is bounded
     * (grace + force wait + channel close + gateway), so this only protects
     * against a misbehaving stop implementation.
     */
    private static final long STOP_COMPLETION_WAIT_MS = 30_000L;

    public static PersistentTerminalSessionPool getInstance() {
        return INSTANCE;
    }

    public static final class SessionHolder {
        private final String instanceId;
        private final ExecutionSession session;
        private final String generation;
        private volatile StopIntent stopIntent = StopIntent.NONE;
        private final TerminalRingBuffer ringBuffer;
        private final io.github.nyzeep.orchestella.terminal.TerminalScreenModel screenModel =
                new io.github.nyzeep.orchestella.terminal.TerminalScreenModel(80, 24);

        /** The emulator lives with the shell, so reattachment never replays a truncated VT stream. */
        public io.github.nyzeep.orchestella.terminal.TerminalScreenModel getScreenModel() { return screenModel; }
        private final Set<Consumer<String>> outputListeners = new CopyOnWriteArraySet<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean pumpFinished = new AtomicBoolean(false);
        /**
         * Retirement handshake. {@code stopping} means one thread is inside a
         * bounded runtime stop for this holder; {@code stopMonitor} lets other
         * callers wait for that stop to settle without holding the per-instance
         * transition lock. Both fields are only mutated while the instance
         * transition lock is held (the monitor makes the transition visible to
         * waiters that deliberately do not hold it).
         */
        private final Object stopMonitor = new Object();
        private boolean stopping;
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        private final Thread pumpThread;
        /**
         * W1-05 (T-14): bounded FIFO write queue drained by {@link #writeThread}.
         * User input and protocol replies share it so the byte order to the PTY
         * stays consistent, and the blocking write never runs under the rwLock or
         * the screen model monitor (the old deadlock vector: a full guest PTY
         * stdin froze pumpLoop, attach(), detach() and displaySnapshot() at once).
         */
        private final ArrayBlockingQueue<byte[]> writeQueue = new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY);
        private final Thread writeThread;
        /**
         * W1-05 (T-14): set when a write to the PTY input failed. The writer thread
         * keeps draining the queue (every stranded job is reported as dropped) but
         * no longer touches the stream, and new submissions fail fast instead of
         * piling up behind a dead pipe until the queue fills.
         */
        private volatile boolean writeStreamBroken;
        private volatile IoProbe ioProbe;
        private final Object endListenerLock = new Object();
        private final ProvisioningListener provisioningListener;
        private final AtomicBoolean provisioningNotified = new AtomicBoolean(false);
        private Consumer<String> sessionEndListener;
        private boolean streamEndDelivered;
        private boolean ownerEndDelivered;

        public SessionHolder(String instanceId, ExecutionSession session) {
            this(instanceId, session, TerminalRingBuffer.DEFAULT_MAX_CAPACITY);
        }

        public SessionHolder(String instanceId, ExecutionSession session, int ringBufferCapacity) {
            this(instanceId, session, ringBufferCapacity, null);
        }

        public SessionHolder(
                String instanceId,
                ExecutionSession session,
                int ringBufferCapacity,
                ProvisioningListener provisioningListener) {
            this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
            this.session = Objects.requireNonNull(session, "session");
            this.provisioningListener = provisioningListener;
            String launching = InstanceLaunchGuard.generation(instanceId);
            this.generation = launching != null ? launching : java.util.UUID.randomUUID().toString();
            this.ringBuffer = new TerminalRingBuffer(ringBufferCapacity);
            // W1-05 (T-14): protocol replies go through the bounded write queue and
            // never block. The pump thread calls this sink from inside the screen
            // model monitor and the rwLock write lock, so a synchronous write here
            // (the old sendBytes) would deadlock the whole terminal against a guest
            // that stopped reading its PTY stdin.
            screenModel.setResponseSink(response -> enqueueResponseBytes(response.getBytes(StandardCharsets.UTF_8)));

            this.pumpThread = new Thread(this::pumpLoop, "pty-term-pump-" + instanceId);
            this.pumpThread.setDaemon(true);
            this.pumpThread.start();
            this.writeThread = new Thread(this::writeLoop, "pty-term-writer-" + instanceId);
            this.writeThread.setDaemon(true);
            this.writeThread.start();
        }

        /**
         * Reports terminal completion and later owner retirement once each per binding.
         * EOF alone is not a VM exit. Callers must hop to their UI thread and recheck
         * the holder/generation before reconciling either event.
         */
        public void setSessionEndListener(java.util.function.Consumer<String> listener) {
            synchronized (endListenerLock) {
                sessionEndListener = listener;
                streamEndDelivered = false;
                ownerEndDelivered = false;
            }
            notifySessionEnded();
        }

        private void notifySessionEnded() {
            Consumer<String> listener;
            int events = 0;
            synchronized (endListenerLock) {
                listener = sessionEndListener;
                if (listener == null) return;
                if (pumpFinished.get() && !streamEndDelivered) {
                    streamEndDelivered = true;
                    events++;
                }
                if (closed.get() && !ownerEndDelivered) {
                    ownerEndDelivered = true;
                    events++;
                }
                if (closed.get()) sessionEndListener = null;
            }
            for (int i = 0; i < events; i++) {
                try {
                    listener.accept(instanceId);
                } catch (Exception ignored) {
                    // Observer isolation: one broken session-end listener must not take down
                    // the bookkeeping around this one-shot notification.
                }
            }
        }

        private void pumpLoop() {
            ProvisioningSignal provisioningSignal = new ProvisioningSignal();
            // W1-04 (R-08): the same live console stream carries the shell
            // handover markers, so the launch timeline's T0 → SHELL_READY chain
            // closes from the only host-side point that observes the guest
            // console from the first boot byte. Best-effort: engines without a
            // timeline answer markShellReady() as a no-op.
            ShellHandoverSignal shellHandover = new ShellHandoverSignal();
            boolean reachedEof = false;
            try {
                // One decoder owns the whole stream: read boundaries may split a UTF-8
                // character (including CJK and supplementary emoji) at any byte.
                InputStreamReader in = new InputStreamReader(session.terminalOutput(), StandardCharsets.UTF_8);
                char[] buffer = new char[4096];
                while (!closed.get()) {
                    int count = in.read(buffer);
                    if (count < 0) {
                        reachedEof = true;
                        break;
                    }
                    if (count == 0) continue;
                    String chunk = new String(buffer, 0, count);
                    boolean provisioned = provisioningSignal.accept(chunk);
                    if (shellHandover.accept(chunk)) {
                        session.markShellReady();
                    }
                    // Append and deliver under one write lock so attach() snapshots can
                    // never overlap delivery: a chunk is either in the snapshot or handed
                    // to the listener, never both and never neither.
                    rwLock.writeLock().lock();
                    try {
                        ringBuffer.append(chunk);
                        screenModel.append(chunk);
                        for (Consumer<String> listener : outputListeners) {
                            try {
                                listener.accept(chunk);
                            } catch (Exception ignored) {
                                // Observer isolation: one broken output listener must not break
                                // chunk delivery to the others, nor the ring buffer append.
                            }
                        }
                    } finally {
                        rwLock.writeLock().unlock();
                    }
                    // W1-05 (T-14): the canonical screen model advanced by `count` chars.
                    IoProbe pumpProbe = ioProbe;
                    if (pumpProbe != null) pumpProbe.onPumpChunkAppended(System.nanoTime(), count);
                    // Persisting evidence touches the filesystem; never do that while the
                    // terminal write lock is held.
                    if (provisioned) notifyProvisioning(ProvisioningSignal.MARKER_VERSION);
                }
            } catch (Exception pumpFailure) {
                // A normal EOF leaves via break above, so anything landing here is an abnormal
                // pump death; without a record the session just ends with no reason trail.
                android.util.Log.d("TerminalPump", "pty pump ended abnormally: "
                        + pumpFailure.getClass().getSimpleName());
            } finally {
                // Only a real end of stream can serve as the marker's trailing delimiter;
                // a host-side close is not evidence that the guest finished Provisioning.
                if (reachedEof && provisioningSignal.finish()) {
                    notifyProvisioning(ProvisioningSignal.MARKER_VERSION);
                }
                // W1-04 (R-08): the same end-of-stream rule closes a handover marker
                // whose trailing whitespace never arrived.
                if (reachedEof && shellHandover.finish()) {
                    session.markShellReady();
                }
                pumpFinished.set(true);
                notifySessionEnded();
            }
        }

        private void notifyProvisioning(int markerVersion) {
            if (!provisioningNotified.compareAndSet(false, true)) {
                return;
            }
            ProvisioningListener listener = provisioningListener;
            if (listener == null) {
                return;
            }
            try {
                listener.onProvisioningComplete(instanceId, markerVersion);
            } catch (Exception ignored) {
                // Observer isolation: the provisioning listener is UI-side; its failure must
                // not affect the pump bookkeeping around this one-shot notification.
            }
        }

        /**
         * Attaches a UI listener to receive ongoing terminal output chunks.
         *
         * @param listener callback for live terminal output
         * @return recent transcript snapshot for immediate replay
         */
        public String attach(Consumer<String> listener) {
            rwLock.writeLock().lock();
            try {
                if (listener != null) {
                    outputListeners.add(listener);
                }
                return ringBuffer.snapshot();
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        /**
         * Detaches a UI listener. Does not stop background I/O or terminate the session.
         */
        public void detach(Consumer<String> listener) {
            rwLock.writeLock().lock();
            try {
                if (listener != null) {
                    outputListeners.remove(listener);
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        /**
         * W1-05 (T-14): enqueues user input into the same bounded FIFO write queue
         * the protocol replies use, so the byte order to the PTY stays consistent
         * while the blocking write runs on the dedicated writer thread — never under
         * the rwLock or the screen model monitor. A saturated queue (guest stopped
         * reading its PTY stdin) fails with {@link IOException} after a bounded wait
         * instead of blocking the caller indefinitely; a broken PTY input stream
         * fails fast for every later submission.
         */
        public void sendBytes(byte[] bytes) throws IOException {
            if (!isAlive()) {
                throw new IOException("Terminal stream has ended for instance " + instanceId);
            }
            if (bytes == null || bytes.length == 0) {
                return;
            }
            if (writeStreamBroken) {
                throw new IOException("Terminal input stream failed for instance " + instanceId);
            }
            if (!offerToWriteQueue(bytes, true)) {
                throw new IOException("Terminal write queue saturated for instance " + instanceId);
            }
        }

        /**
         * W1-05 (T-14): protocol replies (CSI DSR/DA answers) are best-effort and
         * must never block the pump thread, which calls this sink while holding the
         * screen model monitor and the rwLock write lock. A full queue or a broken
         * PTY input stream drops the reply instead of deadlocking the read path;
         * the pump keeps draining the guest output either way.
         */
        private void enqueueResponseBytes(byte[] bytes) {
            if (writeStreamBroken) {
                IoProbe probe = ioProbe;
                if (probe != null) probe.onWriteDropped(System.nanoTime(), bytes.length, writeQueue.size());
                return;
            }
            try {
                offerToWriteQueue(bytes, false);
            } catch (IOException unreachable) {
                // A non-blocking offer never waits, so it cannot be interrupted;
                // treat any IOException defensively as a drop so the pump thread
                // never dies on an unexpected failure.
                IoProbe probe = ioProbe;
                if (probe != null) probe.onWriteDropped(System.nanoTime(), bytes.length, writeQueue.size());
            }
        }

        /**
         * Shared enqueue primitive: offers the bytes to the bounded write queue and
         * reports the outcome on the probe. The timestamp is taken after the offer
         * settles, so {@code onWriteSubmitted} consistently means "entered the
         * queue". Returns false when the queue had no room; the caller decides
         * whether that is a drop (protocol replies) or a failure (user input).
         */
        private boolean offerToWriteQueue(byte[] bytes, boolean waitForRoom) throws IOException {
            boolean accepted;
            if (waitForRoom) {
                try {
                    accepted = writeQueue.offer(bytes, WRITE_ENQUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while enqueueing terminal input for instance " + instanceId);
                }
            } else {
                accepted = writeQueue.offer(bytes);
            }
            IoProbe probe = ioProbe;
            if (accepted) {
                if (probe != null) probe.onWriteSubmitted(System.nanoTime(), bytes.length, writeQueue.size());
            } else if (probe != null) {
                probe.onWriteDropped(System.nanoTime(), bytes.length, writeQueue.size());
            }
            return accepted;
        }

        /** Sets (or clears with {@code null}) the diagnostic probe for this holder. */
        public void setIoProbe(IoProbe probe) {
            this.ioProbe = probe;
        }

        /**
         * W1-05 (T-14): the only place that performs blocking PTY input writes.
         * It holds no rwLock and no screen model monitor, so a stalled guest PTY
         * stdin blocks at most this daemon thread: the pump keeps decoding output,
         * and attach()/detach()/displaySnapshot() stay responsive. Single consumer
         * means FIFO delivery preserves the enqueue order between keyboard input
         * and protocol replies. A failed write marks the stream broken and stops
         * touching it — the thread then keeps draining the queue so every stranded
         * and later submission is reported as dropped, never silently lost behind a
         * dead pipe (the pump EOF closes the holder and ends the loop).
         */
        private void writeLoop() {
            while (true) {
                byte[] bytes;
                try {
                    bytes = writeQueue.poll(WRITE_POLL_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    continue;
                }
                if (bytes == null) {
                    if (closed.get() && writeQueue.isEmpty()) return;
                    continue;
                }
                if (writeStreamBroken) {
                    IoProbe probe = ioProbe;
                    if (probe != null) probe.onWriteDropped(System.nanoTime(), bytes.length, writeQueue.size());
                    continue;
                }
                try {
                    OutputStream out = session.terminalInput();
                    if (out == null) continue;
                    IoProbe probe = ioProbe;
                    long start = probe != null ? System.nanoTime() : 0L;
                    synchronized (out) {
                        out.write(bytes);
                        out.flush();
                    }
                    if (probe != null) {
                        probe.onWriteCompleted(System.nanoTime(), bytes.length, System.nanoTime() - start);
                    }
                } catch (IOException | RuntimeException writeFailure) {
                    // The PTY input stream is gone (or the guest stalled until the
                    // socket died). The pump loop surfaces the session end. Keep the
                    // failure visible on the probe (no android.util dependency: this
                    // thread also runs inside plain JVM unit tests).
                    writeStreamBroken = true;
                    IoProbe probe = ioProbe;
                    if (probe != null) probe.onWriteDropped(System.nanoTime(), bytes.length, writeQueue.size());
                }
            }
        }

        public String getTranscript() {
            return ringBuffer.snapshot();
        }

        public boolean isAlive() {
            return !closed.get() && !pumpFinished.get();
        }

        /** The registered VM owner remains authoritative even after terminal EOF. */
        public boolean hasOwner() {
            return !closed.get();
        }

        public ExecutionSession rawSession() {
            return session;
        }

        public String generation() { return generation; }

        public String instanceId() {
            return instanceId;
        }

        public int subscriberCount() {
            return outputListeners.size();
        }

        /** Marks one bounded stop as owned by the caller; false when one is already in flight. */
        private boolean tryBeginStop() {
            synchronized (stopMonitor) {
                if (stopping) return false;
                stopping = true;
                return true;
            }
        }

        /** Publishes the stop outcome and wakes every transition waiter. */
        private void finishStop() {
            synchronized (stopMonitor) {
                stopping = false;
                stopMonitor.notifyAll();
            }
        }

        private boolean isStopping() {
            synchronized (stopMonitor) {
                return stopping;
            }
        }

        /**
         * Waits for the in-flight stop to settle. Must be called without the
         * instance transition lock so the stop owner can reacquire it to publish
         * the outcome. Returns false on timeout or interruption.
         */
        private boolean awaitStopFinished(long timeoutMs) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            boolean interrupted = false;
            synchronized (stopMonitor) {
                while (stopping) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        if (interrupted) Thread.currentThread().interrupt();
                        return false;
                    }
                    try {
                        stopMonitor.wait(
                                Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return true;
        }

        public void close() {
            if (closed.compareAndSet(false, true)) {
                rwLock.writeLock().lock();
                try {
                    outputListeners.clear();
                } finally {
                    rwLock.writeLock().unlock();
                }
                try {
                    session.close();
                } catch (Exception ignored) {
                    // Close is best-effort: the holder is already marked closed above, and the
                    // end notification below must run regardless of how the close ended.
                }
                notifySessionEnded();
            }
        }
    }

    private final ConcurrentMap<String, SessionHolder> holders = new ConcurrentHashMap<>();
    /**
     * Per-instance transition locks, never held while mutating another instance.
     * They serialize ownership transitions for one instance, but the bounded
     * runtime stop itself runs outside them: a {@link SessionHolder} STOPPING
     * marker keeps the old owner published until the stop settles, and waiters
     * block on the holder's stop monitor instead of the transition lock. A slow
     * stop therefore cannot block another instance, and it cannot block a fast
     * request-stop probe for its own instance. Entries are retained for the
     * process lifetime; there is one small lock per instance id.
     */
    private final ConcurrentMap<String, ReentrantLock> transitions = new ConcurrentHashMap<>();

    public PersistentTerminalSessionPool() {}

    private ReentrantLock transition(String instanceId) {
        return transitions.computeIfAbsent(instanceId, id -> new ReentrantLock());
    }

    public SessionHolder register(String instanceId, ExecutionSession session) throws IOException {
        return register(instanceId, session, null);
    }

    public SessionHolder register(
            String instanceId,
            ExecutionSession session,
            ProvisioningListener provisioningListener) throws IOException {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(session, "session");
        ReentrantLock transition = transition(instanceId);
        while (true) {
            SessionHolder existing;
            boolean ownsStop = false;
            transition.lock();
            try {
                existing = holders.get(instanceId);
                if (existing == null) {
                    return publish(instanceId, session, provisioningListener);
                }
                if (existing.rawSession() == session) return existing;
                // Never publish a second owner while the previous runtime may still
                // be alive: a fixed gvproxy port and the root disk belong to the old
                // owner. Only one thread may run that bounded stop.
                ownsStop = existing.tryBeginStop();
            } finally {
                transition.unlock();
            }

            if (!ownsStop) {
                // Another thread owns the stop. Wait outside the transition lock so
                // its bounded stop can reacquire the lock and publish the outcome.
                if (!existing.awaitStopFinished(STOP_COMPLETION_WAIT_MS)) {
                    throw new IOException("previous runtime session did not confirm stop");
                }
                continue;
            }

            boolean confirmed;
            try {
                confirmed = stopOwnedRuntime(existing);
            } catch (RuntimeException | Error failure) {
                completeStop(transition, instanceId, existing, false, null, null);
                throw failure;
            }
            if (!confirmed) {
                completeStop(transition, instanceId, existing, false, null, null);
                throw new IOException("previous runtime session did not confirm stop");
            }
            // Remove the old owner, close it, wake waiters and publish the replacement
            // under one lock section: a waiter must never observe a gap in which a
            // third owner could be published between retirement and replacement.
            return completeStop(transition, instanceId, existing, true, session, provisioningListener);
        }
    }

    /** Publishes a new holder; called with the instance transition lock held. */
    private SessionHolder publish(String instanceId, ExecutionSession session,
            ProvisioningListener provisioningListener) {
        SessionHolder holder = new SessionHolder(instanceId, session,
                TerminalRingBuffer.DEFAULT_MAX_CAPACITY, provisioningListener);
        holders.put(instanceId, holder);
        return holder;
    }

    /**
     * Publishes a stop outcome under the instance transition lock. A confirmed
     * stop retires the old holder (optionally publishing a replacement in the
     * same critical section); an unconfirmed stop keeps it registered and only
     * clears the STOPPING marker so a later caller may retry.
     */
    private SessionHolder completeStop(ReentrantLock transition, String instanceId,
            SessionHolder holder, boolean confirmed, ExecutionSession replacement,
            ProvisioningListener provisioningListener) {
        transition.lock();
        try {
            if (!confirmed) {
                holder.finishStop();
                return null;
            }
            holders.remove(instanceId, holder);
            try {
                holder.close();
            } finally {
                holder.finishStop();
            }
            return replacement == null ? null
                    : publish(instanceId, replacement, provisioningListener);
        } finally {
            transition.unlock();
        }
    }

    @FunctionalInterface
    public interface ExitAction {
        void run() throws IOException;
    }

    /** Serializes confirmed exit side effects with replacement registration. */
    public boolean completeExit(String instanceId, ExecutionSession expected,
            ExitAction action) throws IOException {
        ReentrantLock transition = transition(instanceId);
        transition.lock();
        try {
            SessionHolder current = holders.get(instanceId);
            if (current == null || current.rawSession() != expected
                    || current.isStopping()
                    || InstanceLaunchGuard.isSuperseded(instanceId, current.generation())) return false;
            action.run();
            holders.remove(instanceId, current);
            current.close();
            return true;
        } finally {
            transition.unlock();
        }
    }

    /** Executes a fixed caller-supplied power action only for the expected owner. */
    public boolean requestStop(String instanceId, ExecutionSession expected,
            StopIntent intent, ExitAction action) throws IOException {
        ReentrantLock transition = transition(instanceId);
        transition.lock();
        try {
            SessionHolder current = holders.get(instanceId);
            if (current == null || current.rawSession() != expected) return false;
            // Retirement is already in flight and owns the shutdown. Do not wait for
            // it (the caller may be the one that must release its gate) and do not
            // run a second power action against a runtime that is being stopped.
            if (current.isStopping()) return false;
            if (intent == null || intent == StopIntent.NONE) throw new IllegalArgumentException("stop intent required");
            if (current.stopIntent == StopIntent.FORCE || current.stopIntent == intent) return true;
            // The transition lock also gates confirmed completion, so a synchronous
            // exit cannot overtake the accepted action. Failed/rejected actions leave
            // no new intent.
            action.run();
            current.stopIntent = intent;
            return true;
        } finally {
            transition.unlock();
        }
    }

    public StopIntent stopIntent(String instanceId, ExecutionSession expected) {
        SessionHolder current = holders.get(instanceId);
        return current != null && current.rawSession() == expected ? current.stopIntent : StopIntent.NONE;
    }

    /** Retires only this owner. A delayed caller cannot close a replacement. */
    public boolean unregister(String instanceId, ExecutionSession expected) {
        ReentrantLock transition = transition(instanceId);
        SessionHolder current;
        boolean ownsStop;
        transition.lock();
        try {
            current = holders.get(instanceId);
            if (current == null || current.rawSession() != expected) return false;
            ownsStop = current.tryBeginStop();
        } finally {
            transition.unlock();
        }

        if (!ownsStop) {
            // The in-flight stop may retire this owner and publish a replacement.
            // Waiting is required, but this delayed caller must never delete or
            // stop that replacement.
            current.awaitStopFinished(STOP_COMPLETION_WAIT_MS);
            return false;
        }

        boolean confirmed;
        try {
            confirmed = stopOwnedRuntime(current);
        } catch (RuntimeException | Error failure) {
            completeStop(transition, instanceId, current, false, null, null);
            throw failure;
        }
        completeStop(transition, instanceId, current, confirmed, null, null);
        return confirmed;
    }

    public SessionHolder getHolder(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        return holders.get(instanceId);
    }

    public ExecutionSession getSession(String instanceId) {
        SessionHolder holder = getHolder(instanceId);
        return holder != null ? holder.rawSession() : null;
    }

    public boolean hasActiveSession(String instanceId) {
        SessionHolder holder = getHolder(instanceId);
        return holder != null && holder.isAlive();
    }

    public String attach(String instanceId, Consumer<String> listener) {
        SessionHolder holder = getHolder(instanceId);
        if (holder != null) {
            return holder.attach(listener);
        }
        return null;
    }

    public void detach(String instanceId, Consumer<String> listener) {
        SessionHolder holder = getHolder(instanceId);
        if (holder != null) {
            holder.detach(listener);
        }
    }

    public void sendBytes(String instanceId, byte[] bytes) throws IOException {
        SessionHolder holder = getHolder(instanceId);
        if (holder != null) {
            holder.sendBytes(bytes);
        } else {
            throw new IOException("No active session holder for instance " + instanceId);
        }
    }

    public void unregister(String instanceId) {
        if (instanceId == null) return;
        retire(instanceId, null);
    }

    public void clear() {
        // Snapshot first: a stop may be slow, and holding a pool-wide monitor for it
        // would block registers on unrelated instances. Holders registered after the
        // snapshot keep their own transition lock and stay owned.
        for (SessionHolder holder : new java.util.ArrayList<>(holders.values())) {
            retire(holder.instanceId(), holder);
        }
    }

    /**
     * Retires one holder with the actual bounded stop outside the transition
     * lock. A concurrent stop is awaited and then re-inspected: a confirmed
     * stop has already removed the holder, while an unconfirmed stop leaves it
     * registered for this caller to retry. Passing a null expected holder means
     * "the owner current when the lock is acquired"; clear() passes a snapshot
     * so a holder published after the snapshot is not touched.
     */
    private boolean retire(String instanceId, SessionHolder expected) {
        ReentrantLock transition = transition(instanceId);
        while (true) {
            SessionHolder current;
            boolean ownsStop;
            transition.lock();
            try {
                current = holders.get(instanceId);
                if (current == null || (expected != null && current != expected)) return false;
                ownsStop = current.tryBeginStop();
            } finally {
                transition.unlock();
            }

            if (!ownsStop) {
                if (!current.awaitStopFinished(STOP_COMPLETION_WAIT_MS)) return false;
                continue;
            }

            boolean confirmed;
            try {
                confirmed = stopOwnedRuntime(current);
            } catch (RuntimeException | Error failure) {
                completeStop(transition, instanceId, current, false, null, null);
                throw failure;
            }
            completeStop(transition, instanceId, current, confirmed, null, null);
            return confirmed;
        }
    }

    /**
     * Stops a runtime owner with a bounded wait. A session that cannot be
     * confirmed as stopped (including one that does not expose the owner seam)
     * keeps its holder; "legacy" is not evidence of a stop (ADR-0021).
     */
    private static boolean stopOwnedRuntime(SessionHolder holder) {
        ExecutionSession owned = holder.rawSession();
        if (owned instanceof RuntimeOwnedSession) {
            return ((RuntimeOwnedSession) owned).stopRuntime(RUNTIME_STOP_GRACE_MS)
                    != StopResult.UNCONFIRMED;
        }
        return false;
    }
}
