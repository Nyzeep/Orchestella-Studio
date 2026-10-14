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
    private ThemeStore themeStore;
    private SettingsManager settingsManager;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener appearanceListener =
            (preferences, key) -> { if (ui != null) ui.post(this::applyAppearance); };
    private PersistentTerminalSessionPool.SessionHolder attachedHolder;
    private Consumer<String> terminalOutputListener;
    private int bindingGeneration;
    private volatile boolean terminalConnected;
    private volatile boolean foregroundActive;
    private volatile int foregroundGeneration;
    private volatile io.github.nyzeep.orchestella.runtime.contract.BridgeClient activeBridgeClient;
    private String bridgeStatus = "正在启动";
    private final java.util.concurrent.ExecutorService resizeExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "terminal-resize-queue");
                thread.setDaemon(true);
                return thread;
            });
    private final Runnable resizeTask = this::sendTerminalResize;
    private String instanceId;
    private String bridgeToken;
    private InstanceSessionReconciler sessionReconciler;
    private InstanceLifecycle.FileStore instanceStore;
    private InstanceLifecycle.Controller instanceController;
    private DrawerLayout drawerLayout;
    private LinearLayout rootLayout;
    private TerminalDrawerPanel drawerPanel;
    private LinearLayout sessionHeader;
    private TextView tvSessionTitle;
    private Button btnDrawerHandle;
    private MultiTerminalSessionManager multiManager;
    private final java.util.concurrent.atomic.AtomicBoolean sessionDeathHandled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.ExecutorService sendExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "terminal-send-queue");
                t.setDaemon(true);
                return t;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        bridgeToken = getIntent().getStringExtra("bridgeToken");
        instanceId = getIntent().getStringExtra("instanceId");
        session = ExecutionSessionRegistry.get(instanceId);
        ui = new Handler(Looper.getMainLooper());
        stickyStateMachine = new StickyModifierStateMachine();

        themeStore = new ThemeStore(this);
        settingsManager = new SettingsManager(this);
        // The guest shell owns prompts, history and completion. All input streams to it.
        io.github.nyzeep.orchestella.ui.theme.ThemeApplier.prepare(this);

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
        terminalView.setSizeListener((columns, rows) -> scheduleTerminalResize());
        terminalView.setTerminalInputEnabled(false);
        contentArea.addView(terminalView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        offlineCardView = buildOfflineCardView();
        offlineCardView.setVisibility(View.GONE);
        contentArea.addView(offlineCardView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Reserve header height above the VT viewport; controls must never cover output.
        sessionHeader = new LinearLayout(this);
        sessionHeader.setOrientation(LinearLayout.HORIZONTAL);
        sessionHeader.setGravity(Gravity.CENTER_VERTICAL);
        sessionHeader.setPadding(dpToPx(10), 0, dpToPx(8), 0);
        sessionHeader.setBackgroundColor(0xFF21222C);
        tvSessionTitle = new TextView(this);
        tvSessionTitle.setText("终端 1");
        tvSessionTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvSessionTitle.setTextColor(DraculaPalette.FOREGROUND);
        tvSessionTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvSessionTitle.setSingleLine(true);
        tvSessionTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvSessionTitle.setGravity(Gravity.CENTER_VERTICAL);
        sessionHeader.addView(tvSessionTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        btnDrawerHandle = new Button(this, null, android.R.attr.buttonStyleSmall);
        btnDrawerHandle.setText("1个输出视图");
        btnDrawerHandle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btnDrawerHandle.setTextColor(DraculaPalette.COLOR_CYAN);
        btnDrawerHandle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnDrawerHandle.setAllCaps(false);
        btnDrawerHandle.setSingleLine(true);
        btnDrawerHandle.setFocusable(false);
        btnDrawerHandle.setMinWidth(0);
        btnDrawerHandle.setMinimumWidth(0);
        btnDrawerHandle.setMinHeight(0);
        btnDrawerHandle.setMinimumHeight(0);
        btnDrawerHandle.setBackground(createPillBackground(0xFF282A36, 0xFF6272A4, 8f));
        btnDrawerHandle.setPadding(dpToPx(10), 0, dpToPx(10), 0);
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
        sessionHeader.addView(btnDrawerHandle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        rootLayout.addView(sessionHeader, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(40)));
        rootLayout.addView(contentArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 2. Mobile auxiliary key bar (floating dynamically above the soft keyboard)
        auxiliaryKeyBar = new MobileAuxiliaryKeyBar(this);
        auxiliaryKeyBar.setInputSink(inputSink);
        auxiliaryKeyBar.setStateMachine(stickyStateMachine);
        auxiliaryKeyBar.setTerminalView(terminalView);
        auxiliaryKeyBar.setCommandEntryAction(this::showCommandEntryDialog);
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
                        android.widget.Toast.makeText(TerminalActivity.this,
                                "已新建输出视图，与当前 Shell 共享输入", android.widget.Toast.LENGTH_SHORT).show();
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
            if (terminalView != null) terminalView.scrollToBottomIfFollowing();
            return insets;
        });

        applyAppearance();

        reconcileAndConnect();
        handleCommandExtra(getIntent());
    }

    private void applyAppearance() {
        try {
            ThemeStore store = themeStore != null ? themeStore : new ThemeStore(this);
            AppTheme theme = store.resolve();
            int[] ansi = new int[theme.getAnsi().size()];
            for (int i = 0; i < ansi.length; i++) {
                ansi[i] = theme.getAnsi().get(i);
            }
            DraculaPalette.setPaletteOverride(
                    ansi, theme.getTerminalForeground(), theme.getTerminalBackground());
            rootLayout.setBackgroundColor(theme.getTerminalBackground());
            io.github.nyzeep.orchestella.ui.theme.ThemeApplier.bind(this);
            if (terminalView != null) {
                terminalView.applyTheme(theme,
                        store.terminalFontSizeSp(),
                        store.terminalLetterSpacingEm(),
                        store.terminalLineSpacingPx());
                terminalView.setCursorBlinkEnabled(settingsManager == null || settingsManager.isTerminalCursorBlinkEnabled());
            }
            if (offlineCardView != null) {
                offlineCardView.setBackgroundColor(theme.getBackground());
            }
        } catch (Exception e) {
            android.util.Log.w("TerminalActivity", "appearance apply failed", e);
        }
    }

    private BridgeConnector.Events eventsForBridge(ExecutionSession target,
            io.github.nyzeep.orchestella.runtime.contract.BridgeClient expectedClient) {
        return new BridgeConnector.Events() {
            @Override
            public void onReady() {
                if (ui != null) ui.post(() -> {
                    if (session != target || activeBridgeClient != expectedClient || !foregroundActive || isDestroyed()) return;
                    bridgeStatus = "";
                    updateDrawerHandleText(multiManager.getActiveSession(instanceId));
                    scheduleTerminalResize();
                });
            }

            @Override
            public void onPong() {
                // Keep protocol diagnostics outside the guest's screen/cursor state.
            }

            @Override
            public void onResize(int columns, int rows) {
                // A resize acknowledgement must not overwrite the guest's terminal grid.
            }

            @Override
            public void onNotification(String title, String message) {
                // AndroidNotificationAdapter owns this user-visible notification.
            }

            @Override
            public void onOpenUrl(String url) {
                // AndroidUrlOpenerAdapter owns this user-visible action.
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        foregroundActive = true;
        foregroundGeneration++;
        if (themeStore != null) themeStore.addOnThemeChangeListener(appearanceListener);
        if (settingsManager != null) settingsManager.addOnSettingsChangeListener(appearanceListener);
        applyAppearance();
        reconcileAndConnect();
        recoverControlOnForeground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (terminalView != null && terminalView.getVisibility() == View.VISIBLE) {
            terminalView.post(() -> {
                terminalView.scrollToBottomIfFollowing();
                terminalView.requestTerminalFocus();
                // Getty may reset winsize after the early bridge handshake. Reassert
                // the current viewport on every foreground entry, even if it is unchanged.
                scheduleTerminalResize();
            });
        }
    }

    @Override
    protected void onStop() {
        foregroundActive = false;
        if (themeStore != null) themeStore.removeOnThemeChangeListener(appearanceListener);
        if (settingsManager != null) settingsManager.removeOnSettingsChangeListener(appearanceListener);
        detachTerminalListener();
        if (ui != null) ui.removeCallbacks(resizeTask);
        if (terminalView != null) terminalView.stopCursorBlink();
        super.onStop();
    }

    private void detachTerminalListener() {
        bindingGeneration++;
        if (attachedHolder != null) {
            attachedHolder.detach(terminalOutputListener);
            attachedHolder.setSessionEndListener(null);
        }
        attachedHolder = null;
        terminalOutputListener = null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            String newInstanceId = intent.getStringExtra("instanceId");
            if (newInstanceId != null && !newInstanceId.isEmpty() && !newInstanceId.equals(this.instanceId)) {
                detachTerminalListener();
                terminalConnected = false;
                this.instanceId = newInstanceId;
                session = null;
                stickyStateMachine.reset();
                if (terminalView != null) {
                    terminalView.setTerminalInputEnabled(false);
                    terminalView.setSessionScreen(null);
                }
                if (drawerPanel != null) drawerPanel.setInstanceId(newInstanceId);
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
            // A lifecycle restart must never re-run a previously consumed command.
            intent.removeExtra("command");
            if (cmd != null && !cmd.isEmpty()) {
                final String commandInstance = instanceId;
                Runnable dispatch = () -> {
                    if (!java.util.Objects.equals(commandInstance, instanceId) || !terminalConnected) return;
                    send(cmd);
                };
                if (terminalView != null) terminalView.postDelayed(dispatch, 300L);
                else dispatch.run();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detachTerminalListener();
        terminalConnected = false;
        if (ui != null) ui.removeCallbacksAndMessages(null);
        sendExecutor.shutdownNow();
        // The client belongs to the persistent session. Rotation must not interrupt an
        // in-flight request and thereby close that session's authenticated control stream.
        resizeExecutor.shutdown();
    }

    private void reconcileAndConnect() {
        if (instanceId == null || instanceId.isEmpty()) {
            showMissingPlaceholder("未提供实例 ID");
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
        if (sessionHeader != null) {
            sessionHeader.setVisibility(View.VISIBLE);
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

        if (session != holder.rawSession()) bridgeStatus = "正在启动";
        session = holder.rawSession();
        terminalConnected = true;
        terminalView.setTerminalInputEnabled(true);
        terminalView.startCursorBlink();
        if (attachedHolder == holder) {
            scheduleTerminalResize();
            return;
        }
        detachTerminalListener();
        final int generation = bindingGeneration;
        final String boundInstanceId = instanceId;
        terminalOutputListener = chunk -> {
            if (chunk == null || chunk.isEmpty()) return;
            // Posting before rendering guarantees attach's locked snapshot precedes all live
            // chunks on the UI thread. No clear() can race a pump callback anymore.
            ui.post(() -> {
                if (generation != bindingGeneration || attachedHolder != holder) return;
                TerminalSessionInstance active = multiManager.getActiveSession(boundInstanceId);
                if (active != null) active.appendOutput(chunk);
                terminalView.appendOutput(chunk);
                LogViewerActivity.appendGuestConsole(this, boundInstanceId, chunk);
            });
        };
        attachedHolder = holder;
        sessionDeathHandled.set(false);
        holder.setSessionEndListener(id -> ui.post(() -> {
            if (generation != bindingGeneration || attachedHolder != holder) return;
            if (sessionDeathHandled.compareAndSet(false, true)) reconcileAndConnect();
        }));
        String replay = holder.attach(terminalOutputListener);
        terminalView.setSessionScreen(holder.getScreenModel());
        TerminalSessionInstance active = multiManager.getActiveSession(boundInstanceId);
        if (active != null) { active.clear(); active.appendOutput(replay); }
        startBridgeServingIfNecessary(session);
        scheduleTerminalResize();
    }

    private void showOfflinePlaceholder(InstanceLifecycle.Instance instance, boolean isCrashed) {
        detachTerminalListener();
        terminalConnected = false;
        session = null;
        stickyStateMachine.reset();
        if (terminalView != null) terminalView.setTerminalInputEnabled(false);
        if (offlineCardView != null) {
            offlineCardView.setVisibility(View.VISIBLE);
        }
        if (auxiliaryKeyBar != null) {
            auxiliaryKeyBar.setVisibility(View.GONE);
        }
        if (terminalView != null) {
            terminalView.setVisibility(View.GONE);
        }
        if (sessionHeader != null) {
            sessionHeader.setVisibility(View.GONE);
        }

        btnOfflineAction.setVisibility(View.VISIBLE);
        if (isCrashed) {
            tvOfflineTitle.setText("实例异常退出");
            String detail = (instance != null && instance.crashDiagnostic() != null)
                    ? "退出码: " + instance.crashDiagnostic().exitCode() + "\n"
                    + (instance.crashDiagnostic().message() != null ? instance.crashDiagnostic().message() : "")
                    : "底层虚拟机或终端进程已异常退出。";
            tvOfflineMessage.setText(detail);
            btnOfflineAction.setText("重启实例");
        } else {
            String stateStr = instance != null ? instance.state().name() : "STOPPED";
            tvOfflineTitle.setText("实例处于离线状态 (" + stateStr + ")");
            tvOfflineMessage.setText("当前实例尚未启动，终端交互未连接。\n点击下方按钮即可一键启动并自动进入 Shell。");
            btnOfflineAction.setText("启动实例");
        }
        btnOfflineAction.setOnClickListener(v -> launchInstanceFromTerminal(instanceId));
        btnOfflineBack.setOnClickListener(v -> finish());
    }

    private void showMissingPlaceholder(String id) {
        detachTerminalListener();
        terminalConnected = false;
        session = null;
        if (terminalView != null) terminalView.setTerminalInputEnabled(false);
        if (offlineCardView != null) {
            offlineCardView.setVisibility(View.VISIBLE);
        }
        if (auxiliaryKeyBar != null) {
            auxiliaryKeyBar.setVisibility(View.GONE);
        }
        if (terminalView != null) {
            terminalView.setVisibility(View.GONE);
        }
        if (sessionHeader != null) {
            sessionHeader.setVisibility(View.GONE);
        }
        tvOfflineTitle.setText("实例不存在");
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
        }, instance.id());
    }

    private void recoverControlOnForeground() {
        if (!foregroundActive || !terminalConnected || session == null || resizeExecutor.isShutdown()) return;
        final ExecutionSession target = session;
        final int foreground = foregroundGeneration;
        final int binding = bindingGeneration;
        // Exactly one task per explicit onStart. Output/layout/IME never trigger recovery.
        resizeExecutor.execute(() -> {
            if (!foregroundActive || foreground != foregroundGeneration || target != session || binding != bindingGeneration) return;
            io.github.nyzeep.orchestella.runtime.contract.BridgeClient failed = target.bridgeClient();
            if (failed == null || !"failed".equals(failed.readinessState())) return;
            android.util.Log.i("OrchestellaBridge", "control_reconnect_begin");
            boolean recovered = target.recoverControlChannel();
            android.util.Log.i("OrchestellaBridge", "control_reconnect_result recovered=" + recovered);
            ui.post(() -> {
                if (!foregroundActive || foreground != foregroundGeneration || target != session || binding != bindingGeneration || isDestroyed()) return;
                bridgeStatus = recovered ? "正在连接" : "控制不可用";
                if (recovered) {
                    activeBridgeClient = null; // Retire old callbacks before publishing the new client.
                    startBridgeServingIfNecessary(target);
                }
                updateDrawerHandleText(multiManager.getActiveSession(instanceId));
            });
        });
    }

    private void startBridgeServingIfNecessary(ExecutionSession s) {
        if (s == null || !s.markBridgeServing()) {
            return;
        }
        final String sessionBridgeToken = bridgeToken;
        terminalView.post(() -> {
            final int gridColumns = terminalView.getTerminalColumns();
            final int gridRows = terminalView.getTerminalRows();
            new Thread(() -> {
                final io.github.nyzeep.orchestella.runtime.contract.BridgeClient expectedClient = s.bridgeClient();
                activeBridgeClient = expectedClient;
                try {
                    AndroidNotificationAdapter notificationAdapter =
                            new AndroidNotificationAdapter(TerminalActivity.this);
                    AndroidUrlOpenerAdapter urlOpenerAdapter =
                            new AndroidUrlOpenerAdapter(TerminalActivity.this);
                    new BridgeConnector().serveSession(
                            s, sessionBridgeToken,
                            gridColumns, gridRows, eventsForBridge(s, expectedClient), notificationAdapter,
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
                    android.util.Log.w("TerminalActivity", "Bridge control ended: " + e.getClass().getSimpleName());
                    ui.post(() -> {
                        if (session == s && activeBridgeClient == expectedClient && foregroundActive && !isDestroyed()) {
                            bridgeStatus = "控制不可用";
                            updateDrawerHandleText(multiManager.getActiveSession(instanceId));
                        }
                    });
                }
            }, "bridge-serve").start();
        });
    }

    private void showCommandEntryDialog() {
        if (!terminalConnected) return;
        final ExecutionSession commandSession = session;
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入要发送给当前 Shell 的命令");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTypeface(Typeface.MONOSPACE);
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("发送命令")
                .setMessage("命令将发送到当前 Shell；输出由 Shell 返回。")
                .setView(input)
                .setPositiveButton("发送", (owner, which) -> {
                    if (!terminalConnected || session != commandSession || input.length() == 0) return;
                    TerminalInputConnection connection = terminalView.getCurrentInputConnection();
                    if (connection != null && connection.pasteText(input.getText()))
                        terminalView.sendTerminalKey(android.view.KeyEvent.KEYCODE_ENTER);
                    terminalView.requestTerminalFocus();
                })
                .setNegativeButton("取消", null).create();
        dialog.setOnShowListener(owner -> {
            io.github.nyzeep.orchestella.ui.theme.ThemeApplier.apply(dialog);
            input.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        dialog.show();
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
        if (!terminalConnected || instanceId == null || bytes == null || bytes.length == 0 || sendExecutor.isShutdown()) return;
        final PersistentTerminalSessionPool.SessionHolder target = attachedHolder;
        final int generation = bindingGeneration;
        final byte[] payload = bytes.clone();
        if (target == null) return;
        sendExecutor.execute(() -> {
            try {
                // Bind to the holder at enqueue time. A queued key can never reach a newly
                // selected instance or a restarted shell with the same instance ID.
                target.sendBytes(payload);
            } catch (Exception e) {
                if (ui != null) ui.post(() -> {
                    if (generation == bindingGeneration && sessionDeathHandled.compareAndSet(false, true))
                        reconcileAndConnect();
                });
            }
        });
    }

    private void scheduleTerminalResize() {
        if (ui == null || terminalView == null) return;
        ui.removeCallbacks(resizeTask);
        ui.postDelayed(resizeTask, 150L);
    }

    private void sendTerminalResize() {
        if (!terminalConnected || session == null || resizeExecutor.isShutdown()) return;
        final ExecutionSession target = session;
        final int columns = terminalView.getTerminalColumns();
        final int rows = terminalView.getTerminalRows();
        final int generation = bindingGeneration;
        resizeExecutor.execute(() -> {
            if (generation != bindingGeneration || target != session) return;
            io.github.nyzeep.orchestella.runtime.contract.BridgeClient client = target.bridgeClient();
            if (client == null || !client.isReady()) {
                android.util.Log.i("OrchestellaBridge", "resize skipped state="
                        + (client == null ? "client-unavailable" : client.readinessState()));
                return;
            }
            ui.post(() -> {
                if (session == target && !bridgeStatus.isEmpty()) {
                    bridgeStatus = "";
                    updateDrawerHandleText(multiManager.getActiveSession(instanceId));
                }
            });
            try {
                android.util.Log.i("OrchestellaBridge", "resize request columns=" + columns + " rows=" + rows);
                boolean accepted = client.resize(columns, rows).accepted();
                android.util.Log.i("OrchestellaBridge", "resize accepted=" + accepted);
            } catch (Exception failure) { android.util.Log.w("TerminalActivity", "Terminal resize unavailable"); }
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
        stickyStateMachine.reset();
        // These are output views of one shell. Replay the canonical shell snapshot so
        // a view switch never invents an independent shell/cursor state.
        PersistentTerminalSessionPool.SessionHolder holder = attachedHolder;
        if (holder != null) {
            detachTerminalListener();
            showTerminalSession(holder);
        } else terminalView.replaceTranscript(targetSession.getTranscript());
        terminalView.post(() -> {
            terminalView.scrollToBottom();
            terminalView.requestTerminalFocus();
        });
        if (drawerPanel != null) {
            drawerPanel.refresh();
        }
    }

    private void updateDrawerHandleText(TerminalSessionInstance session) {
        if (btnDrawerHandle == null || tvSessionTitle == null) {
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
        String status = bridgeStatus.isEmpty() ? "" : bridgeStatus + " · ";
        tvSessionTitle.setText(status + title);
        tvSessionTitle.setContentDescription(status + title);
        btnDrawerHandle.setText(count + "个输出视图");
        btnDrawerHandle.setContentDescription("打开输出视图列表，" + count + "个输出视图共享同一 Shell");
    }
}
