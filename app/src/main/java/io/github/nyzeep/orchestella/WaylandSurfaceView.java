package io.github.nyzeep.orchestella;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import io.github.nyzeep.orchestella.peripherals.PointerCaptureHandler;
import io.github.nyzeep.orchestella.runtime.contract.DisplayControl;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;

/**
 * Custom SurfaceView widget presenting high-performance Wayland compositor graphics.
 *
 * <p>Conforms to ADR-0017 and Ticket 39:
 * <ul>
 *   <li>Inherits from {@link SurfaceView} and implements {@link SurfaceHolder.Callback};</li>
 *   <li>Integrates double-buffered hardware presentation via {@link WaylandSurfaceController};</li>
 *   <li>Delegates dynamic resolution renegotiation to {@link DisplayControl};</li>
 *   <li>Integrates with Activity foreground/background lifecycle.</li>
 * </ul>
 */
public class WaylandSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    private WaylandSurfaceController controller;
    private TouchGestureMapper touchGestureMapper;
    private ImeBridgeInputConnection imeBridge;
    private PointerCaptureHandler pointerCaptureHandler;

    public WaylandSurfaceView(Context context) {
        super(context);
        init(null);
    }

    public WaylandSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(null);
    }

    public WaylandSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(null);
    }

    /** Constructor allowing injection of a testable or custom controller seam. */
    public WaylandSurfaceView(Context context, WaylandSurfaceController controller) {
        super(context);
        init(controller);
    }

    private void init(WaylandSurfaceController customController) {
        try {
            setFocusable(true);
            setFocusableInTouchMode(true);
        } catch (Exception ignored) {
        }

        SurfaceHolder holder = null;
        try {
            holder = getHolder();
        } catch (Exception ignored) {
        }

        if (customController != null) {
            this.controller = customController;
        } else {
            this.controller = new WaylandSurfaceController(holder);
        }

        if (holder != null) {
            holder.addCallback(this);
        }
    }

    public WaylandSurfaceController getController() {
        return controller;
    }

    public TouchGestureMapper getTouchGestureMapper() {
        return touchGestureMapper;
    }

    public void setTouchGestureMapper(TouchGestureMapper touchGestureMapper) {
        this.touchGestureMapper = touchGestureMapper;
    }

    public ImeBridgeInputConnection getImeBridgeInputConnection() {
        return imeBridge;
    }

    public void setImeBridgeInputConnection(ImeBridgeInputConnection imeBridge) {
        this.imeBridge = imeBridge;
    }

    public PointerCaptureHandler getPointerCaptureHandler() {
        return pointerCaptureHandler;
    }

    public void setPointerCaptureHandler(PointerCaptureHandler pointerCaptureHandler) {
        this.pointerCaptureHandler = pointerCaptureHandler;
    }

    public void bind(DisplayControl displayControl, WaylandChannel waylandChannel) {
        controller.bind(displayControl, waylandChannel);
        if (waylandChannel != null) {
            int cw = (displayControl != null && displayControl.currentMode() != null)
                    ? displayControl.currentMode().width() : 1920;
            int ch = (displayControl != null && displayControl.currentMode() != null)
                    ? displayControl.currentMode().height() : 1080;
            int vw = controller.getRenderer() != null ? controller.getRenderer().getViewportWidth() : 1920;
            int vh = controller.getRenderer() != null ? controller.getRenderer().getViewportHeight() : 1080;
            if (vw <= 0) {
                vw = 1920;
            }
            if (vh <= 0) {
                vh = 1080;
            }
            ViewportAspectCalculator.ViewportMapping mapping =
                    ViewportAspectCalculator.calculate(cw, ch, vw, vh);
            this.touchGestureMapper = new TouchGestureMapper(waylandChannel, mapping);
            this.imeBridge = new ImeBridgeInputConnection(waylandChannel);
        }
    }

    public void unbind() {
        controller.unbind();
        this.touchGestureMapper = null;
        this.imeBridge = null;
        this.pointerCaptureHandler = null;
    }


    public void onResume() {
        controller.onResume();
    }

    public void onPause() {
        controller.onPause();
    }

    public void onStop() {
        controller.onStop();
    }

    public void onDestroy() {
        controller.onDestroy();
    }

    public WaylandSurfaceController.HudBadgeInfo getHudBadgeInfo() {
        return controller.getHudBadgeInfo();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (touchGestureMapper != null && touchGestureMapper.onTouchEvent(event)) {
            return true;
        }
        try {
            return super.onTouchEvent(event);
        } catch (Exception fallback) {
            return false;
        }
    }

    @Override
    public boolean onCapturedPointerEvent(MotionEvent event) {
        if (pointerCaptureHandler != null && pointerCaptureHandler.onCapturedPointerEvent(event)) {
            return true;
        }
        return super.onCapturedPointerEvent(event);
    }


    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (outAttrs != null) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        }
        return imeBridge;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        controller.onSurfaceCreated(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        controller.onSurfaceChanged(holder, format, width, height);
        if (touchGestureMapper != null && width > 0 && height > 0) {
            int cw = (controller.getDisplayControl() != null && controller.getDisplayControl().currentMode() != null)
                    ? controller.getDisplayControl().currentMode().width() : 1920;
            int ch = (controller.getDisplayControl() != null && controller.getDisplayControl().currentMode() != null)
                    ? controller.getDisplayControl().currentMode().height() : 1080;
            touchGestureMapper.setViewportMapping(
                    ViewportAspectCalculator.calculate(cw, ch, width, height));
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        controller.onSurfaceDestroyed(holder);
    }
}
