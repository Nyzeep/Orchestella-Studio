package io.github.nyzeep.orchestella;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * Modern terminal view rendering ANSI/VT100 styled text with Monospace font and Dracula theme.
 *
 * <p>Features:
 * <ul>
 *   <li>Parses ANSI escape sequences into Android spans via {@link AnsiEscapeParser};</li>
 *   <li>Monospace font alignment for CLI tables and tree rendering;</li>
 *   <li>Dracula theme palette (background #282A36, default text #F8F8F2);</li>
 *   <li>Bounded scrollback buffer to prevent memory exhaustion on large outputs;</li>
 *   <li>Thread-safe append operations and smooth auto-scrolling to bottom.</li>
 * </ul>
 */
public class ModernTerminalView extends ScrollView {

    private static final int MAX_BUFFER_CHARS = 24_000;
    private static final int TRIM_CHARS = 6_000;
    private static final long FLUSH_INTERVAL_MS = 66L; // ~15 FPS batching to guarantee UI thread responsiveness and prevent ANRs

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AnsiEscapeParser parser = new AnsiEscapeParser();
    private final SpannableStringBuilder transcriptBuffer = new SpannableStringBuilder();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingChunks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean flushScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Runnable flushTask = this::flushPendingOutput;

    private TextView transcriptView;
    private TerminalInputSink inputSink;
    private StickyModifierStateMachine stickyStateMachine;
    private TerminalInputConnection currentInputConnection;

    public ModernTerminalView(Context context) {
        super(context);
        init(context);
    }

    public ModernTerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ModernTerminalView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public void setInputSink(TerminalInputSink inputSink) {
        this.inputSink = inputSink;
    }

    public void setStickyStateMachine(StickyModifierStateMachine stickyStateMachine) {
        this.stickyStateMachine = stickyStateMachine;
    }

    public TerminalInputConnection getCurrentInputConnection() {
        return currentInputConnection;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo outAttrs) {
        if (outAttrs != null) {
            outAttrs.inputType = android.text.InputType.TYPE_NULL;
            outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NONE
                    | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN;
        }
        if (inputSink != null) {
            currentInputConnection = new TerminalInputConnection(inputSink, stickyStateMachine);
            return currentInputConnection;
        }
        return null;
    }

    public void requestTerminalFocus() {
        requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private float touchDownX;
    private float touchDownY;

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            touchDownX = ev.getX();
            touchDownY = ev.getY();
        } else if (ev.getAction() == android.view.MotionEvent.ACTION_UP) {
            float dx = ev.getX() - touchDownX;
            float dy = ev.getY() - touchDownY;
            float slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (Math.hypot(dx, dy) < slop) {
                requestTerminalFocus();
            }
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (currentInputConnection != null && currentInputConnection.sendKeyEvent(event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (currentInputConnection != null && currentInputConnection.sendKeyEvent(event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void init(Context context) {
        setBackgroundColor(DraculaPalette.BACKGROUND);
        setFillViewport(true);
        setFocusable(true);
        setFocusableInTouchMode(true);

        transcriptView = new TextView(context);
        transcriptView.setTypeface(Typeface.MONOSPACE);
        transcriptView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        transcriptView.setTextColor(DraculaPalette.FOREGROUND);
        transcriptView.setBackgroundColor(DraculaPalette.BACKGROUND);
        transcriptView.setTextIsSelectable(false);

        int paddingPx = dpToPx(context, 12);
        transcriptView.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        addView(transcriptView, params);

        setOnClickListener(v -> requestTerminalFocus());
        transcriptView.setOnClickListener(v -> requestTerminalFocus());
        transcriptView.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                requestTerminalFocus();
            }
            return false;
        });

        transcriptView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom > oldBottom) {
                post(() -> fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    /**
     * Appends ANSI raw output to the terminal transcript. Thread-safe and non-blocking.
     */
    public void appendOutput(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        pendingChunks.offer(text);
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(flushTask, FLUSH_INTERVAL_MS);
        }
    }

    private void flushPendingOutput() {
        flushScheduled.set(false);
        String chunk;
        boolean hasContent = false;
        while ((chunk = pendingChunks.poll()) != null) {
            if (chunk.isEmpty()) {
                continue;
            }
            List<TerminalSpan> spans = parser.parse(chunk);
            for (TerminalSpan span : spans) {
                String str = span.getText();
                if (str.isEmpty()) {
                    continue;
                }
                hasContent = true;
                appendSpanToBuffer(span);
            }
        }

        if (!hasContent) {
            return;
        }

        // Enforce scrollback limit without allocating large intermediate strings
        if (transcriptBuffer.length() > MAX_BUFFER_CHARS) {
            int cutIndex = -1;
            int len = transcriptBuffer.length();
            for (int i = TRIM_CHARS; i < len; i++) {
                if (transcriptBuffer.charAt(i) == '\n') {
                    cutIndex = i;
                    break;
                }
            }
            if (cutIndex != -1 && cutIndex < len - 1) {
                transcriptBuffer.delete(0, cutIndex + 1);
            } else {
                transcriptBuffer.delete(0, TRIM_CHARS);
            }
        }

        transcriptView.setText(transcriptBuffer, TextView.BufferType.SPANNABLE);
        scrollToBottom();

        // If more chunks arrived while flushing, reschedule
        if (!pendingChunks.isEmpty()) {
            scheduleFlush();
        }
    }

    private void appendSpanToBuffer(TerminalSpan span) {
        String str = span.getText();
        if (str == null || str.isEmpty()) {
            return;
        }

        // Handle VT100 control characters (\b and standalone \r)
        int len = str.length();
        int normalStart = -1;

        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if (c == '\b') {
                if (normalStart != -1) {
                    appendStyledSubSequence(span, str.substring(normalStart, i));
                    normalStart = -1;
                }
                // Backspace: delete preceding character in transcript if not a newline
                int bufLen = transcriptBuffer.length();
                if (bufLen > 0 && transcriptBuffer.charAt(bufLen - 1) != '\n') {
                    transcriptBuffer.delete(bufLen - 1, bufLen);
                }
            } else if (c == '\r') {
                if (i + 1 < len && str.charAt(i + 1) == '\n') {
                    // Part of CRLF sequence: let normalStart consume it
                    if (normalStart == -1) {
                        normalStart = i;
                    }
                } else {
                    // Standalone CR: carriage return rewinds to beginning of the active line
                    if (normalStart != -1) {
                        appendStyledSubSequence(span, str.substring(normalStart, i));
                        normalStart = -1;
                    }
                    int bufLen = transcriptBuffer.length();
                    int lastNl = -1;
                    for (int j = bufLen - 1; j >= 0; j--) {
                        if (transcriptBuffer.charAt(j) == '\n') {
                            lastNl = j;
                            break;
                        }
                    }
                    int lineStart = lastNl + 1;
                    if (lineStart < bufLen) {
                        transcriptBuffer.delete(lineStart, bufLen);
                    }
                }
            } else {
                if (normalStart == -1) {
                    normalStart = i;
                }
            }
        }

        if (normalStart != -1) {
            appendStyledSubSequence(span, str.substring(normalStart, len));
        }
    }

    private void appendStyledSubSequence(TerminalSpan span, String sub) {
        if (sub.isEmpty()) {
            return;
        }
        int start = transcriptBuffer.length();
        transcriptBuffer.append(sub);
        int end = transcriptBuffer.length();

        // Foreground
        int fg = span.getEffectiveForegroundColor();
        transcriptBuffer.setSpan(
                new ForegroundColorSpan(fg),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Background
        Integer bg = span.getEffectiveBackgroundColor();
        if (bg != null) {
            transcriptBuffer.setSpan(
                    new BackgroundColorSpan(bg),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Bold & Italic
        if (span.isBold() && span.isItalic()) {
            transcriptBuffer.setSpan(
                    new StyleSpan(Typeface.BOLD_ITALIC),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (span.isBold()) {
            transcriptBuffer.setSpan(
                    new StyleSpan(Typeface.BOLD),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (span.isItalic()) {
            transcriptBuffer.setSpan(
                    new StyleSpan(Typeface.ITALIC),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Underline
        if (span.isUnderline()) {
            transcriptBuffer.setSpan(
                    new UnderlineSpan(),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /**
     * Clears all content and resets ANSI parser state.
     */
    public void clear() {
        pendingChunks.clear();
        mainHandler.removeCallbacks(flushTask);
        flushScheduled.set(false);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearInternal();
        } else {
            mainHandler.post(this::clearInternal);
        }
    }

    private void clearInternal() {
        transcriptBuffer.clear();
        transcriptView.setText("");
        parser.reset();
    }

    /**
     * Scrolls the view to the latest output.
     */
    public void scrollToBottom() {
        post(() -> fullScroll(View.FOCUS_DOWN));
    }

    public TextView getTranscriptView() {
        return transcriptView;
    }

    public AnsiEscapeParser getParser() {
        return parser;
    }

    private static int dpToPx(Context context, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }
}
