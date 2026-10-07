package io.github.nyzeep.droidlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * In-process registry and lifecycle manager of live {@link ExecutionSession}s.
 *
 * <p>Preserves session state across Activity re-entries (ADR-0002, US-26).
 * Maintains a dedicated, single-reader output pump and a bounded scrollback
 * transcript buffer for each instance so that background VM logs are never
 * lost and multiple Activities (or re-opened activities) don't race on the
 * underlying socket input stream.
 */
public final class ExecutionSessionRegistry {

    public static final class LiveSession {
        private final ExecutionSession session;
        private final StringBuilder transcript = new StringBuilder();
        private final Object lock = new Object();
        private volatile Consumer<String> outputListener;
        private volatile boolean closed = false;

        LiveSession(ExecutionSession session) {
            this.session = Objects.requireNonNull(session, "session");
            Thread pump = new Thread(this::pumpLoop, "session-term-pump");
            pump.setDaemon(true);
            pump.start();
        }

        private void pumpLoop() {
            try {
                InputStream in = session.terminalOutput();
                byte[] buffer = new byte[4096];
                int count;
                while (!closed && (count = in.read(buffer)) >= 0) {
                    String chunk = new String(buffer, 0, count, StandardCharsets.UTF_8);
                    synchronized (lock) {
                        transcript.append(chunk);
                        if (transcript.length() > 200_000) {
                            transcript.delete(0, 50_000);
                        }
                    }
                    Consumer<String> listener = outputListener;
                    if (listener != null) {
                        try {
                            listener.accept(chunk);
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        public String getTranscript() {
            synchronized (lock) {
                return transcript.toString();
            }
        }

        public void setOutputListener(Consumer<String> listener) {
            this.outputListener = listener;
        }

        public void sendBytes(byte[] bytes) throws IOException {
            OutputStream out = session.terminalInput();
            if (out != null && bytes != null && bytes.length > 0) {
                out.write(bytes);
                out.flush();
            }
        }

        public ExecutionSession rawSession() {
            return session;
        }

        void close() {
            closed = true;
            outputListener = null;
            session.close();
        }
    }

    private static final ConcurrentMap<String, LiveSession> SESSIONS =
            new ConcurrentHashMap<>();

    private ExecutionSessionRegistry() {}

    public static void register(String instanceId, ExecutionSession session) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(session, "session");
        LiveSession existing = SESSIONS.get(instanceId);
        if (existing != null) {
            existing.close();
        }
        SESSIONS.put(instanceId, new LiveSession(session));
    }

    /** Null when no live session exists for the id. */
    public static ExecutionSession get(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        LiveSession live = SESSIONS.get(instanceId);
        return live != null ? live.rawSession() : null;
    }

    public static LiveSession getLiveSession(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        return SESSIONS.get(instanceId);
    }

    public static void unregister(String instanceId) {
        if (instanceId != null) {
            LiveSession removed = SESSIONS.remove(instanceId);
            if (removed != null) {
                removed.close();
            }
        }
    }
}
