package io.github.nyzeep.orchestella;

import android.view.View;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;

/**
 * Controller for the in-desktop HUD floating badge overlay.
 *
 * <p>Supports clicking to toggle between expanded metrics mode and collapsed minimal mode
 * to avoid obstructing Linux desktop window controls. G-10: the badge line is formatted
 * here ({@link #formatBadge}) and applied by the controller's diff-gated text plane.
 */
public class DesktopHudOverlay {

    private final View containerView;
    private final FullLinuxContract.CapabilityMatrix capabilityMatrix;
    private boolean collapsed = false;

    public DesktopHudOverlay(
            View containerView,
            FullLinuxContract.CapabilityMatrix capabilityMatrix) {
        this.containerView = containerView;
        this.capabilityMatrix = capabilityMatrix;

        if (this.containerView != null) {
            this.containerView.setOnClickListener(v -> toggleCollapsed());
        }
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /** Collapse state only; the render follows on the next 500 ms tick (G-10 text plane). */
    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
    }

    public void toggleCollapsed() {
        this.collapsed = !this.collapsed;
    }

    /**
     * Formats the badge line for the current collapse state without touching any view.
     * G-10: the controller composes badge + cached status suffix and applies one
     * diff-gated setText, so the overlay is a pure formatter plus collapse-state holder.
     */
    public String formatBadge(WaylandSurfaceController.HudBadgeInfo info) {
        return collapsed
                ? DesktopHudFormatter.formatCollapsed(info)
                : DesktopHudFormatter.formatExpanded(info, capabilityMatrix);
    }
}
