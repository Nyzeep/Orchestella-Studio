package io.github.nyzeep.orchestella;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;
import io.github.nyzeep.orchestella.ModifierKeyStateMachine.ModifierKey;
import io.github.nyzeep.orchestella.ModifierKeyStateMachine.ModifierState;
import io.github.nyzeep.orchestella.peripherals.PointerCaptureHandler;
import io.github.nyzeep.orchestella.runtime.contract.DisplayChannel;
import io.github.nyzeep.orchestella.ui.design.components.OeDialogs;
import java.util.function.Supplier;

/**
 * Input controller split out of DesktopActivity (G-03): VirtualKeyBar 12-key wiring,
 * PointerCaptureHandler hand-off to the surface, mouse device detection, the touch
 * sensitivity dialog and the key-dispatch path, all terminating in
 * {@link DisplayChannel#sendInputEvent}.
 *
 * <p>The TouchGestureMapper and ImeBridgeInputConnection are created inside
 * {@code WaylandSurfaceView.bind()} (see {@link DesktopSurfaceController}); this
 * controller consumes the mapper for the sensitivity dialog and receives captured
 * pointer/key events routed by the view.
 */
public final class DesktopInputController {

    /** Sensitivity damping choices; index layout is part of the dialog copy contract. */
    static final double[] SENSITIVITY_FACTORS = {0.6, 0.8, 1.0, 1.3, 1.6};
    private static final String[] SENSITIVITY_OPTIONS = {
            "0.6x (高阻尼/平稳防抖)",
            "0.8x (柔和阻尼)",
            "1.0x (默认平衡)",
            "1.3x (轻微阻尼/轻快)",
            "1.6x (超低阻尼/极速滑动)"
    };

    private final Activity activity;
    private final DisplayChannel channel;
    private final Supplier<DisplayChannel> dispatchChannelSupplier;
    private final Handler uiHandler;
    private final VirtualKeyBar virtualKeyBar;
    private Button btnCtrl, btnAlt, btnShift, btnSuper;
    private PointerCaptureHandler pointerCaptureHandler;
    private WaylandSurfaceView surfaceView;
    private Button btnPointerCapture;
    private InputManager.InputDeviceListener inputDeviceListener;

    public DesktopInputController(Activity activity, DisplayChannel channel,
            Supplier<DisplayChannel> dispatchChannelSupplier, Handler uiHandler) {
        this.activity = activity;
        this.channel = channel;
        this.dispatchChannelSupplier = dispatchChannelSupplier;
        this.uiHandler = uiHandler;
        this.virtualKeyBar = new VirtualKeyBar(channel);
    }

    /** Modifier label: LATCHED marks with * and LOCKED with #, matching the pre-split copy. */
    public static String modifierLabel(String base, ModifierState state) {
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

    /** Nearest sensitivity option index for the current damping factor (|delta| &lt; 0.1 wins). */
    public static int sensitivityIndexFor(double currentDamping) {
        int selectedIndex = 2;
        for (int i = 0; i < SENSITIVITY_FACTORS.length; i++) {
            if (Math.abs(SENSITIVITY_FACTORS[i] - currentDamping) < 0.1) {
                selectedIndex = i;
                break;
            }
        }
        return selectedIndex;
    }

    /**
     * Builds the bottom key bar (Esc/Tab/modifiers/arrows/IME) and wires every button
     * through {@link VirtualKeyBar} to the display channel.
     */
    public HorizontalScrollView buildKeyBar() {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setBackground(new ColorDrawable((int) 0xCC1E1E1EL));
        int pad = activity.getResources().getDimensionPixelSize(R.dimen.oe_space_xs);
        scroll.setPadding(pad, pad, pad, pad);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row);

        Button btnEsc = keyBarButton(R.string.key_esc, 56, 11);
        Button btnTab = keyBarButton(R.string.key_tab, 56, 11);
        Button btnUp = keyBarButton(R.string.key_up, 48, 12);
        Button btnDown = keyBarButton(R.string.key_down, 48, 12);
        Button btnLeft = keyBarButton(R.string.key_left, 48, 12);
        Button btnRight = keyBarButton(R.string.key_right, 48, 12);
        Button btnToggleIme = keyBarButton(R.string.key_ime, 48, 14);
        Button btnCtrlLocal = keyBarButton(R.string.key_ctrl, 56, 11);
        Button btnAltLocal = keyBarButton(R.string.key_alt, 56, 11);
        Button btnShiftLocal = keyBarButton(R.string.key_shift, 56, 11);
        Button btnSuperLocal = keyBarButton(R.string.key_super, 56, 11);
        this.btnCtrl = btnCtrlLocal;
        this.btnAlt = btnAltLocal;
        this.btnShift = btnShiftLocal;
        this.btnSuper = btnSuperLocal;

        btnEsc.setOnClickListener(v -> {
            virtualKeyBar.pressEsc();
            updateModifierButtons();
        });
        btnTab.setOnClickListener(v -> {
            virtualKeyBar.pressTab();
            updateModifierButtons();
        });
        btnUp.setOnClickListener(v -> {
            virtualKeyBar.pressUp();
            updateModifierButtons();
        });
        btnDown.setOnClickListener(v -> {
            virtualKeyBar.pressDown();
            updateModifierButtons();
        });
        btnLeft.setOnClickListener(v -> {
            virtualKeyBar.pressLeft();
            updateModifierButtons();
        });
        btnRight.setOnClickListener(v -> {
            virtualKeyBar.pressRight();
            updateModifierButtons();
        });
        btnCtrl.setOnClickListener(v -> {
            virtualKeyBar.pressModifier(ModifierKey.CTRL);
            updateModifierButtons();
        });
        btnAlt.setOnClickListener(v -> {
            virtualKeyBar.pressModifier(ModifierKey.ALT);
            updateModifierButtons();
        });
        btnShift.setOnClickListener(v -> {
            virtualKeyBar.pressModifier(ModifierKey.SHIFT);
            updateModifierButtons();
        });
        btnSuper.setOnClickListener(v -> {
            virtualKeyBar.pressModifier(ModifierKey.SUPER);
            updateModifierButtons();
        });

        virtualKeyBar.setOnSoftKeyboardToggleListener(() -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && surfaceView != null) {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }
        });
        btnToggleIme.setOnClickListener(v -> virtualKeyBar.toggleSoftKeyboard());

        row.addView(btnEsc);
        row.addView(btnTab);
        row.addView(btnCtrl);
        row.addView(btnAlt);
        row.addView(btnShift);
        row.addView(btnSuper);
        row.addView(btnUp);
        row.addView(btnDown);
        row.addView(btnLeft);
        row.addView(btnRight);
        row.addView(btnToggleIme);
        return scroll;
    }

    private void styleModifierButton(Button button, int labelRes) {
        button.setText(labelRes);
        button.setTextSize(11f);
        button.setMinHeight(dp(48));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(48)));
    }

    private Button keyBarButton(int labelRes, int widthDp, int textSizeSp) {
        Button button = new Button(activity);
        button.setText(labelRes);
        button.setTextSize(textSizeSp);
        button.setMinHeight(dp(48));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), dp(48)));
        return button;
    }

    private int dp(int dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }

    /** Refreshes the four modifier labels from the state machine (* = latched, # = locked). */
    private void updateModifierButtons() {
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

    /**
     * Hands the surface over: pointer capture handler, tap-to-capture and the capture
     * state listener driving the button label.
     */
    public void attachSurface(WaylandSurfaceView surface) {
        this.surfaceView = surface;
        if (surface == null) return;
        pointerCaptureHandler = new PointerCaptureHandler(surface, channel);
        surface.setPointerCaptureHandler(pointerCaptureHandler);
        surface.setOnClickListener(v -> {
            if (pointerCaptureHandler != null && !pointerCaptureHandler.isCaptured()) {
                pointerCaptureHandler.requestCapture();
            }
        });
        pointerCaptureHandler.addCaptureStateListener(isCaptured -> {
            uiHandler.post(() -> {
                if (btnPointerCapture != null) {
                    btnPointerCapture.setText(isCaptured ? R.string.mouse_release : R.string.mouse_capture);
                }
            });
        });
    }

    /** Wires the pointer-capture toggle button (label updated by the capture state listener). */
    public void attachPointerCaptureButton(Button button) {
        this.btnPointerCapture = button;
        if (button != null) {
            button.setOnClickListener(v -> {
                if (pointerCaptureHandler == null) return;
                if (pointerCaptureHandler.isCaptured()) {
                    pointerCaptureHandler.releaseCapture();
                } else {
                    pointerCaptureHandler.requestCapture();
                }
            });
        }
    }

    /** Wires the touch-mode button into the sensitivity dialog (click and long-press). */
    public void attachTouchModeButton(Button button) {
        if (button != null && surfaceView != null) {
            updateTouchModeButtonText(button);
            button.setText("触控板灵敏度");
            button.setContentDescription("当前触摸转换为鼠标事件，调整触控板灵敏度");
            button.setOnClickListener(v -> showSensitivityAdjustmentDialog());
            button.setOnLongClickListener(v -> {
                showSensitivityAdjustmentDialog();
                return true;
            });
        }
    }

    /** Registers the mouse device listener that keeps the pointer-capture affordance available. */
    public void startMouseDetection() {
        InputManager inputManager = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
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

    /**
     * Key dispatch: captured pointer keys first, then the Linux key mapping onto the
     * live session channel. Returns {@code null} when the activity must fall through
     * to {@code super.dispatchKeyEvent}.
     */
    public Boolean dispatchKeyEvent(KeyEvent event) {
        if (pointerCaptureHandler != null && pointerCaptureHandler.isCaptured()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (pointerCaptureHandler.onKeyDown(event.getKeyCode(), event)) {
                    return true;
                }
            }
        }
        DisplayChannel dispatchChannel = dispatchChannelSupplier != null ? dispatchChannelSupplier.get() : null;
        int linuxCode = DesktopKeyMapper.linuxCode(event.getKeyCode());
        if (dispatchChannel != null && linuxCode != 0
                && (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP)) {
            int modifiers = DesktopKeyMapper.modifiers(event.getMetaState());
            io.github.nyzeep.orchestella.runtime.contract.KeyEvent input = event.getAction() == KeyEvent.ACTION_DOWN
                    ? io.github.nyzeep.orchestella.runtime.contract.KeyEvent.press(linuxCode, modifiers, System.nanoTime())
                    : io.github.nyzeep.orchestella.runtime.contract.KeyEvent.release(linuxCode, modifiers, System.nanoTime());
            return dispatchChannel.sendInputEvent(input);
        }
        return null;
    }

    /** Host-side capture loss syncs the handler so its listeners and button stay consistent. */
    public void onHostPointerCaptureChanged(boolean hasCapture) {
        if (pointerCaptureHandler != null && !hasCapture && pointerCaptureHandler.isCaptured()) {
            pointerCaptureHandler.releaseCapture();
        }
    }

    /** Pointer-handler detach and mouse-listener unregister at activity teardown. */
    public void detach() {
        if (pointerCaptureHandler != null) {
            pointerCaptureHandler.detach();
            pointerCaptureHandler = null;
        }
        if (inputDeviceListener != null) {
            InputManager inputManager = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
            if (inputManager != null) {
                inputManager.unregisterInputDeviceListener(inputDeviceListener);
            }
            inputDeviceListener = null;
        }
    }

    private void updateTouchModeButtonText(Button button) {
        TouchGestureMapper mapper = surfaceView != null ? surfaceView.getTouchGestureMapper() : null;
        if (mapper != null && mapper.getTouchMode() == TouchGestureMapper.TouchMode.TOUCHSCREEN) {
            button.setText(R.string.touch_mode_touchscreen_label);
        } else {
            button.setText(R.string.touch_mode_trackpad_label);
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

    private void showSensitivityAdjustmentDialog() {
        TouchGestureMapper mapper = surfaceView != null ? surfaceView.getTouchGestureMapper() : null;
        if (mapper == null) {
            return;
        }
        final String[] options = SENSITIVITY_OPTIONS;
        int selectedIndex = sensitivityIndexFor(mapper.getDampingFactor());
        OeDialogs.choose(activity, activity.getString(R.string.trackpad_sensitivity_title), options, which -> {
            mapper.setDampingFactor(SENSITIVITY_FACTORS[which]);
            Toast.makeText(
                    activity,
                    activity.getString(R.string.trackpad_sensitivity_updated, options[which]),
                    Toast.LENGTH_SHORT).show();
        });
    }
}
