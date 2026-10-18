package io.github.nyzeep.orchestella;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * In-process registry and lifecycle facade over {@link PersistentTerminalSessionPool}.
 *
 * <p>Preserves session state across Activity re-entries (ADR-0002, US-26, Ticket 07).
 * Backed by {@link PersistentTerminalSessionPool} so that session state and transcript
 * buffers outlive ephemeral Activities and are guarded against stream contention.
 */
public final class ExecutionSessionRegistry {

    public static final class LiveSession {
        private final PersistentTerminalSessionPool.SessionHolder holder;
        private volatile Consumer<String> currentListener;

        public LiveSession(PersistentTerminalSessionPool.SessionHolder holder) {
            this.holder = Objects.requireNonNull(holder, "holder");
        }

        public String getTranscript() {
            return holder.getTranscript();
        }

        public void setOutputListener(Consumer<String> listener) {
            if (this.currentListener != null) {
                holder.detach(this.currentListener);
            }
            this.currentListener = listener;
            if (listener != null) {
                holder.attach(listener);
            }
        }

        public void sendBytes(byte[] bytes) throws IOException {
            holder.sendBytes(bytes);
        }

        public ExecutionSession rawSession() {
            return holder.rawSession();
        }

        public boolean isAlive() {
            return holder.isAlive();
        }

        public PersistentTerminalSessionPool.SessionHolder holder() {
            return holder;
        }

        void close() {
            if (this.currentListener != null) {
                holder.detach(this.currentListener);
                this.currentListener = null;
            }
            holder.close();
        }
    }

    private ExecutionSessionRegistry() {}

    public static void register(String instanceId, ExecutionSession session) throws IOException {
        PersistentTerminalSessionPool.getInstance().register(instanceId, session);
    }

    /** Null when no live session exists for the id. */
    public static ExecutionSession get(String instanceId) {
        return PersistentTerminalSessionPool.getInstance().getSession(instanceId);
    }

    public static LiveSession getLiveSession(String instanceId) {
        PersistentTerminalSessionPool.SessionHolder holder =
                PersistentTerminalSessionPool.getInstance().getHolder(instanceId);
        return holder != null ? new LiveSession(holder) : null;
    }

    public static void unregister(String instanceId) {
        PersistentTerminalSessionPool.getInstance().unregister(instanceId);
    }

    public static boolean unregister(String instanceId, ExecutionSession expected) {
        return PersistentTerminalSessionPool.getInstance().unregister(instanceId, expected);
    }

    public static boolean completeExit(String instanceId, ExecutionSession expected,
            PersistentTerminalSessionPool.ExitAction action) throws IOException {
        return PersistentTerminalSessionPool.getInstance().completeExit(instanceId, expected, action);
    }

    public static void requestStop(String instanceId, ExecutionSession expected,
            PersistentTerminalSessionPool.StopIntent intent,
            PersistentTerminalSessionPool.ExitAction action) throws IOException {
        if (!PersistentTerminalSessionPool.getInstance().requestStop(instanceId, expected, intent, action)) {
            throw new IOException("运行会话已变化");
        }
    }

    public static PersistentTerminalSessionPool.StopIntent stopIntent(String instanceId, ExecutionSession expected) {
        return PersistentTerminalSessionPool.getInstance().stopIntent(instanceId, expected);
    }

    public static String generation(String instanceId, ExecutionSession expected) {
        PersistentTerminalSessionPool.SessionHolder holder =
                PersistentTerminalSessionPool.getInstance().getHolder(instanceId);
        return holder != null && holder.rawSession() == expected ? holder.generation() : null;
    }
}
