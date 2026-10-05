package io.github.nyzeep.droidlayer;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;
import io.github.nyzeep.droidlayer.ModifierKeyStateMachine.ModifierKey;
import io.github.nyzeep.droidlayer.runtime.contract.DisplayControl;
import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import io.github.nyzeep.droidlayer.runtime.contract.WaylandChannel;

/**
 * Main Android host activity presenting the Wayland graphical desktop environment.
 *
 * <p>Conforms to ADR-0017 and Ticket 43:
 * <ul>
 *   <li>Integrates {@link WaylandSurfaceView} as hardware double-buffered presentation surface;</li>
 *   <li>Enables Android Immersive Sticky full-screen mode;</li>
 *   <li>Provides semi-transparent collapsible {@link DesktopHudOverlay};</li>
 *   <li>Embeds floating {@link VirtualKeyBar} for Linux modifiers and shortcuts;</li>
 *   <li>Enables seamless dual-mode switching to {@link TerminalActivity};</li>
 *   <li>Binds and safely unbinds live session and graphics channels across lifecycle.</li>
 * </ul>
 */
public class DesktopActivity extends Activity {

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private String instanceId;
    private String bridgeToken;
    private ExecutionSession session;

    private WaylandSurfaceView surfaceView;
    private DesktopHudOverlay hudOverlay;
    private VirtualKeyBar virtualKeyBar;
    private Button btnSwitchTerminal;

    private Button btnCtrl;
    private Button btnAlt;
    private Button btnShift;
    private Button btnSuper;

    private Runnable hudUpdater;
    private boolean isHudUpdating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enableImmersiveMode();
        setContentView(R.layout.activity_desktop);

        instanceId = getIntent() != null ? getIntent().getStringExtra(DesktopLaunchIntent.EXTRA_INSTANCE_ID) : null;
        bridgeToken = getIntent() != null ? getIntent().getStringExtra(DesktopLaunchIntent.EXTRA_BRIDGE_TOKEN) : null;
        session = ExecutionSessionRegistry.get(instanceId);

        surfaceView = findViewById(R.id.wayland_surface_view);
        btnSwitchTerminal = findViewById(R.id.btn_switch_terminal);
        View hudContainer = findViewById(R.id.desktop_hud_overlay);
        TextView hudTextView = findViewById(R.id.desktop_hud_text);

        FullLinuxContract.CapabilityMatrix capabilityMatrix = resolveCapabilityMatrix();
        hudOverlay = new DesktopHudOverlay(hudContainer, hudTextView, capabilityMatrix);

        DisplayControl displayControl = session != null ? session.displayControl() : null;
        WaylandChannel waylandChannel = session != null ? session.waylandChannel() : null;
        if (surfaceView != null) {
            surfaceView.bind(displayControl, waylandChannel);
        }

        virtualKeyBar = new VirtualKeyBar(waylandChannel);
        setupVirtualKeyBar();

        if (btnSwitchTerminal != null) {
            btnSwitchTerminal.setOnClickListener(v -> {
                DualModeNavigation.navigateToTerminal(DesktopActivity.this, instanceId, bridgeToken);
            });
        }

        hudUpdater = new Runnable() {
            @Override
            public void run() {
                if (isHudUpdating && surfaceView != null && !isFinishing()) {
                    hudOverlay.update(surfaceView.getHudBadgeInfo());
                    uiHandler.postDelayed(this, 500);
                }
            }
        };
    }

    private void setupVirtualKeyBar() {
        btnCtrl = findViewById(R.id.btn_key_ctrl);
        btnAlt = findViewById(R.id.btn_key_alt);
        btnShift = findViewById(R.id.btn_key_shift);
        btnSuper = findViewById(R.id.btn_key_super);

        Button btnEsc = findViewById(R.id.btn_key_esc);
        Button btnTab = findViewById(R.id.btn_key_tab);
        Button btnUp = findViewById(R.id.btn_key_up);
        Button btnDown = findViewById(R.id.btn_key_down);
        Button btnLeft = findViewById(R.id.btn_key_left);
        Button btnRight = findViewById(R.id.btn_key_right);
        Button btnToggleIme = findViewById(R.id.btn_toggle_ime);

        if (btnEsc != null) {
            btnEsc.setOnClickListener(v -> {
                virtualKeyBar.pressEsc();
                updateModifierButtons();
            });
        }
        if (btnTab != null) {
            btnTab.setOnClickListener(v -> {
                virtualKeyBar.pressTab();
                updateModifierButtons();
            });
        }
        if (btnUp != null) {
            btnUp.setOnClickListener(v -> {
                virtualKeyBar.pressUp();
                updateModifierButtons();
            });
        }
        if (btnDown != null) {
            btnDown.setOnClickListener(v -> {
                virtualKeyBar.pressDown();
                updateModifierButtons();
            });
        }
        if (btnLeft != null) {
            btnLeft.setOnClickListener(v -> {
                virtualKeyBar.pressLeft();
                updateModifierButtons();
            });
        }
        if (btnRight != null) {
            btnRight.setOnClickListener(v -> {
                virtualKeyBar.pressRight();
                updateModifierButtons();
            });
        }

        if (btnCtrl != null) {
            btnCtrl.setOnClickListener(v -> {
                virtualKeyBar.pressModifier(ModifierKey.CTRL);
                updateModifierButtons();
            });
        }
        if (btnAlt != null) {
            btnAlt.setOnClickListener(v -> {
                virtualKeyBar.pressModifier(ModifierKey.ALT);
                updateModifierButtons();
            });
        }
        if (btnShift != null) {
            btnShift.setOnClickListener(v -> {
                virtualKeyBar.pressModifier(ModifierKey.SHIFT);
                updateModifierButtons();
            });
        }
        if (btnSuper != null) {
            btnSuper.setOnClickListener(v -> {
                virtualKeyBar.pressModifier(ModifierKey.SUPER);
                updateModifierButtons();
            });
        }

        virtualKeyBar.setOnSoftKeyboardToggleListener(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && surfaceView != null) {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }
        });

        if (btnToggleIme != null) {
            btnToggleIme.setOnClickListener(v -> virtualKeyBar.toggleSoftKeyboard());
        }
    }

    private void updateModifierButtons() {
        if (virtualKeyBar == null) return;
        ModifierKeyStateMachine sm = virtualKeyBar.getStateMachine();
        if (btnCtrl != null) {
            btnCtrl.setText(modifierLabel("CTRL", sm.getState(ModifierKey.CTRL)));
        }
        if (btnAlt != null) {
            btnAlt.setText(modifierLabel("ALT", sm.getState(ModifierKey.ALT)));
        }
        if (btnShift != null) {
            btnShift.setText(modifierLabel("SHIFT", sm.getState(ModifierKey.SHIFT)));
        }
        if (btnSuper != null) {
            btnSuper.setText(modifierLabel("SUPER", sm.getState(ModifierKey.SUPER)));
        }
    }

    private String modifierLabel(String base, ModifierKeyStateMachine.ModifierState state) {
        switch (state) {
            case LATCHED:
                return base + "*";
            case LOCKED:
                return base + "#";
            case RELEASED:
            default:
                return base;
        }
    }

    private FullLinuxContract.CapabilityMatrix resolveCapabilityMatrix() {
        try {
            boolean hasAvf = AvfHostFactsProbe.probe(this).manageVirtualMachinePermission();
            if (hasAvf) {
                return FullLinuxContract.CapabilityMatrix.hardwareVirtualizationWithGraphics();
            }
        } catch (Exception ignored) {
        }
        return FullLinuxContract.CapabilityMatrix.softwareVirtualizationWithGraphics();
    }

    private void enableImmersiveMode() {
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
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    private void startHudUpdates() {
        if (!isHudUpdating) {
            isHudUpdating = true;
            uiHandler.post(hudUpdater);
        }
    }

    private void stopHudUpdates() {
        isHudUpdating = false;
        uiHandler.removeCallbacks(hudUpdater);
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableImmersiveMode();
        if (surfaceView != null) {
            surfaceView.onResume();
        }
        startHudUpdates();
    }

    @Override
    protected void onPause() {
        stopHudUpdates();
        if (surfaceView != null) {
            surfaceView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (surfaceView != null) {
            surfaceView.onStop();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopHudUpdates();
        if (surfaceView != null) {
            surfaceView.onDestroy();
            surfaceView.unbind();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enableImmersiveMode();
        }
    }
}
