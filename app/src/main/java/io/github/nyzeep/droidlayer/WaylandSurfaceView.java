package io.github.nyzeep.droidlayer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import io.github.nyzeep.droidlayer.runtime.contract.DisplayControl;
import io.github.nyzeep.droidlayer.runtime.contract.WaylandChannel;

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

    public void bind(DisplayControl displayControl, WaylandChannel waylandChannel) {
        controller.bind(displayControl, waylandChannel);
    }

    public void unbind() {
        controller.unbind();
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
    public void surfaceCreated(SurfaceHolder holder) {
        controller.onSurfaceCreated(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        controller.onSurfaceChanged(holder, format, width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        controller.onSurfaceDestroyed(holder);
    }
}
