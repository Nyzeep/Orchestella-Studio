package io.github.nyzeep.orchestella;

import android.content.Context;
import io.github.nyzeep.orchestella.runtime.contract.DisplayChannel;

/**
 * Surface-plane controller split out of DesktopActivity (G-03): code-builds the
 * {@link WaylandSurfaceView}, applies the DPI target scale and binds/unbinds the display
 * channel across the activity lifecycle. The frame-confirmation semantics themselves
 * (presentation gate + guard + confirm dispatch) live in {@link DesktopSessionCoordinator};
 * this controller only wires the renderer observer to them.
 */
public final class DesktopSurfaceController {

    private final WaylandSurfaceView surfaceView;
    private final double targetScale;

    public DesktopSurfaceController(Context context, ExecutionSession session, DisplayChannel waylandChannel,
            double targetScale, DesktopSessionCoordinator coordinator) {
        this.surfaceView = new WaylandSurfaceView(context);
        this.targetScale = targetScale;
        // Same assembly order as the pre-split activity: scale first, then bind, then
        // the frame-confirmation observer (bind() creates the TouchGestureMapper and
        // the IME bridge from the channel; a null channel simply unbinds).
        surfaceView.getController().setTargetScale(targetScale);
        surfaceView.bind(session != null ? session.displayControl() : null, waylandChannel);
        surfaceView.getController().getRenderer().setPresentationObserver(coordinator::confirmFrame);
    }

    public WaylandSurfaceView view() {
        return surfaceView;
    }

    public double targetScale() {
        return targetScale;
    }

    public void onResume() {
        surfaceView.onResume();
    }

    public void onPause() {
        surfaceView.onPause();
    }

    public void onStop() {
        surfaceView.onStop();
    }

    /** Clears the frame-confirmation observer (activity teardown step 1 of the surface path). */
    public void clearPresentationObserver() {
        surfaceView.getController().getRenderer().setPresentationObserver(null);
    }

    /** Destroys and unbinds the surface (activity teardown, after every other cleanup). */
    public void onActivityDestroyed() {
        surfaceView.onDestroy();
        surfaceView.unbind();
    }
}
