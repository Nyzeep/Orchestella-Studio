package io.github.nyzeep.orchestella.terminal;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * W2-02: exposes the terminal's visible display rows as virtual accessibility nodes
 * so TalkBack can still read the transcript once the {@code TextView} fallback is
 * switched off. One node per visible row, carrying the row's cell content as text and
 * a copy action when a selection exists.
 */
public class TerminalCanvasAccessibilityHelper extends ExploreByTouchHelper {
    private final View host;
    private final Supplier<TerminalCanvasRenderer.LineModel> modelSupplier;
    private final Supplier<TerminalCanvasRenderer> rendererSupplier;
    private final IntSupplier viewportTopPx;
    private final IntSupplier viewportHeightPx;
    private final BooleanSupplier selectionAvailable;
    private final Runnable copySelection;

    public TerminalCanvasAccessibilityHelper(View host,
            Supplier<TerminalCanvasRenderer.LineModel> modelSupplier,
            Supplier<TerminalCanvasRenderer> rendererSupplier,
            IntSupplier viewportTopPx,
            IntSupplier viewportHeightPx,
            BooleanSupplier selectionAvailable,
            Runnable copySelection) {
        super(host);
        this.host = host;
        this.modelSupplier = modelSupplier;
        this.rendererSupplier = rendererSupplier;
        this.viewportTopPx = viewportTopPx;
        this.viewportHeightPx = viewportHeightPx;
        this.selectionAvailable = selectionAvailable;
        this.copySelection = copySelection;
    }

    private int rowHeightPx() {
        TerminalCanvasRenderer renderer = rendererSupplier.get();
        return renderer != null ? renderer.rowHeightPx() : 1;
    }

    private int originYPx() {
        TerminalCanvasRenderer renderer = rendererSupplier.get();
        return renderer != null ? renderer.originYPx() : 0;
    }

    @Override protected int getVirtualViewAt(float x, float y) {
        TerminalCanvasRenderer.LineModel model = modelSupplier.get();
        if (model == null || y < originYPx() || x < 0 || x >= host.getWidth()) return INVALID_ID;
        int row = (int) ((y - originYPx()) / Math.max(1, rowHeightPx()));
        return row < model.lineCount ? row : INVALID_ID;
    }

    @Override protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
        TerminalCanvasRenderer.LineModel model = modelSupplier.get();
        if (model == null) return;
        int rowHeight = Math.max(1, rowHeightPx());
        int top = viewportTopPx.getAsInt();
        int bottom = top + Math.max(0, viewportHeightPx.getAsInt());
        if (bottom <= top) return;
        int[] rows = TerminalCanvasRenderer.visibleRowRange(top, bottom, originYPx(), rowHeight, model.lineCount);
        for (int row = rows[0]; row <= rows[1]; row++) virtualViewIds.add(row);
    }

    @Override protected void onPopulateNodeForVirtualView(int virtualViewId, AccessibilityNodeInfoCompat node) {
        TerminalCanvasRenderer.LineModel model = modelSupplier.get();
        int row = Math.max(0, virtualViewId);
        int rowHeight = Math.max(1, rowHeightPx());
        int top = originYPx() + row * rowHeight;
        node.setBoundsInParent(new Rect(0, top, Math.max(0, host.getWidth()), top + rowHeight));
        node.setClassName(android.widget.TextView.class.getName());
        if (model != null && row < model.lineCount) {
            String line = model.text.substring(model.lineStart(row), model.lineEnd(row));
            if (line.isEmpty()) {
                node.setText(host.getContext().getString(io.github.nyzeep.orchestella.R.string.terminal_a11y_blank_row));
            } else {
                node.setText(line);
            }
            if (selectionAvailable.getAsBoolean()) {
                node.addAction(AccessibilityNodeInfoCompat.ACTION_COPY);
            }
        } else {
            // The accessibility service may keep an ID from the previous viewport.
            node.setText("");
        }
    }

    @Override protected boolean onPerformActionForVirtualView(int virtualViewId, int action, Bundle arguments) {
        if (action == AccessibilityNodeInfoCompat.ACTION_COPY && selectionAvailable.getAsBoolean()) {
            copySelection.run();
            return true;
        }
        return false;
    }
}
