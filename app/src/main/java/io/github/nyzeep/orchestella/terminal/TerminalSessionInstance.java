package io.github.nyzeep.orchestella.terminal;

import io.github.nyzeep.orchestella.TerminalRingBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * An independent, thread-safe background terminal session instance.
 *
 * <p>Each session maintains its own isolated ring buffer, cursor location,
 * IME composition text, and title. Even when a session is not active on screen,
 * its background stdout/stderr stream continuously flows into its private ring buffer,
 * ensuring zero cross-session data bleeding and instant re-render upon switching.
 */
public final class TerminalSessionInstance {

    private final String sessionId;
    private final String instanceId;
    private volatile String title;
    private final TerminalRingBuffer ringBuffer;
    /**
     * Screen state for this tab (2026-11-14 terminal round, ownership reworked by
     * W1-05/T-14). Created with a private model, but while the runtime console is
     * live every session is bound through {@link #bindScreenModel} to the pool's
     * canonical {@link TerminalScreenModel} — the pump thread is the only parser,
     * so a bound tab refreshes live and no UI thread ever parses console bytes a
     * second time. The private model remains only the render state for a session
     * that is not bound (e.g. detached/offline hosts).
     */
    private volatile TerminalScreenModel screenModel = new TerminalScreenModel(80, 24);
    private volatile int cursorPosition = 0;
    private volatile String imeBuffer = "";
    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile long lastOutputTimestamp = System.currentTimeMillis();
    private final Set<Consumer<String>> outputListeners = new CopyOnWriteArraySet<>();

    public TerminalSessionInstance(String sessionId, String instanceId, String title) {
        this(sessionId, instanceId, title, TerminalRingBuffer.DEFAULT_MAX_CAPACITY);
    }

    public TerminalSessionInstance(String sessionId, String instanceId, String title, int bufferCapacity) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
        this.title = (title != null && !title.trim().isEmpty()) ? title.trim() : sessionId;
        this.ringBuffer = new TerminalRingBuffer(bufferCapacity);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        if (title != null && !title.trim().isEmpty()) {
            this.title = title.trim();
        }
    }

    public int getCursorPosition() {
        return cursorPosition;
    }

    public void setCursorPosition(int cursorPosition) {
        this.cursorPosition = Math.max(0, cursorPosition);
    }

    public String getImeBuffer() {
        return imeBuffer;
    }

    public void setImeBuffer(String imeBuffer) {
        this.imeBuffer = imeBuffer != null ? imeBuffer : "";
    }

    public TerminalRingBuffer getRingBuffer() {
        return ringBuffer;
    }

    public TerminalScreenModel getScreenModel() {
        return screenModel;
    }

    /**
     * Binds this session to an externally owned screen model. Since W1-05 (T-14)
     * every session bound to the live runtime holder shares the holder's canonical
     * model (single pump-thread parser, live secondary panes, no duplicate DSR
     * replies); sessions that are never bound keep the private model created with
     * the session (detached/offline render state).
     */
    public void bindScreenModel(TerminalScreenModel model) {
        if (model != null) {
            screenModel = model;
        }
    }

    public String getTranscript() {
        return ringBuffer.snapshot();
    }

    public void appendOutput(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        ringBuffer.append(chunk);
        lastOutputTimestamp = System.currentTimeMillis();
        for (Consumer<String> listener : outputListeners) {
            try {
                listener.accept(chunk);
            } catch (Exception ignored) {
                // Observer isolation: one broken output listener must not break transcript
                // delivery to the others.
            }
        }
    }

    public void addOutputListener(Consumer<String> listener) {
        if (listener != null) {
            outputListeners.add(listener);
        }
    }

    public void removeOutputListener(Consumer<String> listener) {
        if (listener != null) {
            outputListeners.remove(listener);
        }
    }

    public boolean isActive() {
        return active.get();
    }

    public long getLastOutputTimestamp() {
        return lastOutputTimestamp;
    }

    public void clear() {
        ringBuffer.clear();
        cursorPosition = 0;
        imeBuffer = "";
    }

    public void close() {
        if (active.compareAndSet(true, false)) {
            outputListeners.clear();
        }
    }

    @Override
    public String toString() {
        return "TerminalSessionInstance{" +
                "sessionId='" + sessionId + '\'' +
                ", instanceId='" + instanceId + '\'' +
                ", title='" + title + '\'' +
                ", active=" + active.get() +
                '}';
    }
}