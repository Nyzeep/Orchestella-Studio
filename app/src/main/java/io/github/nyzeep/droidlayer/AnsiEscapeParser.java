package io.github.nyzeep.droidlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight, high-performance ANSI / VT100 escape sequence parser.
 *
 * <p>Parses raw character streams into {@link TerminalSpan} instances:
 * <ul>
 *   <li>SGR styling: bold, italic, underline, inverse, reset;</li>
 *   <li>Standard ANSI 16 colors (30-37, 40-47, 90-97, 100-107);</li>
 *   <li>Extended 256-color palette (38;5;n and 48;5;n);</li>
 *   <li>24-bit TrueColor RGB (38;2;r;g;b and 48;2;r;g;b, semicolon and colon variants);</li>
 *   <li>Default Dracula terminal palette;</li>
 *   <li>Robust against chunk boundary splits, malformed inputs, and non-SGR sequences.</li>
 * </ul>
 */
public final class AnsiEscapeParser {

    private Integer currentFg = null;
    private Integer currentBg = null;
    private boolean bold = false;
    private boolean italic = false;
    private boolean underline = false;
    private boolean inverse = false;

    private String pendingEscape = "";

    public AnsiEscapeParser() {
        reset();
    }

    /**
     * Resets parser style state and pending buffer to initial defaults.
     */
    public synchronized void reset() {
        currentFg = null;
        currentBg = null;
        bold = false;
        italic = false;
        underline = false;
        inverse = false;
        pendingEscape = "";
    }

    /**
     * Parses the given raw text and returns a list of styled {@link TerminalSpan}s.
     * State is retained across calls to support streaming terminal output.
     */
    public synchronized List<TerminalSpan> parse(String text) {
        List<TerminalSpan> spans = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return spans;
        }

        String input;
        if (!pendingEscape.isEmpty()) {
            input = pendingEscape + text;
            pendingEscape = "";
        } else {
            input = text;
        }

        StringBuilder textBuffer = new StringBuilder();
        int len = input.length();
        int i = 0;

        while (i < len) {
            char c = input.charAt(i);

            if (c == '\u001b') { // ESC (0x1B)
                // Check if we have enough characters or if this escape is at the very end
                if (i + 1 >= len) {
                    // Incomplete escape at chunk boundary
                    pendingEscape = input.substring(i);
                    break;
                }

                char next = input.charAt(i + 1);

                if (next == '[') {
                    // CSI sequence: ESC [ ... <final_char>
                    int csiEnd = findCsiEnd(input, i + 2);
                    if (csiEnd == -1) {
                        // Incomplete CSI at chunk boundary
                        pendingEscape = input.substring(i);
                        break;
                    }

                    // Flush any pending text before style change
                    flushText(textBuffer, spans);

                    char finalChar = input.charAt(csiEnd);
                    String params = input.substring(i + 2, csiEnd);

                    if (finalChar == 'm') {
                        // SGR sequence
                        handleSgr(params);
                    }
                    // Non-SGR CSI commands (e.g., 'H', 'J', 'K', 'A', etc.) are stripped
                    i = csiEnd + 1;
                    continue;

                } else if (next == ']') {
                    // OSC sequence: ESC ] ... (BEL or ESC \)
                    int oscEnd = findOscEnd(input, i + 2);
                    if (oscEnd == -1) {
                        // Incomplete OSC at chunk boundary
                        pendingEscape = input.substring(i);
                        break;
                    }
                    flushText(textBuffer, spans);
                    i = oscEnd;
                    continue;

                } else if (next == '(' || next == ')') {
                    // Character set selection: ESC ( <char> or ESC ) <char>
                    if (i + 2 >= len) {
                        pendingEscape = input.substring(i);
                        break;
                    }
                    i += 3;
                    continue;

                } else {
                    // Other 2-char escape sequences: skip ESC and next char
                    i += 2;
                    continue;
                }
            } else {
                textBuffer.append(c);
                i++;
            }
        }

        flushText(textBuffer, spans);
        return spans;
    }

    private void flushText(StringBuilder buffer, List<TerminalSpan> spans) {
        if (buffer.length() > 0) {
            spans.add(new TerminalSpan(
                    buffer.toString(),
                    currentFg,
                    currentBg,
                    bold,
                    italic,
                    underline,
                    inverse
            ));
            buffer.setLength(0);
        }
    }

    private static int findCsiEnd(String s, int start) {
        int len = s.length();
        for (int i = start; i < len; i++) {
            char c = s.charAt(i);
            // Final character of CSI is in range 0x40 - 0x7E (@ through ~)
            if (c >= 0x40 && c <= 0x7E) {
                return i;
            }
        }
        return -1;
    }

    private static int findOscEnd(String s, int start) {
        int len = s.length();
        for (int i = start; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\u0007') { // BEL
                return i + 1;
            }
            if (c == '\u001b' && i + 1 < len && s.charAt(i + 1) == '\\') { // ST (ESC \)
                return i + 2;
            }
        }
        return -1;
    }

    private void handleSgr(String params) {
        if (params.isEmpty()) {
            resetStyles();
            return;
        }

        String[] tokens = params.split("[;:]+");
        if (tokens.length == 0 || (tokens.length == 1 && tokens[0].isEmpty())) {
            resetStyles();
            return;
        }

        for (int i = 0; i < tokens.length; i++) {
            int code = parseInteger(tokens[i], -1);
            if (code == -1) {
                continue;
            }

            switch (code) {
                case 0:
                    resetStyles();
                    break;
                case 1:
                    bold = true;
                    break;
                case 3:
                    italic = true;
                    break;
                case 4:
                    underline = true;
                    break;
                case 7:
                    inverse = true;
                    break;
                case 21:
                case 22:
                    bold = false;
                    break;
                case 23:
                    italic = false;
                    break;
                case 24:
                    underline = false;
                    break;
                case 27:
                    inverse = false;
                    break;
                case 30: case 31: case 32: case 33: case 34: case 35: case 36: case 37:
                    currentFg = DraculaPalette.getAnsi16(code - 30);
                    break;
                case 38:
                    // Extended foreground: 38;5;n or 38;2;r;g;b
                    if (i + 1 < tokens.length) {
                        int type = parseInteger(tokens[i + 1], -1);
                        if (type == 5 && i + 2 < tokens.length) { // 256 color
                            int idx = parseInteger(tokens[i + 2], -1);
                            if (idx >= 0 && idx <= 255) {
                                currentFg = DraculaPalette.get256(idx);
                            }
                            i += 2;
                        } else if (type == 2 && i + 4 < tokens.length) { // TrueColor
                            int r = parseInteger(tokens[i + 2], 0);
                            int g = parseInteger(tokens[i + 3], 0);
                            int b = parseInteger(tokens[i + 4], 0);
                            currentFg = DraculaPalette.getTrueColor(r, g, b);
                            i += 4;
                        }
                    }
                    break;
                case 39:
                    currentFg = null; // reset to default foreground
                    break;
                case 40: case 41: case 42: case 43: case 44: case 45: case 46: case 47:
                    currentBg = DraculaPalette.getAnsi16(code - 40);
                    break;
                case 48:
                    // Extended background: 48;5;n or 48;2;r;g;b
                    if (i + 1 < tokens.length) {
                        int type = parseInteger(tokens[i + 1], -1);
                        if (type == 5 && i + 2 < tokens.length) { // 256 color
                            int idx = parseInteger(tokens[i + 2], -1);
                            if (idx >= 0 && idx <= 255) {
                                currentBg = DraculaPalette.get256(idx);
                            }
                            i += 2;
                        } else if (type == 2 && i + 4 < tokens.length) { // TrueColor
                            int r = parseInteger(tokens[i + 2], 0);
                            int g = parseInteger(tokens[i + 3], 0);
                            int b = parseInteger(tokens[i + 4], 0);
                            currentBg = DraculaPalette.getTrueColor(r, g, b);
                            i += 4;
                        }
                    }
                    break;
                case 49:
                    currentBg = null; // reset to default background
                    break;
                case 90: case 91: case 92: case 93: case 94: case 95: case 96: case 97:
                    currentFg = DraculaPalette.getAnsi16(8 + (code - 90));
                    break;
                case 100: case 101: case 102: case 103: case 104: case 105: case 106: case 107:
                    currentBg = DraculaPalette.getAnsi16(8 + (code - 100));
                    break;
                default:
                    // Unknown or unsupported SGR code ignored
                    break;
            }
        }
    }

    private void resetStyles() {
        currentFg = null;
        currentBg = null;
        bold = false;
        italic = false;
        underline = false;
        inverse = false;
    }

    private static int parseInteger(String s, int defaultValue) {
        if (s == null || s.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
