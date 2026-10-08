package io.github.nyzeep.droidlayer;

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

    private static final int MAX_BUFFER_CHARS = 80_000;
    private static final int TRIM_CHARS = 20_000;
    private static final long FLUSH_INTERVAL_MS = 33L; // ~30 FPS UI batching to prevent StaticLayout ANR

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AnsiEscapeParser parser = new AnsiEscapeParser();
    private final SpannableStringBuilder transcriptBuffer = new SpannableStringBuilder();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingChunks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean flushScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Runnable flushTask = this::flushPendingOutput;

    private TextView transcriptView;

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

    private void init(Context context) {
        setBackgroundColor(DraculaPalette.BACKGROUND);
        setFillViewport(true);

        transcriptView = new TextView(context);
        transcriptView.setTypeface(Typeface.MONOSPACE);
        transcriptView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        transcriptView.setTextColor(DraculaPalette.FOREGROUND);
        transcriptView.setBackgroundColor(DraculaPalette.BACKGROUND);
        transcriptView.setTextIsSelectable(true);

        int paddingPx = dpToPx(context, 12);
        transcriptView.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        addView(transcriptView, params);

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
                int start = transcriptBuffer.length();
                transcriptBuffer.append(str);
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
        }

        if (!hasContent) {
            return;
        }

        // Enforce scrollback limit
        if (transcriptBuffer.length() > MAX_BUFFER_CHARS) {
            int cutIndex = transcriptBuffer.toString().indexOf('\n', TRIM_CHARS);
            if (cutIndex != -1 && cutIndex < transcriptBuffer.length() - 1) {
                transcriptBuffer.delete(0, cutIndex + 1);
            }
        }

        transcriptView.setText(transcriptBuffer, TextView.BufferType.SPANNABLE);
        scrollToBottom();

        // If more chunks arrived while flushing, reschedule
        if (!pendingChunks.isEmpty()) {
            scheduleFlush();
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
        post(() -> {
            fullScroll(View.FOCUS_DOWN);
            postDelayed(() -> fullScroll(View.FOCUS_DOWN), 60);
        });
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
