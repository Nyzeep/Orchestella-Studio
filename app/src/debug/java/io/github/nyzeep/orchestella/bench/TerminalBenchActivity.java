package io.github.nyzeep.orchestella.bench;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.FrameMetrics;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

import io.github.nyzeep.orchestella.ModernTerminalView;
import io.github.nyzeep.orchestella.StickyModifierStateMachine;
import io.github.nyzeep.orchestella.TerminalInputSink;
import io.github.nyzeep.orchestella.terminal.TerminalScreenModel;
import io.github.nyzeep.orchestella.ui.theme.AppTheme;
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog;

/**
 * Debug-only terminal frame benchmark. Runs five fixed fixtures against the production
 * {@link ModernTerminalView} and emits one {@code OE_BENCH {...}} JSON line per fixture.
 *
 * <p>Launch: {@code adb shell am start -W -n <pkg>/io.github.nyzeep.orchestella.bench
 * .TerminalBenchActivity --es fixture all}. Valid fixture ids are {@code flood_seq},
 * {@code flood_color}, {@code scroll_2000}, {@code type_100}, {@code resize_font} and
 * {@code all}. A run ends with one {@code OE_BENCH_DONE {...}} line (tag
 * {@code OE_BENCH_DONE}); failures emit {@code OE_BENCH_ERROR {...}}.
 *
 * <p>Bytes for the flood fixtures are appended straight to {@link TerminalScreenModel},
 * bypassing the PTY, so a run needs no guest VM and no shell. {@code type_100} and
 * {@code resize_font} drive the real view paths (InputConnection, applyTheme).
 *
 * <p>Frame metrics are collected through {@link Window#addOnFrameMetricsAvailableListener}
 * on a dedicated thread so aggregation never competes with rendering. All durations are
 * reported in milliseconds; {@link FrameMetrics} itself reports nanoseconds.
 */
public final class TerminalBenchActivity extends Activity {
    public static final String EXTRA_FIXTURE = "fixture";
    public static final String EXTRA_WARMUP_MS = "warmupMs";
    /** Stable bench alias for the device; the committed baseline must never key on Build.MODEL. */
    public static final String EXTRA_ALIAS = "alias";

    private static final String TAG = "OE_BENCH";
    private static final String TAG_DONE = "OE_BENCH_DONE";
    private static final String TAG_ERROR = "OE_BENCH_ERROR";

    private static final String FIXTURE_ALL = "all";
    private static final String[] FIXTURES = {
            "flood_seq", "flood_color", "scroll_2000", "type_100", "resize_font"
    };

    /** Chunk size used by the two flood fixtures; 8 KiB keeps one append well under a frame. */
    private static final int FLOOD_CHUNK_CHARS = 8 * 1024;
    /** Feed cadence for flood fixtures; the view's own flush is 32 ms, so this saturates it. */
    private static final long FLOOD_STEP_MS = 16L;
    private static final int SCROLL_SWIPES = 20;
    private static final int SWIPE_STEPS = 8;
    private static final long SWIPE_STEP_MS = 16L;
    private static final long SWIPE_SETTLE_MS = 160L;
    private static final int TYPE_ASCII_CHARS = 100;
    private static final int TYPE_CJK_CHARS = 20;
    private static final long TYPE_STEP_MS = 16L;
    private static final int[] RESIZE_SIZES_SP = {10, 20, 10, 20, 10, 20, 10, 20, 10, 20, 10};
    private static final long RESIZE_STEP_MS = 400L;
    private static final long SETTLE_BETWEEN_FIXTURES_MS = 900L;
    private static final long TAIL_MS = 350L;
    private static final long DEFAULT_WARMUP_MS = 600L;
    private static final int MAX_LAYOUT_RETRIES = 50;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Deque<String> pendingFixtures = new ArrayDeque<>();

    private HandlerThread metricsThread;
    private Handler metricsHandler;
    private FrameCollector collector;
    private ModernTerminalView terminalView;
    private TerminalScreenModel model;
    private AppTheme benchTheme;

    private int benchColumns;
    private int benchRows;
    private String benchAlias = "";
    private float refreshRateHz = 60f;
    private long measureStartedAtMs;
    private int layoutRetries;

    private String seqPayload;
    private String colorPayload;
    private String requestedSpec = FIXTURE_ALL;
    private int completedFixtures;
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        benchTheme = ThemeCatalog.INSTANCE.getMascotLavender();

        String requested = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_FIXTURE);
        if (requested == null || requested.trim().isEmpty()) requested = FIXTURE_ALL;
        requestedSpec = requested.trim();
        String alias = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ALIAS);
        benchAlias = alias == null ? "" : alias.trim();
        for (String fixture : requested.trim().split(",")) {
            String id = fixture.trim();
            if (id.isEmpty()) continue;
            if (FIXTURE_ALL.equals(id)) {
                pendingFixtures.clear();
                pendingFixtures.addAll(Arrays.asList(FIXTURES));
                break;
            }
            if (indexOfFixture(id) < 0) {
                logError(id, "unknown fixture id; expected one of " + Arrays.toString(FIXTURES));
                finish();
                return;
            }
            if (!pendingFixtures.contains(id)) pendingFixtures.addLast(id);
        }
        if (pendingFixtures.isEmpty()) {
            logError(requested, "no fixtures selected");
            finish();
            return;
        }

        buildUi();
        seqPayload = buildSeqPayload(200_000);
        colorPayload = buildColorPayload(20_000);

        metricsThread = new HandlerThread("oe-bench-metrics");
        metricsThread.start();
        metricsHandler = new Handler(metricsThread.getLooper());
        collector = new FrameCollector();

        terminalView.postDelayed(this::startWhenDrawn, 400L);
    }

    private int indexOfFixture(String id) {
        for (int i = 0; i < FIXTURES.length; i++) if (FIXTURES[i].equals(id)) return i;
        return -1;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        terminalView = new ModernTerminalView(this);
        terminalView.setId(ViewGroup.generateViewId());
        terminalView.applyTheme(benchTheme, 12, 0f, 0f);
        root.addView(terminalView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        model = new TerminalScreenModel(80, 24);
        model.setResponseSink(ignored -> {});
        terminalView.setSessionScreen(model);
        terminalView.setSizeListener((columns, rows) -> {
            benchColumns = columns;
            benchRows = rows;
        });
    }

    private void startWhenDrawn() {
        if (started || isFinishing()) return;
        if (terminalView.getWidth() <= 0 || terminalView.getHeight() <= 0) {
            // A cold start on a slow device can outlive the first post; retry for a few
            // seconds before treating the window as headless.
            if (++layoutRetries > MAX_LAYOUT_RETRIES) {
                logError("setup", "terminal view has zero size; cannot measure frames");
                finish();
                return;
            }
            terminalView.postDelayed(this::startWhenDrawn, 100L);
            return;
        }
        started = true;
        refreshRateHz = resolveRefreshRateHz();
        getWindow().addOnFrameMetricsAvailableListener(collector, metricsHandler);
        mainHandler.postDelayed(this::warmUp, 500L);
    }

    private float resolveRefreshRateHz() {
        try {
            // Activity.getDisplay() and Display.getRefreshRate() are the post-API-30 path;
            // the WindowManager display is the only option down to minSdk 26.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.Display display = getDisplay();
                if (display != null) {
                    float hz = display.getRefreshRate();
                    if (hz > 1f && hz < 1000f) return hz;
                }
            }
            @SuppressWarnings("deprecation")
            float legacyHz = getWindowManager().getDefaultDisplay().getRefreshRate();
            if (legacyHz > 1f && legacyHz < 1000f) return legacyHz;
        } catch (RuntimeException ignored) {
            // Fall through to the 60 Hz assumption; reported in the JSON so a reader can tell.
        }
        return 60f;
    }

    /**
     * JIT/class-load warm-up outside the measured window. Without it the first fixture of a
     * cold process pays for dex verification and would look like a permanent regression.
     */
    private void warmUp() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) text.append("warm-up line ").append(i).append('\n');
        model.append(text.toString());
        for (int i = 0; i < 30; i++) terminalView.setSessionScreen(model);
        long warmupMs = Math.max(DEFAULT_WARMUP_MS, warmUpExtraMs());
        mainHandler.postDelayed(this::runNext, warmupMs);
    }

    private long warmUpExtraMs() {
        Intent intent = getIntent();
        if (intent == null) return 0L;
        return Math.max(0L, intent.getIntExtra(EXTRA_WARMUP_MS, 0));
    }

    private void runNext() {
        if (isFinishing()) return;
        String fixture = pendingFixtures.pollFirst();
        if (fixture == null) {
            logDone();
            mainHandler.postDelayed(this::finish, 200L);
            return;
        }
        resetSession();
        try {
            switch (fixture) {
                case "flood_seq": runFlood(fixture, seqPayload); break;
                case "flood_color": runFlood(fixture, colorPayload); break;
                case "scroll_2000": runScroll(fixture); break;
                case "type_100": runType(fixture); break;
                case "resize_font": runResize(fixture); break;
                default: logError(fixture, "unreachable fixture id"); runNext(); break;
            }
        } catch (RuntimeException failure) {
            logError(fixture, String.valueOf(failure));
            finishMeasurement(fixture, true);
        }
    }

    private void resetSession() {
        model = new TerminalScreenModel(80, 24);
        model.setResponseSink(ignored -> {});
        terminalView.setSessionScreen(model);
        terminalView.scrollToBottom();
    }

    // --- fixtures -------------------------------------------------------------------------

    private void runFlood(String fixture, String payload) {
        beginMeasurement();
        floodStep(fixture, payload, 0);
    }

    private void floodStep(String fixture, String payload, int offset) {
        if (isFinishing()) return;
        int end = Math.min(payload.length(), offset + FLOOD_CHUNK_CHARS);
        if (end > offset) {
            String part = payload.substring(offset, end);
            // The parser runs on the UI thread exactly as it does inside the production flush;
            // appendOutput only schedules the next render because this is a session screen.
            model.append(part);
            terminalView.appendOutput(part);
        }
        if (end >= payload.length()) {
            finishMeasurement(fixture, false);
            return;
        }
        mainHandler.postDelayed(() -> floodStep(fixture, payload, end), FLOOD_STEP_MS);
    }

    private void runScroll(String fixture) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 2000; i++) text.append("scrollback ").append(i).append(" abcdefghijklmnopqrstuvwxyz\n");
        model.append(text.toString());
        // Let the 2000 rows settle and lay out before timing starts: the fixture measures
        // dragging, not the initial population.
        terminalView.setSessionScreen(model);
        mainHandler.postDelayed(() -> {
            terminalView.scrollToBottom();
            mainHandler.postDelayed(() -> {
                beginMeasurement();
                swipe(fixture, 0);
            }, 300L);
        }, 400L);
    }

    private void swipe(String fixture, int index) {
        if (isFinishing()) return;
        if (index >= SCROLL_SWIPES) {
            finishMeasurement(fixture, false);
            return;
        }
        int width = Math.max(1, terminalView.getWidth());
        int height = Math.max(1, terminalView.getHeight());
        float x = width / 2f;
        // Alternating directions exercise both "read older output" and "return to bottom".
        boolean towardsOlder = index % 2 == 0;
        float fromY = towardsOlder ? height * 0.25f : height * 0.75f;
        float toY = towardsOlder ? height * 0.85f : height * 0.15f;
        long downTime = SystemClock.uptimeMillis();
        dispatchTouch(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, fromY, 0));
        swipeStep(fixture, index, downTime, x, fromY, toY, 1);
    }

    private void swipeStep(String fixture, int index, long downTime, float x,
            float fromY, float toY, int step) {
        if (isFinishing()) return;
        long now = SystemClock.uptimeMillis();
        if (step <= SWIPE_STEPS) {
            float y = fromY + (toY - fromY) * ((float) step / SWIPE_STEPS);
            dispatchTouch(MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, x, y, 0));
            mainHandler.postDelayed(
                    () -> swipeStep(fixture, index, downTime, x, fromY, toY, step + 1), SWIPE_STEP_MS);
            return;
        }
        dispatchTouch(MotionEvent.obtain(downTime, now, MotionEvent.ACTION_UP, x, toY, 0));
        mainHandler.postDelayed(() -> swipe(fixture, index + 1), SWIPE_SETTLE_MS);
    }

    private void dispatchTouch(MotionEvent event) {
        try {
            terminalView.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private void runType(String fixture) {
        // Local echo: input is routed through the real TerminalInputConnection and then pressed
        // back into the model, standing in for the guest's tty echo without a PTY. appendOutput
        // is used instead of setSessionScreen because the latter rebuilds the input connection
        // and would close the connection mid-fixture.
        TerminalInputSink echo = new TerminalInputSink() {
            @Override public void sendBytes(byte[] bytes) {
                if (bytes == null || bytes.length == 0) return;
                String text = new String(bytes, StandardCharsets.UTF_8);
                model.append(text);
                terminalView.appendOutput(text);
            }
        };
        terminalView.setStickyStateMachine(new StickyModifierStateMachine());
        terminalView.setInputSink(echo);
        terminalView.setTerminalInputEnabled(true);
        terminalView.setSessionScreen(model);
        terminalView.startCursorBlink();

        InputConnection connection = terminalView.getCurrentInputConnection();
        if (connection == null) {
            logError(fixture, "terminal input connection unavailable");
            runNext();
            return;
        }
        final int[] calls = {0};
        final int totalCalls = TYPE_ASCII_CHARS + TYPE_CJK_CHARS;
        beginMeasurement();
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                if (calls[0] >= totalCalls) {
                    connection.finishComposingText();
                    finishMeasurement(fixture, false);
                    return;
                }
                int index = calls[0]++;
                if (index % 6 == 5) {
                    // One CJK character per composition batch: typing through an IME.
                    String cjk = CJK_CHARS[(index / 6) % CJK_CHARS.length];
                    connection.beginBatchEdit();
                    connection.setComposingText(cjk, 1);
                    connection.commitText(cjk, 1);
                    connection.endBatchEdit();
                } else {
                    char ascii = (char) ('a' + (index % 26));
                    connection.commitText(String.valueOf(ascii), 1);
                }
                mainHandler.postDelayed(this, TYPE_STEP_MS);
            }
        }, TYPE_STEP_MS);
    }

    private static final String[] CJK_CHARS = {
            "终", "端", "渲", "染", "帧", "性", "能", "基", "线", "测",
            "试", "洪", "泛", "输", "出", "滚", "动", "输", "入", "好"
    };

    private void runResize(String fixture) {
        beginMeasurement();
        resizeStep(fixture, 0);
    }

    private void resizeStep(String fixture, int index) {
        if (isFinishing()) return;
        if (index >= RESIZE_SIZES_SP.length) {
            finishMeasurement(fixture, false);
            return;
        }
        terminalView.applyTheme(benchTheme, RESIZE_SIZES_SP[index], 0f, 0f);
        mainHandler.postDelayed(() -> resizeStep(fixture, index + 1), RESIZE_STEP_MS);
    }

    // --- measurement ----------------------------------------------------------------------

    private void beginMeasurement() {
        measureStartedAtMs = SystemClock.elapsedRealtime();
        collector.begin();
    }

    private void finishMeasurement(String fixture, boolean failed) {
        if (collector == null) return;
        mainHandler.postDelayed(() -> {
            if (collector == null) return;
            long elapsedMs = Math.max(1L, SystemClock.elapsedRealtime() - measureStartedAtMs);
            FrameCollector.Summary summary = collector.end(elapsedMs, refreshRateHz);
            int historyRows = -1;
            try {
                if (model != null) historyRows = model.snapshot().historyRows;
            } catch (RuntimeException ignored) {
                // Keep -1: the frame numbers are still valid, only the row count is unknown.
            }
            if (summary.frames == 0) {
                logError(fixture, "no frames captured; the window may be hidden or not drawing");
            } else if (!failed) {
                logResult(fixture, summary, elapsedMs, historyRows);
            }
            mainHandler.postDelayed(() -> {
                if (isFinishing()) return;
                resetSession();
                mainHandler.postDelayed(this::runNext, SETTLE_BETWEEN_FIXTURES_MS);
            }, 50L);
        }, TAIL_MS);
    }

    private void logResult(String fixture, FrameCollector.Summary summary, long elapsedMs,
            int historyRows) {
        try {
            JSONObject json = new JSONObject();
            json.put("fixture", fixture);
            json.put("device", Build.MODEL == null ? "unknown" : Build.MODEL);
            json.put("alias", benchAlias);
            json.put("api", Build.VERSION.SDK_INT);
            json.put("frames", summary.frames);
            json.put("janky", summary.jankyFrames);
            json.put("p50", round1(summary.p50Ms));
            json.put("p95", round1(summary.p95Ms));
            json.put("p99", round1(summary.p99Ms));
            json.put("max", round1(summary.maxMs));
            json.put("fps", round1(summary.frames * 1000.0 / elapsedMs));
            json.put("javaKb", summary.javaKbDelta);
            json.put("nativeKb", summary.nativeKbDelta);
            json.put("cols", benchColumns);
            json.put("rows", benchRows);
            json.put("historyRows", historyRows);
            json.put("refreshHz", round1(refreshRateHz));
            json.put("durationMs", elapsedMs);
            json.put("drawP95", round1(summary.drawP95Ms));
            json.put("layoutP95", round1(summary.layoutP95Ms));
            json.put("dropped", summary.dropped);
            Log.i(TAG, json.toString());
            completedFixtures++;
        } catch (JSONException failure) {
            Log.e(TAG_ERROR, "{\"fixture\":\"" + fixture + "\",\"error\":\"json encode failed\"}");
        }
    }

    private void logDone() {
        try {
            JSONObject json = new JSONObject();
            json.put("requested", requestedSpec);
            json.put("completed", completedFixtures);
            json.put("ok", true);
            Log.i(TAG_DONE, json.toString());
        } catch (JSONException ignored) {
            Log.i(TAG_DONE, "{\"ok\":true}");
        }
    }

    private void logError(String fixture, String message) {
        try {
            JSONObject json = new JSONObject();
            json.put("fixture", fixture);
            json.put("error", message);
            Log.e(TAG_ERROR, json.toString());
        } catch (JSONException ignored) {
            Log.e(TAG_ERROR, "{\"fixture\":\"unknown\",\"error\":\"encode failed\"}");
        }
    }

    private static double round1(double value) {
        return Double.parseDouble(String.format(Locale.US, "%.1f", value));
    }

    private static String buildSeqPayload(int lines) {
        StringBuilder text = new StringBuilder(lines * 7);
        for (int i = 1; i <= lines; i++) text.append(i).append('\n');
        return text.toString();
    }

    private static String buildColorPayload(int lines) {
        StringBuilder text = new StringBuilder(lines * 24);
        for (int i = 1; i <= lines; i++) {
            text.append("\u001b[3").append(i % 8).append('m')
                    .append("line ").append(i)
                    .append("\u001b[0m\n");
        }
        return text.toString();
    }

    @Override
    protected void onDestroy() {
        if (collector != null && metricsThread != null) {
            try {
                getWindow().removeOnFrameMetricsAvailableListener(collector);
            } catch (RuntimeException ignored) {
                // Window already detached; nothing to unregister.
            }
        }
        if (metricsThread != null) metricsThread.quitSafely();
        metricsThread = null;
        collector = null;
        terminalView = null;
        model = null;
        super.onDestroy();
    }

    /**
     * Collects raw frame durations on a background looper. Frames outside a measurement
     * window are counted but discarded so idle frames never dilute percentile results.
     */
    private static final class FrameCollector implements Window.OnFrameMetricsAvailableListener {
        private long[] totals = new long[512];
        private long[] draws = new long[512];
        private long[] layouts = new long[512];
        private int count;
        private boolean collecting;
        private int droppedFrames;
        private long nativeBeforeBytes;
        private long javaBeforeBytes;

        void begin() {
            synchronized (this) {
                count = 0;
                droppedFrames = 0;
                collecting = true;
            }
            nativeBeforeBytes = Debug.getNativeHeapAllocatedSize();
            javaBeforeBytes = usedJavaBytes();
        }

        @Override
        public void onFrameMetricsAvailable(Window window, FrameMetrics metrics,
                int dropCountSinceLastInvocation) {
            // FrameMetrics.getMetric returns -1 when a metric is unavailable for this frame
            // (AOSP FrameMetrics#getMetric), so a negative total is dropped instead of being
            // folded into the percentiles as a bogus negative duration.
            long total = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
            long draw = metrics.getMetric(FrameMetrics.DRAW_DURATION);
            long layout = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION);
            synchronized (this) {
                droppedFrames += Math.max(0, dropCountSinceLastInvocation);
                if (!collecting || total <= 0) return;
                if (count == totals.length) grow();
                totals[count] = total;
                draws[count] = Math.max(0L, draw);
                layouts[count] = Math.max(0L, layout);
                count++;
            }
        }

        private void grow() {
            int next = totals.length * 2;
            totals = Arrays.copyOf(totals, next);
            draws = Arrays.copyOf(draws, next);
            layouts = Arrays.copyOf(layouts, next);
        }

        Summary end(long elapsedMs, float refreshRateHz) {
            long[] totalCopy;
            long[] drawCopy;
            long[] layoutCopy;
            int frames;
            int dropped;
            synchronized (this) {
                collecting = false;
                frames = count;
                dropped = droppedFrames;
                totalCopy = Arrays.copyOf(totals, count);
                drawCopy = Arrays.copyOf(draws, count);
                layoutCopy = Arrays.copyOf(layouts, count);
            }
            long nativeAfter = Debug.getNativeHeapAllocatedSize();
            long javaAfter = usedJavaBytes();
            Summary summary = new Summary();
            summary.frames = frames;
            summary.dropped = dropped;
            summary.nativeKbDelta = (int) ((nativeAfter - nativeBeforeBytes) / 1024L);
            summary.javaKbDelta = (int) ((javaAfter - javaBeforeBytes) / 1024L);
            summary.elapsedMs = elapsedMs;
            if (frames == 0) return summary;
            long jankyThresholdNanos =
                    (long) (2_000_000_000.0 / Math.max(1.0, refreshRateHz));
            int janky = 0;
            for (int i = 0; i < frames; i++) if (totalCopy[i] > jankyThresholdNanos) janky++;
            summary.jankyFrames = janky;
            Arrays.sort(totalCopy);
            Arrays.sort(drawCopy);
            Arrays.sort(layoutCopy);
            summary.p50Ms = nanosToMs(percentile(totalCopy, 50));
            summary.p95Ms = nanosToMs(percentile(totalCopy, 95));
            summary.p99Ms = nanosToMs(percentile(totalCopy, 99));
            summary.maxMs = nanosToMs(totalCopy[frames - 1]);
            summary.drawP95Ms = nanosToMs(percentile(drawCopy, 95));
            summary.layoutP95Ms = nanosToMs(percentile(layoutCopy, 95));
            return summary;
        }

        private static long usedJavaBytes() {
            Runtime runtime = Runtime.getRuntime();
            return runtime.totalMemory() - runtime.freeMemory();
        }

        private static long percentile(long[] sorted, int percentile) {
            if (sorted.length == 0) return 0L;
            int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
        }

        private static double nanosToMs(long nanos) {
            return nanos / 1_000_000.0;
        }

        static final class Summary {
            int frames;
            int jankyFrames;
            double p50Ms;
            double p95Ms;
            double p99Ms;
            double maxMs;
            double drawP95Ms;
            double layoutP95Ms;
            int javaKbDelta;
            int nativeKbDelta;
            int dropped;
            long elapsedMs;
        }
    }
}
