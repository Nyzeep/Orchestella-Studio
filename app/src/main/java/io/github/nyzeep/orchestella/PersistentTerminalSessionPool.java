package io.github.nyzeep.orchestella;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import io.github.nyzeep.orchestella.RuntimeProcessSupervisor.StopResult;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
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

    private static final PersistentTerminalSessionPool INSTANCE = new PersistentTerminalSessionPool();
    private static final long RUNTIME_STOP_GRACE_MS = 2000L;

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
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        private final Thread pumpThread;
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
            screenModel.setResponseSink(response -> {
                try { sendBytes(response.getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
            });

            this.pumpThread = new Thread(this::pumpLoop, "pty-term-pump-" + instanceId);
            this.pumpThread.setDaemon(true);
            this.pumpThread.start();
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
                try { listener.accept(instanceId); } catch (Exception ignored) { }
            }
        }

        private void pumpLoop() {
            ProvisioningSignal provisioningSignal = new ProvisioningSignal();
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
                            }
                        }
                    } finally {
                        rwLock.writeLock().unlock();
                    }
                    // Persisting evidence touches the filesystem; never do that while the
                    // terminal write lock is held.
                    if (provisioned) notifyProvisioning(ProvisioningSignal.MARKER_VERSION);
                }
            } catch (Exception ignored) {
            } finally {
                // Only a real end of stream can serve as the marker's trailing delimiter;
                // a host-side close is not evidence that the guest finished Provisioning.
                if (reachedEof && provisioningSignal.finish()) {
                    notifyProvisioning(ProvisioningSignal.MARKER_VERSION);
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

        public void sendBytes(byte[] bytes) throws IOException {
            if (!isAlive()) {
                throw new IOException("Terminal stream has ended for instance " + instanceId);
            }
            OutputStream out = session.terminalInput();
            if (out != null && bytes != null && bytes.length > 0) {
                synchronized (out) {
                    out.write(bytes);
                    out.flush();
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
                }
                notifySessionEnded();
            }
        }
    }

    private final ConcurrentMap<String, SessionHolder> holders = new ConcurrentHashMap<>();
    /**
     * Per-instance transition locks, never held while mutating another instance.
     * Stops run under this lock and outside any pool-wide monitor, so a slow stop
     * cannot block operations on other instances while it still serializes
     * registration, retirement and ownership changes for its own instance. Entries
     * are retained for the process lifetime; there is one small lock per instance id.
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
        transition.lock();
        try {
            SessionHolder existing = holders.get(instanceId);
            if (existing != null && existing.rawSession() == session) return existing;
            if (existing != null) {
                // Never publish a second owner while the previous runtime may still be
                // alive: a fixed gvproxy port and the root disk belong to the old owner.
                // The bounded stop happens here, outside any pool-wide monitor, so
                // other instances keep progressing.
                if (!stopOwnedRuntime(existing)) {
                    throw new IOException("previous runtime session did not confirm stop");
                }
                existing.close();
                holders.remove(instanceId, existing);
            }
            SessionHolder holder = new SessionHolder(instanceId, session,
                    TerminalRingBuffer.DEFAULT_MAX_CAPACITY, provisioningListener);
            holders.put(instanceId, holder);
            return holder;
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
        transition.lock();
        try {
            SessionHolder current = holders.get(instanceId);
            if (current == null || current.rawSession() != expected) return false;
            if (!stopOwnedRuntime(current)) return false;
            holders.remove(instanceId, current);
            current.close();
            return true;
        } finally {
            transition.unlock();
        }
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
        ReentrantLock transition = transition(instanceId);
        transition.lock();
        try {
            SessionHolder removed = holders.get(instanceId);
            if (removed == null || !stopOwnedRuntime(removed)) return;
            holders.remove(instanceId, removed);
            removed.close();
        } finally {
            transition.unlock();
        }
    }

    public void clear() {
        // Snapshot first: a stop may be slow, and holding a pool-wide monitor for it
        // would block registers on unrelated instances. Holders registered after the
        // snapshot keep their own transition lock and stay owned.
        for (String instanceId : new java.util.ArrayList<>(holders.keySet())) {
            ReentrantLock transition = transition(instanceId);
            transition.lock();
            try {
                SessionHolder holder = holders.get(instanceId);
                if (holder == null || !stopOwnedRuntime(holder)) continue;
                holders.remove(instanceId, holder);
                holder.close();
            } finally {
                transition.unlock();
            }
        }
    }

    /** Stops a runtime owner with a bounded wait; legacy sessions keep close-only. */
    private static boolean stopOwnedRuntime(SessionHolder holder) {
        ExecutionSession owned = holder.rawSession();
        if (owned instanceof RuntimeOwnedSession) {
            return ((RuntimeOwnedSession) owned).stopRuntime(RUNTIME_STOP_GRACE_MS)
                    != StopResult.UNCONFIRMED;
        }
        return true;
    }
}
