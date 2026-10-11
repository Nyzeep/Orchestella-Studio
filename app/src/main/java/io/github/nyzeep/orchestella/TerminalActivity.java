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
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import io.github.nyzeep.orchestella.ui.theme.AppTheme;
import io.github.nyzeep.orchestella.ui.theme.ThemeStore;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.terminal.MultiTerminalSessionManager;
import io.github.nyzeep.orchestella.terminal.TerminalDrawerPanel;
import io.github.nyzeep.orchestella.terminal.TerminalSessionInstance;
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
    private MobileAuxiliaryKeyBar auxiliaryKeyBar;
    private StickyModifierStateMachine stickyStateMachine;
    private ExecutionSession session;
    private InteractiveTerminalEngine terminalEngine;
    private TerminalHistoryStore historyStore;
    private String instanceId;
    private String bridgeToken;
    private InstanceSessionReconciler sessionReconciler;
    private InstanceLifecycle.FileStore instanceStore;
    private InstanceLifecycle.Controller instanceController;
    private DrawerLayout drawerLayout;
    private LinearLayout rootLayout;
    private TerminalDrawerPanel drawerPanel;
    private Button btnDrawerHandle;
    private MultiTerminalSessionManager multiManager;
    private final java.util.concurrent.atomic.AtomicBoolean sessionDeathHandled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean replayingTranscript = false;
    private final java.util.concurrent.ExecutorService sendExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "terminal-send-queue");
                t.setDaemon(true);
                return t;
            });
    private final Consumer<String> terminalOutputListener = chunk -> {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        if (instanceId != null && multiManager != null) {
            TerminalSessionInstance active = multiManager.getActiveSession(instanceId);
            if (active != null) {
                active.appendOutput(chunk);
            }
        }
        if (!replayingTranscript && terminalView != null) {
            terminalView.appendOutput(chunk);
        }
        // Persist the console stream so field failures are diagnosable in-app
        // (InstanceDetailActivity -> Linux 日志) without adb.
        LogViewerActivity.appendGuestConsole(this, instanceId, chunk);
        if (drawerPanel != null) {
            drawerPanel.post(() -> drawerPanel.refresh());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        bridgeToken = getIntent().getStringExtra("bridgeToken");
        instanceId = getIntent().getStringExtra("instanceId");
        session = ExecutionSessionRegistry.get(instanceId);
        ui = new Handler(Looper.getMainLooper());
        stickyStateMachine = new StickyModifierStateMachine();

        String intentHostname = getIntent().getStringExtra("hostname");
        String intentUser = getIntent().getStringExtra("defaultUser");
        String effectiveHost = (intentHostname != null && !intentHostname.isEmpty()) ? intentHostname : "orchestella";
        String effectiveUser = (intentUser != null && !intentUser.isEmpty()) ? intentUser : "root";
        boolean isRoot = "root".equals(effectiveUser);

        terminalEngine = new InteractiveTerminalEngine(
                new InteractiveTerminalEngine.PromptInfo(
                        effectiveUser,
                        effectiveHost,
                        "~",
                        "❯",
                        isRoot));

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

        rootLayout = new LinearLayout(this);
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

        // Floating quick drawer handle button in top-right corner
        btnDrawerHandle = new Button(this, null, android.R.attr.buttonStyleSmall);
        btnDrawerHandle.setText("◫ 终端 (1)");
        btnDrawerHandle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btnDrawerHandle.setTextColor(DraculaPalette.COLOR_CYAN);
        btnDrawerHandle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnDrawerHandle.setBackground(createPillBackground(0xCC282A36, 0xFF6272A4, 12f));
        btnDrawerHandle.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        handleLp.setMargins(0, dpToPx(10), dpToPx(12), 0);
        btnDrawerHandle.setOnClickListener(v -> {
            if (drawerLayout != null) {
                if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    drawerLayout.closeDrawer(GravityCompat.END);
                } else {
                    if (drawerPanel != null) {
                        drawerPanel.refresh();
                    }
                    drawerLayout.openDrawer(GravityCompat.END);
                }
            }
        });
        contentArea.addView(btnDrawerHandle, handleLp);

        rootLayout.addView(contentArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 2. Mobile auxiliary key bar (floating dynamically above the soft keyboard)
        auxiliaryKeyBar = new MobileAuxiliaryKeyBar(this);
        auxiliaryKeyBar.setInputSink(inputSink);
        auxiliaryKeyBar.setStateMachine(stickyStateMachine);
        rootLayout.addView(auxiliaryKeyBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 3. Multi-terminal right slide-out drawer layout container
        multiManager = MultiTerminalSessionManager.getInstance();
        drawerLayout = new DrawerLayout(this);
        drawerLayout.setScrimColor(0x88000000);
        drawerLayout.addView(rootLayout, new DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT));

        drawerPanel = new TerminalDrawerPanel(this, instanceId, new TerminalDrawerPanel.DrawerCallback() {
            @Override
            public void onSessionSelected(TerminalSessionInstance targetSession) {
                if (multiManager != null && instanceId != null) {
                    multiManager.switchActiveSession(instanceId, targetSession.getSessionId());
                }
                if (drawerLayout != null) {
                    drawerLayout.closeDrawer(GravityCompat.END);
                }
                switchToSessionUi(targetSession);
            }

            @Override
            public void onNewSessionRequested() {
                if (multiManager != null && instanceId != null) {
                    try {
                        TerminalSessionInstance newSession = multiManager.createSession(instanceId, null);
                        multiManager.switchActiveSession(instanceId, newSession.getSessionId());
                        if (drawerLayout != null) {
                            drawerLayout.closeDrawer(GravityCompat.END);
                        }
                        switchToSessionUi(newSession);
                        newSession.appendOutput("\n[已新建输出视图: " + newSession.getTitle() + "（与当前 Shell 共享会话）]\n");
                        if (terminalView != null) {
                            terminalView.appendOutput("\n[已新建输出视图: " + newSession.getTitle() + "（与当前 Shell 共享会话）]\n");
                        }
                    } catch (Exception e) {
                        android.widget.Toast.makeText(TerminalActivity.this, e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                    }
                    if (drawerPanel != null) {
                        drawerPanel.refresh();
                    }
                }
            }

            @Override
            public void onSessionRenamed(TerminalSessionInstance targetSession, String newTitle) {
                if (multiManager != null && instanceId != null) {
                    multiManager.renameSession(instanceId, targetSession.getSessionId(), newTitle);
                    TerminalSessionInstance currentActive = multiManager.getActiveSession(instanceId);
                    updateDrawerHandleText(currentActive);
                    if (drawerPanel != null) {
                        drawerPanel.refresh();
                    }
                }
            }

            @Override
            public void onSessionClosed(TerminalSessionInstance targetSession) {
                if (multiManager != null && instanceId != null) {
                    boolean wasActive = targetSession.getSessionId().equals(
                            multiManager.getActiveSession(instanceId) != null
                                    ? multiManager.getActiveSession(instanceId).getSessionId()
                                    : "");
                    multiManager.closeSession(instanceId, targetSession.getSessionId());
                    if (wasActive) {
                        TerminalSessionInstance nextActive = multiManager.getActiveSession(instanceId);
                        if (nextActive != null) {
                            switchToSessionUi(nextActive);
                        } else {
                            TerminalSessionInstance fallback = multiManager.createSession(instanceId, "终端 1");
                            switchToSessionUi(fallback);
                        }
                    } else {
                        TerminalSessionInstance currentActive = multiManager.getActiveSession(instanceId);
                        updateDrawerHandleText(currentActive);
                    }
                    if (drawerPanel != null) {
                        drawerPanel.refresh();
                    }
                }
            }

            @Override
            public void onCloseDrawerRequested() {
                if (drawerLayout != null) {
                    drawerLayout.closeDrawer(GravityCompat.END);
                }
            }
        });

        int drawerWidth = Math.min(dpToPx(300), (int) (getResources().getDisplayMetrics().widthPixels * 0.82f));
        DrawerLayout.LayoutParams drawerLp = new DrawerLayout.LayoutParams(
                drawerWidth,
                DrawerLayout.LayoutParams.MATCH_PARENT,
                GravityCompat.END);
        drawerLayout.addView(drawerPanel, drawerLp);

        setContentView(drawerLayout);

        // WindowInsets handling: Edge-to-Edge status bar padding on rootLayout,
        // while IME and navigation bar bottom insets are dynamically handled by auxiliaryKeyBar
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            Insets statusBarAndCutoutInsets = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout());
            Insets navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            int leftInset = Math.max(statusBarAndCutoutInsets.left, navBars.left);
            int rightInset = Math.max(statusBarAndCutoutInsets.right, navBars.right);
            v.setPadding(leftInset, statusBarAndCutoutInsets.top, rightInset, 0);
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

        applyAppearance();

        reconcileAndConnect();
        handleCommandExtra(getIntent());
    }

    private void applyAppearance() {
        try {
            ThemeStore store = new ThemeStore(this);
            AppTheme theme = store.resolve();
            int[] ansi = new int[theme.getAnsi().size()];
            for (int i = 0; i < ansi.length; i++) {
                ansi[i] = theme.getAnsi().get(i);
            }
            DraculaPalette.setPaletteOverride(
                    ansi, theme.getTerminalForeground(), theme.getTerminalBackground());
            rootLayout.setBackgroundColor(theme.getBackground());
            if (terminalView != null) {
                terminalView.applyTheme(theme,
                        store.terminalFontSizeSp(),
                        store.terminalLetterSpacingEm(),
                        store.terminalLineSpacingPx());
            }
            if (offlineCardView != null) {
                offlineCardView.setBackgroundColor(theme.getBackground());
            }
        } catch (Exception e) {
            android.util.Log.w("TerminalActivity", "appearance apply failed", e);
        }
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
        sendExecutor.shutdown();
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
        if (btnDrawerHandle != null) {
            btnDrawerHandle.setVisibility(View.VISIBLE);
        }

        if (instanceId != null) {
            if (multiManager == null) {
                multiManager = MultiTerminalSessionManager.getInstance();
            }
            TerminalSessionInstance active = multiManager.getActiveSession(instanceId);
            if (active == null) {
                active = multiManager.createSession(instanceId, "终端 1");
                String snapshot = holder.getTranscript();
                if (snapshot != null && !snapshot.isEmpty()) {
                    active.appendOutput(snapshot);
                }
            }
            updateDrawerHandleText(active);
            if (drawerPanel != null) {
                drawerPanel.refresh();
            }
        }

        session = holder.rawSession();
        sessionDeathHandled.set(false);
        holder.setSessionEndListener(id -> {
            // Guest exited or the stream pump ended: reconcile on the UI thread, which
            // renders the offline/crashed recovery card instead of a dead terminal.
            if (ui != null) {
                ui.post(() -> {
                    if (!sessionDeathHandled.compareAndSet(false, true)) {
                        return;
                    }
                    reconcileAndConnect();
                });
            }
        });
        String replay = holder.attach(terminalOutputListener);
        if (terminalView != null) {
            replayingTranscript = true;
            try {
                terminalView.clear();
                if (replay != null && !replay.isEmpty()) {
                    terminalView.appendOutput(replay);
                } else {
                    terminalView.appendOutput("Orchestella Studio terminal (" + instanceId + ")\n[terminal connected]\n");
                }
            } finally {
                replayingTranscript = false;
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
        if (btnDrawerHandle != null) {
            btnDrawerHandle.setVisibility(View.GONE);
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
        if (btnDrawerHandle != null) {
            btnDrawerHandle.setVisibility(View.GONE);
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
        sendExecutor.execute(() -> {
            try {
                PersistentTerminalSessionPool.getInstance().sendBytes(this.instanceId, bytes);
            } catch (Exception e) {
                // A dead or missing session means the guest is gone: reconcile once to
                // swap in the offline/recovery card instead of spamming an error per key.
                if (sessionDeathHandled.compareAndSet(false, true) && ui != null) {
                    ui.post(this::reconcileAndConnect);
                }
            }
        });
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

    private void switchToSessionUi(TerminalSessionInstance targetSession) {
        if (targetSession == null || terminalView == null) {
            return;
        }
        updateDrawerHandleText(targetSession);
        // Pause live chunks while replaying the transcript so a chunk cannot land both in
        // the replayed snapshot and the live view (out-of-order/duplicate rendering).
        replayingTranscript = true;
        try {
            terminalView.clear();
            String transcript = targetSession.getTranscript();
            if (transcript != null && !transcript.isEmpty()) {
                terminalView.appendOutput(transcript);
            }
        } finally {
            replayingTranscript = false;
        }
        terminalView.post(() -> {
            terminalView.scrollToBottom();
            terminalView.requestTerminalFocus();
        });
        if (drawerPanel != null) {
            drawerPanel.refresh();
        }
    }

    private void updateDrawerHandleText(TerminalSessionInstance session) {
        if (btnDrawerHandle == null) {
            return;
        }
        int count = 1;
        String title = "终端";
        if (multiManager != null && instanceId != null) {
            count = multiManager.listSessions(instanceId).size();
        }
        if (session != null && session.getTitle() != null && !session.getTitle().isEmpty()) {
            title = session.getTitle();
        }
        btnDrawerHandle.setText("◫ " + title + " (" + count + ")");
    }
}
