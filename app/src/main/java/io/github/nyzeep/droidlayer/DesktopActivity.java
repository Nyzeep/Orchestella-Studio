package io.github.nyzeep.droidlayer;

import android.app.Activity;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import io.github.nyzeep.droidlayer.ModifierKeyStateMachine.ModifierKey;
import io.github.nyzeep.droidlayer.audio.AndroidAudioFocusAdapter;
import io.github.nyzeep.droidlayer.audio.AndroidAudioTrackAdapter;
import io.github.nyzeep.droidlayer.audio.AudioDriftCompensator;
import io.github.nyzeep.droidlayer.audio.AudioFocusSeam;
import io.github.nyzeep.droidlayer.audio.AudioSourceSeam;
import io.github.nyzeep.droidlayer.audio.AudioTrackSeam;
import io.github.nyzeep.droidlayer.audio.FallbackAudioSource;
import io.github.nyzeep.droidlayer.audio.LocalSocketAudioSource;
import io.github.nyzeep.droidlayer.audio.LowLatencyAudioPlayer;
import io.github.nyzeep.droidlayer.audio.TcpAudioSource;
import io.github.nyzeep.droidlayer.clipboard.ClipboardBridgeManager;
import io.github.nyzeep.droidlayer.peripherals.FileDropTargetListener;
import io.github.nyzeep.droidlayer.peripherals.PointerCaptureHandler;
import io.github.nyzeep.droidlayer.runtime.contract.AudioStreamContract;
import io.github.nyzeep.droidlayer.runtime.contract.BridgeClient;
import io.github.nyzeep.droidlayer.runtime.contract.BridgeControl;
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

    private LowLatencyAudioPlayer audioPlayer;
    private ClipboardBridgeManager clipboardManager;
    private PointerCaptureHandler pointerCaptureHandler;
    private FileDropTargetListener fileDropTargetListener;
    private Button btnPointerCapture;
    private InputManager.InputDeviceListener inputDeviceListener;

    private Runnable hudUpdater;
    private boolean isHudUpdating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_desktop);
        enableImmersiveMode();

        instanceId = getIntent() != null ? getIntent().getStringExtra(DesktopLaunchIntent.EXTRA_INSTANCE_ID) : null;
        bridgeToken = getIntent() != null ? getIntent().getStringExtra(DesktopLaunchIntent.EXTRA_BRIDGE_TOKEN) : null;
        session = ExecutionSessionRegistry.get(instanceId);

        surfaceView = findViewById(R.id.wayland_surface_view);
        btnSwitchTerminal = findViewById(R.id.btn_switch_terminal);
        btnPointerCapture = findViewById(R.id.btn_pointer_capture);
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

        if (isHeadlessInstance(instanceId)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.desktop_headless_title)
                    .setMessage(R.string.desktop_headless_message)
                    .setPositiveButton(R.string.instance_open_terminal, (dialog, which) -> {
                        DualModeNavigation.navigateToTerminal(DesktopActivity.this, instanceId, bridgeToken);
                        finish();
                    })
                    .setNegativeButton(R.string.dialog_close, null)
                    .show();
        }

        if (btnSwitchTerminal != null) {
            btnSwitchTerminal.setOnClickListener(v -> {
                DualModeNavigation.navigateToTerminal(DesktopActivity.this, instanceId, bridgeToken);
            });
        }

        // 1. LowLatencyAudioPlayer assembly
        try {
            AudioSourceSeam audioSource = new FallbackAudioSource(
                    new LocalSocketAudioSource(LocalSocketAudioSource.DEFAULT_SOCKET_NAME),
                    new TcpAudioSource(TcpAudioSource.DEFAULT_HOST, TcpAudioSource.DEFAULT_PORT)
            );
            AudioTrackSeam audioTrack = new AndroidAudioTrackAdapter();
            AudioDriftCompensator compensator = new AudioDriftCompensator();
            AudioFocusSeam focusSeam = new AndroidAudioFocusAdapter(this);
            audioPlayer = new LowLatencyAudioPlayer(audioSource, audioTrack, compensator, focusSeam);
        } catch (Exception ignored) {
            audioPlayer = null;
        }

        // 2. ClipboardBridgeManager assembly
        BridgeClient bridgeClient = session != null ? session.bridgeClient() : null;
        if (bridgeClient != null) {
            clipboardManager = new ClipboardBridgeManager(this, bridgeClient);
        } else {
            clipboardManager = new ClipboardBridgeManager(
                    new ClipboardBridgeManager.AndroidSystemClipboard(this),
                    text -> {
                        BridgeClient client = session != null ? session.bridgeClient() : null;
                        if (client != null) {
                            try {
                                return client.clipboardPut(text).accepted();
                            } catch (IOException ignored) {
                                return false;
                            }
                        }
                        return false;
                    }
            );
        }
        if (session != null) {
            session.registerClipboardBridge(clipboardManager);
            if (bridgeToken != null && session.markBridgeServing()) {
                new Thread(() -> {
                    try {
                        AndroidNotificationAdapter notificationAdapter =
                                new AndroidNotificationAdapter(DesktopActivity.this);
                        AndroidUrlOpenerAdapter urlOpenerAdapter =
                                new AndroidUrlOpenerAdapter(DesktopActivity.this);
                        new BridgeConnector().serveStreams(
                                session.controlOutput(), session.controlInput(), bridgeToken,
                                0, 0, new BridgeConnector.Events() {
                                    @Override public void onReady() {}
                                    @Override public void onPong() {}
                                    @Override public void onResize(int c, int r) {}
                                },
                                notificationAdapter, urlOpenerAdapter, clipboardManager);
                    } catch (Exception ignored) {
                    }
                }, "desktop-bridge-serve").start();
            }
        }

        // 3. PointerCaptureHandler assembly
        pointerCaptureHandler = new PointerCaptureHandler(surfaceView, waylandChannel);
        if (surfaceView != null) {
            surfaceView.setPointerCaptureHandler(pointerCaptureHandler);
            surfaceView.setOnClickListener(v -> {
                if (pointerCaptureHandler != null && !pointerCaptureHandler.isCaptured()) {
                    pointerCaptureHandler.requestCapture();
                }
            });
        }
        if (btnPointerCapture != null) {
            btnPointerCapture.setOnClickListener(v -> {
                if (pointerCaptureHandler == null) return;
                if (pointerCaptureHandler.isCaptured()) {
                    pointerCaptureHandler.releaseCapture();
                } else {
                    pointerCaptureHandler.requestCapture();
                }
            });
        }
        pointerCaptureHandler.addCaptureStateListener(isCaptured -> {
            uiHandler.post(() -> {
                if (btnPointerCapture != null) {
                    btnPointerCapture.setText(isCaptured ? R.string.mouse_release : R.string.mouse_capture);
                }
            });
        });
        setupMouseDetection();

        // 4. FileDropTargetListener assembly
        fileDropTargetListener = new FileDropTargetListener(this, bridgeClient);
        View desktopRoot = findViewById(R.id.desktop_root);
        if (desktopRoot != null) {
            fileDropTargetListener.attach(desktopRoot);
        }
        if (surfaceView != null) {
            fileDropTargetListener.attach(surfaceView);
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

    private void setupMouseDetection() {
        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) {
            updateMouseAvailability(hasMouseConnected(inputManager));
            inputDeviceListener = new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    updateMouseAvailability(hasMouseConnected(inputManager));
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    updateMouseAvailability(hasMouseConnected(inputManager));
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                    updateMouseAvailability(hasMouseConnected(inputManager));
                }
            };
            inputManager.registerInputDeviceListener(inputDeviceListener, uiHandler);
        }
    }

    private boolean hasMouseConnected(InputManager inputManager) {
        int[] deviceIds = inputManager.getInputDeviceIds();
        for (int id : deviceIds) {
            InputDevice device = inputManager.getInputDevice(id);
            if (device != null && (device.getSources() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
                return true;
            }
        }
        return false;
    }

    private void updateMouseAvailability(boolean hasMouse) {
        if (btnPointerCapture != null) {
            btnPointerCapture.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableImmersiveMode();
        if (surfaceView != null) {
            surfaceView.onResume();
        }
        startHudUpdates();
        if (audioPlayer != null) {
            if (audioPlayer.state() == AudioStreamContract.State.PAUSED) {
                audioPlayer.resume();
            } else if (audioPlayer.state() == AudioStreamContract.State.IDLE) {
                audioPlayer.start();
            }
        }
    }

    @Override
    protected void onPause() {
        stopHudUpdates();
        if (audioPlayer != null) {
            audioPlayer.pause();
        }
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
        if (audioPlayer != null) {
            audioPlayer.release();
            audioPlayer = null;
        }
        if (clipboardManager != null) {
            clipboardManager.detach();
            clipboardManager = null;
        }
        if (pointerCaptureHandler != null) {
            pointerCaptureHandler.detach();
            pointerCaptureHandler = null;
        }
        if (inputDeviceListener != null) {
            InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
            if (inputManager != null) {
                inputManager.unregisterInputDeviceListener(inputDeviceListener);
            }
            inputDeviceListener = null;
        }
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
        if (clipboardManager != null) {
            clipboardManager.onWindowFocusChanged(hasFocus);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (pointerCaptureHandler != null && pointerCaptureHandler.isCaptured()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (pointerCaptureHandler.onKeyDown(event.getKeyCode(), event)) {
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
        if (pointerCaptureHandler != null && !hasCapture && pointerCaptureHandler.isCaptured()) {
            pointerCaptureHandler.releaseCapture();
        }
    }

    private boolean isHeadlessInstance(String instanceId) {
        return false;
    }
}
