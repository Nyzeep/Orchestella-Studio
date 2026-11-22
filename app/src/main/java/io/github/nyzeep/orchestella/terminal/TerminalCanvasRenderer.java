package io.github.nyzeep.orchestella.terminal;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.TextPaint;
import io.github.nyzeep.orchestella.TerminalSpan;
import java.util.Arrays;
import java.util.List;

/**
 * W2-02: draws only the viewport-visible display lines straight from the VT model's
 * cached snapshot, replacing the ~100k-character Spannable + StaticLayout rebuild.
 *
 * <p>Every cell is anchored at {@code x = originX + column * cellStepPx}, so fallback
 * glyphs (CJK/Emoji shapes a mono font borrows from the system) can never push later
 * columns, the cursor or the selection off the logical grid. A cell joins a same-style
 * run only while its shaped advance matches the grid step; everything else — wide
 * cells, emoji, any glyph the font renders off-grid — is drawn solo at its own slot.
 * This is a clean-room structure reference of a visible-row canvas renderer (per-run
 * grouping, per-column anchors, effective-color resolution); no GPLv3 code was copied.
 *
 * <p>The per-line draw plan is a static pure function over the line model and a
 * pluggable advance oracle, so column anchoring is unit-tested on the plain JVM.
 */
public final class TerminalCanvasRenderer {
    /** A shaped advance differing from the grid step by more than this fraction breaks a run. */
    private static final float RUN_TOLERANCE = 0.01f;

    /** Immutable line-indexed projection of one display snapshot. */
    public static final class LineModel {
        public final String text;
        public final int lineCount;
        final int textLength;
        final int[] lineStarts;
        final TerminalSpan[] spans;
        /** Span i covers {@code [spanStarts[i], spanStarts[i+1])}; the runs partition the text. */
        final int[] spanStarts;

        private LineModel(String text, int[] lineStarts, TerminalSpan[] spans, int[] spanStarts) {
            this.text = text;
            this.textLength = text.length();
            this.lineCount = lineStarts.length;
            this.lineStarts = lineStarts;
            this.spans = spans;
            this.spanStarts = spanStarts;
        }

        /** Projects a display snapshot into the line model the renderer consumes. */
        public static LineModel from(TerminalScreenModel.Snapshot snapshot) {
            String text = snapshot.text();
            List<TerminalSpan> spanList = snapshot.spans;
            int spanCount = spanList.size();
            int[] spanStarts = new int[spanCount + 1];
            int total = 0;
            for (int i = 0; i < spanCount; i++) {
                spanStarts[i] = total;
                total += spanList.get(i).getText().length();
            }
            spanStarts[spanCount] = total;

            int lineCount = 1;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') lineCount++;
            }
            int[] lineStarts = new int[lineCount];
            int line = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    lineStarts[line] = i + 1;
                }
            }
            return new LineModel(text, lineStarts, spanList.toArray(new TerminalSpan[0]), spanStarts);
        }

        /** Char offset of the line's first cell. */
        public int lineStart(int line) { return lineStarts[line]; }

        /** Exclusive end of the line's cell content; see {@link #lineEnd(int[], int, int, int)}. */
        public int lineEnd(int line) {
            return lineEnd(lineStarts, lineCount, textLength, line);
        }

        int lineColumns(int line) {
            return TerminalSelectionGeometry.lineColumns(text, lineStart(line), lineEnd(line));
        }

        /** Style run covering {@code offset} (clamped into the text). */
        TerminalSpan styleAt(int offset) {
            if (spans.length == 0) return null;
            int probe = Math.max(0, Math.min(offset, textLength - 1));
            int low = 0;
            int high = spans.length - 1;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (spanStarts[mid] <= probe) low = mid;
                else high = mid - 1;
            }
            return spans[low];
        }

        /** Display line owning {@code offset}; public for cross-package selection geometry. */
        public int lineOfOffset(int offset) {
            return TerminalSelectionGeometry.lineOfOffset(lineStarts, lineCount, textLength, offset);
        }

        /** Exclusive end of the line's cell content (the terminating {@code '\n'} excluded). */
        static int lineEnd(int[] lineStarts, int lineCount, int textLength, int line) {
            if (line + 1 >= lineCount) return textLength;
            return Math.max(lineStarts[line], lineStarts[line + 1] - 1);
        }
    }

    /** Per-line draw plan: anchored text ops plus grouped background runs (tests read these). */
    static final class LinePlan {
        int textOpCount;
        int[] opStart = new int[32];
        int[] opEnd = new int[32];
        int[] opColumn = new int[32];
        boolean[] opSolo = new boolean[32];
        TerminalSpan[] opStyle = new TerminalSpan[32];

        int bgRunCount;
        int[] bgColumn = new int[16];
        int[] bgWidth = new int[16];
        int[] bgArgb = new int[16];

        void reset() {
            textOpCount = 0;
            bgRunCount = 0;
        }
    }

    /** Shaped advance lookup for run faithfulness; tests inject fixed values. */
    interface AdvanceOracle { float advance(int codePoint); }

    private TextPaint textPaint;
    private final Paint fillPaint = new Paint();
    private final Paint railPaint = new Paint();
    private final Paint cursorPaint = new Paint();
    private final Paint selectionPaint = new Paint();

    private final Typeface[] styledTypefaces = new Typeface[4];
    private float textSizePx;
    private float letterSpacingPx;
    private float cellStepPx;
    private int rowHeightPx;
    private float ascentPx;
    private int originX;
    private int originY;
    private int railGapPx;
    private int railWidthPx;

    private final float[] asciiAdvances = new float[128];
    private final java.util.HashMap<Integer, Float> codePointAdvances = new java.util.HashMap<>();
    // Draw-scratch reused across frames (single UI-thread caller).
    private Rect drawClipRect;
    private LinePlan drawPlan;

    /** Test probe: rows the last {@link #draw} actually walked (never scales with history). */
    public int lastDrawnRowCount;

    /** Configures fonts, metrics and paint colors; call again when theme or text size changes. */
    public void configure(Typeface typeface, float textSizePx, float letterSpacingEm,
            float lineSpacingExtraPx, int selectionArgb, int cursorArgb, int railArgb) {
        textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(typeface);
        textPaint.setTextSize(textSizePx);
        textPaint.setLetterSpacing(letterSpacingEm);
        letterSpacingPx = letterSpacingEm * textSizePx;
        // The TextView path measures the mono cell the same way; exactly one
        // letterSpacing term (W1-06 P2-2), never twice.
        cellStepPx = Layout.getDesiredWidth("MM", textPaint) - Layout.getDesiredWidth("M", textPaint);
        if (cellStepPx <= 0) cellStepPx = textPaint.measureText("M");
        rowHeightPx = Math.max(1, (int) Math.ceil(textPaint.getFontSpacing() + Math.max(0f, lineSpacingExtraPx)));
        ascentPx = textPaint.ascent();
        this.textSizePx = textSizePx;
        // Explicit variants: lint's WrongConstant cannot prove computed style ints.
        styledTypefaces[0] = typeface;
        styledTypefaces[1] = Typeface.create(typeface, Typeface.BOLD);
        styledTypefaces[2] = Typeface.create(typeface, Typeface.ITALIC);
        styledTypefaces[3] = Typeface.create(typeface, Typeface.BOLD_ITALIC);
        Arrays.fill(asciiAdvances, 0f);
        codePointAdvances.clear();
        fillPaint.setStyle(Paint.Style.FILL);
        railPaint.setStyle(Paint.Style.FILL);
        railPaint.setColor(railArgb);
        cursorPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setColor(cursorArgb);
        selectionPaint.setStyle(Paint.Style.FILL);
        selectionPaint.setColor(selectionArgb);
    }

    public void setOrigin(int x, int y) {
        this.originX = x;
        this.originY = y;
    }

    /** Gap between the text origin and the command rail, and the rail thickness. */
    public void setRailGeometry(int gapPx, int widthPx) {
        this.railGapPx = gapPx;
        this.railWidthPx = widthPx;
    }

    public float cellStepPx() { return cellStepPx; }
    public int rowHeightPx() { return rowHeightPx; }
    public float ascentPx() { return ascentPx; }
    public int originXPx() { return originX; }
    public int originYPx() { return originY; }
    public float textSizePx() { return textSizePx; }

    /** Content height the canvas child must occupy so the ScrollView can scroll it. */
    public int contentHeightPx(int lineCount) {
        return originY * 2 + rowHeightPx * lineCount;
    }

    private float advanceOf(int codePoint) {
        if (codePoint < 128) {
            float cached = asciiAdvances[codePoint];
            if (cached == 0f) {
                cached = textPaint.measureText(String.valueOf((char) codePoint));
                asciiAdvances[codePoint] = cached;
            }
            return cached;
        }
        Integer key = codePoint;
        Float cached = codePointAdvances.get(key);
        if (cached == null) {
            cached = textPaint.measureText(new String(Character.toChars(codePoint)));
            // Bounded cache: pathological streams cannot grow it without limit.
            if (codePointAdvances.size() >= 512) codePointAdvances.clear();
            codePointAdvances.put(key, cached);
        }
        return cached;
    }

    /**
     * Per-cell advance inside a drawn run: the shaped glyph width plus the inter-cell
     * letter spacing the shaper inserts. Wide or solo cells never use this (they are
     * anchored individually), so the spacing term applies exactly once per cell.
     */
    private final AdvanceOracle measuredAdvance = codePoint -> advanceOf(codePoint) + letterSpacingPx;

    /** First and last (inclusive) rows intersecting the clip, clamped into the model. */
    static int[] visibleRowRange(int clipTop, int clipBottom, int originY, int rowHeightPx, int lineCount) {
        if (clipBottom <= clipTop || clipBottom <= originY || clipTop >= originY + rowHeightPx * lineCount)
            return new int[]{0, -1};
        int first = 0;
        if (clipTop > originY) first = (clipTop - originY) / rowHeightPx;
        // Exclusive clip bottom: a row starting exactly at the edge is not visible.
        int last = Math.min(lineCount - 1, (clipBottom - originY - 1) / rowHeightPx);
        first = Math.max(0, Math.min(first, lineCount - 1));
        last = Math.max(first, Math.min(last, lineCount - 1));
        return new int[]{first, last};
    }

    /**
     * Plans one line: same-style runs of grid-faithful half-width cells share one
     * anchored draw; wide or off-grid cells draw solo at their own slot. Backgrounds
     * group per effective color. Package-private and pure for tests.
     */
    static void planLine(LineModel model, int line, AdvanceOracle advance, float cellStep, LinePlan out) {
        out.reset();
        // The cell walk rides the shared geometry so the run/background grouping can
        // never drift from the selection and hit-test semantics.
        PlanWalker walker = new PlanWalker(model, advance, cellStep, out);
        TerminalSelectionGeometry.forEachCell(model.text, model.lineStart(line), model.lineEnd(line), walker);
        walker.finish();
    }

    /** Accumulates run/background state while the shared cell walk feeds cells in. */
    private static final class PlanWalker implements TerminalSelectionGeometry.CellVisitor {
        private final LineModel model;
        private final AdvanceOracle advance;
        private final float cellStep;
        private final LinePlan out;
        // Run state: a run is a streak of grid-faithful half-width cells sharing one style.
        private TerminalSpan runStyle;
        private int runStart = -1;
        private int runEnd = -1;
        private int runColumn = -1;
        // Background state: a streak of cells sharing one effective background color.
        private int bgColumn = -1;
        private int bgWidth = 0;
        private int bgArgb = 0;

        PlanWalker(LineModel model, AdvanceOracle advance, float cellStep, LinePlan out) {
            this.model = model;
            this.advance = advance;
            this.cellStep = cellStep;
            this.out = out;
        }

        void finish() {
            if (runStart >= 0) emitText(out, runStart, runEnd, runColumn, runStyle, false);
            if (bgColumn >= 0 && bgArgb != 0) emitBackground(out, bgColumn, bgWidth, bgArgb);
        }

        @Override public void cell(int column, int cellStart, int cellEnd, int width) {
            TerminalSpan style = model.styleAt(cellStart);
            Integer background = style.getEffectiveBackgroundColor();
            int argb = background == null ? 0 : background;
            if (argb != bgArgb) {
                if (bgColumn >= 0 && bgArgb != 0) emitBackground(out, bgColumn, bgWidth, bgArgb);
                bgColumn = column;
                bgArgb = argb;
            }
            bgWidth = column + width - bgColumn;

            boolean faithful = width == 1;
            if (faithful) {
                float step = advance.advance(model.text.codePointAt(cellStart));
                faithful = Math.abs(step - cellStep) <= cellStep * RUN_TOLERANCE;
            }
            if (faithful) {
                if (runStart < 0 || runStyle != style) {
                    if (runStart >= 0) emitText(out, runStart, runEnd, runColumn, runStyle, false);
                    runStart = cellStart;
                    runEnd = cellEnd;
                    runColumn = column;
                    runStyle = style;
                } else {
                    runEnd = cellEnd;
                }
            } else {
                if (runStart >= 0) emitText(out, runStart, runEnd, runColumn, runStyle, false);
                emitText(out, cellStart, cellEnd, column, style, true);
                runStart = -1;
                runStyle = null;
            }
        }
    }

    private static void emitText(LinePlan out, int start, int end, int column, TerminalSpan style, boolean solo) {
        if (out.textOpCount >= out.opStart.length) {
            out.opStart = growInt(out.opStart);
            out.opEnd = growInt(out.opEnd);
            out.opColumn = growInt(out.opColumn);
            out.opSolo = growBool(out.opSolo);
            out.opStyle = growStyle(out.opStyle);
        }
        out.opStart[out.textOpCount] = start;
        out.opEnd[out.textOpCount] = end;
        out.opColumn[out.textOpCount] = column;
        out.opSolo[out.textOpCount] = solo;
        out.opStyle[out.textOpCount] = style;
        out.textOpCount++;
    }

    private static void emitBackground(LinePlan out, int column, int width, int argb) {
        if (out.bgRunCount >= out.bgColumn.length) {
            out.bgColumn = growInt(out.bgColumn);
            out.bgWidth = growInt(out.bgWidth);
            out.bgArgb = growInt(out.bgArgb);
        }
        out.bgColumn[out.bgRunCount] = column;
        out.bgWidth[out.bgRunCount] = width;
        out.bgArgb[out.bgRunCount] = argb;
        out.bgRunCount++;
    }

    private static int[] growInt(int[] array) {
        int[] result = new int[array.length * 2];
        System.arraycopy(array, 0, result, 0, array.length);
        return result;
    }

    private static boolean[] growBool(boolean[] array) {
        boolean[] result = new boolean[array.length * 2];
        System.arraycopy(array, 0, result, 0, array.length);
        return result;
    }

    private static TerminalSpan[] growStyle(TerminalSpan[] array) {
        TerminalSpan[] result = new TerminalSpan[array.length * 2];
        System.arraycopy(array, 0, result, 0, array.length);
        return result;
    }

    /**
     * Draws the visible window of {@code model}. The ScrollView translates the canvas
     * by the scroll offset, so clip bounds are already in content coordinates.
     */
    public void draw(Canvas canvas, LineModel model, int cursorOffset, boolean cursorShown,
            boolean selecting, int selectionStart, int selectionEnd,
            List<TerminalScreenModel.Snapshot.CommandRange> commandRanges) {
        if (drawClipRect == null) drawClipRect = new Rect();
        Rect clip = drawClipRect;
        lastDrawnRowCount = 0;
        if (!canvas.getClipBounds(clip)) return;
        int[] range = visibleRowRange(clip.top, clip.bottom, originY, rowHeightPx, model.lineCount);
        lastDrawnRowCount = range[1] - range[0] + 1;
        if (range[0] > range[1]) return;

        int clampedCursor = Math.max(0, Math.min(cursorOffset, model.textLength));
        int cursorLine = cursorShown ? model.lineOfOffset(clampedCursor) : -1;
        int cursorColumn = cursorLine >= range[0] && cursorLine <= range[1]
                ? TerminalSelectionGeometry.columnAtOffset(model.text,
                        model.lineStarts[cursorLine], model.lineEnd(cursorLine), clampedCursor) : -1;
        float barWidth = Math.max(2f, textSizePx / 10f);
        if (drawPlan == null) drawPlan = new LinePlan();
        LinePlan plan = drawPlan;
        int commandRangeIndex = firstCommandRangeAtOrAfter(commandRanges, range[0]);
        for (int row = range[0]; row <= range[1]; row++) {
            int top = originY + row * rowHeightPx;
            int bottom = top + rowHeightPx;
            while (commandRangeIndex < commandRanges.size() &&
                    commandRanges.get(commandRangeIndex).endLineExclusive() <= row) {
                commandRangeIndex++;
            }
            if (commandRangeIndex < commandRanges.size() && commandRanges.get(commandRangeIndex).startLine() <= row) {
                float left = originX - railGapPx - railWidthPx;
                canvas.drawRect(left, top, left + railWidthPx, bottom, railPaint);
            }
            planLine(model, row, measuredAdvance, cellStepPx, plan);
            for (int i = 0; i < plan.bgRunCount; i++) {
                fillPaint.setColor(plan.bgArgb[i]);
                float left = originX + plan.bgColumn[i] * cellStepPx;
                float right = left + plan.bgWidth[i] * cellStepPx;
                canvas.drawRect(left, top, right, bottom, fillPaint);
            }
            if (selecting) {
                drawSelectionBand(canvas, model, row, top, bottom, selectionStart, selectionEnd);
            }
            for (int i = 0; i < plan.textOpCount; i++) {
                TerminalSpan style = plan.opStyle[i];
                if (style == null) continue;
                int styleIndex = (style.isBold() ? 1 : 0) | (style.isItalic() ? 2 : 0);
                textPaint.setTypeface(styledTypefaces[styleIndex]);
                textPaint.setColor(style.getEffectiveForegroundColor());
                textPaint.setUnderlineText(style.isUnderline());
                canvas.drawText(model.text, plan.opStart[i], plan.opEnd[i],
                        originX + plan.opColumn[i] * cellStepPx, top - ascentPx, textPaint);
            }
            if (row == cursorLine && cursorColumn >= 0) {
                float left = originX + cursorColumn * cellStepPx;
                canvas.drawRect(left, top, left + barWidth, bottom, cursorPaint);
            }
        }
    }

    /** The transcript emits command ranges in display order, without overlap. */
    private static int firstCommandRangeAtOrAfter(List<TerminalScreenModel.Snapshot.CommandRange> ranges, int row) {
        int low = 0;
        int high = ranges.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (ranges.get(mid).endLineExclusive() <= row) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    /** Selection band for one row in cell-space; boundaries are cell starts by construction. */
    private void drawSelectionBand(Canvas canvas, LineModel model, int row, int top, int bottom,
            int selectionStart, int selectionEnd) {
        int rowStart = model.lineStarts[row];
        int rowEnd = model.lineEnd(row);
        int firstRow = model.lineOfOffset(selectionStart);
        int lastRow = model.lineOfOffset(Math.max(selectionStart, selectionEnd - 1));
        if (row < firstRow || row > lastRow) return;
        int startColumn = row == firstRow
                ? TerminalSelectionGeometry.columnAtOffset(model.text, rowStart, rowEnd, selectionStart) : 0;
        int endColumn = row == lastRow
                ? TerminalSelectionGeometry.columnAtOffset(model.text, rowStart, rowEnd, selectionEnd)
                : model.lineColumns(row);
        if (endColumn <= startColumn) return;
        float left = originX + startColumn * cellStepPx;
        float right = originX + endColumn * cellStepPx;
        canvas.drawRect(left, top, right, bottom, selectionPaint);
    }
}
