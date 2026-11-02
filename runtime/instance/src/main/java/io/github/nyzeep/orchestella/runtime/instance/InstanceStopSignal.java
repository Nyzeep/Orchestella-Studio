package io.github.nyzeep.orchestella.runtime.instance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * File-marker stop signal with the three-phase invariants extracted from the UML runner
 * stop-file watcher ({@code research/uml-runner/tools/stop_watcher_sim.sh}).
 *
 * <p>The contract is a single invariant: <em>when a round ends, the marker does not exist</em>.
 * It is upheld by three phases that the caller drives in order:
 *
 * <ul>
 *   <li><b>A — {@link #prepareRound()} (startup sweep):</b> removes a marker left behind by a
 *       previous round. This is the authoritative fix for stale markers: if the hosting process
 *       is killed, its watcher dies with it and the post-exit sweep can never run, so only the
 *       next round can clean up.</li>
 *   <li><b>B — {@link #watch(BooleanSupplier, Runnable)} (in-round watcher):</b> polls the marker
 *       while the guest is alive, runs {@code onConsume} at most once, deletes the marker, and
 *       returns. One-shot by design: a marker observed twice must not trigger two stops.</li>
 *   <li><b>C — {@link #completeRound()} (post-exit sweep):</b> removes a marker created after the
 *       guest died — the window in which phase B has already exited. Complements phase A: A
 *       cleans the past, C cleans the present round.</li>
 * </ul>
 *
 * <p>Threading model: this class starts no threads and owns no scheduler. {@link #watch} blocks
 * the calling thread (polling at a fixed interval) and must be called from a single thread at a
 * time. The lifecycle of that thread is the caller's decision; {@link #completeRound()} is meant
 * to be called on the round-owning thread after the guest process has exited.
 *
 * <p>No Android dependency — pure {@code java.nio.file}.
 *
 * <p><b>Intentionally not wired into production.</b> As of ticket ZP-09 the product has no
 * stop-file surface: every stop path funnels into {@code RuntimeProcessSupervisor.stop()}, so
 * only the invariant (not the file mechanism) is portable. This type is therefore exercised
 * solely by {@code InstanceStopSignalContractTest} and is kept as a deep module on purpose; it is
 * not dead code. Adding a production caller is a separate decision (spec §T3, option C).
 */
public final class InstanceStopSignal {

    /** Default polling period used by phase B when the caller does not inject one. */
    public static final long DEFAULT_POLL_INTERVAL_MILLIS = 500L;

    private final Path stopMarker;
    private final long pollIntervalMillis;

    private volatile boolean consumed;

    /**
     * Creates a signal for {@code stopMarker} polling at {@value #DEFAULT_POLL_INTERVAL_MILLIS} ms.
     *
     * @param stopMarker marker path; need not exist yet
     */
    public InstanceStopSignal(Path stopMarker) {
        this(stopMarker, DEFAULT_POLL_INTERVAL_MILLIS);
    }

    /**
     * Creates a signal with an explicit polling period (tests inject a short one).
     *
     * @param stopMarker marker path; need not exist yet
     * @param pollIntervalMillis polling period in milliseconds, must be positive
     */
    public InstanceStopSignal(Path stopMarker, long pollIntervalMillis) {
        this.stopMarker = Objects.requireNonNull(stopMarker, "stopMarker");
        if (pollIntervalMillis <= 0L) {
            throw new IllegalArgumentException("pollIntervalMillis must be positive: " + pollIntervalMillis);
        }
        this.pollIntervalMillis = pollIntervalMillis;
    }

    /**
     * Phase A: removes any marker left over from an earlier round and re-arms this signal,
     * so that one instance can be reused for consecutive rounds.
     *
     * <p>Idempotent: a missing marker is not an error.
     */
    public void prepareRound() {
        consumed = false;
        deleteMarkerIfPresent();
    }

    /**
     * Phase B: polls the marker while {@code alive} reports true.
     *
     * <p>When the marker is observed, {@code onConsume} is invoked exactly once, the marker is
     * deleted, and the method returns {@code true}. If the guest is already dead, if the signal
     * was already consumed, or if the calling thread is interrupted, it returns {@code false}
     * without invoking {@code onConsume}. It never starts a thread and never consumes twice.
     *
     * @param alive guest liveness probe; {@code false} ends the watch
     * @param onConsume action performing the stop (signal / kill); must be idempotent-safe
     * @return true if this call consumed the marker
     */
    public boolean watch(BooleanSupplier alive, Runnable onConsume) {
        Objects.requireNonNull(alive, "alive");
        Objects.requireNonNull(onConsume, "onConsume");
        if (consumed) {
            return false;
        }
        while (alive.getAsBoolean()) {
            if (Files.exists(stopMarker)) {
                onConsume.run();
                deleteMarkerIfPresent();
                consumed = true;
                return true;
            }
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Phase C: post-exit sweep, called after the guest process has exited.
     *
     * <p>Closes the race in which the marker is created after phase B's loop has already exited
     * (the exact defect behind the T5 gate-1 cycle-1 false failure). Idempotent: on the normal
     * stop path phase B already deleted the marker, so this is a no-op.
     */
    public void completeRound() {
        deleteMarkerIfPresent();
    }

    private void deleteMarkerIfPresent() {
        try {
            Files.deleteIfExists(stopMarker);
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to delete stop marker: " + stopMarker, failure);
        }
    }
}
