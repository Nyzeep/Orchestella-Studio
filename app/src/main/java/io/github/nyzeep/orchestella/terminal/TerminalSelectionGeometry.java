package io.github.nyzeep.orchestella.terminal;

/**
 * W2-02: display-text cell geometry shared by the Canvas renderer, canvas selection
 * and the accessibility helper. Pure JVM so the column↔offset mapping can be tested
 * without Robolectric.
 *
 * <p>The VT model projects one flat display text in which every cell is its source
 * code point (possibly followed by zero-width combining marks that the model merged
 * into the cell) and occupies {@link TerminalScreenModel#characterWidth(int)} grid
 * columns. Wide-character continuation cells never appear in the text, so a single
 * forward walk reconstructs the exact logical grid.
 *
 * <p>Selection and cursor boundaries are always <em>cell starts</em> (hit testing and
 * cursor offsets produce them by construction); the helpers clamp defensively.
 */
public final class TerminalSelectionGeometry {
    private TerminalSelectionGeometry() {}

    /** Receives one cell of a display line in left-to-right column order. */
    public interface CellVisitor { void cell(int column, int charStart, int charEnd, int width); }

    /** Walks the cells of the content range {@code [lineStart, lineEnd)} in column order. */
    public static void forEachCell(String text, int lineStart, int lineEnd, CellVisitor visitor) {
        int column = 0;
        int index = lineStart;
        while (index < lineEnd) {
            int codePoint = text.codePointAt(index);
            int width = TerminalScreenModel.characterWidth(codePoint);
            // Orphan combining mark at a range boundary: keep the grid moving with one column.
            if (width <= 0) width = 1;
            int cellStart = index;
            index += Character.charCount(codePoint);
            // Marks merged by the model ride along with their base cell.
            while (index < lineEnd && TerminalScreenModel.characterWidth(text.codePointAt(index)) == 0) {
                index += Character.charCount(text.codePointAt(index));
            }
            visitor.cell(column, cellStart, index, width);
            column += width;
        }
    }

    /** Total logical columns of the content range {@code [lineStart, lineEnd)}. */
    public static int lineColumns(String text, int lineStart, int lineEnd) {
        int[] total = {0};
        forEachCell(text, lineStart, lineEnd, (column, charStart, charEnd, width) -> total[0] = column + width);
        return total[0];
    }

    /**
     * Display line owning {@code offset}. A line owns its content range plus its
     * terminating {@code '\n'}; {@code lineStarts} must be ascending and cover
     * {@code [0, textLength]}.
     */
    public static int lineOfOffset(int[] lineStarts, int lineCount, int textLength, int offset) {
        if (offset <= 0) return 0;
        if (offset >= textLength) return lineCount - 1;
        int low = 0;
        int high = lineCount - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (lineStarts[mid] <= offset) low = mid;
            else high = mid - 1;
        }
        return low;
    }

    /**
     * Column of the cell at or after {@code charOffset} within
     * {@code [lineStart, lineEnd)}; offsets beyond the line clamp to the line's
     * total columns, so exclusive selection ends land on the next cell boundary.
     */
    public static int columnAtOffset(String text, int lineStart, int lineEnd, int charOffset) {
        int[] result = {Integer.MAX_VALUE};
        forEachCell(text, lineStart, lineEnd, (column, charStart, charEnd, width) -> {
            if (result[0] == Integer.MAX_VALUE && charEnd > charOffset) result[0] = column;
        });
        if (result[0] == Integer.MAX_VALUE) result[0] = TerminalSelectionGeometry.lineColumns(text, lineStart, lineEnd);
        return result[0];
    }

    /**
     * Char offset of the cell start at or after {@code column}; a column past the
     * line's end clamps to {@code lineEnd} (a stable "end of line" anchor).
     */
    public static int offsetAtColumn(String text, int lineStart, int lineEnd, int column) {
        int[] result = {lineEnd};
        forEachCell(text, lineStart, lineEnd, (cellColumn, charStart, charEnd, width) -> {
            if (result[0] == lineEnd && column < cellColumn + width) result[0] = charStart;
        });
        return result[0];
    }
}
