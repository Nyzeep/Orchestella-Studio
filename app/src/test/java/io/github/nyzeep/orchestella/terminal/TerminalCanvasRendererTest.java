package io.github.nyzeep.orchestella.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * W2-02: pure-JVM column-anchoring and geometry gates for the visible-row Canvas
 * renderer. The draw plan is the same static function the hot path executes, so these
 * assertions pin the ticket's hard requirement: a wide character may never move a
 * later ASCII column, the cursor or the selection off {@code column * cellStep}.
 *
 * <p>Every fixture ends its first line with a newline so the VT cursor (and its
 * trailing-cell extension) never widens the line under test.
 */
public class TerminalCanvasRendererTest {

    private static TerminalCanvasRenderer.LineModel modelOf(String firstLine) {
        TerminalScreenModel model = new TerminalScreenModel(80, 24);
        model.append(firstLine + "\r\n");
        return TerminalCanvasRenderer.LineModel.from(model.displaySnapshot());
    }

    private static TerminalCanvasRenderer.LinePlan planOf(TerminalCanvasRenderer.LineModel model, int line,
            TerminalCanvasRenderer.AdvanceOracle oracle, float cellStep) {
        TerminalCanvasRenderer.LinePlan plan = new TerminalCanvasRenderer.LinePlan();
        TerminalCanvasRenderer.planLine(model, line, oracle, cellStep, plan);
        return plan;
    }

    /** Grid-faithful oracle: every half-width glyph advances exactly one cell step. */
    private static final TerminalCanvasRenderer.AdvanceOracle FAITHFUL = codePoint -> 10f;

    @Test
    public void wideCharacterThenAsciiAnchorsToLogicalColumns() {
        TerminalCanvasRenderer.LineModel model = modelOf("中A");
        assertEquals("中A", model.text.substring(0, model.lineEnd(0)));
        TerminalCanvasRenderer.LinePlan plan = planOf(model, 0, FAITHFUL, 10f);
        assertEquals(2, plan.textOpCount);
        // The wide cell draws solo at its own slot.
        assertEquals(0, plan.opColumn[0]);
        assertTrue(plan.opSolo[0]);
        assertEquals(0, plan.opStart[0]);
        assertEquals(1, plan.opEnd[0]);
        // The ASCII cell after the wide character lands exactly on column 2: no drift.
        assertEquals(2, plan.opColumn[1]);
        assertFalse(plan.opSolo[1]);
        assertEquals("A", model.text.substring(plan.opStart[1], plan.opEnd[1]));
    }

    @Test
    public void gridFaithfulAsciiRunSharesOneAnchoredDraw() {
        TerminalCanvasRenderer.LineModel model = modelOf("ABCD");
        TerminalCanvasRenderer.LinePlan plan = planOf(model, 0, FAITHFUL, 10f);
        assertEquals(1, plan.textOpCount);
        assertEquals(0, plan.opStart[0]);
        assertEquals(4, plan.opEnd[0]);
        assertEquals(0, plan.opColumn[0]);
        assertFalse(plan.opSolo[0]);
    }

    @Test
    public void offGridGlyphBreaksTheRunAndStaysAnchoredAtItsSlot() {
        TerminalCanvasRenderer.LineModel model = modelOf("AÉB");
        TerminalCanvasRenderer.AdvanceOracle drifting = codePoint -> codePoint == 0xc9 ? 13f : 10f;
        TerminalCanvasRenderer.LinePlan plan = planOf(model, 0, drifting, 10f);
        assertEquals(3, plan.textOpCount);
        assertEquals(0, plan.opColumn[0]);
        assertFalse(plan.opSolo[0]);
        assertEquals(1, plan.opColumn[1]);
        assertTrue(plan.opSolo[1]);
        assertEquals(2, plan.opColumn[2]);
        assertFalse(plan.opSolo[2]);
        assertEquals("A", model.text.substring(plan.opStart[0], plan.opEnd[0]));
        assertEquals("É", model.text.substring(plan.opStart[1], plan.opEnd[1]));
        assertEquals("B", model.text.substring(plan.opStart[2], plan.opEnd[2]));
    }

    @Test
    public void letterSpacingCountedOncePerCellInRuns() {
        // Simulates the production oracle: shaped advance plus one letterSpacing term,
        // against a cell step that also carries exactly that one term (W1-06 P2-2).
        TerminalCanvasRenderer.LineModel model = modelOf("MK");
        TerminalCanvasRenderer.AdvanceOracle spaced = codePoint -> 12f;
        TerminalCanvasRenderer.LinePlan plan = planOf(model, 0, spaced, 12f);
        assertEquals(1, plan.textOpCount);
        assertEquals(0, plan.opColumn[0]);
        assertFalse(plan.opSolo[0]);
    }

    @Test
    public void combiningMarkStaysInsideItsBaseCell() {
        TerminalCanvasRenderer.LineModel model = modelOf("e\u0301");
        assertEquals(1, model.lineColumns[0]);
        TerminalCanvasRenderer.LinePlan plan = planOf(model, 0, FAITHFUL, 10f);
        assertEquals(1, plan.textOpCount);
        assertEquals(0, plan.opStart[0]);
        assertEquals(2, plan.opEnd[0]);
    }

    @Test
    public void emojiSurrogatePairOccupiesTwoColumnsSolo() {
        TerminalCanvasRenderer.LineModel model = modelOf("\ud83d\ude80B");
        TerminalCanvasRenderer.LinePlan plan = planOf(model, 0, FAITHFUL, 10f);
        assertEquals(2, plan.textOpCount);
        assertEquals(0, plan.opColumn[0]);
        assertTrue(plan.opSolo[0]);
        assertEquals(2, plan.opColumn[1]);
        assertEquals("B", model.text.substring(plan.opStart[1], plan.opEnd[1]));
    }

    @Test
    public void backgroundRunsGroupPerEffectiveColor() {
        TerminalScreenModel model = new TerminalScreenModel(80, 24);
        model.append("\u001b[41mAB\u001b[42mCD\u001b[0m\r\n");
        TerminalCanvasRenderer.LineModel lines = TerminalCanvasRenderer.LineModel.from(model.displaySnapshot());
        TerminalCanvasRenderer.LinePlan plan = planOf(lines, 0, FAITHFUL, 10f);
        assertEquals(2, plan.bgRunCount);
        assertEquals(0, plan.bgColumn[0]);
        assertEquals(2, plan.bgWidth[0]);
        assertEquals(2, plan.bgColumn[1]);
        assertEquals(2, plan.bgWidth[1]);
        assertEquals(lines.styleAt(0).getEffectiveBackgroundColor().intValue(), plan.bgArgb[0]);
        assertEquals(lines.styleAt(2).getEffectiveBackgroundColor().intValue(), plan.bgArgb[1]);
    }

    @Test
    public void visibleRowRangeNeverExceedsTheViewport() {
        int rowHeight = 20;
        int lineCount = 2000;
        int[] top = TerminalCanvasRenderer.visibleRowRange(0, 30 * rowHeight, 0, rowHeight, lineCount);
        assertEquals(0, top[0]);
        assertEquals(29, top[1]);
        int[] middle = TerminalCanvasRenderer.visibleRowRange(500 * rowHeight, 530 * rowHeight, 0, rowHeight, lineCount);
        assertEquals(500, middle[0]);
        assertEquals(529, middle[1]);
        int[] bottom = TerminalCanvasRenderer.visibleRowRange(1995 * rowHeight, 2100 * rowHeight, 0, rowHeight, lineCount);
        assertEquals(1995, bottom[0]);
        assertEquals(1999, bottom[1]);
        int[] empty = TerminalCanvasRenderer.visibleRowRange(0, 0, 0, rowHeight, lineCount);
        assertTrue(empty[0] > empty[1]);
    }

    @Test
    public void lineModelSplitsLinesIncludingEmptyOnes() {
        // Raw (shell-integration off) model keeps the fixture deterministic. Bare LF
        // does not reset the VT column, so every break here is CRLF; the display text
        // is "a\n\nb\n " — the trailing cursor line holds one blank cell.
        TerminalScreenModel raw = new TerminalScreenModel(80, 24, false);
        raw.append("a\r\n\r\nb\r\n");
        TerminalCanvasRenderer.LineModel model = TerminalCanvasRenderer.LineModel.from(raw.displaySnapshot());
        assertEquals(4, model.lineCount);
        assertEquals(0, model.lineStart(0));
        assertEquals(1, model.lineEnd(0));
        assertEquals(2, model.lineStart(1));
        assertEquals(2, model.lineEnd(1));
        assertEquals(3, model.lineStart(2));
        assertEquals(4, model.lineEnd(2));
        assertEquals(0, model.lineColumns[1]);
        assertEquals(1, model.lineColumns[2]);
    }

    @Test
    public void lineOfOffsetTracksLinesAndTerminators() {
        int[] starts = {0, 2, 3};
        assertEquals(0, TerminalSelectionGeometry.lineOfOffset(starts, 3, 4, 0));
        assertEquals(0, TerminalSelectionGeometry.lineOfOffset(starts, 3, 4, 1));
        assertEquals(1, TerminalSelectionGeometry.lineOfOffset(starts, 3, 4, 2));
        assertEquals(2, TerminalSelectionGeometry.lineOfOffset(starts, 3, 4, 3));
        assertEquals(2, TerminalSelectionGeometry.lineOfOffset(starts, 3, 4, 4));
    }

    @Test
    public void columnOffsetRoundTripRespectsWideCells() {
        String text = "ab\u4e2dde"; // a b 中(2 cols) d e → 6 columns
        assertEquals(6, TerminalSelectionGeometry.lineColumns(text, 0, 5));
        assertEquals(2, TerminalSelectionGeometry.columnAtOffset(text, 0, 5, 2));
        assertEquals(4, TerminalSelectionGeometry.columnAtOffset(text, 0, 5, 3));
        assertEquals(5, TerminalSelectionGeometry.columnAtOffset(text, 0, 5, 4));
        assertEquals(6, TerminalSelectionGeometry.columnAtOffset(text, 0, 5, 5));
        assertEquals(0, TerminalSelectionGeometry.offsetAtColumn(text, 0, 5, 0));
        assertEquals(2, TerminalSelectionGeometry.offsetAtColumn(text, 0, 5, 2));
        assertEquals(2, TerminalSelectionGeometry.offsetAtColumn(text, 0, 5, 3));
        assertEquals(3, TerminalSelectionGeometry.offsetAtColumn(text, 0, 5, 4));
        assertEquals(5, TerminalSelectionGeometry.offsetAtColumn(text, 0, 5, 6));
    }

    @Test
    public void blankLineGeometryStaysAtZero() {
        String text = "a\n\nb";
        assertEquals(0, TerminalSelectionGeometry.lineColumns(text, 2, 2));
        assertEquals(0, TerminalSelectionGeometry.columnAtOffset(text, 2, 2, 0));
        assertEquals(2, TerminalSelectionGeometry.offsetAtColumn(text, 2, 2, 0));
    }
}
