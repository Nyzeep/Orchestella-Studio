package io.github.nyzeep.orchestella;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import io.github.nyzeep.orchestella.terminal.TerminalCanvasAccessibilityHelper;
import io.github.nyzeep.orchestella.terminal.TerminalCanvasRenderer;
import io.github.nyzeep.orchestella.terminal.TerminalScreenModel;
import io.github.nyzeep.orchestella.terminal.TerminalSelectionGeometry;
import io.github.nyzeep.orchestella.ui.design.components.OeLegacyViewStyle;

/** Selectable, bounded VT screen with a single input path for IME, hardware and extra keys. */
public class ModernTerminalView extends ScrollView {
    private static final long FLUSH_INTERVAL_MS = 32L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TerminalScreenModel screen = new TerminalScreenModel(80, 24);
    private boolean sessionScreen;
    private boolean sessionRenderPending;
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingChunks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean flushScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Runnable flushTask = this::flushPendingOutput;
    private TranscriptView transcriptView;
    // Cursor blink repaints at 2 Hz for the whole session; a full-view invalidate here forces
    // the TextView stack to redraw thousands of transcript lines twice a second. Track the
    // on-screen cursor bar instead and invalidate only its damage rect (old ∪ new position).
    private final Rect lastCursorRect = new Rect();
    private final Rect cursorScratchRect = new Rect();
    private TerminalInputSink inputSink;
    private StickyModifierStateMachine stickyStateMachine;
    private TerminalInputConnection currentInputConnection;
    private boolean inputEnabled = true;
    private final io.github.nyzeep.orchestella.terminal.TerminalScrollState scrollState =
            new io.github.nyzeep.orchestella.terminal.TerminalScrollState();
    private boolean selecting;
    private boolean viewportRestorePending;
    private boolean restoringViewport;
    private boolean userScrollAnimation;
    private long lastDisplayEpoch;
    private android.view.ActionMode selectionActionMode;
    private final Runnable restoreViewportTask = this::restoreViewport;
    private final Runnable finishUserScrollTask = () -> {
        userScrollAnimation = false;
        renderScreen();
    };
    private boolean cursorEnabled = true;
    private boolean cursorVisible = true;
    private boolean cursorBlinkEnabled = true;
    private boolean suppressResponses;
    private int cursorOffset;
    private long lastEvictedHistoryRows;
    private float touchDownX;
    private float touchDownY;
    private long touchDownTime;
    private SizeListener sizeListener;
    // Terminal-only background image (below the transcript, above the solid color).
    private boolean hasBackgroundImage;
    private int scrimColor;
    // W2-02: runtime-switchable visible-row Canvas renderer. The selectable TextView
    // is kept as a fallback but only the active child is attached to the ScrollView.
    private final TerminalCanvasRenderer canvasRenderer =
            new TerminalCanvasRenderer();
    private TerminalCanvasView canvasView;
    private TerminalCanvasAccessibilityHelper canvasAccessibility;
    private boolean canvasRenderingEnabled;
    private TerminalCanvasRenderer.LineModel canvasModel;
    private TerminalScreenModel.Snapshot canvasSnapshot;
    private java.util.List<TerminalScreenModel.Snapshot.CommandRange> lastCommandRanges = java.util.List.of();
    private int canvasSelectionStart;
    private int canvasSelectionEnd;
    private boolean canvasSelectionActive;
    private boolean canvasSelectionDragged;
    private boolean canvasSelectionAtTouchDown;
    private float canvasTouchX;
    private float canvasTouchY;
    private boolean canvasLongPressArmed;
    private final Runnable canvasLongPressTask = this::fireCanvasLongPress;
    // W2-02: first-draw presentation probe closing the W1-05 chain (input submit →
    // write completed → pump appended → presented). Null in production by default;
    // armed once per content render and a single field check inside onDraw, so an
    // absent probe costs nothing.
    private java.util.function.LongConsumer presentProbe;
    private boolean presentPending;
    /** Optional paste gate (multi-line paste confirmation hook); false defers the paste. */
    private java.util.function.Predicate<String> pasteInterceptor;

    public interface SizeListener { void onTerminalSizeChanged(int columns, int rows); }

    public ModernTerminalView(Context context) { super(context); init(context); }
    public ModernTerminalView(Context context, AttributeSet attrs) { super(context, attrs); init(context); }
    public ModernTerminalView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); init(context); }

    public void setInputSink(TerminalInputSink sink) {
        inputSink = sink;
        resetInputConnection();
    }

    public void setStickyStateMachine(StickyModifierStateMachine stateMachine) {
        stickyStateMachine = stateMachine;
        resetInputConnection();
    }

    public void setSizeListener(SizeListener listener) {
        sizeListener = listener;
        if (listener != null) listener.onTerminalSizeChanged(screen.columns(), screen.rows());
    }

    public void setSessionScreen(TerminalScreenModel model) {
        if (model != null && sessionScreen && screen == model) {
            resetInputConnection();
            updateTerminalSize();
            renderScreen();
            return;
        }
        sessionScreen = model != null;
        screen = model != null ? model : new TerminalScreenModel(screen.columns(), screen.rows());
        resetInputConnection();
        if (canvasSelectionActive) clearCanvasSelection();
        scrollState.follow();
        lastDisplayEpoch = screen.displayEpoch();
        lastEvictedHistoryRows = screen.displaySnapshot().evictedHistoryRows;
        pendingChunks.clear();
        sessionRenderPending = false;
        mainHandler.removeCallbacks(flushTask);
        flushScheduled.set(false);
        if (!sessionScreen) screen.setResponseSink(response -> {
            if (!suppressResponses && inputEnabled && inputSink != null) inputSink.sendText(response);
        });
        updateTerminalSize();
        renderScreen();
    }

    public int getTerminalColumns() { return screen.columns(); }
    public int getTerminalRows() { return screen.rows(); }
    public TerminalInputConnection getCurrentInputConnection() { return ensureInputConnection(); }

    private TerminalInputConnection ensureInputConnection() {
        if (!inputEnabled || inputSink == null) return null;
        if (currentInputConnection == null || currentInputConnection.isClosed()) {
            currentInputConnection = new TerminalInputConnection(inputSink, stickyStateMachine);
            currentInputConnection.setTerminalModes(screen::isApplicationCursorKeys, screen::isBracketedPaste);
            currentInputConnection.setContextMenuHandler(this::performTerminalContextAction);
        }
        return currentInputConnection;
    }

    private void resetInputConnection() {
        if (currentInputConnection != null) currentInputConnection.closeConnection();
        currentInputConnection = null;
    }

    public void setTerminalInputEnabled(boolean enabled) {
        inputEnabled = enabled;
        setCursorEnabled(enabled);
        if (!enabled) {
            resetInputConnection();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    // ── W2-02: Canvas renderer runtime switch, presentation probe, paste hook ──

    /**
     * Toggles between the visible-row Canvas child and the selectable TextView
     * fallback. A ScrollView hosts exactly one direct child, so the switch swaps
     * which view is attached; the detached view keeps its state untouched, which is
     * what keeps the fallback instantly available.
     */
    public void setCanvasRenderingEnabled(boolean enabled) {
        if (canvasRenderingEnabled == enabled) return;
        canvasRenderingEnabled = enabled;
        if (canvasView == null) return;
        if (enabled) {
            if (selectionActionMode != null) selectionActionMode.finish();
            removeView(transcriptView);
            if (canvasView.getParent() == null) {
                addView(canvasView, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            }
        } else {
            cancelCanvasLongPress();
            if (canvasSelectionActive) clearCanvasSelection();
            removeView(canvasView);
            canvasModel = null;
            canvasSnapshot = null;
            lastCommandRanges = java.util.List.of();
            addView(transcriptView, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        // Both paths rebuild from the same model, so content and scroll semantics stay identical.
        updateTerminalSize();
        renderScreen();
    }

    /** Presentation probe fired once per content render's first draw (elapsedRealtimeNanos). */
    public void setPresentProbe(java.util.function.LongConsumer probe) {
        presentProbe = probe;
    }

    /** Multi-line paste confirmation hook; returning false defers the current paste. */
    public void setPasteInterceptor(java.util.function.Predicate<String> interceptor) {
        pasteInterceptor = interceptor;
    }

    private void maybeFirePresentProbe() {
        if (presentPending && presentProbe != null) {
            presentPending = false;
            presentProbe.accept(android.os.SystemClock.elapsedRealtimeNanos());
        }
    }

    private View activeContentView() {
        return canvasRenderingEnabled ? canvasView : transcriptView;
    }

    private int activeLineHeight() {
        return canvasRenderingEnabled ? canvasRenderer.rowHeightPx()
                : Math.max(2, transcriptView.getLineHeight());
    }

    private void invalidateActiveView() {
        (canvasRenderingEnabled ? canvasView : transcriptView).invalidate();
    }

    /** Test/inspection access to the active Canvas projection (package-private). */
    TerminalCanvasRenderer.LineModel getCanvasModel() {
        return canvasModel;
    }

    /** The Canvas child once attached (package-private); null before the first enable. */
    View canvasChild() { return canvasView; }

    /** Whether a canvas selection is being drawn (package-private for tests). */
    boolean isCanvasSelectionActive() { return canvasSelectionActive; }

    /** Exclusive end of the canvas selection (package-private for tests). */
    int canvasSelectionEnd() { return canvasSelectionEnd; }

    /** Test/inspection access to the Canvas renderer metrics and draw probe. */
    TerminalCanvasRenderer getCanvasRenderer() {
        return canvasRenderer;
    }

    /** Theme-independent selection wash: foreground blended over the background. */
    private int canvasSelectionColor() {
        return androidx.core.graphics.ColorUtils.blendARGB(
                DraculaPalette.themedBackground(), DraculaPalette.themedForeground(), 0.35f);
    }

    @Override public boolean onCheckIsTextEditor() { return inputEnabled; }

    @Override public InputConnection onCreateInputConnection(EditorInfo attributes) {
        if (attributes != null) {
            attributes.inputType = android.text.InputType.TYPE_NULL;
            attributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        }
        resetInputConnection();
        return ensureInputConnection();
    }

    public void requestTerminalFocus() {
        if (!inputEnabled || selecting) return;
        requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (inputEnabled && event.isCtrlPressed() && event.isShiftPressed()
                && (event.getKeyCode() == KeyEvent.KEYCODE_C || event.getKeyCode() == KeyEvent.KEYCODE_V)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_C) copySelection(); else pasteFromClipboard();
            }
            return true;
        }
        TerminalInputConnection connection = ensureInputConnection();
        if (!selecting && connection != null && connection.sendKeyEvent(event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) scrollToBottom();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    public boolean sendTerminalKey(int keyCode) {
        TerminalInputConnection connection = ensureInputConnection();
        if (connection == null) return false;
        boolean sent = connection.sendKey(KeyEvent.ACTION_DOWN, keyCode);
        if (sent) scrollToBottom();
        return sent;
    }

    public boolean sendTerminalText(CharSequence text) {
        TerminalInputConnection connection = ensureInputConnection();
        if (connection == null) return false;
        boolean sent = connection.commitText(text, 1);
        if (sent) scrollToBottom();
        return sent;
    }

    public boolean pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        TerminalInputConnection connection = ensureInputConnection();
        if (clipboard == null || connection == null || !clipboard.hasPrimaryClip()) return false;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return false;
        CharSequence text = clip.getItemAt(0).coerceToText(getContext());
        if (text == null) return false;
        // W2-02 hook: a multi-line paste confirmation can veto here and replay the
        // paste later via sendTerminalText; null (default) pastes immediately.
        if (pasteInterceptor != null && !pasteInterceptor.test(text.toString())) return false;
        boolean sent = connection.pasteText(text);
        if (sent) scrollToBottom();
        return sent;
    }

    public boolean copySelection() {
        CharSequence selected;
        if (canvasRenderingEnabled) {
            if (!canvasSelectionActive || canvasSelectionStart == canvasSelectionEnd) return false;
            if (canvasModel == null) return false;
            // Pure cell text: the command rail is a paint-only decoration, so nothing
            // extra can leak into the clipboard.
            selected = canvasModel.text.substring(
                    Math.min(canvasSelectionStart, canvasSelectionEnd),
                    Math.max(canvasSelectionStart, canvasSelectionEnd));
        } else {
            int start = transcriptView.getSelectionStart();
            int end = transcriptView.getSelectionEnd();
            if (start < 0 || end < 0 || start == end) return false;
            selected = transcriptView.getText()
                    .subSequence(Math.min(start, end), Math.max(start, end));
        }
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return false;
        clipboard.setPrimaryClip(ClipData.newPlainText("终端", selected));
        // T2 (2026-11-08): user-perceivable feedback on the success path only —
        // the failure paths above stay silent on purpose.
        Toast.makeText(getContext(), R.string.terminal_copy_done, Toast.LENGTH_SHORT).show();
        return true;
    }

    private boolean performTerminalContextAction(int id) {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) return pasteFromClipboard();
        if (id == android.R.id.copy) return copySelection();
        if (id == android.R.id.selectAll) {
            if (canvasRenderingEnabled) {
                selectAllTerminalText();
                return true;
            }
            return transcriptView.onTextContextMenuItem(id);
        }
        return false;
    }

    private void init(Context context) {
        setBackgroundColor(DraculaPalette.themedBackground());
        setFillViewport(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        // The parent owns the IME connection; the selectable transcript may still take focus for selection.
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        transcriptView = new TranscriptView(context);
        // W1-06: JetBrains Mono NL; no size here — applyTheme() sets the
        // ThemeStore size once, avoiding a second updateTerminalSize on attach.
        transcriptView.setTypeface(OeLegacyViewStyle.monoTypeface(context));
        transcriptView.setTextColor(DraculaPalette.themedForeground());
        transcriptView.setBackgroundColor(DraculaPalette.themedBackground());
        transcriptView.setIncludeFontPadding(false);
        // The VT model owns wrapping. Android must not add a second, glyph-dependent wrap.
        transcriptView.setHorizontallyScrolling(true);
        transcriptView.setTextIsSelectable(true);
        int padding = dpToPx(context, 10);
        transcriptView.setPadding(padding, padding, padding, padding);
        addView(transcriptView, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        transcriptView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchDownX = event.getX(); touchDownY = event.getY(); touchDownTime = event.getEventTime();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP && !selecting
                    && event.getEventTime() - touchDownTime < ViewConfiguration.getLongPressTimeout()
                    && Math.hypot(event.getX() - touchDownX, event.getY() - touchDownY)
                    < ViewConfiguration.get(context).getScaledTouchSlop()) {
                post(this::requestTerminalFocus);
            }
            return false;
        });
        transcriptView.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                selecting = true;
                selectionActionMode = mode;
                scrollState.selectionChanged(true, getScrollY());
                transcriptView.invalidate();
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
            @Override public void onDestroyActionMode(ActionMode mode) {
                selecting = false;
                selectionActionMode = null;
                scrollState.selectionChanged(false, getScrollY());
                post(() -> { renderScreen(); requestFocus(); });
            }
        });
        // W2-02: the Canvas child is built here but attached only when the runtime
        // switch flips (a ScrollView hosts one direct child). Geometry defaults below
        // cover draws before applyTheme().
        canvasView = new TerminalCanvasView(context);
        int canvasPadding = dpToPx(context, 10);
        canvasView.setPadding(canvasPadding, canvasPadding, canvasPadding, canvasPadding);
        canvasRenderer.setOrigin(canvasPadding, canvasPadding);
        canvasRenderer.setRailGeometry(dpToPx(context, 6), dpToPx(context, 2));
        canvasRenderer.configure(OeLegacyViewStyle.monoTypeface(context),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, context.getResources().getDisplayMetrics()),
                0f, 0f, canvasSelectionColor(), DraculaPalette.themedForeground(), DraculaPalette.GLOW_PURPLE);
        canvasView.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    canvasTouchX = event.getX();
                    canvasTouchY = event.getY();
                    touchDownTime = event.getEventTime();
                    canvasSelectionDragged = false;
                    canvasSelectionAtTouchDown = canvasSelectionActive;
                    if (canvasSelectionActive) return true;
                    canvasLongPressArmed = true;
                    mainHandler.postDelayed(canvasLongPressTask, ViewConfiguration.getLongPressTimeout());
                    // Claim the DOWN so MOVE/UP/CANCEL keep flowing into this listener;
                    // the ScrollView still intercepts beyond the touch slop and then
                    // delivers ACTION_CANCEL, which the branch below handles.
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (canvasSelectionActive) {
                        extendCanvasSelection(event.getX(), event.getY());
                        return true;
                    }
                    if (canvasLongPressArmed && Math.hypot(event.getX() - canvasTouchX, event.getY() - canvasTouchY)
                            > ViewConfiguration.get(context).getScaledTouchSlop()) {
                        cancelCanvasLongPress();
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                    cancelCanvasLongPress();
                    if (canvasSelectionActive) {
                        // Keep the menu after the lift that ends the initiating long press.
                        if (canvasSelectionAtTouchDown && !canvasSelectionDragged) clearCanvasSelection();
                        if (canvasView.getParent() != null) {
                            canvasView.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        return true;
                    }
                    if (event.getEventTime() - touchDownTime < ViewConfiguration.getLongPressTimeout()
                            && Math.hypot(event.getX() - canvasTouchX, event.getY() - canvasTouchY)
                            < ViewConfiguration.get(context).getScaledTouchSlop()) {
                        post(this::requestTerminalFocus);
                    }
                    return false;
                case MotionEvent.ACTION_CANCEL:
                    cancelCanvasLongPress();
                    if (canvasSelectionActive) clearCanvasSelection();
                    if (canvasView.getParent() != null) {
                        canvasView.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return false;
                default:
                    return false;
            }
        });
        screen.setResponseSink(response -> {
            if (!suppressResponses && inputEnabled && inputSink != null) inputSink.sendText(response);
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mainHandler.removeCallbacks(finishUserScrollTask);
            mainHandler.removeCallbacks(restoreViewportTask);
            userScrollAnimation = false;
            viewportRestorePending = false;
            scrollState.beginTouch(getScrollY());
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            scrollState.endTouch(getScrollY(), maximumScrollY(), activeLineHeight());
            userScrollAnimation = true;
            mainHandler.postDelayed(finishUserScrollTask, 100L);
        }
        return handled;
    }

    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if (canvasRenderingEnabled) {
            canvasView.invalidate();
            canvasAccessibility.invalidateRoot();
        }
        if (!restoringViewport && !viewportRestorePending && (scrollState.isTouching() || userScrollAnimation)) {
            scrollState.userScrolled(y, maximumScrollY(), activeLineHeight());
            if (userScrollAnimation) {
                mainHandler.removeCallbacks(finishUserScrollTask);
                mainHandler.postDelayed(finishUserScrollTask, 100L);
            }
        }
    }

    @Override public void requestChildFocus(View child, View focused) {
        // TextView focus does not decide which terminal rows the user is reading.
        int previousY = getScrollY();
        boolean oldRestoring = restoringViewport;
        restoringViewport = true;
        super.requestChildFocus(child, focused);
        scrollTo(0, previousY);
        restoringViewport = oldRestoring;
    }

    @Override public boolean requestChildRectangleOnScreen(View child, android.graphics.Rect rectangle, boolean immediate) {
        // Selecting output uses touch scrolling; setText/cursor focus must not jump to row zero.
        return false;
    }

    private int maximumScrollY() {
        int viewport = getHeight() - getPaddingTop() - getPaddingBottom();
        return Math.max(0, activeContentView().getHeight() - viewport);
    }

    private void restoreViewport() {
        if (activeContentView().isLayoutRequested()) return;
        viewportRestorePending = false;
        int target = scrollState.targetY(maximumScrollY(), screen.isAlternateScreen());
        if (target < 0 || userScrollAnimation) return;
        restoringViewport = true;
        scrollTo(0, target);
        restoringViewport = false;
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        boolean oldRestoring = restoringViewport;
        restoringViewport = true;
        super.onLayout(changed, left, top, right, bottom);
        restoringViewport = oldRestoring;
        // Restore after TextView has its new measured height, not before a queued traversal.
        if (!scrollState.isTouching() && !selecting && !userScrollAnimation) restoreViewport();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateTerminalSize();
    }

    private void updateTerminalSize() {
        if (transcriptView == null || getWidth() <= 0 || getHeight() <= 0) return;
        // Measure the additional mono cell with the active paint. Differencing cancels
        // edge spacing and includes inter-cell tracking once (W1-06 P2-2). Canvas mode
        // reuses the renderer's identical metrics so both modes agree on the grid.
        TextPaint paint = transcriptView.getPaint();
        float cellWidth = Layout.getDesiredWidth("MM", paint) - Layout.getDesiredWidth("M", paint);
        int lineHeight = transcriptView.getLineHeight();
        if (canvasRenderingEnabled) {
            cellWidth = canvasRenderer.cellStepPx();
            lineHeight = canvasRenderer.rowHeightPx();
        }
        int width = getWidth() - getPaddingLeft() - getPaddingRight()
                - transcriptView.getPaddingLeft() - transcriptView.getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom()
                - transcriptView.getPaddingTop() - transcriptView.getPaddingBottom();
        int columns = Math.max(2, Math.min(512, (int) (width / Math.max(1f, cellWidth))));
        int rows = Math.max(2, Math.min(256, height / Math.max(1, lineHeight)));
        if (columns != screen.columns() || rows != screen.rows()) {
            screen.resize(columns, rows);
            renderScreen();
            if (sizeListener != null) sizeListener.onTerminalSizeChanged(columns, rows);
        }
        if (scrollState.isFollowing() && !selecting) scrollToBottom();
    }

    /** Thread-safe output ingestion; parsing and rendering always run on the UI thread. */
    public void appendOutput(String text) {
        if (text == null || text.isEmpty()) return;
        pendingChunks.offer(text);
        if (flushScheduled.compareAndSet(false, true)) mainHandler.postDelayed(flushTask, FLUSH_INTERVAL_MS);
    }

    /** UI-thread repaint request for a screen already advanced by its PTY pump. */
    void requestSessionRender() {
        sessionRenderPending = true;
        if (flushScheduled.compareAndSet(false, true)) mainHandler.postDelayed(flushTask, FLUSH_INTERVAL_MS);
    }

    /**
     * Writes text that this view itself owns into the session model and repaints.
     *
     * This is the preview's entry point: Theme Studio hands the view a model that no
     * PTY pump feeds, so the text must land in the model rather than ride the
     * {@link #appendOutput(String)} render-tick path (which deliberately does not append
     * while {@code sessionScreen} is true — the pool already owns that model).
     * A no-op outside session mode, so it can never double-write a live shell.
     */
    public void appendSessionOutput(String text) {
        if (text == null || text.isEmpty()) return;
        Runnable append = () -> {
            if (!sessionScreen) return;
            screen.append(text);
            renderScreen();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) append.run();
        else mainHandler.post(append);
    }

    private void flushPendingOutput() {
        flushScheduled.set(false);
        String chunk;
        boolean changed = sessionRenderPending;
        sessionRenderPending = false;
        while ((chunk = pendingChunks.poll()) != null) {
            // Session screens share a model that their owner already writes to:
            // PersistentTerminalSessionPool.pumpLoop() appends every PTY chunk to the
            // same TerminalScreenModel handed to setSessionScreen(), and TerminalActivity
            // forwards the chunk here only to request a repaint. Appending again would
            // duplicate the live shell output, so session mode stays a render tick.
            // Owned screens (sessionScreen == false) still ingest the text here.
            if (!sessionScreen) screen.append(chunk);
            changed = true;
        }
        if (changed) renderScreen();
        if (!pendingChunks.isEmpty() && flushScheduled.compareAndSet(false, true))
            mainHandler.postDelayed(flushTask, FLUSH_INTERVAL_MS);
    }

    private void renderScreen() {
        // While a gesture/selection owns the viewport, only the persistent model changes.
        if (selecting || scrollState.isTouching() || userScrollAnimation) return;
        if (!viewportRestorePending) scrollState.captureAnchor(getScrollY());
        TerminalScreenModel.Snapshot snapshot = screen.displaySnapshot();
        long epoch = screen.displayEpoch();
        if (epoch != lastDisplayEpoch) {
            scrollState.displayCleared();
            lastDisplayEpoch = epoch;
            lastEvictedHistoryRows = snapshot.evictedHistoryRows;
        }
        cursorOffset = snapshot.cursorOffset;
        long trimmedRows = Math.max(0, snapshot.evictedHistoryRows - lastEvictedHistoryRows);
        scrollState.rowsEvicted(trimmedRows, activeLineHeight());
        lastEvictedHistoryRows = snapshot.evictedHistoryRows;
        viewportRestorePending = true;
        if (canvasRenderingEnabled && canvasView != null) {
            if (snapshot != canvasSnapshot) {
                canvasModel = TerminalCanvasRenderer.LineModel.from(snapshot);
                canvasSnapshot = snapshot;
                lastCommandRanges = snapshot.commandLineRanges();
                canvasAccessibility.invalidateRoot();
            }
            canvasView.setContentHeight(canvasRenderer.contentHeightPx(canvasModel.lineCount));
            canvasView.invalidate();
        } else {
            SpannableStringBuilder display = new SpannableStringBuilder();
            for (TerminalSpan span : snapshot.spans) appendStyled(display, span);
            transcriptView.setText(display, TextView.BufferType.SPANNABLE);
        }
        // The layout was rebuilt, so the previously tracked cursor damage rect is meaningless;
        // the rebuilt view already invalidated itself for this frame.
        lastCursorRect.setEmpty();
        cursorVisible = true;
        presentPending = true;
        mainHandler.removeCallbacks(restoreViewportTask);
        mainHandler.post(restoreViewportTask);
    }

    private static void appendStyled(SpannableStringBuilder display, TerminalSpan span) {
        int start = display.length();
        display.append(span.getText());
        int end = display.length();
        if (start == end) return;
        display.setSpan(new ForegroundColorSpan(span.getEffectiveForegroundColor()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Integer background = span.getEffectiveBackgroundColor();
        if (background != null) display.setSpan(new BackgroundColorSpan(background), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int font = (span.isBold() ? Typeface.BOLD : 0) | (span.isItalic() ? Typeface.ITALIC : 0);
        if (font != 0) display.setSpan(new StyleSpan(font), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (span.isUnderline()) display.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Replace replay and queued output together, without replying to historical terminal queries. UI thread only. */
    public void replaceTranscript(String transcript) {
        if (sessionScreen) { renderScreen(); return; }
        pendingChunks.clear();
        mainHandler.removeCallbacks(flushTask);
        flushScheduled.set(false);
        screen.reset();
        suppressResponses = true;
        try { screen.append(transcript); } finally { suppressResponses = false; }
        scrollState.follow();
        renderScreen();
    }

    public void clear() {
        if (Looper.myLooper() == Looper.getMainLooper()) replaceTranscript("");
        else mainHandler.post(() -> replaceTranscript(""));
    }

    public void scrollToBottom() {
        scrollState.follow();
        mainHandler.removeCallbacks(restoreViewportTask);
        mainHandler.post(restoreViewportTask);
    }

    public void scrollToBottomIfFollowing() { if (scrollState.isFollowing() && !selecting) scrollToBottom(); }

    /** Explicit UI display clear; guest Ctrl+L performs the real prompt/program redraw. */
    public void clearVisibleScreen() {
        if (selectionActionMode != null) selectionActionMode.finish();
        if (screen.clearVisibleHistory()) {
            scrollState.displayCleared();
            renderScreen();
        }
        if (inputEnabled && inputSink != null) {
            // CLEAR is an explicit control action, not text input or a sticky Alt chord.
            inputSink.sendBytes(new byte[]{0x0c});
            if (stickyStateMachine != null) stickyStateMachine.consumeLatched();
        }
        scrollToBottom();
    }
    public TextView getTranscriptView() { return transcriptView; }
    public AnsiEscapeParser getParser() { return screen.getStyleParser(); }

    private final Runnable cursorBlinkTask = new Runnable() {
        @Override public void run() {
            cursorVisible = !cursorVisible;
            invalidateCursorDamage();
            if (cursorEnabled && cursorBlinkEnabled) mainHandler.postDelayed(this, 500L);
        }
    };

    /**
     * Repaints only the cursor bar region: the union of the previously drawn bar (to erase it)
     * and the current bar position. Falls back to a full invalidate whenever the geometry
     * cannot be computed yet, which is rare (layout not created or terminal not cursor-visible).
     */
    private void invalidateCursorDamage() {
        if (canvasRenderingEnabled) {
            boolean hasCurrent = computeCanvasCursorRect(cursorScratchRect);
            if (!hasCurrent && lastCursorRect.isEmpty()) {
                canvasView.invalidate();
                return;
            }
            if (hasCurrent) {
                if (!lastCursorRect.isEmpty()) cursorScratchRect.union(lastCursorRect);
                canvasView.invalidate(cursorScratchRect);
                lastCursorRect.set(cursorScratchRect);
            } else {
                canvasView.invalidate(lastCursorRect);
                lastCursorRect.setEmpty();
            }
            return;
        }
        boolean hasCurrent = computeCursorRect(cursorScratchRect);
        if (!hasCurrent && lastCursorRect.isEmpty()) {
            transcriptView.invalidate();
            return;
        }
        if (hasCurrent) {
            if (!lastCursorRect.isEmpty()) cursorScratchRect.union(lastCursorRect);
            transcriptView.invalidate(cursorScratchRect);
            lastCursorRect.set(cursorScratchRect);
        } else {
            transcriptView.invalidate(lastCursorRect);
            lastCursorRect.setEmpty();
        }
    }

    /** Cursor damage rect in canvas-view coordinates; false when nothing to draw. */
    private boolean computeCanvasCursorRect(Rect out) {
        TerminalCanvasRenderer.LineModel model = canvasModel;
        if (model == null || !cursorEnabled || !cursorVisible || !screen.isCursorVisible() || selecting) {
            return false;
        }
        int offset = Math.max(0, Math.min(cursorOffset, model.text.length()));
        int row = model.lineOfOffset(offset);
        int top = canvasRenderer.originYPx() + row * canvasRenderer.rowHeightPx();
        out.set(0, top, Math.max(1, canvasView.getWidth()), top + canvasRenderer.rowHeightPx());
        return true;
    }

    /** Computes the cursor bar bounds in transcript-view coordinates; false when there is nothing to draw. */
    private boolean computeCursorRect(Rect out) {
        Layout layout = transcriptView.getLayout();
        if (layout == null || !cursorEnabled || !cursorVisible || !screen.isCursorVisible() || selecting) return false;
        int offset = Math.max(0, Math.min(cursorOffset, transcriptView.getText().length()));
        int line = layout.getLineForOffset(offset);
        float barWidth = Math.max(2f, transcriptView.getTextSize() / 10f);
        int x = (int) (transcriptView.getTotalPaddingLeft() + layout.getPrimaryHorizontal(offset));
        int top = transcriptView.getTotalPaddingTop() + layout.getLineTop(line);
        int bottom = transcriptView.getTotalPaddingTop() + layout.getLineBottom(line);
        out.set(x - 2, top, (int) (x + barWidth) + 2, bottom);
        return true;
    }

    public void setCursorEnabled(boolean enabled) {
        cursorEnabled = enabled;
        if (enabled) startCursorBlink(); else stopCursorBlink();
    }
    public boolean isCursorEnabled() { return cursorEnabled; }
    public void setCursorBlinkEnabled(boolean enabled) { cursorBlinkEnabled = enabled; startCursorBlink(); }
    public void startCursorBlink() {
        mainHandler.removeCallbacks(cursorBlinkTask);
        cursorVisible = true;
        invalidateActiveView();
        if (cursorEnabled && cursorBlinkEnabled && isAttachedToWindow()) mainHandler.postDelayed(cursorBlinkTask, 500L);
    }
    public void stopCursorBlink() {
        mainHandler.removeCallbacks(cursorBlinkTask);
        cursorVisible = false;
        invalidateActiveView();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); startCursorBlink(); }
    @Override protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(cursorBlinkTask);
        mainHandler.removeCallbacks(restoreViewportTask);
        mainHandler.removeCallbacks(finishUserScrollTask);
        cancelCanvasLongPress();
        if (canvasSelectionActive) clearCanvasSelection();
        resetInputConnection();
        super.onDetachedFromWindow();
    }

    public void applyTheme(io.github.nyzeep.orchestella.ui.theme.AppTheme theme,
            int fontSizeSp, float letterSpacingEm, float lineSpacingExtraPx) {
        // A picked image survives theme switches; only the scrim color follows the theme.
        if (hasBackgroundImage) {
            applyScrimColor();
        } else {
            setBackgroundColor(theme.getTerminalBackground());
        }
        transcriptView.setBackgroundColor(hasBackgroundImage ? android.graphics.Color.TRANSPARENT
                : theme.getTerminalBackground());
        transcriptView.setTextColor(theme.getTerminalForeground());
        transcriptView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
        transcriptView.setLetterSpacing(letterSpacingEm);
        transcriptView.setLineSpacing(lineSpacingExtraPx, 1f);
        // W2-02: keep the Canvas grid in lockstep with the TextView metrics — same
        // typeface, size, single letterSpacing term and row spacing.
        canvasRenderer.configure(OeLegacyViewStyle.monoTypeface(getContext()),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, getResources().getDisplayMetrics()),
                letterSpacingEm, lineSpacingExtraPx,
                canvasSelectionColor(), DraculaPalette.themedForeground(), theme.getAccent());
        updateTerminalSize();
        renderScreen();
    }

    /** Layers the decoded image under a scrim of the given color; the transcript turns transparent. */
    public void setTerminalBackgroundImage(android.graphics.Bitmap bitmap, int scrimArgb) {
        hasBackgroundImage = true;
        scrimColor = scrimArgb;
        android.graphics.drawable.Drawable image =
                new CoverCropDrawable(getResources(), bitmap);
        setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                image, new android.graphics.drawable.ColorDrawable(scrimArgb)}));
        transcriptView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    }

    /** Keeps the scrim color for the next image; falls back to the solid terminal color now. */
    public void clearTerminalBackgroundImage(int fallbackColor) {
        hasBackgroundImage = false;
        setBackground(null);
        setBackgroundColor(fallbackColor);
        transcriptView.setBackgroundColor(fallbackColor);
    }

    /** Updates only the overlay color; the bitmap stays untouched. */
    public void setTerminalScrimColor(int scrimArgb) {
        scrimColor = scrimArgb;
        if (hasBackgroundImage) applyScrimColor();
    }

    private void applyScrimColor() {
        if (getBackground() instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.Drawable scrim =
                    ((android.graphics.drawable.LayerDrawable) getBackground()).getDrawable(1);
            if (scrim instanceof android.graphics.drawable.ColorDrawable) {
                ((android.graphics.drawable.ColorDrawable) scrim).setColor(scrimColor);
            }
        }
    }

    /** W2-02: visible-row Canvas child; the selectable TextView above stays as the fallback. */
    private final class TerminalCanvasView extends android.view.View {
        private int contentHeight;

        TerminalCanvasView(Context context) {
            super(context);
            setBackgroundColor(android.graphics.Color.TRANSPARENT);
            setClickable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            canvasAccessibility = new TerminalCanvasAccessibilityHelper(
                    this,
                    () -> canvasModel,
                    () -> canvasRenderer,
                    ModernTerminalView.this::getScrollY,
                    () -> ModernTerminalView.this.getHeight()
                            - ModernTerminalView.this.getPaddingTop()
                            - ModernTerminalView.this.getPaddingBottom(),
                    ModernTerminalView.this::hasCanvasSelectionText,
                    ModernTerminalView.this::copySelection);
            androidx.core.view.ViewCompat.setAccessibilityDelegate(this, canvasAccessibility);
        }

        void setContentHeight(int height) {
            if (contentHeight == height) return;
            contentHeight = height;
            requestLayout();
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // ScrollView measures its child with UNSPECIFIED height on the scrolling axis.
            setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                    resolveSize(contentHeight, heightMeasureSpec));
        }

        @Override public boolean performClick() {
            // Touch claiming needs a clickable view; accessibility click stays a no-op
            // here (focus and selection live on the host/OnTouchListener path).
            return super.performClick();
        }

        @Override public boolean dispatchHoverEvent(MotionEvent event) {
            return canvasAccessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            maybeFirePresentProbe();
            TerminalCanvasRenderer.LineModel model = canvasModel;
            if (model == null) return;
            // A hardware display list can record the entire child. Intersect it with
            // the ScrollView's viewport explicitly, and redraw that list on scroll.
            int viewportTop = ModernTerminalView.this.getScrollY();
            int viewportHeight = ModernTerminalView.this.getHeight()
                    - ModernTerminalView.this.getPaddingTop() - ModernTerminalView.this.getPaddingBottom();
            int saveCount = canvas.save();
            canvas.clipRect(0, viewportTop, getWidth(), viewportTop + viewportHeight);
            canvasRenderer.draw(canvas, model, cursorOffset,
                    cursorEnabled && cursorVisible && screen.isCursorVisible() && !selecting,
                    canvasSelectionActive,
                    Math.min(canvasSelectionStart, canvasSelectionEnd),
                    Math.max(canvasSelectionStart, canvasSelectionEnd),
                    lastCommandRanges);
            canvas.restoreToCount(saveCount);
        }
    }

    // ── W2-02: canvas selection (long-press → drag → floating ActionMode) ──

    private void fireCanvasLongPress() {
        if (!canvasLongPressArmed || canvasModel == null || !canvasRenderingEnabled) return;
        canvasLongPressArmed = false;
        beginCanvasSelection();
    }

    private void cancelCanvasLongPress() {
        canvasLongPressArmed = false;
        mainHandler.removeCallbacks(canvasLongPressTask);
    }

    /** Maps a point in canvas-view coordinates to a cell-start char offset. */
    private int canvasOffsetAt(float x, float y) {
        TerminalCanvasRenderer.LineModel model = canvasModel;
        if (model == null || model.lineCount == 0) return 0;
        int row = Math.max(0, Math.min(model.lineCount - 1,
                (int) ((y - canvasRenderer.originYPx()) / Math.max(1, canvasRenderer.rowHeightPx()))));
        int column = Math.max(0,
                (int) ((x - canvasRenderer.originXPx()) / Math.max(1f, canvasRenderer.cellStepPx())));
        return TerminalSelectionGeometry.offsetAtColumn(
                model.text, model.lineStart(row), model.lineEnd(row), column);
    }

    private void beginCanvasSelection() {
        TerminalCanvasRenderer.LineModel model = canvasModel;
        if (model == null || model.lineCount == 0) return;
        int offset = canvasOffsetAt(canvasTouchX, canvasTouchY);
        canvasSelectionStart = offset;
        canvasSelectionEnd = offset;
        canvasSelectionActive = true;
        canvasSelectionDragged = false;
        selecting = true;
        scrollState.selectionChanged(true, getScrollY());
        canvasView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        requestFocus();
        if (canvasView.getParent() != null) canvasView.getParent().requestDisallowInterceptTouchEvent(true);
        startCanvasSelectionActionMode();
        canvasView.invalidate();
    }

    private void extendCanvasSelection(float x, float y) {
        int offset = canvasOffsetAt(x, y);
        if (offset == canvasSelectionEnd) return;
        canvasSelectionEnd = offset;
        canvasSelectionDragged = true;
        canvasView.invalidate();
        canvasAccessibility.invalidateRoot();
        if (selectionActionMode != null) {
            // invalidate() re-runs onPrepareActionMode (copy appears once the
            // selection is non-empty); invalidateContentRect() repositions the bar.
            selectionActionMode.invalidate();
            selectionActionMode.invalidateContentRect();
        }
    }

    /** Clears the Canvas selection (package-private so tests can drive the mode). */
    void clearCanvasSelection() {
        boolean hadSelection = canvasSelectionActive;
        canvasSelectionActive = false;
        canvasSelectionDragged = false;
        canvasSelectionStart = 0;
        canvasSelectionEnd = 0;
        if (selecting) {
            selecting = false;
            scrollState.selectionChanged(false, getScrollY());
        }
        if (selectionActionMode != null) {
            ActionMode mode = selectionActionMode;
            selectionActionMode = null;
            mode.finish();
        }
        if (hadSelection) {
            canvasView.invalidate();
            canvasAccessibility.invalidateRoot();
            post(this::renderScreen);
        }
    }

    /** Selects the whole display text in Canvas mode (package-private for tests). */
    void selectAllTerminalText() {
        TerminalCanvasRenderer.LineModel model = canvasModel;
        if (model == null) return;
        if (!canvasSelectionActive) {
            canvasSelectionActive = true;
            selecting = true;
            scrollState.selectionChanged(true, getScrollY());
        }
        canvasSelectionStart = 0;
        canvasSelectionEnd = model.text.length();
        canvasView.invalidate();
        canvasAccessibility.invalidateRoot();
        if (selectionActionMode != null) {
            selectionActionMode.invalidate();
            selectionActionMode.invalidateContentRect();
        }
    }

    private boolean hasCanvasSelectionText() {
        return canvasSelectionActive && canvasSelectionStart != canvasSelectionEnd;
    }

    private void startCanvasSelectionActionMode() {
        android.view.ActionMode.Callback2 callback = new android.view.ActionMode.Callback2() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(0, android.R.id.copy, 0, R.string.terminal_action_copy)
                        .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS
                                | android.view.MenuItem.SHOW_AS_ACTION_WITH_TEXT);
                menu.add(0, android.R.id.selectAll, 0, R.string.terminal_action_select_all)
                        .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
                menu.add(0, android.R.id.paste, 0, R.string.terminal_action_paste)
                        .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                MenuItem copy = menu.findItem(android.R.id.copy);
                if (copy != null) copy.setVisible(hasCanvasSelectionText());
                return true;
            }

            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int id = item.getItemId();
                if (id == android.R.id.copy) {
                    boolean copied = copySelection();
                    if (copied) mode.finish();
                    return copied;
                }
                if (id == android.R.id.selectAll) {
                    selectAllTerminalText();
                    return true;
                }
                if (id == android.R.id.paste) {
                    boolean pasted = pasteFromClipboard();
                    if (pasted) mode.finish();
                    return pasted;
                }
                return false;
            }

            @Override public void onDestroyActionMode(ActionMode mode) {
                if (selectionActionMode == mode) selectionActionMode = null;
                if (canvasSelectionActive) clearCanvasSelection();
            }

            @Override public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                computeCanvasSelectionRect(outRect);
            }
        };
        selectionActionMode = canvasView.startActionMode(callback, ActionMode.TYPE_FLOATING);
    }

    /** Selection bounding rect in canvas-view coordinates for the floating toolbar. */
    private void computeCanvasSelectionRect(Rect outRect) {
        TerminalCanvasRenderer.LineModel model = canvasModel;
        if (model == null || model.lineCount == 0 || !canvasSelectionActive) {
            int middle = Math.max(1, canvasView.getWidth() / 2);
            outRect.set(middle, canvasRenderer.originYPx(), middle + 1,
                    canvasRenderer.originYPx() + canvasRenderer.rowHeightPx());
            return;
        }
        int first = Math.min(canvasSelectionStart, canvasSelectionEnd);
        int last = Math.max(canvasSelectionStart, canvasSelectionEnd);
        int firstRow = model.lineOfOffset(first);
        int lastRow = model.lineOfOffset(Math.max(first, last - 1));
        int top = canvasRenderer.originYPx() + firstRow * canvasRenderer.rowHeightPx();
        int bottom = canvasRenderer.originYPx() + (lastRow + 1) * canvasRenderer.rowHeightPx();
        outRect.set(0, top, Math.max(1, canvasView.getWidth()), bottom);
    }

    private final class TranscriptView extends androidx.appcompat.widget.AppCompatTextView {
        private final Paint cursorPaint = new Paint();
        TranscriptView(Context context) { super(context); }
        @Override public boolean onTextContextMenuItem(int id) {
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) return pasteFromClipboard();
            return super.onTextContextMenuItem(id);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            maybeFirePresentProbe();
            Layout layout = getLayout();
            if (layout == null || !cursorEnabled || !cursorVisible || !screen.isCursorVisible() || selecting) return;
            int offset = Math.max(0, Math.min(cursorOffset, getText().length()));
            int line = layout.getLineForOffset(offset);
            float x = getTotalPaddingLeft() + layout.getPrimaryHorizontal(offset);
            float y = getTotalPaddingTop() + layout.getLineTop(line);
            cursorPaint.setColor(DraculaPalette.themedForeground());
            canvas.drawRect(x, y, x + Math.max(2f, getTextSize() / 10f),
                    getTotalPaddingTop() + layout.getLineBottom(line), cursorPaint);
        }
    }

    private static int dpToPx(Context context, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }
}
