package io.github.nyzeep.orchestella;

/**
 * Thread-safe bounded character buffer for terminal transcript preservation and replay.
 *
 * <p>Enforces a maximum capacity (e.g. 50,000 characters) to prevent unbounded memory growth
 * while guaranteeing that the most recent terminal output is always preserved for re-attachment.
 *
 * <p>W1-05 (T-14): backed by a true fixed-capacity ring over a single {@code char[]}, addressed by
 * {@code head} (index of the oldest retained character) and {@code size} (number of retained
 * characters). Appends copy through {@link String#getChars(int, int, char[], int)} in at most two
 * contiguous segments (tail segment plus wrap-around segment), and overflow eviction merely
 * advances {@code head} (modulo capacity). This eliminates the O(capacity) full-buffer memmove the
 * previous {@code StringBuilder.delete(0, overflow)} implementation paid on every overflow while
 * streaming terminal output.
 *
 * <p>Complexity: {@link #append(String)} copies only the appended text (eviction is O(1));
 * {@link #snapshot()} is O(size); {@link #length()}, {@link #maxCapacity()} and {@link #clear()}
 * are O(1).
 */
public final class TerminalRingBuffer {

    public static final int DEFAULT_MAX_CAPACITY = 50_000;

    private final int maxCapacity;
    private final char[] ring;
    private final Object lock = new Object();

    /** Index of the oldest retained character in {@link #ring}; advances only on eviction. */
    private int head;

    /** Number of valid characters currently retained in {@link #ring}. */
    private int size;

    public TerminalRingBuffer() {
        this(DEFAULT_MAX_CAPACITY);
    }

    public TerminalRingBuffer(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be positive, got: " + maxCapacity);
        }
        this.maxCapacity = maxCapacity;
        this.ring = new char[maxCapacity];
    }

    public void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (lock) {
            final int textLength = text.length();
            if (textLength >= maxCapacity) {
                // Only the last maxCapacity characters of text survive; all prior content is
                // dropped. The retained tail is exactly maxCapacity characters, so it fills the
                // ring contiguously from index 0 in a single bulk copy.
                text.getChars(textLength - maxCapacity, textLength, ring, 0);
                head = 0;
                size = maxCapacity;
                return;
            }
            final int overflow = (size + textLength) - maxCapacity;
            if (overflow > 0) {
                // Evict the oldest characters by advancing head; O(1), no memmove.
                head = (head + overflow) % maxCapacity;
                size -= overflow;
            }
            // Copy text into the free window starting at (head + size) % maxCapacity in at most
            // two contiguous bulk segments: up to the end of the array, then the wrap remainder.
            final int writePos = (head + size) % maxCapacity;
            final int firstSegment = Math.min(textLength, maxCapacity - writePos);
            text.getChars(0, firstSegment, ring, writePos);
            if (firstSegment < textLength) {
                text.getChars(firstSegment, textLength, ring, 0);
            }
            size += textLength;
        }
    }

    public String snapshot() {
        synchronized (lock) {
            if (size == 0) {
                return "";
            }
            // Assemble the logical (oldest-first) sequence with at most two arraycopy calls:
            // ring[head..capacity) then the wrapped remainder ring[0..head).
            final char[] out = new char[size];
            final int tailLength = Math.min(size, maxCapacity - head);
            System.arraycopy(ring, head, out, 0, tailLength);
            if (tailLength < size) {
                System.arraycopy(ring, 0, out, tailLength, size - tailLength);
            }
            return new String(out);
        }
    }

    public int length() {
        synchronized (lock) {
            return size;
        }
    }

    public int maxCapacity() {
        return maxCapacity;
    }

    public void clear() {
        synchronized (lock) {
            head = 0;
            size = 0;
        }
    }

    @Override
    public String toString() {
        return snapshot();
    }
}
