package io.github.nyzeep.orchestella;

import android.view.View;
import android.widget.TextView;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;

/**
 * Controller for the in-desktop HUD floating badge overlay.
 *
 * <p>Supports clicking to toggle between expanded metrics mode and collapsed minimal mode
 * to avoid obstructing Linux desktop window controls.
 */
public class DesktopHudOverlay {

    private final View containerView;
    private final TextView hudTextView;
    private final FullLinuxContract.CapabilityMatrix capabilityMatrix;
    private boolean collapsed = false;

    public DesktopHudOverlay(
            View containerView,
            TextView hudTextView,
            FullLinuxContract.CapabilityMatrix capabilityMatrix) {
        this.containerView = containerView;
        this.hudTextView = hudTextView;
        this.capabilityMatrix = capabilityMatrix;

        if (this.containerView != null) {
            this.containerView.setOnClickListener(v -> toggleCollapsed());
        }
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        update(null);
    }

    public void toggleCollapsed() {
        this.collapsed = !this.collapsed;
    }

    public void update(WaylandSurfaceController.HudBadgeInfo info) {
        if (hudTextView == null) {
            return;
        }
        String text = collapsed
                ? DesktopHudFormatter.formatCollapsed(info)
                : DesktopHudFormatter.formatExpanded(info, capabilityMatrix);
        hudTextView.setText(text);
    }
}
