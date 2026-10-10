package io.github.nyzeep.orchestella;

/**
 * Thread-safe bounded character buffer for terminal transcript preservation and replay.
 *
 * <p>Enforces a maximum capacity (e.g. 50,000 characters) to prevent unbounded memory growth
 * while guaranteeing that the most recent terminal output is always preserved for re-attachment.
 */
public final class TerminalRingBuffer {

    public static final int DEFAULT_MAX_CAPACITY = 50_000;

    private final int maxCapacity;
    private final StringBuilder buffer;
    private final Object lock = new Object();

    public TerminalRingBuffer() {
        this(DEFAULT_MAX_CAPACITY);
    }

    public TerminalRingBuffer(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be positive, got: " + maxCapacity);
        }
        this.maxCapacity = maxCapacity;
        this.buffer = new StringBuilder(Math.min(maxCapacity, 8192));
    }

    public void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (text.length() >= maxCapacity) {
                buffer.setLength(0);
                buffer.append(text, text.length() - maxCapacity, text.length());
            } else {
                int overflow = (buffer.length() + text.length()) - maxCapacity;
                if (overflow > 0) {
                    buffer.delete(0, overflow);
                }
                buffer.append(text);
            }
        }
    }

    public String snapshot() {
        synchronized (lock) {
            return buffer.toString();
        }
    }

    public int length() {
        synchronized (lock) {
            return buffer.length();
        }
    }

    public int maxCapacity() {
        return maxCapacity;
    }

    public void clear() {
        synchronized (lock) {
            buffer.setLength(0);
        }
    }

    @Override
    public String toString() {
        return snapshot();
    }
}
