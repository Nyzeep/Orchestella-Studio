package io.github.nyzeep.droidlayer;

import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import io.github.nyzeep.droidlayer.runtime.contract.WaylandChannel;
import java.util.Locale;

/**
 * Pure JVM formatter decoupling Wayland HUD metrics (FPS, resolution, scale,
 * connection state, and virtualization strategy badge) from Android views.
 *
 * <p>Conforms to ADR-0017 and Ticket 43.
 */
public final class DesktopHudFormatter {

    private DesktopHudFormatter() {}

    /**
     * Formats frame rate into human-readable FPS string. Clamps non-positive values to 0 FPS.
     */
    public static String formatFps(int fps) {
        int safeFps = Math.max(0, fps);
        return safeFps + " FPS";
    }

    /**
     * Formats resolution and scaling factor (e.g. {@code 1920x1080 @ 1.0x}).
     */
    public static String formatResolution(int width, int height, double scale) {
        int safeW = Math.max(0, width);
        int safeH = Math.max(0, height);
        double safeScale = scale > 0.0 ? scale : 1.0;
        return String.format(Locale.ROOT, "%dx%d @ %.1fx", safeW, safeH, safeScale);
    }

    /**
     * Formats connection lifecycle state into display badge text.
     */
    public static String formatConnectionState(WaylandChannel.ConnectionState state) {
        if (state == null) {
            return WaylandChannel.ConnectionState.DISCONNECTED.name();
        }
        return state.name();
    }

    /**
     * Formats graphics strategy badge from the strategy-neutral capability matrix.
     */
    public static String formatBadge(FullLinuxContract.CapabilityMatrix matrix) {
        if (matrix == null) {
            return "未知通道";
        }
        return matrix.graphicsBadge();
    }

    /**
     * Formats a raw badge string with fallback defense.
     */
    public static String formatBadge(String badge) {
        if (badge == null || badge.trim().isEmpty()) {
            return "未知通道";
        }
        return badge.trim();
    }

    /**
     * Formats expanded HUD badge text with full metrics.
     */
    public static String formatExpanded(
            int fps,
            int width,
            int height,
            double scale,
            WaylandChannel.ConnectionState state,
            String graphicsBadge) {
        return String.format(
                Locale.ROOT,
                "%s | %s | %s | %s",
                formatFps(fps),
                formatResolution(width, height, scale),
                formatConnectionState(state),
                formatBadge(graphicsBadge));
    }

    /**
     * Formats expanded HUD text directly from controller badge info snapshot and capability matrix.
     */
    public static String formatExpanded(
            WaylandSurfaceController.HudBadgeInfo info,
            FullLinuxContract.CapabilityMatrix matrix) {
        if (info == null) {
            return formatExpanded(0, 0, 0, 1.0, WaylandChannel.ConnectionState.DISCONNECTED, formatBadge(matrix));
        }
        int w = info.contentWidth() > 0 ? info.contentWidth() : info.viewportWidth();
        int h = info.contentHeight() > 0 ? info.contentHeight() : info.viewportHeight();
        double scale = info.scale() > 0.0 ? info.scale() : 1.0;
        return formatExpanded(info.fps(), w, h, scale, info.connectionState(), formatBadge(matrix));
    }

    /**
     * Formats compact/collapsed HUD badge text to minimize screen obstruction.
     */
    public static String formatCollapsed(int fps, WaylandChannel.ConnectionState state) {
        return String.format(
                Locale.ROOT,
                "%s | %s",
                formatFps(fps),
                formatConnectionState(state));
    }

    /**
     * Formats collapsed HUD text directly from controller badge info snapshot.
     */
    public static String formatCollapsed(WaylandSurfaceController.HudBadgeInfo info) {
        if (info == null) {
            return formatCollapsed(0, WaylandChannel.ConnectionState.DISCONNECTED);
        }
        return formatCollapsed(info.fps(), info.connectionState());
    }
}
