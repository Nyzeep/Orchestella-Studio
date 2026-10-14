package io.github.nyzeep.orchestella;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final PersistentTerminalSessionPool INSTANCE = new PersistentTerminalSessionPool();

    public static PersistentTerminalSessionPool getInstance() {
        return INSTANCE;
    }

    public static final class SessionHolder {
        private final String instanceId;
        private final ExecutionSession session;
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
        private final java.util.concurrent.atomic.AtomicReference<Consumer<String>> sessionEndListener =
                new java.util.concurrent.atomic.AtomicReference<>();

        public SessionHolder(String instanceId, ExecutionSession session) {
            this(instanceId, session, TerminalRingBuffer.DEFAULT_MAX_CAPACITY);
        }

        public SessionHolder(String instanceId, ExecutionSession session, int ringBufferCapacity) {
            this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
            this.session = Objects.requireNonNull(session, "session");
            this.ringBuffer = new TerminalRingBuffer(ringBufferCapacity);
            screenModel.setResponseSink(response -> {
                try { sendBytes(response.getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
            });

            this.pumpThread = new Thread(this::pumpLoop, "pty-term-pump-" + instanceId);
            this.pumpThread.setDaemon(true);
            this.pumpThread.start();
        }

        /**
         * Registers a one-shot callback fired when the underlying stream pump ends
         * (guest exit or stream failure). Invoked once, on the pump thread; callers
         * must hop to their own UI thread.
         */
        public void setSessionEndListener(java.util.function.Consumer<String> listener) {
            sessionEndListener.set(listener);
            if (pumpFinished.get()) notifySessionEnded();
        }

        private void notifySessionEnded() {
            Consumer<String> listener = sessionEndListener.getAndSet(null);
            if (listener != null) {
                try { listener.accept(instanceId); } catch (Exception ignored) { }
            }
        }

        private void pumpLoop() {
            try {
                // One decoder owns the whole stream: read boundaries may split a UTF-8
                // character (including CJK and supplementary emoji) at any byte.
                InputStreamReader in = new InputStreamReader(session.terminalOutput(), StandardCharsets.UTF_8);
                char[] buffer = new char[4096];
                int count;
                while (!closed.get() && (count = in.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    String chunk = new String(buffer, 0, count);
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
                }
            } catch (Exception ignored) {
            } finally {
                pumpFinished.set(true);
                notifySessionEnded();
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

        public ExecutionSession rawSession() {
            return session;
        }

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
            }
        }
    }

    private final ConcurrentMap<String, SessionHolder> holders = new ConcurrentHashMap<>();

    public PersistentTerminalSessionPool() {}

    public SessionHolder register(String instanceId, ExecutionSession session) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(session, "session");
        SessionHolder existing = holders.get(instanceId);
        if (existing != null) {
            existing.close();
        }
        SessionHolder holder = new SessionHolder(instanceId, session);
        holders.put(instanceId, holder);
        return holder;
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
        if (instanceId != null) {
            SessionHolder removed = holders.remove(instanceId);
            if (removed != null) {
                removed.close();
            }
        }
    }

    public void clear() {
        for (SessionHolder holder : holders.values()) {
            holder.close();
        }
        holders.clear();
    }
}
