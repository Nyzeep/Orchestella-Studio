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
    private final Deque<List<TerminalSpan>> completed = new ArrayDeque<>();
    private int retainedChars;
    private long evictedRows;
    private int columns;
    private int rows;
    private boolean enabled;
    private Phase phase = Phase.BETWEEN;
    private TerminalScreenModel pane;
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

    ShellCommandTranscript(int columns, int rows) { this.columns = columns; this.rows = rows; }
    boolean enabled() { return enabled; }
    boolean isEditingPrompt() { return phase == Phase.PROMPT || phase == Phase.INPUT; }

    void clearVisibleHistory(boolean preserveInput, boolean eraseGrid) {
        completed.clear(); retainedChars = 0; evictedRows = 0;
        if (preserveInput && isEditingPrompt()) {
            if (eraseGrid) clearContinuations();
            return;
        }
        clearContinuations();
        pane = newPane(); prompt = new ArrayList<>(); submitted = null; phase = Phase.BETWEEN;
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
    boolean marker(String value, TerminalScreenModel.Snapshot original) {
        flush();
        if (value.equals("133;A;orchestella=1")) {
            if (!enabled) {
                enabled = true;
                addCompleted(trim(original.spans));
            } else if (phase == Phase.OUTPUT) finishOutput();
            prompt = new ArrayList<>(); submitted = null;
            pane = newPane(); phase = Phase.PROMPT;
            clearContinuations();
            return false;
        }
        if (!enabled) return false;
        if (value.equals("133;B") && phase == Phase.PROMPT) {
            TerminalScreenModel.Snapshot snapshot = pane.snapshot();
            prompt = slice(snapshot.spans, 0, snapshot.cursorOffset);
            pane = newPane(); phase = Phase.INPUT;
            clearContinuations();
            return true;
        } else if (value.equals("133;B;continuation=1") && phase == Phase.INPUT) {
            // A second opening marker without a closing one means a redraw
            // invalidated the previous pair: drop it rather than keeping a
            // range that now points at command text.
            TerminalScreenModel.Snapshot snapshot = pane.snapshot();
            continuationStart = snapshot.cursorOffset;
            continuationStartEvictions = snapshot.evictedHistoryRows;
        } else if (value.equals("133;B;continuation=2") && phase == Phase.INPUT) {
            TerminalScreenModel.Snapshot snapshot = pane.snapshot();
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
                TerminalScreenModel.Snapshot snapshot = pane.snapshot();
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
                if (!trimText(observed).isEmpty()) addCompleted(plain(trimText(observed)));
            } else {
                // A missing/invalid payload (HISTCONTROL/HISTIGNORE, >8 KiB, silent
                // reads) is deliberately never archived as a command, but the echoed
                // input line stays visible as ordinary VT text instead of vanishing
                // with the discarded pane. Unlike the payload path above, no PS2
                // ranges are stripped: raw VT keeps exactly what the screen showed.
                List<TerminalSpan> kept = new ArrayList<>(prompt);
                kept.addAll(pane.snapshot().spans);
                addCompleted(trim(kept));
            }
            clearContinuations();
            pane = newPane(); phase = Phase.OUTPUT;
        } else if (value.startsWith("133;D")) {
            if (phase == Phase.OUTPUT) finishOutput();
            else if (phase == Phase.BETWEEN && pane != null) addCompleted(trim(pane.snapshot().spans));
            // Ctrl+C and empty input have no C payload. Discard unsubmitted echo.
            pane = newPane(); prompt = new ArrayList<>(); submitted = null; phase = Phase.BETWEEN;
            clearContinuations();
        }
        return false;
    }

    void resize(int columns, int rows) {
        this.columns = columns; this.rows = rows;
        if (pane != null) pane.resize(columns, rows);
    }

    TerminalScreenModel.Snapshot snapshot() {
        List<TerminalSpan> result = new ArrayList<>();
        for (List<TerminalSpan> block : completed) appendBlock(result, block);
        int cursorOffset = length(result);
        long currentEvictions = 0;
        if (pane != null) {
            TerminalScreenModel.Snapshot current = pane.snapshot();
            currentEvictions = current.evictedHistoryRows;
            List<TerminalSpan> active = new ArrayList<>();
            int panePrefix = 0;
            if (phase == Phase.INPUT) {
                active.addAll(prompt); panePrefix = length(prompt);
            } else if (phase == Phase.OUTPUT && submitted != null) {
                active.addAll(command());
                active.add(unstyled("\n\n"));
                panePrefix = length(active);
            }
            active.addAll(current.spans);
            int blockStart = length(result) + (result.isEmpty() ? 0 : 2);
            appendBlock(result, active);
            cursorOffset = blockStart + panePrefix + current.cursorOffset;
        }
        return wrapForDisplay(result, cursorOffset, evictedRows + currentEvictions);
    }

    /**
     * TextView intentionally disables automatic wrapping for VT grids. Semantic
     * history must therefore supply its own display line breaks, using the same
     * cell widths as the grid and including the prompt's occupied columns.
     * Stored command text stays unchanged so resizing can lay it out again.
     */
    private TerminalScreenModel.Snapshot wrapForDisplay(List<TerminalSpan> spans,
            int sourceCursor, long evictions) {
        List<TerminalSpan> wrapped = new ArrayList<>();
        int column = 0;
        int sourceOffset = 0;
        int displayOffset = 0;
        int displayCursor = 0;
        for (TerminalSpan span : spans) {
            String value = span.getText();
            StringBuilder text = new StringBuilder(value.length());
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                int width = codePoint == '\t' ? 1 : TerminalScreenModel.characterWidth(codePoint);
                if (codePoint != '\n' && width > 0 && column + width > columns) {
                    text.append('\n');
                    column = 0;
                }
                if (sourceOffset == sourceCursor) displayCursor = displayOffset + text.length();
                if (codePoint == '\n') {
                    text.append('\n');
                    column = 0;
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
        return new TerminalScreenModel.Snapshot(wrapped, displayCursor, 0, evictions);
    }

    private void finishOutput() {
        List<TerminalSpan> block = new ArrayList<>();
        if (submitted != null) block.addAll(command());
        TerminalScreenModel.Snapshot current = pane.snapshot();
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
        addCompleted(block);
        submitted = null;
    }

    private List<TerminalSpan> command() {
        List<TerminalSpan> result = new ArrayList<>(prompt);
        result.add(TerminalSpan.forCommand(submitted));
        return result;
    }

    private void addCompleted(List<TerminalSpan> block) {
        if (block.isEmpty()) return;
        int size = length(block);
        if (size > MAX_HISTORY_CHARS) block = slice(block, size - MAX_HISTORY_CHARS, size);
        completed.addLast(block);
        retainedChars += length(block);
        while (retainedChars > MAX_HISTORY_CHARS || completed.size() > MAX_BLOCKS) {
            List<TerminalSpan> dropped = completed.removeFirst();
            retainedChars -= length(dropped);
            evictedRows += newlines(wrapForDisplay(dropped, 0, 0).spans) + 2;
        }
    }

    private TerminalScreenModel newPane() { return new TerminalScreenModel(columns, rows, false); }
    private static void appendBlock(List<TerminalSpan> result, List<TerminalSpan> block) {
        if (block.isEmpty()) return;
        if (!result.isEmpty()) result.add(unstyled("\n\n"));
        result.addAll(block);
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
