package io.github.nyzeep.orchestella;

import android.content.Context;
import io.github.nyzeep.orchestella.runtime.contract.DisplayChannel;
import io.github.nyzeep.orchestella.runtime.contract.display.DisplayViewportBudget;
import java.util.Objects;

/**
 * Surface-plane controller split out of DesktopActivity (G-03) and deepened in G-01:
 * owns the {@link WaylandSurfaceView} lifecycle binding, unifies initial viewport budget
 * calculation via {@link DisplayViewportBudget}, wires the frame-confirmation observer to
 * {@link DesktopSessionCoordinator}, and guarantees idempotent teardown including
 * {@link DisplayResizeDebounce} closure.
 */
public final class DesktopSurfaceController {

    private final WaylandSurfaceView surfaceView;
    private final double targetScale;
    private boolean destroyed;

    public DesktopSurfaceController(Context context, ExecutionSession session, DisplayChannel waylandChannel,
            double targetScale, DesktopSessionCoordinator coordinator) {
        this(new WaylandSurfaceView(context), session, waylandChannel, targetScale, coordinator);
    }

    DesktopSurfaceController(WaylandSurfaceView surfaceView, ExecutionSession session, DisplayChannel waylandChannel,
            double targetScale, DesktopSessionCoordinator coordinator) {
        this.surfaceView = Objects.requireNonNull(surfaceView, "surfaceView");
        this.targetScale = DisplayViewportBudget.clampScale(targetScale);
        // Scale first, then bind, then wire the frame-confirmation observer.
        surfaceView.getController().setTargetScale(this.targetScale);
        surfaceView.bind(session != null ? session.displayControl() : null, waylandChannel);
        if (coordinator != null) {
            surfaceView.getController().getRenderer().setPresentationObserver(coordinator::confirmFrame);
        }
    }

    /**
     * Computes budgeted guest display dimensions and effective scaling from raw host dimensions.
     */
    public static DisplayViewportBudget.FittedViewport negotiateViewport(
            int rawWidth, int rawHeight, double targetScale) {
        return DisplayViewportBudget.fitWithinBudget(
                Math.max(DisplayViewportBudget.MIN_EDGE, rawWidth),
                Math.max(DisplayViewportBudget.MIN_EDGE, rawHeight),
                targetScale);
    }

    public DisplayViewportBudget.FittedViewport negotiatedViewport(int rawWidth, int rawHeight) {
        return negotiateViewport(rawWidth, rawHeight, targetScale);
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

    /** Idempotently destroys and unbinds the surface, closing resize debounce resources. */
    public void onActivityDestroyed() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        clearPresentationObserver();
        surfaceView.onDestroy();
    }
}
