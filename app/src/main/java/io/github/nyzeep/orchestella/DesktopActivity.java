package io.github.nyzeep.orchestella;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.activity.ComponentActivity;
import com.google.android.material.button.MaterialButton;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.DisplayChannel;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.ui.design.components.OeDialogs;
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Main Android host activity presenting the Wayland graphical desktop environment.
 *
 * <p>Conforms to ADR-0017 and Ticket 43, restructured by G-03: this activity only owns
 * lifecycle wiring, the fixed view assembly and window chrome; the session control plane
 * lives in {@link DesktopSessionCoordinator}, the surface plane in
 * {@link DesktopSurfaceController}, input in {@link DesktopInputController}, clipboard /
 * file-drop / audio in {@link DesktopPeripheralController} and HUD copy in
 * {@link DesktopHudController}.
 */
public class DesktopActivity extends ComponentActivity
        implements DesktopSessionCoordinator.Host, DesktopHudController.Host {

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService displayEvidence = Executors.newSingleThreadScheduledExecutor();

    private String instanceId;
    private String bridgeToken;
    private ExecutionSession session;

    private DesktopSessionCoordinator sessionCoordinator;
    private DesktopSurfaceController surfaceController;
    private DesktopHudController hudController;
    private DesktopInputController inputController;
    private DesktopPeripheralController peripheralController;
    private Button btnSwitchTerminal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeApplier.prepare(this);
        instanceId = getIntent() != null ? getIntent().getStringExtra(DesktopLaunchIntent.EXTRA_INSTANCE_ID) : null;
        bridgeToken = getIntent() != null ? getIntent().getStringExtra(DesktopLaunchIntent.EXTRA_BRIDGE_TOKEN) : null;
        session = ExecutionSessionRegistry.get(instanceId);
        DisplayChannel waylandChannel = session != null ? session.waylandChannel() : null;
        BridgeClient bridgeClient = session != null ? session.bridgeClient() : null;

        int densityDpi = getResources().getConfiguration().densityDpi;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        double targetScale = DisplayDpiCalculator.calculateScale(densityDpi, screenWidth, screenHeight);

        sessionCoordinator = new DesktopSessionCoordinator(session, instanceId, waylandChannel,
                screenWidth, screenHeight, targetScale, this, this::showNegotiationFailure, displayEvidence);
        surfaceController = new DesktopSurfaceController(this, session, waylandChannel, targetScale,
                sessionCoordinator);
        hudController = new DesktopHudController(this, this,
                () -> session != null ? session.displayStatus() : "实例未运行",
                DesktopHudController.onHandler(uiHandler), resolveCapabilityMatrix());
        hudController.setSurfaceView(surfaceController.view());
        peripheralController = new DesktopPeripheralController(this, session, bridgeClient, bridgeToken,
                target -> DesktopControlClient.of(target.bridgeClient()));
        inputController = new DesktopInputController(this, waylandChannel,
                () -> session != null ? session.waylandChannel() : null, uiHandler);
        inputController.attachSurface(surfaceController.view());

        setContentView(buildContentView());
        enableImmersiveMode();
        showHeadlessRedirectIfNeeded();
        sessionCoordinator.startEvidencePolling();
    }

    /** Fixed view assembly: surface layer, top bar and bottom key bar, plus drop targets. */
    private View buildContentView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setFitsSystemWindows(false);
        WaylandSurfaceView surface = surfaceController.view();
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(buildTopBar(surface), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addView(inputController.buildKeyBar(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        peripheralController.attachFileDropTargets(root, surface);
        return root;
    }

    /** Top bar: terminal switch, pointer capture, touch sensitivity and the HUD badge. */
    private LinearLayout buildTopBar(WaylandSurfaceView surface) {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int barPad = getResources().getDimensionPixelSize(R.dimen.oe_space_sm);
        int gap = getResources().getDimensionPixelSize(R.dimen.oe_space_sm);
        topBar.setPadding(barPad, barPad, barPad, barPad);

        btnSwitchTerminal = topBarButton(R.string.desktop_open_terminal, false);
        Button btnPointerCapture = topBarButton(R.string.mouse_capture, true);
        Button btnToggleTouchMode = topBarButton(R.string.touch_mode_trackpad_label, false);

        btnSwitchTerminal.setOnClickListener(v ->
                DualModeNavigation.navigateToTerminal(DesktopActivity.this, instanceId, bridgeToken));
        inputController.attachPointerCaptureButton(btnPointerCapture);
        inputController.attachTouchModeButton(btnToggleTouchMode);

        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams gapParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gapParams.leftMargin = gap;
        LinearLayout.LayoutParams hudParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hudParams.leftMargin = gap;
        topBar.addView(btnSwitchTerminal, switchParams);
        topBar.addView(btnPointerCapture, gapParams);
        topBar.addView(btnToggleTouchMode, gapParams);
        topBar.addView(hudController.hudContainer(), hudParams);
        return topBar;
    }

    /**
     * Material top-bar button. Material 1.12 exposes no style-res constructor, so the
     * XML looks (Tonal / Outlined) are reproduced through the exact attributes
     * {@code ThemeApplier} role inference reads: fill for the tonal pair, a 1dp
     * outline stroke for the pointer-capture toggle (SECONDARY_ACTION).
     */
    private MaterialButton topBarButton(int labelRes, boolean outlined) {
        MaterialButton button = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonStyle);
        button.setText(labelRes);
        button.setTextSize(12f);
        button.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
        button.setMinimumHeight(dp(48));
        button.setCornerRadius(getResources().getDimensionPixelSize(R.dimen.oe_corner_md));
        int pad = getResources().getDimensionPixelSize(R.dimen.oe_space_md);
        button.setPadding(pad, 0, pad, 0);
        if (outlined) {
            button.setStrokeWidth(Math.max(1, dp(1)));
            button.setStrokeColor(ColorStateList.valueOf(resolveThemeColor(
                    com.google.android.material.R.attr.colorOutline)));
            button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        }
        button.setTextAppearance(R.style.TextAppearance_Orchestella_LabelEmphasized);
        return button;
    }

    private int resolveThemeColor(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Headless instances cannot host the desktop; offer the terminal hand-off instead. */
    private void showHeadlessRedirectIfNeeded() {
        if (!sessionCoordinator.isHeadlessInstance()) return;
        OeDialogs.confirm(this, getString(R.string.desktop_headless_title),
                getString(R.string.desktop_headless_message), getString(R.string.instance_open_terminal),
                getString(R.string.dialog_close), false, () -> {
                    DualModeNavigation.navigateToTerminal(DesktopActivity.this, instanceId, bridgeToken);
                    finish();
                });
    }

    /** Negotiation failure: re-check guard liveness on the UI thread before replacing HUD copy. */
    private void showNegotiationFailure(ExecutionSession target, DesktopControlClient client, long lifecycle,
            String message) {
        uiHandler.post(() -> {
            if (sessionCoordinator.isCurrentDisplay(target, client, lifecycle)) {
                hudController.showNegotiationFailure(message);
            }
        });
    }

    private FullLinuxContract.CapabilityMatrix resolveCapabilityMatrix() {
        return session != null ? session.capabilityMatrix()
                : FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline();
    }

    private void enableImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                View decor = getWindow().getDecorView();
                if (decor != null) {
                    decor.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN);
                }
            }
        } catch (Exception ignored) {
            // Immersive styling is best-effort decoration: if the system flags cannot be
            // applied, the window simply keeps its default system chrome.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Order preserved from the pre-split activity: guard re-arm first, then the
        // per-foreground-entry negotiation thread.
        sessionCoordinator.onForeground();
        sessionCoordinator.runStartupNegotiation();
        enableImmersiveMode();
        surfaceController.onResume();
        hudController.start();
        peripheralController.onForeground();
    }

    @Override
    protected void onPause() {
        sessionCoordinator.onBackground();
        hudController.stop();
        peripheralController.onBackground();
        surfaceController.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        surfaceController.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Teardown order mirrors the pre-split activity: coordinator state, renderer
        // observer, HUD loop, peripherals, input, surface last.
        sessionCoordinator.onActivityDestroyed();
        surfaceController.clearPresentationObserver();
        hudController.stop();
        peripheralController.onActivityDestroyed();
        inputController.detach();
        surfaceController.onActivityDestroyed();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enableImmersiveMode();
        }
        peripheralController.onWindowFocusChanged(hasFocus);
    }

    // 事件路由语义自 DesktopActivity 原实现逐字保留（G-03）；ComponentActivity 场景下
    // super.dispatchKeyEvent 对 app 层是 RestrictedApi 误报类限制，有意抑制。
    @SuppressLint("RestrictedApi")
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Boolean handled = inputController.dispatchKeyEvent(event);
        return handled != null ? handled : super.dispatchKeyEvent(event);
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
        inputController.onHostPointerCaptureChanged(hasCapture);
    }
}
