package io.github.nyzeep.orchestella.terminal;

import io.github.nyzeep.orchestella.TerminalSpan;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * Display-only command blocks, enabled by explicit guest OSC 133 integration.
 * Submitted text is supplied by the guest's newly added shell history entry;
 * this class never receives Android input or infers commands from prompt glyphs.
 * The original terminal grid continues to handle every control sequence unchanged.
 */
final class ShellCommandTranscript {
    private static final int MAX_HISTORY_CHARS = 64_000;
    private static final int MAX_BLOCKS = 32;
    private enum Phase { PROMPT, INPUT, OUTPUT, BETWEEN }
    /** Shared immutable block separator; wrapping treats it like any other span text. */
    private static final TerminalSpan BLOCK_SEPARATOR = unstyled("\n\n");
    private final Deque<BlockEntry> completed = new ArrayDeque<>();
    /** Content version of {@link #completed}: advanced on every structural change. */
    private long completedVersion;
    private int retainedChars;
    private long evictedRows;
    private int columns;
    private int rows;
    private boolean enabled;
    private Phase phase = Phase.BETWEEN;
    private TerminalScreenModel pane;
    /** Identity of {@link #pane}: bumped on every replacement so cache keys survive version collisions. */
    private long paneGeneration;
    private List<TerminalSpan> prompt = new ArrayList<>();
    private String submitted;
    private int continuationStart = -1;
    private long continuationStartEvictions;
    /**
     * PS2 ranges are relative to the pane snapshot that captured them. Text
     * and scrollback eviction checks guard against redraws and shifted rows.
     */
    private record ContinuationPrompt(int start, int end, String text, long evictedRows) {}
    private final List<ContinuationPrompt> continuationPrompts = new ArrayList<>();
    private final StringBuilder bufferedOutput = new StringBuilder();
    // Versioned display cache: the assembled snapshot only changes when one of these
    // inputs changes. Structural block changes advance completedVersion; pane changes
    // are covered by paneGeneration (which survives snapshotVersion collisions across
    // replaced pane instances) plus the pane's own snapshotVersion. prompt/submitted
    // enter the key as length/nullity only, which is sound because their content
    // changes always coincide with a phase change or a pane replacement. Theme is
    // deliberately not part of the key: spans resolve theme colors at render time and
    // carry no theme state here, so cached spans are theme-independent by construction.
    private TerminalScreenModel.Snapshot cachedSnapshot;
    private long cachedCompletedVersion = -1;
    private long cachedPaneGeneration = -1;
    private long cachedPaneVersion = -1;
    private int cachedColumns = -1;
    private Phase cachedPhase;
    private int cachedPromptLength = -1;
    private boolean cachedSubmittedNull;
    /** Test probe: number of paneSnapshot() builds (package-private observability). */
    private long paneSnapshotBuildProbe;

    ShellCommandTranscript(int columns, int rows) { this.columns = columns; this.rows = rows; }
    boolean enabled() { return enabled; }
    boolean isEditingPrompt() { return phase == Phase.PROMPT || phase == Phase.INPUT; }

    void clearVisibleHistory(boolean preserveInput, boolean eraseGrid) {
        completed.clear(); retainedChars = 0; evictedRows = 0; completedVersion++;
        if (preserveInput && isEditingPrompt()) {
            if (eraseGrid) clearContinuations();
            return;
        }
        clearContinuations();
        pane = replacePane(); prompt = new ArrayList<>(); submitted = null; phase = Phase.BETWEEN;
        bufferedOutput.setLength(0);
    }

    private void clearContinuations() {
        continuationStart = -1;
        continuationPrompts.clear();
    }

    void accept(int codePoint) {
        if (enabled && pane != null) bufferedOutput.appendCodePoint(codePoint);
    }

    void flush() {
        if (pane != null && bufferedOutput.length() > 0) pane.append(bufferedOutput.toString());
        bufferedOutput.setLength(0);
    }

    /** Returns true when an integrated primary prompt has finished rendering. */
    boolean marker(String value, Supplier<TerminalScreenModel.Snapshot> original) {
        flush();
        if (value.equals("133;A;orchestella=1")) {
            if (!enabled) {
                enabled = true;
                // The only consumer of the caller's snapshot; fetched lazily so the
                // enabling OSC itself never pays for a full-history projection.
                addCompleted(trim(original.get().spans), -1);
            } else if (phase == Phase.OUTPUT) finishOutput();
            prompt = new ArrayList<>(); submitted = null;
            pane = replacePane(); phase = Phase.PROMPT;
            clearContinuations();
            return false;
        }
        if (!enabled) return false;
        if (value.equals("133;B") && phase == Phase.PROMPT) {
            TerminalScreenModel.Snapshot snapshot = paneSnapshot();
            prompt = slice(snapshot.spans, 0, snapshot.cursorOffset);
            pane = replacePane(); phase = Phase.INPUT;
            clearContinuations();
            return true;
        } else if (value.equals("133;B;continuation=1") && phase == Phase.INPUT) {
            // A second opening marker without a closing one means a redraw
            // invalidated the previous pair: drop it rather than keeping a
            // range that now points at command text.
            TerminalScreenModel.Snapshot snapshot = paneSnapshot();
            continuationStart = snapshot.cursorOffset;
            continuationStartEvictions = snapshot.evictedHistoryRows;
        } else if (value.equals("133;B;continuation=2") && phase == Phase.INPUT) {
            TerminalScreenModel.Snapshot snapshot = paneSnapshot();
            String text = snapshot.text();
            int end = snapshot.cursorOffset;
            if (continuationStart >= 0 && end > continuationStart && end <= text.length()
                    && continuationStartEvictions == snapshot.evictedHistoryRows) {
                // Readline redraws re-send the same PS2 at the same absolute offset.
                // Keeping both records made the second deletion eat the literal "> "
                // a user typed right after the marker: after the first deletion the
                // literal shifts into the stale range and matches its captured text.
                // One non-overlapping range per marker pair keeps that from recurring.
                continuationPrompts.removeIf(range -> range.evictedRows != snapshot.evictedHistoryRows
                        || (range.start < end && continuationStart < range.end));
                continuationPrompts.add(new ContinuationPrompt(continuationStart, end,
                        text.substring(continuationStart, end), snapshot.evictedHistoryRows));
            }
            continuationStart = -1;
        } else if (value.equals("133;C") || value.startsWith("133;C;cmd=")) {
            if (phase != Phase.INPUT) return false;
            submitted = value.startsWith("133;C;cmd=") ? decode(value.substring(10)) : null;
            if (submitted != null) {
                // Validate and strip PS2 ranges on the UNTRIMMED pane text: a trailing
                // empty continuation line ends in "> " and trimming first would cut
                // the marker to ">", pushing its recorded end past the text and
                // leaving a bare '>' behind. Ranges are deleted from the end of the
                // text backwards so earlier offsets never shift under a later range.
                TerminalScreenModel.Snapshot snapshot = paneSnapshot();
                StringBuilder echo = new StringBuilder(snapshot.text());
                continuationPrompts.sort((left, right) -> Integer.compare(right.start, left.start));
                // Strip only PS2 ranges explicitly bracketed by the guest. A literal
                // '>' in command/output text is never used as a classification rule.
                // Each range must still contain the text captured between its markers:
                // readline re-sends PS2 on redraw, and an unverified stale range can
                // sit on top of real command characters.
                for (ContinuationPrompt range : continuationPrompts) {
                    if (range.evictedRows != snapshot.evictedHistoryRows || range.start > echo.length()) continue;
                    String expected = range.text;
                    if (range.end <= echo.length() && expected.equals(echo.substring(range.start, range.end))) {
                        echo.delete(range.start, range.end);
                        continue;
                    }
                    // The pane snapshot suppresses a line's trailing blanks once the
                    // cursor has moved off it, so a PS2 recorded as "> " reappears as
                    // ">" at submission time and the absolute end lands one cell low.
                    // Fall back to the recorded text without its trailing blanks and
                    // delete only what is actually present. The anchor requires the
                    // core to end the line (newline or text end) — exactly where the
                    // suppression applies — so real characters never match by chance.
                    String core = trimText(expected);
                    int coreEnd = range.start + core.length();
                    if (core.isEmpty() || core.equals(expected) || coreEnd > echo.length()
                            || !core.equals(echo.substring(range.start, coreEnd))) continue;
                    if (coreEnd < echo.length() && echo.charAt(coreEnd) != '\n') continue;
                    echo.delete(range.start, coreEnd);
                }
                // Keep interleaved guest output separate from the authoritative command.
                // Unmatched echo is kept as output rather than guessed to be a command;
                // matching runs before trimming so a command that itself ends in
                // whitespace still matches the echoed text.
                String observed = echo.toString();
                int echoedAt = observed.lastIndexOf(submitted);
                int echoLength = submitted.length();
                if (echoedAt < 0) {
                    // Snapshot rows omit inactive trailing blanks. Only accept
                    // a shorter match when it covers an entire visible line.
                    String visible = trimText(submitted);
                    int at = visible.isEmpty() ? -1 : observed.lastIndexOf(visible);
                    int end = at + visible.length();
                    if (at >= 0 && (at == 0 || observed.charAt(at - 1) == '\n')
                            && (end == observed.length() || observed.charAt(end) == '\n')) {
                        echoedAt = at;
                        echoLength = visible.length();
                    }
                }
                if (echoedAt >= 0) {
                    observed = observed.substring(0, echoedAt) + observed.substring(echoedAt + echoLength);
                }
                if (!trimText(observed).isEmpty()) addCompleted(plain(trimText(observed)), -1);
            } else {
                // A missing/invalid payload (HISTCONTROL/HISTIGNORE, >8 KiB, silent
                // reads) is deliberately never archived as a command, but the echoed
                // input line stays visible as ordinary VT text instead of vanishing
                // with the discarded pane. Unlike the payload path above, no PS2
                // ranges are stripped: raw VT keeps exactly what the screen showed.
                List<TerminalSpan> kept = new ArrayList<>(prompt);
                kept.addAll(paneSnapshot().spans);
                addCompleted(trim(kept), -1);
            }
            clearContinuations();
            pane = replacePane(); phase = Phase.OUTPUT;
        } else if (value.startsWith("133;D")) {
            if (phase == Phase.OUTPUT) finishOutput();
            else if (phase == Phase.BETWEEN && pane != null) addCompleted(trim(paneSnapshot().spans), -1);
            // Ctrl+C and empty input have no C payload. Discard unsubmitted echo.
            pane = replacePane(); prompt = new ArrayList<>(); submitted = null; phase = Phase.BETWEEN;
            clearContinuations();
        }
        return false;
    }

    void resize(int columns, int rows) {
        this.columns = columns; this.rows = rows;
        if (pane != null) pane.resize(columns, rows);
    }

    TerminalScreenModel.Snapshot snapshot() {
        long paneVersion = pane == null ? -1 : pane.snapshotVersion();
        int promptLength = length(prompt);
        if (cachedSnapshot != null && cachedCompletedVersion == completedVersion
                && cachedPaneGeneration == paneGeneration && cachedPaneVersion == paneVersion
                && cachedColumns == columns && cachedPhase == phase
                && cachedPromptLength == promptLength && cachedSubmittedNull == (submitted == null)) {
            return cachedSnapshot;
        }
        List<TerminalSpan> result = new ArrayList<>();
        List<TerminalScreenModel.Snapshot.CommandRange> ranges = new ArrayList<>();
        int displayLength = 0;
        int newlineCount = 0;
        for (BlockEntry entry : completed) {
            ensureWrapped(entry);
            if (!result.isEmpty()) {
                result.add(BLOCK_SEPARATOR);
                displayLength += 2; newlineCount += 2;
            }
            result.addAll(entry.wrapped);
            displayLength += entry.wrappedChars;
            if (entry.commandHeadLines > 0) {
                ranges.add(new TerminalScreenModel.Snapshot.CommandRange(
                        newlineCount, newlineCount + entry.commandHeadLines));
            }
            newlineCount += entry.wrappedNewlines;
        }
        int displayCursor;
        long currentEvictions = 0;
        if (pane == null) {
            displayCursor = displayLength;
        } else {
            TerminalScreenModel.Snapshot current = paneSnapshot();
            currentEvictions = current.evictedHistoryRows;
            List<TerminalSpan> active = new ArrayList<>();
            int panePrefix = 0;
            int commandHeadChars = -1;
            if (phase == Phase.INPUT) {
                active.addAll(prompt); panePrefix = length(prompt);
            } else if (phase == Phase.OUTPUT && submitted != null) {
                active.addAll(command());
                active.add(BLOCK_SEPARATOR);
                panePrefix = length(active);
                commandHeadChars = length(prompt) + submitted.length();
            }
            active.addAll(current.spans);
            if (active.isEmpty()) {
                // Historical mapping quirk: with no active text the source cursor
                // target lands past the assembled text whenever a separator was
                // accounted for, which mapped the display cursor to 0. Preserved
                // verbatim so renderer behavior stays bit-identical.
                displayCursor = 0;
            } else {
                if (!result.isEmpty()) {
                    result.add(BLOCK_SEPARATOR);
                    displayLength += 2; newlineCount += 2;
                }
                WrapOutcome wrapped = wrapSpans(active, panePrefix + current.cursorOffset, commandHeadChars);
                result.addAll(wrapped.spans);
                displayCursor = displayLength + wrapped.cursor;
                displayLength += wrapped.displayChars;
                if (wrapped.commandHeadLines > 0) {
                    ranges.add(new TerminalScreenModel.Snapshot.CommandRange(
                            newlineCount, newlineCount + wrapped.commandHeadLines));
                }
                newlineCount += wrapped.newlines;
            }
        }
        TerminalScreenModel.Snapshot snapshot = new TerminalScreenModel.Snapshot(
                result, displayCursor, 0, evictedRows + currentEvictions, ranges);
        cachedSnapshot = snapshot;
        cachedCompletedVersion = completedVersion;
        cachedPaneGeneration = paneGeneration;
        cachedPaneVersion = paneVersion;
        cachedColumns = columns;
        cachedPhase = phase;
        cachedPromptLength = promptLength;
        cachedSubmittedNull = submitted == null;
        return snapshot;
    }

    /** Wrapping outcome: display spans, mapped cursor, newline count, and command-head lines. */
    private static final class WrapOutcome {
        final List<TerminalSpan> spans;
        final int cursor;
        final int newlines;
        final int displayChars;
        final int commandHeadLines;
        WrapOutcome(List<TerminalSpan> spans, int cursor, int newlines, int displayChars, int commandHeadLines) {
            this.spans = spans;
            this.cursor = cursor;
            this.newlines = newlines;
            this.displayChars = displayChars;
            this.commandHeadLines = commandHeadLines;
        }
    }

    /**
     * TextView intentionally disables automatic wrapping for VT grids. Semantic
     * history must therefore supply its own display line breaks, using the same
     * cell widths as the grid and including the prompt's occupied columns.
     * Stored command text stays unchanged so resizing can lay it out again.
     * Also counts wrapped newlines and the display lines covered by the command
     * head: lines from the block's first line through the line holding the last
     * of the first {@code commandHeadChars} source chars (prompt plus submitted
     * command); no range when negative.
     */
    private WrapOutcome wrapSpans(List<TerminalSpan> spans, int sourceCursor, int commandHeadChars) {
        List<TerminalSpan> wrapped = new ArrayList<>();
        int column = 0;
        int sourceOffset = 0;
        int displayOffset = 0;
        int displayCursor = 0;
        int newlines = 0;
        int commandHeadNewlines = -1;
        for (TerminalSpan span : spans) {
            String value = span.getText();
            StringBuilder text = new StringBuilder(value.length());
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                int width = codePoint == '\t' ? 1 : TerminalScreenModel.characterWidth(codePoint);
                if (codePoint != '\n' && width > 0 && column + width > columns) {
                    text.append('\n');
                    column = 0;
                    newlines++;
                }
                if (sourceOffset == sourceCursor) displayCursor = displayOffset + text.length();
                if (commandHeadChars > 0 && sourceOffset < commandHeadChars
                        && sourceOffset + Character.charCount(codePoint) >= commandHeadChars) {
                    commandHeadNewlines = newlines;
                }
                if (codePoint == '\n') {
                    text.append('\n');
                    column = 0;
                    newlines++;
                } else if (codePoint == '\t') {
                    int spaces = Math.min(columns - column, 8 - column % 8);
                    for (int i = 0; i < spaces; i++) text.append(' ');
                    column += spaces;
                } else {
                    text.appendCodePoint(codePoint);
                    column += width;
                }
                int count = Character.charCount(codePoint);
                offset += count;
                sourceOffset += count;
            }
            wrapped.add(span.withText(text.toString()));
            displayOffset += text.length();
        }
        if (sourceCursor == sourceOffset) displayCursor = displayOffset;
        return new WrapOutcome(wrapped, displayCursor, newlines, displayOffset,
                commandHeadNewlines < 0 ? 0 : commandHeadNewlines + 1);
    }

    private void finishOutput() {
        List<TerminalSpan> block = new ArrayList<>();
        int commandHeadChars = -1;
        if (submitted != null) {
            block.addAll(command());
            commandHeadChars = length(prompt) + submitted.length();
        }
        TerminalScreenModel.Snapshot current = paneSnapshot();
        evictedRows += current.evictedHistoryRows;
        List<TerminalSpan> output = trim(current.spans);
        // A single command's output is bounded independently of its immutable command.
        if (length(output) > 32_000) {
            int cut = length(output) - 32_000;
            evictedRows += newlines(slice(output, 0, cut));
            output = slice(output, cut, length(output));
        }
        if (!output.isEmpty()) {
            if (!block.isEmpty()) block.add(unstyled("\n\n"));
            block.addAll(output);
        }
        addCompleted(block, commandHeadChars);
        submitted = null;
    }

    private List<TerminalSpan> command() {
        List<TerminalSpan> result = new ArrayList<>(prompt);
        result.add(TerminalSpan.forCommand(submitted));
        return result;
    }

    private void addCompleted(List<TerminalSpan> block, int commandHeadChars) {
        if (block.isEmpty()) return;
        int size = length(block);
        if (size > MAX_HISTORY_CHARS) {
            int cut = size - MAX_HISTORY_CHARS;
            block = slice(block, cut, size);
            // The history bound keeps the block tail, so the command head can fall
            // out entirely; re-anchor it to the sliced coordinates.
            commandHeadChars = commandHeadChars > cut ? commandHeadChars - cut : -1;
        }
        int retained = length(block);
        completed.addLast(new BlockEntry(block, retained, commandHeadChars));
        retainedChars += retained;
        completedVersion++;
        while (retainedChars > MAX_HISTORY_CHARS || completed.size() > MAX_BLOCKS) {
            BlockEntry dropped = completed.removeFirst();
            retainedChars -= dropped.sourceChars;
            evictedRows += evictedWrappedNewlines(dropped) + 2;
        }
    }

    /**
     * Display rows the evicted block occupied. Reuses the cached wrap count when
     * the block is current for these columns; re-wraps at most once (and discards
     * the spans) when columns moved on without the block being laid out again.
     */
    private int evictedWrappedNewlines(BlockEntry entry) {
        if (entry.cachedColumns == columns) return entry.wrappedNewlines;
        return wrapSpans(entry.source, -1, entry.commandHeadChars).newlines;
    }

    private void ensureWrapped(BlockEntry entry) {
        if (entry.cachedColumns == columns) return;
        WrapOutcome outcome = wrapSpans(entry.source, -1, entry.commandHeadChars);
        entry.wrapped = outcome.spans;
        entry.wrappedNewlines = outcome.newlines;
        entry.wrappedChars = outcome.displayChars;
        entry.commandHeadLines = outcome.commandHeadLines;
        entry.cachedColumns = columns;
    }

    /** Archived block plus its display-wrap cache for the columns it was last laid out at. */
    private static final class BlockEntry {
        final List<TerminalSpan> source;
        final int sourceChars;
        /** Source chars covering prompt + submitted command; -1 when the block has no command head. */
        final int commandHeadChars;
        List<TerminalSpan> wrapped;
        int wrappedNewlines;
        int wrappedChars;
        int commandHeadLines;
        int cachedColumns = -1;
        BlockEntry(List<TerminalSpan> source, int sourceChars, int commandHeadChars) {
            this.source = source;
            this.sourceChars = sourceChars;
            this.commandHeadChars = commandHeadChars;
        }
    }

    private TerminalScreenModel newPane() { return new TerminalScreenModel(columns, rows, false); }

    /** Replaces {@link #pane} and bumps its cache identity so stale versions cannot collide. */
    private TerminalScreenModel replacePane() {
        paneGeneration++;
        return newPane();
    }

    /** Test probe: total pane.snapshot() builds since construction (package-private). */
    long paneSnapshotBuildProbe() { return paneSnapshotBuildProbe; }

    /** Single funnel for pane snapshots so the lazy-OSC probe cannot drift from reality. */
    private TerminalScreenModel.Snapshot paneSnapshot() {
        paneSnapshotBuildProbe++;
        return pane.snapshot();
    }

    private static String decode(String value) {
        if (value.length() > 11_000) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length == 0 || bytes.length > 8192) return null;
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if ((ch < 0x20 && ch != '\n' && ch != '\t') || ch == 0x7f || (ch >= 0x80 && ch <= 0x9f)) return null;
            }
            return text;
        } catch (IllegalArgumentException | CharacterCodingException malformed) { return null; }
    }
    private static List<TerminalSpan> trim(List<TerminalSpan> spans) {
        StringBuilder text = new StringBuilder();
        for (TerminalSpan span : spans) text.append(span.getText());
        return slice(spans, 0, trimText(text.toString()).length());
    }
    private static String trimText(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) end--;
        return value.substring(0, end);
    }
    private static List<TerminalSpan> plain(String text) {
        List<TerminalSpan> result = new ArrayList<>(); result.add(unstyled(text)); return result;
    }
    private static TerminalSpan unstyled(String text) { return new TerminalSpan(text, null, null, false, false, false, false); }
    private static int length(List<TerminalSpan> spans) {
        int size = 0; for (TerminalSpan span : spans) size += span.getText().length(); return size;
    }
    private static int newlines(List<TerminalSpan> spans) {
        int count = 0;
        for (TerminalSpan span : spans) for (int i = 0; i < span.getText().length(); i++) if (span.getText().charAt(i) == '\n') count++;
        return count;
    }
    private static List<TerminalSpan> slice(List<TerminalSpan> spans, int start, int end) {
        List<TerminalSpan> result = new ArrayList<>();
        int offset = 0;
        for (TerminalSpan span : spans) {
            String text = span.getText();
            int from = Math.max(0, start - offset);
            int to = Math.min(text.length(), end - offset);
            if (from < to) {
                if (from > 0 && Character.isLowSurrogate(text.charAt(from))) from--;
                if (to < text.length() && Character.isLowSurrogate(text.charAt(to))) to++;
                result.add(span.withText(text.substring(from, to)));
            }
            offset += text.length();
            if (offset >= end) break;
        }
        return result;
    }
}
