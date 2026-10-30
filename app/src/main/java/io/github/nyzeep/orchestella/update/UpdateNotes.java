package io.github.nyzeep.orchestella.update;

/**
 * Turns a GitHub release body into a short, readable update summary.
 *
 * <p>Release notes are written for the release page: badges, block quotes, tables, headings and
 * links. The in-app update card has room for a handful of lines, so this class keeps the first
 * few substantive lines, drops page furniture and inline markup, and never invents text. It is
 * pure: no I/O, no Android, no locale-dependent behaviour, so the exact same body always yields
 * the same summary and the summariser can be unit tested.
 */
public final class UpdateNotes {

    /** Lines the in-app summary may show before it truncates. */
    public static final int DEFAULT_MAX_LINES = 5;

    /** Hard character ceiling: the dialog must stay readable on a 360 dp screen. */
    public static final int DEFAULT_MAX_CHARS = 320;

    private UpdateNotes() {}

    public static String summarize(String markdown) {
        return summarize(markdown, DEFAULT_MAX_LINES, DEFAULT_MAX_CHARS);
    }

    public static String summarize(String markdown, int maxLines, int maxChars) {
        if (markdown == null || markdown.trim().isEmpty() || maxLines <= 0 || maxChars <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int lines = 0;
        boolean truncated = false;
        for (String raw : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = clean(raw);
            if (line.isEmpty()) {
                continue;
            }
            if (lines >= maxLines || out.length() + line.length() + 1 > maxChars) {
                truncated = true;
                break;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
            lines++;
        }
        if (truncated && out.length() > 0) {
            out.append('…');
        }
        return out.toString();
    }

    /** Returns "" for lines that are page furniture or become empty after markup removal. */
    private static String clean(String raw) {
        String line = raw.trim();
        if (line.isEmpty()
                || line.startsWith(">")
                || line.startsWith("<!--")
                || line.startsWith("|---")
                || line.startsWith("| ")
                || line.startsWith("```")
                || line.equals("---")) {
            return "";
        }
        if (line.startsWith("#")) {
            return "";
        }
        line = stripListMarker(line);
        line = line.replaceAll("!\\[[^\\]]*]\\([^)]*\\)", "");
        line = line.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");
        line = line.replace("**", "").replace("__", "").replace("`", "");
        line = line.replaceAll("<[^>]+>", "");
        line = line.replaceAll("\\s+", " ").trim();
        // A line that was nothing but a badge image or a bare link is noise once stripped.
        return line.length() < 2 ? "" : line;
    }

    private static String stripListMarker(String line) {
        String trimmed = line;
        if (trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ")) {
            return trimmed.substring(6).trim();
        }
        int index = 0;
        while (index < trimmed.length() && (trimmed.charAt(index) == ' ' || trimmed.charAt(index) == '-'
                || trimmed.charAt(index) == '*' || trimmed.charAt(index) == '+')) {
            index++;
        }
        if (index > 0 && index < trimmed.length()) {
            return trimmed.substring(index).trim();
        }
        // Ordered list marker: "1. ", "12. ".
        int digits = 0;
        while (digits < trimmed.length() && Character.isDigit(trimmed.charAt(digits))) {
            digits++;
        }
        if (digits > 0 && digits + 1 < trimmed.length() && trimmed.charAt(digits) == '.'
                && trimmed.charAt(digits + 1) == ' ') {
            return trimmed.substring(digits + 2).trim();
        }
        return trimmed;
    }
}
