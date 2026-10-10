package io.github.nyzeep.orchestella;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.function.Consumer;

/**
 * Modern Full Linux terminal activity featuring:
 * <ul>
 *   <li>ANSI 16/256/TrueColor VT100 renderer with Dracula palette via {@link ModernTerminalView};</li>
 *   <li>Sticky modifier extra keys bar via {@link MobileAuxiliaryKeyBar};</li>
 *   <li>Soft keyboard adaptive viewport scrolling and edge-to-edge status bar insets.</li>
 * </ul>
 * Conforms to ADR-0002, US-26, and US-27.
 */
public class TerminalActivity extends Activity {

    private Handler ui;
    private ModernTerminalView terminalView;
    private FrameLayout contentArea;
    private View offlineCardView;
    private TextView tvOfflineTitle;
    private TextView tvOfflineMessage;
    private Button btnOfflineAction;
    private Button btnOfflineBack;
    private LinearLayout inputBar;
    private MobileAuxiliaryKeyBar auxiliaryKeyBar;
    private StickyModifierStateMachine stickyStateMachine;
    private ExecutionSession session;
    private boolean isHandlingModifier = false;
    private boolean isUpdatingFromEngine = false;
    private InteractiveTerminalEngine terminalEngine;
    private TerminalHistoryStore historyStore;
    private String instanceId;
    private String bridgeToken;
    private InstanceSessionReconciler sessionReconciler;
    private InstanceLifecycle.FileStore instanceStore;
    private InstanceLifecycle.Controller instanceController;
    private final Consumer<String> terminalOutputListener = chunk -> {
        if (terminalView != null) {
            terminalView.appendOutput(chunk);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bridgeToken = getIntent().getStringExtra("bridgeToken");
        instanceId = getIntent().getStringExtra("instanceId");
        session = ExecutionSessionRegistry.get(instanceId);
        ui = new Handler(Looper.getMainLooper());
        stickyStateMachine = new StickyModifierStateMachine();

        terminalEngine = new InteractiveTerminalEngine(
                new InteractiveTerminalEngine.PromptInfo(
                        "root",
                        instanceId != null && !instanceId.isEmpty() ? instanceId : "orchestella",
                        "~",
                        "❯",
                        true));

        try {
            historyStore = new TerminalHistoryStore(getFilesDir().toPath().resolve("terminal_history"));
            if (instanceId != null) {
                List<String> existingHistory = historyStore.loadHistory(instanceId);
                terminalEngine.loadHistory(existingHistory);
            }
        } catch (Exception e) {
            android.util.Log.w("TerminalActivity", "Failed to initialize history store", e);
        }

        terminalEngine.setByteSink(bytes -> sendRaw(bytes));
        terminalEngine.setLineCommitListener((promptInfo, cmd) -> {
            if (historyStore != null && instanceId != null) {
                historyStore.saveHistoryAsync(instanceId, terminalEngine.getHistory());
            }
        });
        terminalEngine.setClearRequestListener(() -> {
            if (terminalView != null) {
                terminalView.clear();
            }
        });
        terminalEngine.setTabCompletionHandler(new CommandCompletionEngine());
        terminalEngine.setTabCompletionListener((buf, pos, candidates) -> {
            if (terminalView != null && candidates != null && !candidates.isEmpty()) {
                terminalView.appendOutput(terminalEngine.renderCompletionListing(candidates) + "\n");
            }
        });

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(DraculaPalette.BACKGROUND);

        // 1. Content area with modern terminal view and offline/crashed recovery card
        contentArea = new FrameLayout(this);
        terminalView = new ModernTerminalView(this);
        TerminalInputSink inputSink = new TerminalInputSink() {
            @Override
            public void sendBytes(byte[] bytes) {
                sendRaw(bytes);
            }
        };
        terminalView.setInputSink(inputSink);
        terminalView.setStickyStateMachine(stickyStateMachine);
        contentArea.addView(terminalView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        offlineCardView = buildOfflineCardView();
        offlineCardView.setVisibility(View.GONE);
        contentArea.addView(offlineCardView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        rootLayout.addView(contentArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 2. Mobile auxiliary key bar (floating dynamically above the soft keyboard)
        auxiliaryKeyBar = new MobileAuxiliaryKeyBar(this);
        auxiliaryKeyBar.setInputSink(inputSink);
        auxiliaryKeyBar.setStateMachine(stickyStateMachine);
        rootLayout.addView(auxiliaryKeyBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(rootLayout);

        // WindowInsets handling: Edge-to-Edge status bar padding on rootLayout,
        // while IME and navigation bar bottom insets are dynamically handled by auxiliaryKeyBar
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            Insets statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout());
            Insets navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            int leftInset = Math.max(statusBars.left, navBars.left);
            int rightInset = Math.max(statusBars.right, navBars.right);
            v.setPadding(leftInset, statusBars.top, rightInset, 0);
            return insets;
        });

        ViewCompat.setOnApplyWindowInsetsListener(auxiliaryKeyBar, (v, insets) -> {
            Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());
            Insets navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            int targetBottomPadding = MobileAuxiliaryKeyBar.calculateTargetBottomPadding(imeInsets.bottom, navInsets.bottom);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), targetBottomPadding);
            if (imeInsets.bottom > 0 && terminalView != null) {
                terminalView.post(() -> terminalView.scrollToBottom());
            }
            return insets;
        });

        reconcileAndConnect();
        handleCommandExtra(getIntent());
    }

    private BridgeConnector.Events eventsForBridge() {
        return new BridgeConnector.Events() {
            @Override
            public void onReady() {
                append("[bridge ready]\n");
            }

            @Override
            public void onPong() {
                append("[bridge pong]\n");
            }

            @Override
            public void onResize(int columns, int rows) {
                append("[bridge resize " + columns + "x" + rows + "]\n");
            }

            @Override
            public void onNotification(String title, String message) {
                append("[bridge notify: " + title + " - " + message + "]\n");
            }

            @Override
            public void onOpenUrl(String url) {
                append("[bridge open-url: " + url + "]\n");
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        reconcileAndConnect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (terminalView != null && terminalView.getVisibility() == View.VISIBLE) {
            terminalView.post(() -> {
                terminalView.scrollToBottom();
                terminalView.requestTerminalFocus();
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistTerminalHistory();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (instanceId != null) {
            PersistentTerminalSessionPool.getInstance().detach(instanceId, terminalOutputListener);
        }
    }

    private void persistTerminalHistory() {
        if (historyStore != null && terminalEngine != null && instanceId != null) {
            historyStore.saveHistoryAsync(instanceId, terminalEngine.getHistory());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            String newInstanceId = intent.getStringExtra("instanceId");
            if (newInstanceId != null && !newInstanceId.isEmpty() && !newInstanceId.equals(this.instanceId)) {
                persistTerminalHistory();
                if (this.instanceId != null) {
                    PersistentTerminalSessionPool.getInstance().detach(this.instanceId, terminalOutputListener);
                }
                this.instanceId = newInstanceId;
                if (historyStore != null) {
                    terminalEngine.loadHistory(historyStore.loadHistory(newInstanceId));
                }
            }
            String newBridgeToken = intent.getStringExtra("bridgeToken");
            if (newBridgeToken != null && !newBridgeToken.isEmpty()) {
                this.bridgeToken = newBridgeToken;
            }
        }
        reconcileAndConnect();
        handleCommandExtra(intent);
    }

    private void handleCommandExtra(Intent intent) {
        if (intent != null && intent.hasExtra("command")) {
            String cmd = intent.getStringExtra("command");
            if (cmd != null && !cmd.isEmpty()) {
                if (terminalView != null) {
                    terminalView.postDelayed(() -> send(cmd), 300);
                } else {
                    send(cmd);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        persistTerminalHistory();
        if (instanceId != null) {
            PersistentTerminalSessionPool.getInstance().detach(instanceId, terminalOutputListener);
        }
    }

    private void reconcileAndConnect() {
        if (instanceId == null || instanceId.isEmpty()) {
            append("[error: instanceId is missing]\n");
            return;
        }

        try {
            if (instanceStore == null) {
                instanceStore = new InstanceLifecycle.FileStore(getFilesDir().toPath().resolve("instances"));
                instanceController = new InstanceLifecycle.Controller(instanceStore, Clock.systemUTC());
            }

            if (sessionReconciler == null) {
                sessionReconciler = new InstanceSessionReconciler(
                        PersistentTerminalSessionPool.getInstance(),
                        instanceStore,
                        instanceController,
                        this::probeAndRecoverSession);
            }

            InstanceSessionReconciler.ReconcileResult result =
                    sessionReconciler.ensureSessionAliveOrRecover(instanceId);

            switch (result.status()) {
                case ATTACHED:
                    showTerminalSession(result.sessionHolder());
                    break;
                case OFFLINE:
                    showOfflinePlaceholder(result.instance(), false);
                    break;
                case CRASHED:
                    showOfflinePlaceholder(result.instance(), true);
                    break;
                case NOT_FOUND:
                default:
                    showMissingPlaceholder(instanceId);
                    break;
            }
        } catch (Exception e) {
            append("[reconcile error: " + e.getMessage() + "]\n");
        }
    }

    private void showTerminalSession(PersistentTerminalSessionPool.SessionHolder holder) {
        if (offlineCardView != null) {
            offlineCardView.setVisibility(View.GONE);
        }
        if (auxiliaryKeyBar != null) {
            auxiliaryKeyBar.setVisibility(View.VISIBLE);
        }
        if (terminalView != null) {
            terminalView.setVisibility(View.VISIBLE);
        }

        session = holder.rawSession();
        String replay = holder.attach(terminalOutputListener);
        if (terminalView != null) {
            terminalView.clear();
            if (replay != null && !replay.isEmpty()) {
                terminalView.appendOutput(replay);
            } else {
                terminalView.appendOutput("Orchestella Studio terminal (" + instanceId + ")\n[terminal connected]\n");
            }
            terminalView.post(() -> terminalView.scrollToBottom());
        }

        startBridgeServingIfNecessary(session);
    }

    private void showOfflinePlaceholder(InstanceLifecycle.Instance instance, boolean isCrashed) {
        if (offlineCardView != null) {
            offlineCardView.setVisibility(View.VISIBLE);
        }
        if (auxiliaryKeyBar != null) {
            auxiliaryKeyBar.setVisibility(View.GONE);
        }
        if (terminalView != null) {
            terminalView.setVisibility(View.GONE);
        }

        if (isCrashed) {
            tvOfflineTitle.setText("⚠️ 实例异常退出 (Crashed)");
            String detail = (instance != null && instance.crashDiagnostic() != null)
                    ? "退出码: " + instance.crashDiagnostic().exitCode() + "\n"
                    + (instance.crashDiagnostic().message() != null ? instance.crashDiagnostic().message() : "")
                    : "底层虚拟机或终端进程已异常退出。";
            tvOfflineMessage.setText(detail);
            btnOfflineAction.setText("🔄 立即重启实例");
        } else {
            String stateStr = instance != null ? instance.state().name() : "STOPPED";
            tvOfflineTitle.setText("🚀 实例处于离线状态 (" + stateStr + ")");
            tvOfflineMessage.setText("当前实例尚未启动，终端交互未连接。\n点击下方按钮即可一键启动并自动进入 Shell。");
            btnOfflineAction.setText("🚀 启动该实例");
        }
        btnOfflineAction.setOnClickListener(v -> launchInstanceFromTerminal(instanceId));
        btnOfflineBack.setOnClickListener(v -> finish());
    }

    private void showMissingPlaceholder(String id) {
        if (offlineCardView != null) {
            offlineCardView.setVisibility(View.VISIBLE);
        }
        if (auxiliaryKeyBar != null) {
            auxiliaryKeyBar.setVisibility(View.GONE);
        }
        if (terminalView != null) {
            terminalView.setVisibility(View.GONE);
        }
        tvOfflineTitle.setText("❌ 实例不存在");
        tvOfflineMessage.setText("在本地数据库中未找到 ID 为 " + id + " 的实例。");
        btnOfflineAction.setVisibility(View.GONE);
        btnOfflineBack.setOnClickListener(v -> finish());
    }

    private View buildOfflineCardView() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24));
        container.setBackgroundColor(DraculaPalette.BACKGROUND);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24));
        card.setBackground(createPillBackground(0xFF282A36, 0xFF44475A, 16f));

        tvOfflineTitle = new TextView(this);
        tvOfflineTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvOfflineTitle.setTextColor(0xFFFF79C6);
        tvOfflineTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvOfflineTitle.setGravity(Gravity.CENTER);
        card.addView(tvOfflineTitle);

        tvOfflineMessage = new TextView(this);
        tvOfflineMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvOfflineMessage.setTextColor(0xFFF8F8F2);
        tvOfflineMessage.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dpToPx(12), 0, dpToPx(24));
        card.addView(tvOfflineMessage, msgLp);

        btnOfflineAction = new Button(this, null, android.R.attr.buttonStyleSmall);
        btnOfflineAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btnOfflineAction.setTextColor(DraculaPalette.FOREGROUND);
        btnOfflineAction.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnOfflineAction.setBackground(createPillBackground(0xFF6272A4, 0xFFBD93F9, 12f));
        btnOfflineAction.setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10));
        card.addView(btnOfflineAction);

        btnOfflineBack = new Button(this, null, android.R.attr.buttonStyleSmall);
        btnOfflineBack.setText("返回实例列表");
        btnOfflineBack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btnOfflineBack.setTextColor(DraculaPalette.COMMENT);
        btnOfflineBack.setTypeface(Typeface.MONOSPACE);
        btnOfflineBack.setBackground(createPillBackground(0xFF21222C, 0xFF44475A, 10f));
        btnOfflineBack.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        backLp.setMargins(0, dpToPx(12), 0, 0);
        card.addView(btnOfflineBack, backLp);

        container.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return container;
    }

    private void launchInstanceFromTerminal(String targetId) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("action_launch_instance_id", targetId);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
        finish();
    }

    private ExecutionSession probeAndRecoverSession(InstanceLifecycle.Instance instance) throws Exception {
        String socketSuffix = instance.id();
        String controlSocket = "orchestella-" + socketSuffix + "-ctrl";
        String terminalSocket = "orchestella-" + socketSuffix + "-term";

        android.net.LocalSocket termSock = new android.net.LocalSocket();
        termSock.connect(new android.net.LocalSocketAddress(
                terminalSocket, android.net.LocalSocketAddress.Namespace.ABSTRACT));
        termSock.close();

        android.net.LocalSocket ctrlSock = new android.net.LocalSocket();
        ctrlSock.connect(new android.net.LocalSocketAddress(
                controlSocket, android.net.LocalSocketAddress.Namespace.ABSTRACT));
        ctrlSock.close();

        return new QemuInstanceLauncher.Session(controlSocket, terminalSocket, new QemuProcessLauncher.ProcessHandle() {
            @Override
            public int waitFor() {
                return 0;
            }

            @Override
            public void destroy() {
            }
        });
    }

    private void startBridgeServingIfNecessary(ExecutionSession s) {
        if (s == null || !s.markBridgeServing()) {
            return;
        }
        terminalView.post(() -> {
            float charWidth = terminalView.getTranscriptView().getPaint().measureText("M");
            float lineHeight = terminalView.getTranscriptView().getPaint().getFontSpacing();
            int columns = (int) (terminalView.getWidth() / (charWidth > 0 ? charWidth : 1));
            int rows = (int) (terminalView.getHeight() / (lineHeight > 0 ? lineHeight : 1));
            final int gridColumns = columns > 0 ? columns : 80;
            final int gridRows = rows > 0 ? rows : 24;
            new Thread(() -> {
                try {
                    AndroidNotificationAdapter notificationAdapter =
                            new AndroidNotificationAdapter(TerminalActivity.this);
                    AndroidUrlOpenerAdapter urlOpenerAdapter =
                            new AndroidUrlOpenerAdapter(TerminalActivity.this);
                    new BridgeConnector().serveStreams(
                            s.controlOutput(), s.controlInput(), bridgeToken,
                            gridColumns, gridRows, eventsForBridge(), notificationAdapter,
                            urlOpenerAdapter,
                            new io.github.nyzeep.orchestella.runtime.contract.BridgeControl.Clipboard() {
                                @Override
                                public boolean onClipboardPut(String text) {
                                    io.github.nyzeep.orchestella.runtime.contract.BridgeControl.Clipboard cb =
                                            s != null ? s.clipboardBridge() : null;
                                    return cb != null && cb.onClipboardPut(text);
                                }

                                @Override
                                public String onClipboardGet() {
                                    io.github.nyzeep.orchestella.runtime.contract.BridgeControl.Clipboard cb =
                                            s != null ? s.clipboardBridge() : null;
                                    return cb != null ? cb.onClipboardGet() : null;
                                }
                            });
                } catch (Exception e) {
                    append("[bridge ended: " + e.getClass().getSimpleName() + "]\n");
                }
            }, "bridge-serve").start();
        });
    }

    private void send(String line) {
        if (line != null) {
            String toSend = line.endsWith("\n")
                    ? line.substring(0, line.length() - 1) + "\r"
                    : line + "\r";
            sendRaw(toSend.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void sendRaw(byte[] bytes) {
        if (instanceId == null || bytes == null || bytes.length == 0) {
            return;
        }
        new Thread(() -> {
            try {
                PersistentTerminalSessionPool.getInstance().sendBytes(this.instanceId, bytes);
            } catch (Exception e) {
                append("[send failed: " + e.getMessage() + "]\n");
            }
        }, "terminal-send").start();
    }

    private void append(String text) {
        if (terminalView != null) {
            terminalView.appendOutput(text);
        }
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private static android.graphics.drawable.GradientDrawable createPillBackground(
            int fillColor, int strokeColor, float cornerRadiusDp) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(cornerRadiusDp * 2.5f);
        gd.setStroke(2, strokeColor);
        return gd;
    }
}
