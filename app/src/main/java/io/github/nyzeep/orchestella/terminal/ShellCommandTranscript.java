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
    private final List<int[]> continuationPrompts = new ArrayList<>();
    /**
     * PS2 text captured between the continuation markers, index-aligned with
     * [continuationPrompts]. Readline re-sends the whole PS2 on every redraw
     * (Ctrl+L, window resize), so an absolute range recorded once can later
     * point at command text; deleting on the range alone silently ate real
     * characters. Matching the captured text before deleting keeps a stale
     * range from touching anything it did not create.
     */
    private final List<String> continuationTexts = new ArrayList<>();
    private final StringBuilder bufferedOutput = new StringBuilder();

    ShellCommandTranscript(int columns, int rows) { this.columns = columns; this.rows = rows; }
    boolean enabled() { return enabled; }
    boolean isEditingPrompt() { return phase == Phase.PROMPT || phase == Phase.INPUT; }

    void clearVisibleHistory(boolean preserveInput) {
        completed.clear(); retainedChars = 0; evictedRows = 0;
        if (!preserveInput || !isEditingPrompt()) {
            pane = newPane(); prompt = new ArrayList<>(); submitted = null; phase = Phase.BETWEEN;
            bufferedOutput.setLength(0); continuationStart = -1; continuationPrompts.clear();
        }
    }

    void accept(int codePoint) {
        if (enabled && pane != null) bufferedOutput.appendCodePoint(codePoint);
    }

    void flush() {
        if (pane != null && bufferedOutput.length() > 0) pane.append(bufferedOutput.toString());
        bufferedOutput.setLength(0);
    }

    void marker(String value, TerminalScreenModel.Snapshot original) {
        flush();
        if (value.equals("133;A;orchestella=1")) {
            if (!enabled) {
                enabled = true;
                addCompleted(trim(original.spans));
            } else if (phase == Phase.OUTPUT) finishOutput();
            prompt = new ArrayList<>(); submitted = null;
            pane = newPane(); phase = Phase.PROMPT;
            continuationStart = -1; continuationPrompts.clear(); continuationTexts.clear();
            return;
        }
        if (!enabled) return;
        if (value.equals("133;B") && phase == Phase.PROMPT) {
            TerminalScreenModel.Snapshot snapshot = pane.snapshot();
            prompt = slice(snapshot.spans, 0, snapshot.cursorOffset);
            pane = newPane(); phase = Phase.INPUT;
            continuationStart = -1; continuationPrompts.clear(); continuationTexts.clear();
        } else if (value.equals("133;B;continuation=1") && phase == Phase.INPUT) {
            // A second opening marker without a closing one means a redraw
            // invalidated the previous pair: drop it rather than keeping a
            // range that now points at command text.
            continuationStart = pane.snapshot().cursorOffset;
        } else if (value.equals("133;B;continuation=2") && phase == Phase.INPUT) {
            String text = pane.snapshot().text();
            int end = pane.snapshot().cursorOffset;
            if (continuationStart >= 0 && end >= continuationStart && end <= text.length()) {
                continuationPrompts.add(new int[]{continuationStart, end});
                continuationTexts.add(text.substring(continuationStart, end));
            }
            continuationStart = -1;
        } else if (value.equals("133;C") || value.startsWith("133;C;cmd=")) {
            if (phase != Phase.INPUT) return;
            submitted = value.startsWith("133;C;cmd=") ? decode(value.substring(10)) : null;
            // Keep interleaved guest output separate from the authoritative command.
            // Unmatched echo is kept as output rather than guessed to be a command.
            if (submitted != null) {
                StringBuilder echo = new StringBuilder(trimText(pane.snapshot().text()));
                // Strip only PS2 ranges explicitly bracketed by the guest. A literal
                // '>' in command/output text is never used as a classification rule.
                // Each range must still contain the text captured between its markers:
                // readline re-sends PS2 on redraw, and an unverified stale range can
                // sit on top of real command characters.
                for (int i = continuationPrompts.size() - 1; i >= 0; i--) {
                    int[] range = continuationPrompts.get(i);
                    String expected = continuationTexts.get(i);
                    if (range[0] > range[1] || range[1] > echo.length()) continue;
                    if (range[1] - range[0] != expected.length()) continue;
                    if (!echo.substring(range[0], range[1]).equals(expected)) continue;
                    echo.delete(range[0], range[1]);
                }
                String observed = echo.toString();
                int echoedAt = observed.lastIndexOf(submitted);
                if (echoedAt >= 0) observed = observed.substring(0, echoedAt) + observed.substring(echoedAt + submitted.length());
                if (!observed.trim().isEmpty()) addCompleted(plain(observed));
            }
            // A missing/invalid payload is deliberately never archived as a command.
            pane = newPane(); phase = Phase.OUTPUT;
        } else if (value.startsWith("133;D")) {
            if (phase == Phase.OUTPUT) finishOutput();
            else if (phase == Phase.BETWEEN && pane != null) addCompleted(trim(pane.snapshot().spans));
            // Ctrl+C and empty input have no C payload. Discard unsubmitted echo.
            pane = newPane(); prompt = new ArrayList<>(); submitted = null; phase = Phase.BETWEEN;
            continuationStart = -1; continuationPrompts.clear(); continuationTexts.clear();
        }
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
            wrapped.add(new TerminalSpan(text.toString(), span.getForegroundColor(), span.getBackgroundColor(),
                    span.isBold(), span.isItalic(), span.isUnderline(), span.isInverse()));
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
        // 只靠字重强调：此前的反色块把命令画成"主题前景色底 + 黑/白字"，
        // 深色主题下是一条近白色横条，比输出更抢眼（用户反馈）。粗体无底色
        // 走 TerminalSpan 的 softenForeground()，与系统日志的粗体同一语言。
        result.add(new TerminalSpan(submitted, null, null, true, false, false, false));
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
                result.add(new TerminalSpan(text.substring(from, to), span.getForegroundColor(), span.getBackgroundColor(),
                        span.isBold(), span.isItalic(), span.isUnderline(), span.isInverse()));
            }
            offset += text.length();
            if (offset >= end) break;
        }
        return result;
    }
}
