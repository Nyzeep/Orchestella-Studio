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
import io.github.nyzeep.orchestella.terminal.TerminalScreenModel;

/** Selectable, bounded VT screen with a single input path for IME, hardware and extra keys. */
public class ModernTerminalView extends ScrollView {
    private static final long FLUSH_INTERVAL_MS = 32L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TerminalScreenModel screen = new TerminalScreenModel(80, 24);
    private boolean sessionScreen;
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
        scrollState.follow();
        lastDisplayEpoch = screen.displayEpoch();
        lastEvictedHistoryRows = screen.displaySnapshot().evictedHistoryRows;
        pendingChunks.clear();
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
        boolean sent = connection.pasteText(text);
        if (sent) scrollToBottom();
        return sent;
    }

    public boolean copySelection() {
        int start = transcriptView.getSelectionStart();
        int end = transcriptView.getSelectionEnd();
        if (start < 0 || end < 0 || start == end) return false;
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return false;
        clipboard.setPrimaryClip(ClipData.newPlainText("终端", transcriptView.getText()
                .subSequence(Math.min(start, end), Math.max(start, end))));
        return true;
    }

    private boolean performTerminalContextAction(int id) {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) return pasteFromClipboard();
        if (id == android.R.id.copy) return copySelection();
        if (id == android.R.id.selectAll) return transcriptView.onTextContextMenuItem(id);
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
        transcriptView.setTypeface(Typeface.MONOSPACE);
        transcriptView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
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
            scrollState.endTouch(getScrollY(), maximumScrollY(), Math.max(2, transcriptView.getLineHeight()));
            userScrollAnimation = true;
            mainHandler.postDelayed(finishUserScrollTask, 100L);
        }
        return handled;
    }

    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if (!restoringViewport && !viewportRestorePending && (scrollState.isTouching() || userScrollAnimation)) {
            scrollState.userScrolled(y, maximumScrollY(), Math.max(2, transcriptView.getLineHeight()));
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
        return Math.max(0, transcriptView.getHeight() - viewport);
    }

    private void restoreViewport() {
        if (transcriptView.isLayoutRequested()) return;
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
        float cellWidth = transcriptView.getPaint().measureText("M")
                + transcriptView.getLetterSpacing() * transcriptView.getTextSize();
        int width = getWidth() - getPaddingLeft() - getPaddingRight()
                - transcriptView.getPaddingLeft() - transcriptView.getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom()
                - transcriptView.getPaddingTop() - transcriptView.getPaddingBottom();
        int columns = Math.max(2, Math.min(512, (int) (width / Math.max(1f, cellWidth))));
        int rows = Math.max(2, Math.min(256, height / Math.max(1, transcriptView.getLineHeight())));
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

    private void flushPendingOutput() {
        flushScheduled.set(false);
        String chunk;
        boolean changed = false;
        while ((chunk = pendingChunks.poll()) != null) {
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
        SpannableStringBuilder display = new SpannableStringBuilder();
        for (TerminalSpan span : snapshot.spans) appendStyled(display, span);
        cursorOffset = snapshot.cursorOffset;
        long trimmedRows = Math.max(0, snapshot.evictedHistoryRows - lastEvictedHistoryRows);
        scrollState.rowsEvicted(trimmedRows, transcriptView.getLineHeight());
        lastEvictedHistoryRows = snapshot.evictedHistoryRows;
        viewportRestorePending = true;
        transcriptView.setText(display, TextView.BufferType.SPANNABLE);
        // The layout was rebuilt, so the previously tracked cursor damage rect is meaningless;
        // setText already invalidated the whole view for this frame.
        lastCursorRect.setEmpty();
        cursorVisible = true;
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
        transcriptView.invalidate();
        if (cursorEnabled && cursorBlinkEnabled && isAttachedToWindow()) mainHandler.postDelayed(cursorBlinkTask, 500L);
    }
    public void stopCursorBlink() {
        mainHandler.removeCallbacks(cursorBlinkTask);
        cursorVisible = false;
        transcriptView.invalidate();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); startCursorBlink(); }
    @Override protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(cursorBlinkTask);
        mainHandler.removeCallbacks(restoreViewportTask);
        mainHandler.removeCallbacks(finishUserScrollTask);
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
        updateTerminalSize();
        renderScreen();
    }

    /** Layers the decoded image under a scrim of the given color; the transcript turns transparent. */
    public void setTerminalBackgroundImage(android.graphics.Bitmap bitmap, int scrimArgb) {
        hasBackgroundImage = true;
        scrimColor = scrimArgb;
        android.graphics.drawable.Drawable image =
                new CenterCropBackgroundDrawable(getResources(), bitmap);
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

    /**
     * Center-crops the bitmap to the view bounds while remembering the largest bounds seen.
     * Under adjustResize the view shrinks when the IME opens; bounds only ever grow, so the
     * image keeps its size instead of being squashed, and no re-scale flickers on IME close.
     */
    static final class CenterCropBackgroundDrawable extends android.graphics.drawable.BitmapDrawable {
        private int moldWidth;
        private int moldHeight;
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();

        CenterCropBackgroundDrawable(android.content.res.Resources res, android.graphics.Bitmap bitmap) {
            super(res, bitmap);
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect bounds) {
            super.onBoundsChange(bounds);
            if (bounds.isEmpty()) return;
            if (moldWidth > 0 && bounds.width() <= moldWidth && bounds.height() <= moldHeight) return;
            moldWidth = Math.max(moldWidth, bounds.width());
            moldHeight = Math.max(moldHeight, bounds.height());
            android.graphics.Bitmap bitmap = getBitmap();
            if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) return;
            float scale = Math.max(moldWidth / (float) bitmap.getWidth(),
                    moldHeight / (float) bitmap.getHeight());
            float dx = (moldWidth - bitmap.getWidth() * scale) / 2f;
            float dy = (moldHeight - bitmap.getHeight() * scale) / 2f;
            matrix.setScale(scale, scale);
            matrix.postTranslate(dx, dy);
            invalidateSelf();
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Bitmap bitmap = getBitmap();
            if (bitmap == null) return;
            canvas.drawBitmap(bitmap, matrix, getPaint());
        }
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

    private final class TranscriptView extends androidx.appcompat.widget.AppCompatTextView {
        private final Paint cursorPaint = new Paint();
        TranscriptView(Context context) { super(context); }
        @Override public boolean onTextContextMenuItem(int id) {
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) return pasteFromClipboard();
            return super.onTextContextMenuItem(id);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
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
