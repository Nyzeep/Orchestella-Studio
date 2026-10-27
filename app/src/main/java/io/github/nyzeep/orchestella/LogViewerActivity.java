package io.github.nyzeep.orchestella;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Unified runtime log viewer for Orchestella Studio.
 *
 * <p>Two log kinds make field diagnosis self-service:
 * <ul>
 *   <li>{@code KIND_GUEST} — the Linux console stream captured live from the guest
 *       serial (provisioning, systemd boot, kernel messages), persisted by
 *       {@link TerminalActivity} under {@code files/logs/console-<instance>.log};</li>
 *   <li>{@code KIND_APP} — this app's own logcat output for the current process,
 *       dumped on open, so crashes and bridge errors can be read without adb.</li>
 * </ul>
 */
public class LogViewerActivity extends Activity {

    public static final String EXTRA_TITLE = "logTitle";
    public static final String EXTRA_KIND = "logKind";
    public static final String EXTRA_INSTANCE_ID = "logInstanceId";
    public static final String KIND_GUEST = "guest";
    public static final String KIND_APP = "app";

    private String kind;
    private String instanceId;
    private TextView logText;
    private File consoleLogFile;
    private io.github.nyzeep.orchestella.ui.theme.AppTheme theme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        kind = getIntent().getStringExtra(EXTRA_KIND);
        if (kind == null) {
            kind = KIND_GUEST;
        }
        instanceId = getIntent().getStringExtra(EXTRA_INSTANCE_ID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null || title.isEmpty()) {
            title = KIND_APP.equals(kind) ? "应用运行日志" : "Linux 运行日志";
        }

        File logsDir = new File(getFilesDir(), "logs");
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }
        consoleLogFile = new File(logsDir, "console-" + (instanceId != null ? instanceId : "unknown") + ".log");

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        theme = new io.github.nyzeep.orchestella.ui.theme.ThemeStore(this).resolve();
        root.setBackgroundColor(theme.getBackground());
        root.setPadding(dp(8), 0, dp(8), 0);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            v.setPadding(dp(8), insets.getInsets(WindowInsetsCompat.Type.statusBars()).top + dp(8),
                    dp(8), insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + dp(8));
            return insets;
        });

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(17f);
        titleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        titleView.setTextColor(theme.getText());
        titleView.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(titleView);

        logText = new TextView(this);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextSize(11f);
        logText.setTextColor(theme.getText());
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(8), dp(4), dp(8), dp(16));

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.addView(logText);
        ScrollView vertical = new ScrollView(this);
        vertical.setFillViewport(true);
        vertical.addView(horizontal, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT));
        root.addView(vertical, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildButtonRow());
        setContentView(root);
        refresh();
    }

    private View buildButtonRow() {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(4), 0, dp(8));

        row.addView(makeActionButton("刷新", v -> refresh()));
        row.addView(makeActionButton("复制", v -> copyAll()));
        row.addView(makeActionButton("分享", v -> shareAll()));
        row.addView(makeActionButton("清空", v -> clearLog()));
        return row;
    }

    private Button makeActionButton(String label, View.OnClickListener listener) {
        Button button = new Button(this, null, android.R.attr.buttonStyleSmall);
        button.setText(label);
        button.setTextSize(12f);
        button.setTypeface(Typeface.MONOSPACE);
        button.setTextColor(theme.getAccent());
        button.setPadding(dp(14), dp(6), dp(14), dp(6));
        button.setOnClickListener(listener);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, dp(6), 0);
        button.setLayoutParams(lp);
        return button;
    }

    private void refresh() {
        String content;
        if (KIND_APP.equals(kind)) {
            content = dumpOwnLogcat();
        } else {
            content = readGuestConsoleLog();
        }
        if (content == null || content.trim().isEmpty()) {
            content = "（暂无日志内容。启动实例后控制台输出会自动记录到此处。）";
        }
        logText.setText(content);
    }

    private String readGuestConsoleLog() {
        if (!consoleLogFile.isFile()) {
            return "";
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(consoleLogFile.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "读取失败: " + e.getMessage();
        }
    }

    private String dumpOwnLogcat() {
        StringBuilder builder = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(
                    new String[] {"logcat", "-d", "-v", "time", "--pid=" + android.os.Process.myPid()});
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
            }
        } catch (Exception e) {
            builder.append("logcat 读取失败: ").append(e.getMessage());
        }
        return builder.toString();
    }

    private void copyAll() {
        CharSequence content = logText.getText();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("OrchestellaLog", content));
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
    }

    private void shareAll() {
        try {
            File shareFile = new File(getCacheDir(), "orchestella-log.txt");
            java.nio.file.Files.write(shareFile.toPath(),
                    logText.getText().toString().getBytes(StandardCharsets.UTF_8));
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, logText.getText().toString());
            startActivity(Intent.createChooser(send, "分享日志"));
        } catch (Exception e) {
            Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void clearLog() {
        if (KIND_APP.equals(kind)) {
            try {
                Runtime.getRuntime().exec(new String[] {"logcat", "-c"});
            } catch (Exception ignored) {
            }
        } else if (consoleLogFile.isFile()) {
            consoleLogFile.delete();
        }
        refresh();
        Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
    }

    /**
     * Single-thread executor for guest console writes. Every PTY output chunk used to be
     * written to disk synchronously on the UI thread (a direct terminal-input jank source);
     * a single worker keeps chunks ordered while the UI thread only enqueues.
     */
    private static final java.util.concurrent.ExecutorService CONSOLE_LOG_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread worker = new Thread(runnable, "guest-console-log");
                worker.setDaemon(true);
                return worker;
            });

    /** Appends one console chunk to the guest log off the UI thread, rotating at {@link GuestConsoleLogWriter#MAX_BYTES}. */
    public static void appendGuestConsole(Context context, String instanceId, String chunk) {
        if (instanceId == null || instanceId.isEmpty() || chunk == null || chunk.isEmpty()) {
            return;
        }
        File logsDir = new File(context.getFilesDir(), "logs");
        CONSOLE_LOG_EXECUTOR.execute(() -> {
            try {
                GuestConsoleLogWriter.append(logsDir, instanceId, chunk);
            } catch (Exception ignored) {
                // Logging must never break the terminal output path.
            }
        });
    }

    private int dp(int value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
