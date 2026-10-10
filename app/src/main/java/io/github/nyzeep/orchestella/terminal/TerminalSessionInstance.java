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