package io.github.nyzeep.orchestella.terminal;

import io.github.nyzeep.orchestella.AnsiEscapeParser;
import io.github.nyzeep.orchestella.TerminalSpan;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Bounded VT screen state, independent of Android and of the guest's line editor.
 * Implements the cursor, erase, scrolling and DEC modes needed by shell/readline,
 * top and ordinary text-mode editors. Unrecognised control strings are discarded.
 */
public final class TerminalScreenModel {
    private static final int MAX_CONTROL_CHARS = 4096;
    private static final int MAX_HISTORY_CELLS = 100_000;
    private static final TerminalSpan DEFAULT_STYLE = new TerminalSpan("", null, null, false, false, false, false);
    private final AnsiEscapeParser styles = new AnsiEscapeParser();
    private TerminalSpan style = defaultStyle();
    private int columns;
    private int rows;
    private Screen primary;
    private Screen alternate;
    private Screen screen;
    private final Deque<Cell[]> history = new ArrayDeque<>();
    private int historyCells;
    private long evictedHistoryRows;
    private final StringBuilder control = new StringBuilder();
    private int state;
    private char pendingHighSurrogate;
    private boolean applicationCursorKeys;
    private boolean bracketedPaste;
    private boolean cursorVisible = true;
    private boolean autoWrap = true;
    private boolean originMode;
    private boolean insertMode;
    private boolean decGraphics;
    private Consumer<String> responseSink = ignored -> {};
    private final boolean shellIntegration;
    private ShellCommandTranscript commandTranscript;
    private boolean oscString;
    private boolean oscOverflow;
    private final StringBuilder osc = new StringBuilder();
    private long displayEpoch;

    public synchronized long displayEpoch() { return displayEpoch; }

    /** Local visible-history action; no cursor reset, process input or shell history mutation. */
    public synchronized boolean clearVisibleHistory() {
        if (screen == alternate) return false;
        history.clear(); historyCells = 0; evictedHistoryRows = 0;
        if (commandTranscript != null) commandTranscript.clearVisibleHistory(true);
        displayEpoch++;
        return true;
    }

    private static final class Cell {
        final String text;
        final TerminalSpan style;
        final int width;
        Cell(String text, TerminalSpan style, int width) {
            this.text = text;
            this.style = style;
            this.width = width;
        }
    }

    private static final class Screen {
        Cell[][] lines;
        int row;
        int column;
        int savedRow;
        int savedColumn;
        TerminalSpan savedStyle;
        int top;
        int bottom;
        boolean wrapPending;
        Screen(int columns, int rows) {
            lines = new Cell[rows][columns];
            bottom = rows - 1;
        }
    }

    public static final class Snapshot {
        public final List<TerminalSpan> spans;
        public final int cursorOffset;
        public final int historyRows;
        public final long evictedHistoryRows;
        Snapshot(List<TerminalSpan> spans, int cursorOffset, int historyRows, long evictedHistoryRows) {
            this.spans = spans;
            this.cursorOffset = cursorOffset;
            this.historyRows = historyRows;
            this.evictedHistoryRows = evictedHistoryRows;
        }
        public String text() {
            StringBuilder value = new StringBuilder();
            for (TerminalSpan span : spans) value.append(span.getText());
            return value.toString();
        }
    }

    public TerminalScreenModel(int columns, int rows) {
        this(columns, rows, true);
    }

    TerminalScreenModel(int columns, int rows, boolean shellIntegration) {
        this.shellIntegration = shellIntegration;
        this.columns = clamp(columns, 2, 512);
        this.rows = clamp(rows, 2, 256);
        reset();
    }

    public synchronized void setResponseSink(Consumer<String> sink) { responseSink = sink == null ? ignored -> {} : sink; }
    public synchronized int columns() { return columns; }
    public synchronized int rows() { return rows; }
    public synchronized int cursorRow() { return screen.row; }
    public synchronized int cursorColumn() { return screen.column; }
    public synchronized boolean isAlternateScreen() { return screen == alternate; }
    public synchronized boolean isApplicationCursorKeys() { return applicationCursorKeys; }
    public synchronized boolean isBracketedPaste() { return bracketedPaste; }
    public synchronized boolean isCursorVisible() { return cursorVisible; }
    public AnsiEscapeParser getStyleParser() { return styles; }

    public synchronized void reset() {
        displayEpoch++;
        primary = new Screen(columns, rows);
        alternate = new Screen(columns, rows);
        screen = primary;
        history.clear();
        historyCells = 0;
        evictedHistoryRows = 0;
        styles.reset();
        style = defaultStyle();
        state = 0;
        control.setLength(0);
        pendingHighSurrogate = 0;
        applicationCursorKeys = bracketedPaste = originMode = insertMode = decGraphics = false;
        cursorVisible = autoWrap = true;
        osc.setLength(0); oscString = oscOverflow = false;
        commandTranscript = shellIntegration ? new ShellCommandTranscript(columns, rows) : null;
    }

    public synchronized void append(String text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (pendingHighSurrogate != 0) {
                char high = pendingHighSurrogate;
                pendingHighSurrogate = 0;
                if (Character.isLowSurrogate(ch)) {
                    accept(Character.toCodePoint(high, ch));
                    continue;
                }
                accept(0xfffd);
            }
            if (Character.isHighSurrogate(ch)) pendingHighSurrogate = ch;
            else accept(Character.isLowSurrogate(ch) ? 0xfffd : ch);
        }
        if (commandTranscript != null) commandTranscript.flush();
    }

    private void accept(int codePoint) {
        // Keep only a bounded OSC payload. Unknown OSC/DCS/APC/PM has no side effects.
        if (state == 3 || state == 4) {
            if (codePoint == 7 || (state == 4 && codePoint == '\\')) {
                if (commandTranscript != null) {
                    // The display pane also saw ESC ] / ESC P; close that ignored control
                    // string so a title/clipboard OSC cannot swallow later plain output.
                    if (codePoint == 7) commandTranscript.accept(7);
                    else { commandTranscript.accept(27); commandTranscript.accept('\\'); }
                }
                if (oscString && !oscOverflow && commandTranscript != null && screen == primary)
                    commandTranscript.marker(osc.toString(), snapshot());
                state = 0; osc.setLength(0); oscString = oscOverflow = false;
            } else if (codePoint == 27) state = 4;
            else {
                state = 3;
                if (oscString && !oscOverflow) {
                    if (osc.length() < 12_000) osc.appendCodePoint(codePoint);
                    else { oscOverflow = true; osc.setLength(0); }
                }
            }
            return;
        }
        // The projection's own primary/alternate buffers consume the same VT stream;
        // it restores primary content after full-screen exit without archiving editor pixels.
        if (commandTranscript != null) commandTranscript.accept(codePoint);
        if (codePoint == 24 || codePoint == 26) { state = 0; control.setLength(0); return; }
        if (codePoint == 27) { state = 1; control.setLength(0); return; }
        if (state == 1) {
            state = 0;
            switch (codePoint) {
                case '[': state = 2; break;
                case ']': state = 3; oscString = true; oscOverflow = false; osc.setLength(0); break;
                case 'P': case '^': case '_': state = 3; oscString = false; break;
                case '(': case ')': state = 5; break;
                case '7': saveCursor(); break;
                case '8': restoreCursor(); break;
                case 'D': index(); break;
                case 'E': screen.column = 0; index(); break;
                case 'M': reverseIndex(); break;
                case 'c':
                    ShellCommandTranscript retainedCommands = commandTranscript;
                    reset();
                    // A user-requested terminal reset starts a new visible display epoch.
                    if (retainedCommands != null) retainedCommands.clearVisibleHistory(false);
                    commandTranscript = retainedCommands;
                    break;
                default: break;
            }
            return;
        }
        if (state == 5) { decGraphics = codePoint == '0'; state = 0; return; }
        if (state == 2) {
            if (codePoint >= 0x40 && codePoint <= 0x7e) {
                String params = control.toString();
                state = 0;
                control.setLength(0);
                csi((char) codePoint, params);
            } else if (codePoint >= 0x20 && codePoint <= 0x3f && control.length() < MAX_CONTROL_CHARS) {
                control.append((char) codePoint);
            } else if (codePoint < 0x20) {
                controlCharacter(codePoint);
            } else { state = 0; control.setLength(0); }
            return;
        }
        if (codePoint < 0x20 || codePoint == 0x7f) controlCharacter(codePoint);
        else put(codePoint);
    }

    private void controlCharacter(int codePoint) {
        switch (codePoint) {
            case '\r': screen.column = 0; screen.wrapPending = false; break;
            case '\n': case 11: case 12: index(); break;
            case '\b': screen.column = Math.max(0, screen.column - 1); screen.wrapPending = false; break;
            case '\t': screen.column = Math.min(columns - 1, (screen.column / 8 + 1) * 8); screen.wrapPending = false; break;
            case 14: decGraphics = true; break;
            case 15: decGraphics = false; break;
            default: break;
        }
    }

    private void put(int codePoint) {
        if (decGraphics && codePoint >= 'j' && codePoint <= 'x') {
            String graphics = "┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│";
            codePoint = graphics.charAt(codePoint - 'j');
        }
        int width = characterWidth(codePoint);
        if (width == 0) {
            int col = screen.wrapPending ? screen.column : screen.column - 1;
            while (col > 0 && screen.lines[screen.row][col] != null
                    && screen.lines[screen.row][col].width == 0) col--;
            if (col >= 0) {
                Cell old = screen.lines[screen.row][col];
                if (old != null) screen.lines[screen.row][col] = new Cell(
                        old.text + new String(Character.toChars(codePoint)), old.style, old.width);
            }
            return;
        }
        if ((screen.wrapPending || (width == 2 && screen.column == columns - 1)) && autoWrap) {
            screen.column = 0;
            index();
        }
        screen.wrapPending = false;
        if (width == 2 && screen.column == columns - 1) width = 1;
        Cell[] line = screen.lines[screen.row];
        if (insertMode) shiftCharacters(width, true);
        clearWideCell(line, screen.column);
        if (width == 2) clearWideCell(line, screen.column + 1);
        line[screen.column] = new Cell(new String(Character.toChars(codePoint)), style, width);
        if (width == 2) line[screen.column + 1] = new Cell("", style, 0);
        if (screen.column + width >= columns) {
            screen.column = columns - 1;
            screen.wrapPending = autoWrap;
        } else screen.column += width;
    }

    private void clearWideCell(Cell[] line, int column) {
        Cell old = line[column];
        if (old != null && old.width == 0 && column > 0) line[column - 1] = null;
        if (old != null && old.width == 2 && column + 1 < columns) line[column + 1] = null;
        line[column] = null;
    }

    private void index() {
        screen.wrapPending = false;
        if (screen.row == screen.bottom) scroll(1, true);
        else screen.row = Math.min(rows - 1, screen.row + 1);
    }

    private void reverseIndex() {
        screen.wrapPending = false;
        if (screen.row == screen.top) scroll(1, false);
        else screen.row = Math.max(0, screen.row - 1);
    }

    private void scroll(int count, boolean up) {
        count = Math.min(count, screen.bottom - screen.top + 1);
        for (int n = 0; n < count; n++) {
            if (up) {
                Cell[] removed = screen.lines[screen.top];
                if (screen == primary && screen.top == 0 && screen.bottom == rows - 1) addHistory(removed);
                System.arraycopy(screen.lines, screen.top + 1, screen.lines, screen.top, screen.bottom - screen.top);
                screen.lines[screen.bottom] = blankLine();
            } else {
                System.arraycopy(screen.lines, screen.top, screen.lines, screen.top + 1, screen.bottom - screen.top);
                screen.lines[screen.top] = blankLine();
            }
        }
    }

    private void addHistory(Cell[] line) {
        history.addLast(line);
        historyCells += line.length;
        while (historyCells > MAX_HISTORY_CELLS || history.size() > 2000) {
            historyCells -= history.removeFirst().length;
            evictedHistoryRows++;
        }
    }

    private void csi(char command, String raw) {
        boolean privateMode = raw.startsWith("?");
        String clean = privateMode || raw.startsWith(">") ? raw.substring(1) : raw;
        String[] values = clean.split(";", -1);
        int n = parameter(values, 0, 1);
        if (command == 'm' && !privateMode && !raw.startsWith(">")) {
            List<TerminalSpan> parsed = styles.parse("\u001b[" + raw + "m ");
            if (!parsed.isEmpty()) style = parsed.get(parsed.size() - 1);
            return;
        }
        if (command == 'h' || command == 'l') {
            for (String value : values) mode(parse(value, 0), privateMode, command == 'h');
            return;
        }
        int top = originMode ? screen.top : 0;
        int bottom = originMode ? screen.bottom : rows - 1;
        switch (command) {
            case 'A': screen.row = Math.max(top, screen.row - n); break;
            case 'B': case 'e': screen.row = Math.min(bottom, screen.row + n); break;
            case 'C': case 'a': screen.column = Math.min(columns - 1, screen.column + n); break;
            case 'D': screen.column = Math.max(0, screen.column - n); break;
            case 'E': screen.row = Math.min(bottom, screen.row + n); screen.column = 0; break;
            case 'F': screen.row = Math.max(top, screen.row - n); screen.column = 0; break;
            case 'G': case '`': screen.column = clamp(n - 1, 0, columns - 1); break;
            case 'd': screen.row = clamp(top + n - 1, top, bottom); break;
            case 'H': case 'f':
                screen.row = clamp(top + n - 1, top, bottom);
                screen.column = clamp(parameter(values, 1, 1) - 1, 0, columns - 1);
                break;
            case 'J': eraseDisplay(parse(values[0], 0)); break;
            case 'K': eraseLine(parse(values[0], 0)); break;
            case 'X': erase(screen.lines[screen.row], screen.column, Math.min(columns, screen.column + n)); break;
            case '@': shiftCharacters(n, true); break;
            case 'P': shiftCharacters(n, false); break;
            case 'L': case 'M':
                if (screen.row >= screen.top && screen.row <= screen.bottom) {
                    int savedTop = screen.top;
                    screen.top = screen.row;
                    scroll(n, command == 'M');
                    screen.top = savedTop;
                }
                break;
            case 'S': scroll(n, true); break;
            case 'T': scroll(n, false); break;
            case 'r':
                int first = clamp(n - 1, 0, rows - 1);
                int last = clamp(parameter(values, 1, rows) - 1, 0, rows - 1);
                if (!privateMode && first < last) {
                    screen.top = first; screen.bottom = last;
                    screen.row = originMode ? first : 0; screen.column = 0;
                }
                break;
            case 's': saveCursor(); break;
            case 'u': restoreCursor(); break;
            case 'n':
                if (raw.equals("5")) responseSink.accept("\u001b[0n");
                if (raw.equals("6")) responseSink.accept("\u001b[" + (screen.row + 1) + ";" + (screen.column + 1) + "R");
                break;
            case 'c':
                // Serial terminals can echo replies; capability reports are not queries.
                if (raw.isEmpty() || raw.equals("0")) responseSink.accept("\u001b[?1;2c");
                break;
            default: break;
        }
        screen.wrapPending = false;
    }

    private void mode(int mode, boolean privateMode, boolean enabled) {
        if (!privateMode) { if (mode == 4) insertMode = enabled; return; }
        switch (mode) {
            case 1: applicationCursorKeys = enabled; break;
            case 6: originMode = enabled; screen.row = enabled ? screen.top : 0; screen.column = 0; break;
            case 7: autoWrap = enabled; break;
            case 25: cursorVisible = enabled; break;
            case 47: case 1047: case 1049:
                if (enabled && screen != alternate) {
                    if (mode == 1049) saveCursor();
                    if (mode != 47) alternate = new Screen(columns, rows);
                    screen = alternate;
                } else if (!enabled && screen != primary) {
                    screen = primary;
                    if (mode == 1049) restoreCursor();
                }
                break;
            case 1048: if (enabled) saveCursor(); else restoreCursor(); break;
            case 2004: bracketedPaste = enabled; break;
            default: break;
        }
    }

    private void saveCursor() {
        screen.savedRow = screen.row; screen.savedColumn = screen.column; screen.savedStyle = style;
    }

    private void restoreCursor() {
        screen.row = clamp(screen.savedRow, 0, rows - 1);
        screen.column = clamp(screen.savedColumn, 0, columns - 1);
        screen.wrapPending = false;
        if (screen.savedStyle != null) {
            style = screen.savedStyle;
            StringBuilder sgr = new StringBuilder("0");
            if (style.isBold()) sgr.append(";1");
            if (style.isItalic()) sgr.append(";3");
            if (style.isUnderline()) sgr.append(";4");
            if (style.isInverse()) sgr.append(";7");
            appendColor(sgr, 38, style.getForegroundColor());
            appendColor(sgr, 48, style.getBackgroundColor());
            styles.parse("\u001b[" + sgr + "m");
        }
    }

    private static void appendColor(StringBuilder sgr, int kind, Integer color) {
        if (color != null) sgr.append(';').append(kind).append(";2;")
                .append((color >> 16) & 255).append(';').append((color >> 8) & 255).append(';').append(color & 255);
    }

    private void eraseLine(int mode) {
        if (mode == 0) erase(screen.lines[screen.row], screen.column, columns);
        else if (mode == 1) erase(screen.lines[screen.row], 0, screen.column + 1);
        else if (mode == 2) erase(screen.lines[screen.row], 0, columns);
    }

    private void eraseDisplay(int mode) {
        if (mode == 3) {
            evictedHistoryRows += history.size();
            history.clear(); historyCells = 0;
            if (screen == primary && commandTranscript != null && commandTranscript.enabled()) {
                commandTranscript.clearVisibleHistory(false);
                displayEpoch++;
            }
            return;
        }
        if (mode == 2) {
            // Readline Ctrl+L clears from HOME while editing. Output/TUI redraws
            // alone do not erase committed blocks; clear's explicit 3J does.
            if (screen == primary && screen.row == 0 && screen.column == 0
                    && commandTranscript != null && commandTranscript.enabled() && commandTranscript.isEditingPrompt()) {
                commandTranscript.clearVisibleHistory(false);
                displayEpoch++;
            }
            for (int row = 0; row < rows; row++) screen.lines[row] = blankLine();
        } else if (mode == 0) {
            // vt220/ncurses clear emits HOME + ED with an omitted (zero) parameter.
            // At the absolute top-left ED0 erases the entire visible screen, in
            // both input and command-output phases. Partial ED0 and alternate
            // redraws keep their existing grid and semantic-history behavior.
            if (screen == primary && screen.row == 0 && screen.column == 0
                    && commandTranscript != null && commandTranscript.enabled()) {
                commandTranscript.clearVisibleHistory(false);
                displayEpoch++;
            }
            eraseLine(0);
            for (int row = screen.row + 1; row < rows; row++) screen.lines[row] = blankLine();
        } else if (mode == 1) {
            eraseLine(1);
            for (int row = 0; row < screen.row; row++) screen.lines[row] = blankLine();
        }
    }

    private Cell blank() { return new Cell(" ", style, 1); }
    private Cell[] blankLine() { Cell[] result = new Cell[columns]; Arrays.fill(result, blank()); return result; }
    private void erase(Cell[] line, int from, int to) {
        if (from >= to) return;
        clearWideCell(line, from);
        clearWideCell(line, to - 1);
        Arrays.fill(line, from, to, blank());
    }

    private void shiftCharacters(int count, boolean insert) {
        Cell[] line = screen.lines[screen.row];
        count = Math.min(count, columns - screen.column);
        if (insert) {
            System.arraycopy(line, screen.column, line, screen.column + count, columns - screen.column - count);
            Arrays.fill(line, screen.column, screen.column + count, blank());
        } else {
            System.arraycopy(line, screen.column + count, line, screen.column, columns - screen.column - count);
            Arrays.fill(line, columns - count, columns, blank());
        }
        // Remove orphan wide-cell halves after shifting at a boundary.
        for (int col = 0; col < columns; col++) {
            if (line[col] != null && line[col].width == 0
                    && (col == 0 || line[col - 1] == null || line[col - 1].width != 2)) line[col] = blank();
            if (line[col] != null && line[col].width == 2
                    && (col + 1 == columns || line[col + 1] == null || line[col + 1].width != 0)) line[col] = blank();
        }
    }

    /** Resize preserves screen contents and keeps the cursor visible; primary overflow becomes history. */
    public synchronized void resize(int newColumns, int newRows) {
        newColumns = clamp(newColumns, 2, 512);
        newRows = clamp(newRows, 2, 256);
        if (newColumns == columns && newRows == rows) return;
        resizeScreen(primary, newColumns, newRows, true);
        resizeScreen(alternate, newColumns, newRows, false);
        columns = newColumns; rows = newRows;
        if (commandTranscript != null) commandTranscript.resize(columns, rows);
    }

    private void resizeScreen(Screen target, int width, int height, boolean saveHistory) {
        int offset = Math.max(0, target.row - height + 1);
        if (saveHistory) for (int row = 0; row < offset; row++) addHistory(target.lines[row]);
        Cell[][] result = new Cell[height][width];
        for (int row = 0; row < height && row + offset < target.lines.length; row++) {
            System.arraycopy(target.lines[row + offset], 0, result[row], 0, Math.min(columns, width));
            if (width < columns && result[row][width - 1] != null && result[row][width - 1].width == 2)
                result[row][width - 1] = null;
        }
        target.lines = result;
        target.row = clamp(target.row - offset, 0, height - 1);
        target.column = clamp(target.column, 0, width - 1);
        target.savedRow = clamp(target.savedRow - offset, 0, height - 1);
        target.savedColumn = clamp(target.savedColumn, 0, width - 1);
        target.top = 0; target.bottom = height - 1; target.wrapPending = false;
    }

    /** Display projection only; VT cursor reports and full-screen programs use the original grid. */
    public synchronized Snapshot displaySnapshot() {
        if (screen == primary && commandTranscript != null && commandTranscript.enabled())
            return commandTranscript.snapshot();
        return snapshot();
    }

    public synchronized Snapshot snapshot() {
        List<Cell[]> lines = new ArrayList<>();
        if (screen == primary) lines.addAll(history);
        int historyRows = lines.size();
        int last = screen == alternate ? rows - 1 : screen.row;
        for (int row = rows - 1; row > last; row--) {
            if (lineLength(screen.lines[row]) > 0) { last = row; break; }
        }
        for (int row = 0; row <= last; row++) lines.add(screen.lines[row]);
        List<TerminalSpan> spans = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        TerminalSpan runStyle = null;
        int total = 0;
        int cursorOffset = 0;
        for (int row = 0; row < lines.size(); row++) {
            Cell[] line = lines.get(row);
            int length = lineLength(line);
            boolean cursorLine = row == historyRows + screen.row;
            if (cursorLine) length = Math.max(length, screen.column + 1);
            for (int col = 0; col < length; col++) {
                Cell cell = col < line.length ? line[col] : null;
                if (cursorLine && col == screen.column) cursorOffset = total;
                if (cell != null && cell.width == 0) continue;
                TerminalSpan cellStyle = cell == null ? defaultStyle() : cell.style;
                if (cellStyle != runStyle) {
                    flushRun(spans, run, runStyle);
                    runStyle = cellStyle;
                }
                String value = cell == null ? " " : cell.text;
                run.append(value); total += value.length();
            }
            if (row + 1 < lines.size()) { run.append('\n'); total++; }
        }
        flushRun(spans, run, runStyle);
        return new Snapshot(spans, cursorOffset, historyRows, screen == primary ? evictedHistoryRows : 0);
    }

    private static int lineLength(Cell[] line) {
        for (int col = line.length - 1; col >= 0; col--) {
            Cell cell = line[col];
            if (cell != null && (!cell.text.trim().isEmpty() || cell.width == 0
                    || cell.style.getBackgroundColor() != null || cell.style.isInverse())) return col + 1;
        }
        return 0;
    }

    private static void flushRun(List<TerminalSpan> spans, StringBuilder run, TerminalSpan style) {
        if (run.length() == 0) return;
        if (style == null) style = defaultStyle();
        spans.add(new TerminalSpan(run.toString(), style.getForegroundColor(), style.getBackgroundColor(),
                style.isBold(), style.isItalic(), style.isUnderline(), style.isInverse()));
        run.setLength(0);
    }

    private static TerminalSpan defaultStyle() { return DEFAULT_STYLE; }
    private static int parameter(String[] values, int index, int fallback) {
        int value = index >= values.length ? fallback : parse(values[index], fallback);
        return value == 0 ? fallback : Math.min(value, 65535);
    }
    private static int parse(String value, int fallback) {
        try { return Math.max(0, Integer.parseInt(value)); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }

    static int characterWidth(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT) return 0;
        return (codePoint >= 0x1100 && (codePoint <= 0x115f || codePoint == 0x2329 || codePoint == 0x232a
                || (codePoint >= 0x2e80 && codePoint <= 0xa4cf && codePoint != 0x303f)
                || (codePoint >= 0xac00 && codePoint <= 0xd7a3)
                || (codePoint >= 0xf900 && codePoint <= 0xfaff)
                || (codePoint >= 0xfe10 && codePoint <= 0xfe19)
                || (codePoint >= 0xfe30 && codePoint <= 0xfe6f)
                || (codePoint >= 0xff00 && codePoint <= 0xff60)
                || (codePoint >= 0xffe0 && codePoint <= 0xffe6)
                || (codePoint >= 0x1f300 && codePoint <= 0x1faff)
                || (codePoint >= 0x20000 && codePoint <= 0x3fffd))) ? 2 : 1;
    }
}
