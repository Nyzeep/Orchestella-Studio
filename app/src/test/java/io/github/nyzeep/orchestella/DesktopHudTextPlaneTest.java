package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * G-10：HUD 文本面 diff/节流纯逻辑。500ms 节拍语义不变；不变段预组装缓存 + 变化才
 * setText 的 diff 门控由控制器套用。W1-03 将通路文案更正为 RFB CPU 拷贝呈现通路。
 */
public final class DesktopHudTextPlaneTest {

    private static final String BADGE = "60 FPS | 1920x1080 @ 1.0x | CONNECTED";

    @Test
    public void composeStatusSuffixIsByteIdenticalToControllerContract() {
        assertEquals(
                "\nRFB CPU 拷贝呈现通路 · 实例未运行"
                        + "\n触摸使用鼠标回退；Unicode 输入未验证。帧流连接不代表 GPU 加速或设备验收",
                DesktopHudTextPlane.composeStatusSuffix("实例未运行"));
        assertEquals(DesktopHudController.composeStatusSuffix("实例未运行"),
                DesktopHudTextPlane.composeStatusSuffix("实例未运行"));
        assertEquals(DesktopHudController.composeStatusSuffix("显示数据面不可用"),
                DesktopHudTextPlane.composeStatusSuffix("显示数据面不可用"));
    }

    @Test
    public void firstTickAppliesFullBadgePlusSuffixText() {
        DesktopHudTextPlane plane = new DesktopHudTextPlane();
        String applied = plane.composeTickText(BADGE, "实例未运行");
        assertEquals(BADGE + DesktopHudController.composeStatusSuffix("实例未运行"), applied);
    }

    @Test
    public void unchangedTickReturnsNullSoControllerSkipsSetText() {
        DesktopHudTextPlane plane = new DesktopHudTextPlane();
        plane.composeTickText(BADGE, "实例未运行");
        assertNull(plane.composeTickText(BADGE, "实例未运行"));
        assertNull(plane.composeTickText(BADGE, "实例未运行"));
    }

    @Test
    public void badgeChangeReappliesTextWhileSuffixStaysCached() {
        DesktopHudTextPlane plane = new DesktopHudTextPlane();
        plane.composeTickText(BADGE, "实例未运行");
        String next = plane.composeTickText("61 FPS | 1920x1080 @ 1.0x | CONNECTED", "实例未运行");
        assertEquals("61 FPS | 1920x1080 @ 1.0x | CONNECTED"
                + DesktopHudController.composeStatusSuffix("实例未运行"), next);
    }

    @Test
    public void statusChangeRecomposesSuffixAndReappliesText() {
        DesktopHudTextPlane plane = new DesktopHudTextPlane();
        plane.composeTickText(BADGE, "实例未运行");
        String next = plane.composeTickText(BADGE, "显示数据面不可用");
        assertEquals(BADGE + DesktopHudController.composeStatusSuffix("显示数据面不可用"), next);
    }

    @Test
    public void statusSuffixCacheReusesInstanceWhileStatusUnchanged() {
        DesktopHudTextPlane plane = new DesktopHudTextPlane();
        String first = plane.statusSuffixFor("实例未运行");
        // Steady state: unchanged status reuses the composed suffix instance (no rebuild).
        assertSame(first, plane.statusSuffixFor("实例未运行"));
        String other = plane.statusSuffixFor("显示数据面不可用");
        assertEquals(DesktopHudController.composeStatusSuffix("显示数据面不可用"), other);
        assertSame(other, plane.statusSuffixFor("显示数据面不可用"));
    }

    @Test
    public void invalidateForcesReapplyAfterOutOfBandOverwrite() {
        DesktopHudTextPlane plane = new DesktopHudTextPlane();
        plane.composeTickText(BADGE, "实例未运行");
        // showNegotiationFailure replaced the view copy outright; the next tick must
        // re-apply badge+suffix even though it matches the cached value.
        plane.invalidate();
        assertEquals(BADGE + DesktopHudController.composeStatusSuffix("实例未运行"),
                plane.composeTickText(BADGE, "实例未运行"));
    }

    @Test
    public void nullBadgeComposesSuffixOnlyLine() {
        DesktopHudTextPlane plane = new DesktopHudTextPlane();
        assertEquals(DesktopHudController.composeStatusSuffix("实例未运行"),
                plane.composeTickText(null, "实例未运行"));
    }
}
