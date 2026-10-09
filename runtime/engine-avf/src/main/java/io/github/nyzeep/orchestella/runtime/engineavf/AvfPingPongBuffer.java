package io.github.nyzeep.orchestella.runtime.engineavf;

/**
 * Ping-Pong double buffer manager for high-throughput 60 FPS graphics streaming.
 *
 * <p>Conforms to ADR-0017 and Ticket 42:
 * <ul>
 *   <li>Alternates between Slot 0 (Ping) and Slot 1 (Pong) to decouple frame ingestion and presentation;</li>
 *   <li>Reuses pre-allocated byte buffers across frames to eliminate GC memory churn;</li>
 *   <li>Protects against memory leaks with upper bounded capacity.</li>
 * </ul>
 */
public final class AvfPingPongBuffer {
    public static final int SLOT_PING = 0;
    public static final int SLOT_PONG = 1;

    private byte[] pingBuffer;
    private byte[] pongBuffer;
    private int activeSlot = SLOT_PING;
    private int currentCapacity;

    public AvfPingPongBuffer() {
        this(1920 * 1080 * 4); // Default 1080p RGBA buffer
    }

    public AvfPingPongBuffer(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive: " + initialCapacity);
        }
        this.currentCapacity = initialCapacity;
        this.pingBuffer = new byte[initialCapacity];
        this.pongBuffer = new byte[initialCapacity];
    }

    /**
     * Ensures that both Ping and Pong buffers have at least {@code requiredCapacity} bytes.
     * If reallocation is needed, both buffers are resized.
     */
    public synchronized void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity > AvfGraphicsVsockProtocol.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "Requested capacity exceeds maximum payload limit of "
                            + AvfGraphicsVsockProtocol.MAX_PAYLOAD_BYTES + " bytes: " + requiredCapacity);
        }
        if (requiredCapacity > currentCapacity) {
            this.pingBuffer = new byte[requiredCapacity];
            this.pongBuffer = new byte[requiredCapacity];
            this.currentCapacity = requiredCapacity;
        }
    }

    /**
     * Returns the currently active buffer (Ping or Pong).
     */
    public synchronized byte[] activeBuffer() {
        return activeSlot == SLOT_PING ? pingBuffer : pongBuffer;
    }

    /**
     * Returns the currently active slot index (0 for Ping, 1 for Pong).
     */
    public synchronized int activeSlot() {
        return activeSlot;
    }

    /**
     * Swaps the active slot between Ping (0) and Pong (1).
     */
    public synchronized void swap() {
        activeSlot = 1 - activeSlot;
    }

    /**
     * Current buffer capacity in bytes.
     */
    public synchronized int capacity() {
        return currentCapacity;
    }

    /**
     * Resets the buffer state back to Ping slot.
     */
    public synchronized void reset() {
        activeSlot = SLOT_PING;
    }
}
