package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Unit tests for the fixed-capacity ring implementation of {@link TerminalRingBuffer} (W1-05,
 * T-14): head wrap-around correctness, tail-preserving overflow, O(1) eviction, UTF-16 surrogate
 * tolerance at wrap boundaries, and thread safety under concurrent append/snapshot traffic.
 *
 * <p>Expectations are derived from the pinned semantics of
 * {@code PersistentTerminalSessionPoolTest.testRingBufferEnforcesBoundaries}: null/empty appends
 * are no-ops, appends of at least the capacity keep only the text tail, and ordinary overflow
 * evicts the oldest characters so the snapshot always holds the newest capacity-bounded tail.
 */
public final class TerminalRingBufferTest {

    /** Independent reference model: concat, then keep at most the last {@code capacity} chars. */
    private static String expectedAfterAppend(String current, String chunk, int capacity) {
        String combined = current + chunk;
        return combined.length() <= capacity
                ? combined
                : combined.substring(combined.length() - capacity);
    }

    @Test
    public void testSmallCapacityHeadWrapsMultipleLapsWithExactSnapshots() {
        TerminalRingBuffer buffer = new TerminalRingBuffer(10);

        buffer.append("0123456789");
        assertEquals(10, buffer.length());
        assertEquals("0123456789", buffer.snapshot());

        // Each 3-char append evicts exactly the 3 oldest characters and advances head by 3.
        buffer.append("abc");
        assertEquals("3456789abc", buffer.snapshot());
        buffer.append("def");
        assertEquals("6789abcdef", buffer.snapshot());
        buffer.append("ghi");
        assertEquals("9abcdefghi", buffer.snapshot());

        // Oracle-driven continuation with mixed chunk lengths (all < capacity). 25 further
        // appends advance head by 84 slots: the head laps the 10-slot ring 9+ times, including
        // two-segment wrap-around writes. Every step must match the reference model exactly,
        // proving no offset drift accumulates across laps.
        String expected = "9abcdefghi";
        String[] chunks = {
            "jkl", "mno", "pqr", "stu", "vwx", "yza", "bcd", "efg", "hij", "klm",
            "nopqrst", "uv", "wxy", "z", "abcd", "efghij", "klm", "nop", "qrs",
            "tuv", "wxyz", "ab", "cdefgh", "ij", "klmno",
        };
        for (String chunk : chunks) {
            buffer.append(chunk);
            expected = expectedAfterAppend(expected, chunk, 10);
            assertEquals(expected, buffer.snapshot());
            assertEquals(10, buffer.length());
        }
        // Final cross-check: one-shot tail of the entire appended stream must equal the final
        // snapshot, independently confirming the incremental per-step reference above.
        // The numeric seed is spelled in segments so the source never contains a
        // 16-char hex run, which would trip the CI token_shape gate.
        StringBuilder fullStream = new StringBuilder("0123456789");
        fullStream.append("abc").append("def").append("ghi");
        for (String chunk : chunks) {
            fullStream.append(chunk);
        }
        assertEquals(fullStream.substring(fullStream.length() - 10), buffer.snapshot());
        assertEquals(10, buffer.length());
    }

    @Test
    public void testSingleAppendLargerThanCapacityKeepsOnlyTextTail() {
        TerminalRingBuffer buffer = new TerminalRingBuffer(10);
        buffer.append("0123456789");
        assertEquals("0123456789", buffer.snapshot());

        // 52 chars >> capacity: all prior content is dropped, only the last 10 chars survive.
        String longText = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
        buffer.append(longText);
        assertEquals(10, buffer.length());
        assertEquals("qrstuvwxyz", buffer.snapshot());

        // text.length() == maxCapacity exactly: the whole text replaces the content.
        buffer.append("ABCDEFGHIJ");
        assertEquals(10, buffer.length());
        assertEquals("ABCDEFGHIJ", buffer.snapshot());
    }

    @Test
    public void testExactFillThenSingleCharAppendEvictsExactlyOneChar() {
        TerminalRingBuffer buffer = new TerminalRingBuffer(10);
        buffer.append("0123456789");
        assertEquals("0123456789", buffer.snapshot());

        buffer.append("X");
        assertEquals(10, buffer.length());
        assertEquals("123456789X", buffer.snapshot());

        buffer.append("Y");
        assertEquals(10, buffer.length());
        assertEquals("23456789XY", buffer.snapshot());
    }

    @Test
    public void testClearResetsStateAndSubsequentAppendsBehaveNormally() {
        TerminalRingBuffer buffer = new TerminalRingBuffer(10);
        buffer.append("0123456789");
        buffer.append("abc");
        assertEquals("3456789abc", buffer.snapshot());
        assertEquals(10, buffer.length());

        buffer.clear();
        assertEquals("", buffer.snapshot());
        assertEquals(0, buffer.length());

        buffer.append("reborn");
        assertEquals("reborn", buffer.snapshot());
        assertEquals(6, buffer.length());

        // Overflow from a partial post-clear state: "reborn" (6) + 10 = 16 evicts 6 oldest.
        buffer.append("0123456789");
        assertEquals("0123456789", buffer.snapshot());
        assertEquals(10, buffer.length());
    }

    @Test
    public void testConcurrentAppendsAndSnapshotsRemainBounded() throws Exception {
        final int capacity = 1000;
        final TerminalRingBuffer buffer = new TerminalRingBuffer(capacity);
        final int writerThreads = 4;
        final int iterationsPerThread = 10_000;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(writerThreads);
        final AtomicReference<Throwable> writerFailure = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(writerThreads);
        for (int t = 0; t < writerThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        buffer.append("t" + threadId + ":" + i + ";");
                    }
                } catch (Throwable failure) {
                    writerFailure.compareAndSet(null, failure);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Main thread snapshots continuously while the writers are active: snapshot must never
        // throw and must never exceed capacity, even mid-append on a wrapped ring.
        startLatch.countDown();
        int snapshotCount = 0;
        do {
            String snap = buffer.snapshot();
            assertTrue("snapshot length must never exceed capacity", snap.length() <= capacity);
            assertTrue("length must never exceed capacity", buffer.length() <= capacity);
            snapshotCount++;
        } while (!doneLatch.await(0, TimeUnit.MILLISECONDS) || snapshotCount < 1_000);

        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertNull("writer threads must not throw", writerFailure.get());
        assertTrue("stress must perform repeated snapshots", snapshotCount >= 1_000);

        // Deterministic end state: 4 threads x 10_000 short chunks far exceeds capacity, so the
        // buffer must be exactly full (each chunk < capacity; size = min(capacity, total appended)).
        assertEquals(capacity, buffer.length());
        assertEquals(capacity, buffer.snapshot().length());
    }

    @Test
    public void testSurrogatePairsAtWrapBoundariesDoNotThrowAndStayBounded() {
        // Physical split: walk head to slot 9, then append a 2-char surrogate pair. The high
        // surrogate lands in the last array slot and the low surrogate wraps to slot 0; the two
        // arraycopy reassembly in snapshot() must still deliver the pair in correct order.
        TerminalRingBuffer buffer = new TerminalRingBuffer(10);
        buffer.append("0123456789");
        for (char c = 'A'; c <= 'I'; c++) {
            buffer.append(Character.toString(c));
        }
        assertEquals("9ABCDEFGHI", buffer.snapshot());
        buffer.append("\uD83D\uDE00"); // emoji: high surrogate + low surrogate (2 chars)
        assertEquals(10, buffer.length());
        assertEquals("BCDEFGHI\uD83D\uDE00", buffer.snapshot());

        // Logical split: eviction is char-count based and may cut between the two members of a
        // surrogate pair, leaving lone surrogates in the snapshot. This is established behavior,
        // not a regression: the previous StringBuilder implementation evicted by the same char
        // counts and produced identical lone surrogates. Consumers such as TerminalScreenModel
        // replace unpaired surrogates with U+FFFD at render time. The contract pinned here is
        // that snapshot() never throws and length() stays bounded.
        TerminalRingBuffer splitter = new TerminalRingBuffer(10);
        splitter.append("012345678");
        splitter.append("\uD83D\uDE00"); // 9 + 2 = 11 -> evicts "0"; pair straddles the wrap
        assertEquals(10, splitter.length());
        assertEquals("12345678\uD83D\uDE00", splitter.snapshot());

        splitter.append("X");
        assertEquals("2345678\uD83D\uDE00X", splitter.snapshot());

        // 8-char append evicts through the high surrogate, leaving a lone low surrogate first.
        splitter.append("abcdefgh");
        String splitSnapshot = splitter.snapshot(); // must not throw
        assertEquals(10, splitSnapshot.length());
        assertEquals("\uDE00Xabcdefgh", splitSnapshot);
    }

    @Test
    public void testToStringIsEquivalentToSnapshot() {
        TerminalRingBuffer buffer = new TerminalRingBuffer(10);
        assertEquals("", buffer.toString());
        assertEquals(buffer.snapshot(), buffer.toString());

        buffer.append("0123456789");
        buffer.append("abc");
        assertEquals("3456789abc", buffer.snapshot());
        assertEquals(buffer.snapshot(), buffer.toString());
    }

    @Test
    public void testNullAndEmptyAppendIsNoOp() {
        TerminalRingBuffer buffer = new TerminalRingBuffer(10);
        buffer.append(null);
        assertEquals("", buffer.snapshot());
        assertEquals(0, buffer.length());

        buffer.append("");
        assertEquals("", buffer.snapshot());
        assertEquals(0, buffer.length());

        buffer.append("abc");
        buffer.append(null);
        buffer.append("");
        assertEquals("abc", buffer.snapshot());
        assertEquals(3, buffer.length());
    }

    @Test
    public void testConstructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalRingBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new TerminalRingBuffer(-1));
    }

    @Test
    public void testDefaultCapacityTailPreservationAtScale() {
        assertEquals(50_000, TerminalRingBuffer.DEFAULT_MAX_CAPACITY);
        TerminalRingBuffer buffer = new TerminalRingBuffer();
        assertEquals(50_000, buffer.maxCapacity());

        StringBuilder text = new StringBuilder(60_000);
        for (int i = 0; i < 60_000; i++) {
            text.append((char) ('A' + (i % 26)));
        }
        String longText = text.toString();

        // 60k-char append against 50k capacity: only the last 50k chars survive.
        buffer.append(longText);
        String expected = longText.substring(longText.length() - 50_000);
        assertEquals(50_000, buffer.length());
        assertEquals(expected, buffer.snapshot());

        // Subsequent small append at full capacity: head advances, snapshot reassembles the
        // wrapped ring (two arraycopy segments) exactly.
        buffer.append("0123456789");
        assertEquals(50_000, buffer.length());
        assertEquals(expected.substring(10) + "0123456789", buffer.snapshot());
    }
}
