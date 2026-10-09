package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import io.github.nyzeep.droidlayer.runtime.contract.WaylandChannel;
import org.junit.Test;

/**
 * TDD Red Phase: Unit tests for DesktopHudFormatter.
 * Verifies FPS formatting, resolution and scale formatting, connection state transitions,
 * virtualization graphics strategy badges, and boundary condition defenses.
 */
public final class DesktopHudFormatterTest {

    @Test
    public void testFormatFpsNormalAndClamped() {
        assertEquals("60 FPS", DesktopHudFormatter.formatFps(60));
        assertEquals("120 FPS", DesktopHudFormatter.formatFps(120));
        assertEquals("0 FPS", DesktopHudFormatter.formatFps(0));
        assertEquals("0 FPS", DesktopHudFormatter.formatFps(-10));
    }

    @Test
    public void testFormatResolutionAndScale() {
        assertEquals("1920x1080 @ 1.0x", DesktopHudFormatter.formatResolution(1920, 1080, 1.0));
        assertEquals("2400x1080 @ 1.5x", DesktopHudFormatter.formatResolution(2400, 1080, 1.5));
        assertEquals("0x0 @ 1.0x", DesktopHudFormatter.formatResolution(0, 0, 1.0));
        assertEquals("1280x720 @ 1.0x", DesktopHudFormatter.formatResolution(1280, 720, 0.0));
        assertEquals("1280x720 @ 1.0x", DesktopHudFormatter.formatResolution(1280, 720, -0.5));
    }

    @Test
    public void testFormatConnectionState() {
        assertEquals("CONNECTED", DesktopHudFormatter.formatConnectionState(WaylandChannel.ConnectionState.CONNECTED));
        assertEquals("DISCONNECTED", DesktopHudFormatter.formatConnectionState(WaylandChannel.ConnectionState.DISCONNECTED));
        assertEquals("ERROR", DesktopHudFormatter.formatConnectionState(WaylandChannel.ConnectionState.ERROR));
        assertEquals("DISCONNECTED", DesktopHudFormatter.formatConnectionState(null));
    }

    @Test
    public void testFormatGraphicsBadge() {
        FullLinuxContract.CapabilityMatrix softwareWithGfx =
                FullLinuxContract.CapabilityMatrix.softwareVirtualizationWithGraphics();
        assertEquals("Wayland 原生通道（共享内存）", DesktopHudFormatter.formatBadge(softwareWithGfx));

        FullLinuxContract.CapabilityMatrix hardwareWithGfx =
                FullLinuxContract.CapabilityMatrix.hardwareVirtualizationWithGraphics();
        assertEquals("Wayland 原生通道（vsock 硬件加速）", DesktopHudFormatter.formatBadge(hardwareWithGfx));

        FullLinuxContract.CapabilityMatrix softwareNoGfx =
                FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline();
        assertEquals("无图形通道", DesktopHudFormatter.formatBadge(softwareNoGfx));

        assertEquals("未知通道", DesktopHudFormatter.formatBadge((FullLinuxContract.CapabilityMatrix) null));
        assertEquals("未知通道", DesktopHudFormatter.formatBadge((String) null));
    }

    @Test
    public void testFormatExpandedComposite() {
        String formatted = DesktopHudFormatter.formatExpanded(
                60, 1920, 1080, 1.0,
                WaylandChannel.ConnectionState.CONNECTED,
                "Wayland 原生通道（共享内存）"
        );
        assertEquals("60 FPS | 1920x1080 @ 1.0x | CONNECTED | Wayland 原生通道（共享内存）", formatted);
    }

    @Test
    public void testFormatCollapsedComposite() {
        String formatted = DesktopHudFormatter.formatCollapsed(
                60, WaylandChannel.ConnectionState.CONNECTED
        );
        assertEquals("60 FPS | CONNECTED", formatted);

        String disconnected = DesktopHudFormatter.formatCollapsed(
                0, WaylandChannel.ConnectionState.DISCONNECTED
        );
        assertEquals("0 FPS | DISCONNECTED", disconnected);
    }

    @Test
    public void testFormatFromBadgeInfo() {
        WaylandSurfaceController.HudBadgeInfo info = new WaylandSurfaceController.HudBadgeInfo(
                60,
                WaylandChannel.ConnectionState.CONNECTED,
                1080,
                2400,
                1920,
                1080,
                1200L,
                2L
        );
        FullLinuxContract.CapabilityMatrix matrix =
                FullLinuxContract.CapabilityMatrix.hardwareVirtualizationWithGraphics();

        String expanded = DesktopHudFormatter.formatExpanded(info, matrix);
        assertTrue(expanded.contains("60 FPS"));
        assertTrue(expanded.contains("1920x1080 @ 1.0x"));
        assertTrue(expanded.contains("CONNECTED"));
        assertTrue(expanded.contains("vsock 硬件加速"));

        WaylandSurfaceController.HudBadgeInfo scaledInfo = new WaylandSurfaceController.HudBadgeInfo(
                60,
                WaylandChannel.ConnectionState.CONNECTED,
                1264,
                2736,
                1264,
                2736,
                1200L,
                2L,
                2.0
        );
        String scaledExpanded = DesktopHudFormatter.formatExpanded(scaledInfo, matrix);
        assertTrue(scaledExpanded.contains("1264x2736 @ 2.0x"));

        String collapsed = DesktopHudFormatter.formatCollapsed(info);
        assertEquals("60 FPS | CONNECTED", collapsed);

        assertNotNull(DesktopHudFormatter.formatExpanded(null, null));
        assertNotNull(DesktopHudFormatter.formatCollapsed((WaylandSurfaceController.HudBadgeInfo) null));
    }
}
