package io.github.nyzeep.orchestella.runtime.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract suite for {@link InstanceStopSignal}, mirroring the four scenarios of
 * {@code research/uml-runner/tools/stop_watcher_sim.sh}:
 *
 * <ul>
 *   <li>S1 — in-round stop: phase B consumes the marker and deletes it (no regression);</li>
 *   <li>S2 — post-mortem race, both directions: without a post-exit sweep the marker survives
 *       (the T5 gate-1 cycle-1 root cause), with {@code completeRound()} it is gone;</li>
 *   <li>S3 — stale marker plus a new round: phase A clears it and phase B does not
 *       mis-consume it;</li>
 *   <li>S4 — natural exit: no marker is left behind.</li>
 * </ul>
 *
 * <p>Polling is injected at 20 ms and liveness is a deterministic counter, so the suite never
 * relies on wall-clock sleeps.
 */
public final class InstanceStopSignalContractTest {

    private static final long POLL_MILLIS = 20L;

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    public static void main(String[] args) throws Exception {
        Path root;
        boolean owned;
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            root = Path.of(args[0]).toAbsolutePath().normalize();
            deleteRecursively(root);
            Files.createDirectories(root);
            owned = false;
        } else {
            root = Files.createTempDirectory("stop-signal-test-");
            owned = true;
        }
        try {
            s1InRoundStopIsConsumedOnce(root.resolve("s1"));
            s2PostMortemMarkerSurvivesWithoutSweepAndIsClearedByCompleteRound(root.resolve("s2"));
            s3StaleMarkerIsSweptAndNotMisConsumed(root.resolve("s3"));
            s4NaturalExitLeavesNoResidue(root.resolve("s4"));
            System.out.println("INSTANCE_STOP_SIGNAL_TEST_OK");
        } finally {
            if (owned) {
                deleteRecursively(root);
            }
        }
    }

    @Test
    public void s1InRoundStop() throws Exception {
        s1InRoundStopIsConsumedOnce(folder.newFolder("s1").toPath());
    }

    @Test
    public void s2PostMortemRace() throws Exception {
        s2PostMortemMarkerSurvivesWithoutSweepAndIsClearedByCompleteRound(folder.newFolder("s2").toPath());
    }

    @Test
    public void s3StaleMarker() throws Exception {
        s3StaleMarkerIsSweptAndNotMisConsumed(folder.newFolder("s3").toPath());
    }

    @Test
    public void s4NaturalExit() throws Exception {
        s4NaturalExitLeavesNoResidue(folder.newFolder("s4").toPath());
    }

    /** S1: marker while the guest runs -> consumed once, deleted, and never consumed again. */
    private static void s1InRoundStopIsConsumedOnce(Path dir) throws IOException {
        Path marker = prepare(dir);
        AtomicBoolean alive = new AtomicBoolean(true);
        AtomicInteger consumedCount = new AtomicInteger();
        InstanceStopSignal signal = new InstanceStopSignal(marker, POLL_MILLIS);

        signal.prepareRound();
        Files.createFile(marker);

        boolean consumedNow = signal.watch(alive::get, () -> {
            consumedCount.incrementAndGet();
            alive.set(false);
        });

        assertTrue("S1: watch must report that it consumed the marker", consumedNow);
        assertEquals("S1: onConsume must run exactly once", 1, consumedCount.get());
        assertFalse("S1: marker must be deleted by the consumer", Files.exists(marker));

        boolean consumedAgain = signal.watch(() -> true, consumedCount::incrementAndGet);
        assertFalse("S1: consumption is one-shot, a second watch must not re-consume", consumedAgain);
        assertEquals("S1: onConsume must not run twice", 1, consumedCount.get());
    }

    /**
     * S2 (both directions): a marker created after the guest died is left behind by a
     * sweep-only control path, and removed by {@link InstanceStopSignal#completeRound()}.
     */
    private static void s2PostMortemMarkerSurvivesWithoutSweepAndIsClearedByCompleteRound(Path dir)
            throws IOException {
        Path marker = prepare(dir);
        BooleanSupplier deadGuest = () -> false;
        AtomicInteger consumedCount = new AtomicInteger();

        // Control path: startup sweep + watch, but no post-exit sweep (the pre-fix behaviour).
        InstanceStopSignal control = new InstanceStopSignal(marker, POLL_MILLIS);
        control.prepareRound();
        assertFalse("S2: a dead guest must end the watch without consuming", control.watch(deadGuest, consumedCount::incrementAndGet));
        Files.createFile(marker);
        assertTrue("S2[no sweep]: post-mortem marker survives — the T5 gate-1 cycle-1 root cause",
                Files.exists(marker));

        // Fixed path: the post-exit sweep closes that window.
        InstanceStopSignal fixed = new InstanceStopSignal(marker, POLL_MILLIS);
        fixed.prepareRound();
        assertFalse("S2: prepareRound must clear the leftover", Files.exists(marker));
        assertFalse("S2: watch must not consume on a dead guest", fixed.watch(deadGuest, consumedCount::incrementAndGet));
        Files.createFile(marker);
        fixed.completeRound();
        assertFalse("S2[with sweep]: completeRound removes the post-mortem marker", Files.exists(marker));
        assertEquals("S2: no stop may be consumed on either path", 0, consumedCount.get());
    }

    /** S3: a stale marker from a previous round is swept and never mis-consumed. */
    private static void s3StaleMarkerIsSweptAndNotMisConsumed(Path dir) throws IOException {
        Path marker = prepare(dir);
        Files.createFile(marker);

        InstanceStopSignal signal = new InstanceStopSignal(marker, POLL_MILLIS);
        signal.prepareRound();
        assertFalse("S3: startup sweep must delete the stale marker", Files.exists(marker));

        AtomicInteger consumedCount = new AtomicInteger();
        AtomicInteger polls = new AtomicInteger();
        // Deterministic liveness: three live polls, then the guest exits. No wall-clock sleeps.
        BooleanSupplier alive = () -> polls.incrementAndGet() <= 3;

        boolean consumedNow = signal.watch(alive, consumedCount::incrementAndGet);
        assertFalse("S3: a stale marker must not be consumed by the new round", consumedNow);
        assertEquals("S3: onConsume must not run for a swept marker", 0, consumedCount.get());
        assertTrue("S3: the watch must actually have polled before the guest exited", polls.get() >= 4);
        assertFalse("S3: no marker remains after the round", Files.exists(marker));
    }

    /** S4: a round that simply ends leaves nothing behind. */
    private static void s4NaturalExitLeavesNoResidue(Path dir) throws IOException {
        Path marker = prepare(dir);
        AtomicInteger consumedCount = new AtomicInteger();
        InstanceStopSignal signal = new InstanceStopSignal(marker, POLL_MILLIS);

        signal.prepareRound();
        assertFalse("S4: watch returns false when the guest is already gone",
                signal.watch(() -> false, consumedCount::incrementAndGet));
        signal.completeRound();

        assertEquals("S4: nothing may be consumed on natural exit", 0, consumedCount.get());
        assertFalse("S4: no marker may survive a natural exit", Files.exists(marker));
    }

    private static Path prepare(Path dir) throws IOException {
        Files.createDirectories(dir);
        return dir.resolve("uml-stop");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        }
    }
}
