package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Memory allocation quota sentinel (M4 Ticket 57 / ADR-0019).
 *
 * <p>Enforces a hard ceiling on guest GPU video memory allocations (default: 1024 MB)
 * to prevent host Low Memory Killer (LMK) triggers, GPU Hangs, or Out-Of-Memory crashes.
 * Thread-safe and non-blocking under normal operation.
 */
public final class MemoryQuotaSentinel {
    public static final long DEFAULT_MAX_QUOTA_BYTES = 1024L * 1024L * 1024L; // 1024 MB (1 GB)

    private final long maxQuotaBytes;
    private final AtomicLong allocatedBytes = new AtomicLong(0);
    private volatile boolean tripped = false;

    public MemoryQuotaSentinel() {
        this(DEFAULT_MAX_QUOTA_BYTES);
    }

    public MemoryQuotaSentinel(long maxQuotaBytes) {
        if (maxQuotaBytes <= 0) {
            throw new IllegalArgumentException("maxQuotaBytes must be positive, got: " + maxQuotaBytes);
        }
        this.maxQuotaBytes = maxQuotaBytes;
    }

    /**
     * Attempts to allocate {@code bytes} from the guarded memory pool.
     *
     * @param bytes the number of bytes to allocate
     * @return {@code true} if allocation succeeded within quota, or {@code false} if quota was exceeded
     */
    public boolean tryAllocate(long bytes) {
        if (bytes <= 0) {
            return false;
        }
        while (true) {
            long current = allocatedBytes.get();
            if (current + bytes > maxQuotaBytes) {
                tripped = true;
                return false;
            }
            if (allocatedBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    /**
     * Releases {@code bytes} back to the guarded memory pool.
     *
     * @param bytes the number of bytes to release
     */
    public void release(long bytes) {
        if (bytes <= 0) {
            return;
        }
        while (true) {
            long current = allocatedBytes.get();
            long updated = Math.max(0, current - bytes);
            if (allocatedBytes.compareAndSet(current, updated)) {
                if (updated < maxQuotaBytes) {
                    tripped = false;
                }
                return;
            }
        }
    }

    /** Returns currently allocated memory in bytes. */
    public long allocatedBytes() {
        return allocatedBytes.get();
    }

    /** Returns the maximum configured quota in bytes. */
    public long maxQuotaBytes() {
        return maxQuotaBytes;
    }

    /** Returns the available remaining quota in bytes. */
    public long availableBytes() {
        return Math.max(0, maxQuotaBytes - allocatedBytes.get());
    }

    /** Returns whether the quota circuit breaker has been tripped. */
    public boolean isTripped() {
        return tripped;
    }

    /** Resets the allocated byte counter and clears the tripped state. */
    public void reset() {
        allocatedBytes.set(0);
        tripped = false;
    }
}
