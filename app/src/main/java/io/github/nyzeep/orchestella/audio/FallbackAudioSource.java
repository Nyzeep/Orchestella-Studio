package io.github.nyzeep.orchestella.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Composite audio source that attempts primary audio source (e.g. per-instance LocalSocket)
 * and optionally falls back to secondary audio source with bounded retry and backoff.
 *
 * <p>G-05: the probe walk is an explicit finite state machine ({@link Phase}) so the fallback
 * rhythm is observable and logged. Honest reachability note: the TCP 9104 secondary is only
 * reachable from the host when the instance launch spec actually carries a hostfwd rule for
 * the guest port — the default spec has none, so the fallback is expected to fail there.
 * Production QEMU instances use {@link #forPrimaryOnly(AudioSourceSeam)} with their isolated
 * {@code @orchestella-audio-<id12>-<gen8>} endpoint.
 */
public final class FallbackAudioSource implements AudioSourceSeam {

    /**
     * Probe-walk phases. IDLE -> PROBING_PRIMARY -> (PRIMARY_ACTIVE | PROBING_SECONDARY)
     * -> (SECONDARY_ACTIVE | BACKOFF) -> ... -> EXHAUSTED; CLOSED is terminal.
     */
    public enum Phase {
        IDLE, PROBING_PRIMARY, PROBING_SECONDARY, BACKOFF, PRIMARY_ACTIVE, SECONDARY_ACTIVE, EXHAUSTED, CLOSED
    }

    /** Optional JVM-safe observation seam for tests and diagnostics; production defaults to logcat. */
    public interface ProbeObserver {
        void onProbeEvent(Phase phase, String detail);
    }

    static final String TAG = "AudioSource";

    /**
     * Appended to every secondary fallback event. The default QEMU launch spec has no
     * inbound hostfwd, so 127.0.0.1:9104 is normally unreachable from the host; this is
     * stated in the copy rather than implied.
     */
    static final String SECONDARY_REACHABILITY_NOTE =
            "TCP 9104 fallback reachable only with a hostfwd rule; unverified on device";

    private final AudioSourceSeam primary;
    private final AudioSourceSeam secondary;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final ProbeObserver observer;

    private volatile Phase phase = Phase.IDLE;
    private final AtomicInteger consecutiveFailedRounds = new AtomicInteger(0);
    private final AtomicInteger connectionEpoch = new AtomicInteger(0);
    private final Object sleepLock = new Object();
    private volatile AudioSourceSeam activeSource;
    private volatile boolean closed = false;

    public FallbackAudioSource(AudioSourceSeam primary, AudioSourceSeam secondary) {
        this(primary, secondary, 5, 500);
    }

    public FallbackAudioSource(
            AudioSourceSeam primary,
            AudioSourceSeam secondary,
            int maxAttempts,
            long retryDelayMs) {
        this(primary, secondary, maxAttempts, retryDelayMs, null);
    }

    public FallbackAudioSource(
            AudioSourceSeam primary,
            AudioSourceSeam secondary,
            int maxAttempts,
            long retryDelayMs,
            ProbeObserver observer) {
        this(primary, Objects.requireNonNull(secondary, "secondary"), maxAttempts, retryDelayMs, observer, true);
    }

    /** Per-instance local-socket source without a shared TCP 9104 secondary fallback. */
    public static FallbackAudioSource forPrimaryOnly(AudioSourceSeam primary) {
        return forPrimaryOnly(primary, 5, 500, null);
    }

    /** Per-instance local-socket source with configurable retry/observer and no secondary fallback. */
    public static FallbackAudioSource forPrimaryOnly(
            AudioSourceSeam primary,
            int maxAttempts,
            long retryDelayMs,
            ProbeObserver observer) {
        return new FallbackAudioSource(primary, null, maxAttempts, retryDelayMs, observer, false);
    }

    private FallbackAudioSource(
            AudioSourceSeam primary,
            AudioSourceSeam secondary,
            int maxAttempts,
            long retryDelayMs,
            ProbeObserver observer,
            boolean unusedInternalMarker) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.secondary = secondary;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
        this.observer = observer;
    }

    @Override
    public InputStream openStream() throws IOException {
        if (closed) {
            throw new IOException("FallbackAudioSource already closed");
        }

        int epoch = connectionEpoch.get();
        IOException lastError = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (closed || epoch != connectionEpoch.get() || Thread.currentThread().isInterrupted()) {
                break;
            }

            enter(Phase.PROBING_PRIMARY, "attempt=" + (attempt + 1) + "/" + maxAttempts);
            try {
                InputStream stream = primary.openStream();
                if (stream != null) {
                    if (closed || epoch != connectionEpoch.get()) {
                        closeQuietly(stream);
                        break;
                    }
                    activeSource = primary;
                    enter(Phase.PRIMARY_ACTIVE, recoveryPrefix() + "primary-source-active");
                    return stream;
                }
                lastError = new IOException("primary audio source returned no stream");
            } catch (IOException e) {
                lastError = e;
            }
            if (secondary != null && !closed && epoch == connectionEpoch.get()) {
                enter(Phase.PROBING_SECONDARY, "primary-unavailable: " + safeReason(lastError));

                try {
                    InputStream stream = secondary.openStream();
                    if (stream != null) {
                        if (closed || epoch != connectionEpoch.get()) {
                            closeQuietly(stream);
                            break;
                        }
                        activeSource = secondary;
                        enter(Phase.SECONDARY_ACTIVE,
                                recoveryPrefix() + "secondary-source-active; " + SECONDARY_REACHABILITY_NOTE);
                        return stream;
                    }
                    lastError = new IOException("secondary audio source returned no stream");
                } catch (IOException e) {
                    lastError = e;
                }
            }
            if (closed || epoch != connectionEpoch.get()) {
                break;
            }
            int rounds = consecutiveFailedRounds.incrementAndGet();
            enter(Phase.BACKOFF, secondary != null
                    ? "round-failed=" + rounds + "; secondary-note: " + SECONDARY_REACHABILITY_NOTE
                    : "round-failed=" + rounds + "; primary-unavailable: " + safeReason(lastError));

            if (attempt < maxAttempts - 1 && retryDelayMs > 0) {
                awaitBackoff(epoch);
            }
        }

        if (closed || epoch != connectionEpoch.get()) {
            throw new IOException(closed
                    ? "FallbackAudioSource closed while connecting"
                    : "Audio source reset while connecting");
        }
        enter(Phase.EXHAUSTED, "both-sources-failed: " + safeReason(lastError));
        throw lastError != null ? lastError : new IOException("Audio sources failed to connect");
    }

    private void awaitBackoff(int epoch) throws IOException {
        long deadlineNs = System.nanoTime() + retryDelayMs * 1_000_000L;
        synchronized (sleepLock) {
            while (!closed && epoch == connectionEpoch.get()) {
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0L) {
                    return;
                }
                long waitMs = remainingNs / 1_000_000L;
                int waitNanos = (int) (remainingNs % 1_000_000L);
                try {
                    sleepLock.wait(waitMs, waitNanos);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while connecting audio stream", interrupted);
                }
            }
        }
    }

    @Override
    public boolean isConnected() {
        AudioSourceSeam current = activeSource;
        return current != null && current.isConnected();
    }

    @Override
    public void resetConnection() {
        if (closed) {
            return;
        }
        connectionEpoch.incrementAndGet();
        activeSource = null;
        synchronized (sleepLock) {
            sleepLock.notifyAll();
        }
        primary.resetConnection();
        if (secondary != null) {
            secondary.resetConnection();
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        connectionEpoch.incrementAndGet();
        activeSource = null;
        synchronized (sleepLock) {
            sleepLock.notifyAll();
        }
        enter(Phase.CLOSED, "fallback-source-closed");
        IOException last = null;
        try {
            primary.close();
        } catch (IOException e) {
            last = e;
        }
        if (secondary != null) {
            try {
                secondary.close();
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Best-effort cleanup when cancelled mid-open.
        }
    }

    public AudioSourceSeam getActiveSource() {
        return activeSource;
    }

    public AudioSourceSeam getPrimary() {
        return primary;
    }

    public AudioSourceSeam getSecondary() {
        return secondary;
    }

    /** Current probe-walk phase; observability only, never used for control flow decisions. */
    public Phase getPhase() {
        return phase;
    }

    /** How many complete primary+secondary probe rounds have failed since the last success. */
    public int getConsecutiveFailedRounds() {
        return consecutiveFailedRounds.get();
    }

    /**
     * Recovery bookkeeping: a successful probe resets the failed-round counter and
     * reports the recovery inside the active-phase detail (no extra event).
     */
    private String recoveryPrefix() {
        int previous = consecutiveFailedRounds.getAndSet(0);
        if (previous == 0) {
            return "";
        }
        return "recovered-after-" + previous + "-failed-rounds; ";
    }

    private void enter(Phase next, String detail) {
        phase = next;
        if (observer != null) {
            observer.onProbeEvent(next, detail);
        }
        logProbeEvent(next, detail);
    }

    /**
     * android.util.Log is guarded: JVM unit tests run against android.jar stubs whose
     * methods throw, so FSM events stay observable there via the injected ProbeObserver
     * instead of the platform logger.
     */
    private static void logProbeEvent(Phase phase, String detail) {
        try {
            android.util.Log.i(TAG, "audio-probe phase=" + phase.name() + " " + detail);
        } catch (Throwable ignored) {
            // No platform logger in this environment (JVM test or restricted runtime).
        }
    }

    /** Static classification only: never let a transport error message leak a path or token. */
    private static String safeReason(IOException error) {
        return error == null ? "none" : error.getClass().getSimpleName();
    }
}
