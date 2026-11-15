package io.github.nyzeep.orchestella.terminal;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Pure-JVM throughput guards for {@link TerminalScreenModel}: no Android APIs, no Robolectric.
 * Budgets are deliberately loose catastrophic-regression guards, not device-comparable
 * benchmarks; a failure means an order-of-magnitude regression in a hot path.
 */
public final class TerminalThroughputTest {
    private static final int FLOOD_LINES = 100_000;
    private static final int CHUNK_CHARS = 32 * 1024;
    private static final long APPEND_BUDGET_MS = 8_000;
    private static final long SNAPSHOT_BUDGET_MS = 200;

    /** The flood_color shape: one SGR foreground per line plus reset, then a newline. */
    private static String floodPayload() {
        StringBuilder payload = new StringBuilder(FLOOD_LINES * 24);
        for (int i = 0; i < FLOOD_LINES; i++)
            payload.append("\u001b[3").append(i % 8).append("mline ").append(i).append("\u001b[0m\n");
        return payload.toString();
    }

    /**
     * Pre-split on newline boundaries so the timed region is streaming input of realistic
     * write-sized chunks and never hands the model a half-finished escape sequence.
     */
    private static List<String> chunks(String payload) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < payload.length()) {
            int end = Math.min(payload.length(), start + CHUNK_CHARS);
            if (end < payload.length()) {
                int newline = payload.lastIndexOf('\n', end - 1);
                if (newline >= start) end = newline + 1;
            }
            result.add(payload.substring(start, end));
            start = end;
        }
        assertTrue("payload must split into several chunks", result.size() > 1);
        return result;
    }

    private static int appendPrefix(TerminalScreenModel model, List<String> chunks, int chars) {
        int appended = 0;
        int index = 0;
        while (index < chunks.size() && appended < chars) {
            String chunk = chunks.get(index++);
            model.append(chunk);
            appended += chunk.length();
        }
        return index;
    }

    /**
     * JIT compilation and one-time class loading would otherwise be charged to the timed
     * region exactly once (the first 100k-line run), inflating it by an unrepresentative
     * amount. Warm the same code paths on a throwaway model first.
     */
    private static void warmUp(List<String> chunks) {
        TerminalScreenModel model = new TerminalScreenModel(80, 24);
        appendPrefix(model, chunks, 40 * 1024);
        for (int i = 0; i < 5; i++) model.snapshot().text();
    }

    @Test public void hundredThousandColoredLinesAppendWithinBudgetAndKeepNewestLine() {
        List<String> chunks = chunks(floodPayload());
        warmUp(chunks);

        // Payload construction and pre-splitting stay outside the timed region; the model
        // still sees realistic incremental writes rather than one monolithic append.
        TerminalScreenModel model = new TerminalScreenModel(80, 24);
        long started = System.nanoTime();
        try {
            for (String chunk : chunks) model.append(chunk);
        } catch (OutOfMemoryError error) {
            fail("OutOfMemoryError while appending " + FLOOD_LINES + " colored lines: " + error);
        }
        double ms = (System.nanoTime() - started) / 1_000_000.0;
        System.out.println("[throughput] append " + FLOOD_LINES + " colored lines: " + ms + " ms");
        assertTrue("append took " + ms + " ms for " + FLOOD_LINES + " lines (budget "
                + APPEND_BUDGET_MS + " ms)", ms < APPEND_BUDGET_MS);

        TerminalScreenModel.Snapshot snapshot = model.snapshot();
        // The ticket wording says historyRowCount(), but TerminalScreenModel has no such
        // method; the real accessor is Snapshot.historyRows, which counts scrollback rows
        // only (the deque is capped at MAX_HISTORY_CELLS = 100_000 / 2000 rows).
        assertTrue("historyRows=" + snapshot.historyRows + " exceeds scrollback row cap 2000",
                snapshot.historyRows <= 2000);
        assertTrue("historyRows=" + snapshot.historyRows + " means output never reached scrollback",
                snapshot.historyRows > 0);
        assertTrue("newest line 'line " + (FLOOD_LINES - 1) + "' missing from snapshot",
                snapshot.text().contains("line " + (FLOOD_LINES - 1)));
    }

    @Test public void thousandScreenSnapshotsStayWithinBudget() {
        TerminalScreenModel model = new TerminalScreenModel(80, 24);
        for (int i = 0; i < 32; i++) model.append("\u001b[32mline " + i + "\u001b[0m\n");
        assertTrue("model should hold a few scrollback rows", model.snapshot().historyRows > 0);

        long checksum = 0;
        for (int i = 0; i < 50; i++) checksum += model.snapshot().spans.size();
        long started = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            TerminalScreenModel.Snapshot snapshot = model.snapshot();
            checksum += snapshot.spans.size() + snapshot.historyRows + snapshot.cursorOffset;
        }
        double ms = (System.nanoTime() - started) / 1_000_000.0;
        System.out.println("[throughput] 1000 screen snapshots: " + ms + " ms");
        assertTrue("1000 snapshots took " + ms + " ms (budget " + SNAPSHOT_BUDGET_MS + " ms)",
                ms < SNAPSHOT_BUDGET_MS);

        TerminalScreenModel.Snapshot last = model.snapshot();
        assertFalse("snapshot must carry spans", last.spans.isEmpty());
        assertFalse("snapshot text must not be blank", last.text().trim().isEmpty());
        assertTrue("last snapshot should end with the newest line", last.text().contains("line 31"));
        assertTrue("accumulated snapshot work must be observable", checksum > 0);
    }
}
