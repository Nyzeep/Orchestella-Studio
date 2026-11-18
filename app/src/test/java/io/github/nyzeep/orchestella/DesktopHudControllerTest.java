package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * G-03: HUD copy assembly and refresh-loop gating split out of DesktopActivity.
 * The status suffix must stay byte-identical to the pre-split 500 ms updater, and
 * the loop must keep its single-flight start/stop contract ({@code isHudUpdating}).
 */
public final class DesktopHudControllerTest {

    /** Records scheduling calls without touching the Android Handler. */
    private static final class RecordingScheduler implements DesktopHudController.UiScheduler {
        final List<Runnable> posted = new ArrayList<>();
        final List<Runnable> delayed = new ArrayList<>();
        final List<Runnable> removed = new ArrayList<>();

        @Override public void post(Runnable task) {
            posted.add(task);
        }

        @Override public void postDelayed(Runnable task, long delayMs) {
            delayed.add(task);
        }

        @Override public void removeCallbacks(Runnable task) {
            removed.add(task);
        }
    }

    private static final class FixedHost implements DesktopHudController.Host {
        boolean finishing;

        @Override public boolean isFinishing() {
            return finishing;
        }
    }

    @Test
    public void composeStatusSuffixKeepsExactPreSplitCopy() {
        assertEquals(
                "\nWayland CPU 拷贝实验通路 · 实例未运行"
                        + "\n触摸使用鼠标回退；Unicode 输入未验证。帧流连接不代表 GPU 加速或设备验收",
                DesktopHudController.composeStatusSuffix("实例未运行"));
        assertEquals(
                "\nWayland CPU 拷贝实验通路 · 显示数据面不可用"
                        + "\n触摸使用鼠标回退；Unicode 输入未验证。帧流连接不代表 GPU 加速或设备验收",
                DesktopHudController.composeStatusSuffix("显示数据面不可用"));
    }

    @Test
    public void refreshIntervalStaysAt500MsUntilG10TightensIt() {
        assertEquals(500L, DesktopHudController.REFRESH_INTERVAL_MS);
    }

    @Test
    public void startSchedulesExactlyOnceAndStopRemovesPendingTick() {
        RecordingScheduler scheduler = new RecordingScheduler();
        FixedHost host = new FixedHost();
        DesktopHudController controller = new DesktopHudController(
                host, () -> "实例未运行", scheduler, null, null, null);

        controller.start();
        controller.start(); // second start is a no-op while the loop is live
        assertEquals(1, scheduler.posted.size());
        Runnable updater = scheduler.posted.get(0);

        controller.stop();
        assertEquals(1, scheduler.removed.size());
        assertSame(updater, scheduler.removed.get(0));

        controller.start();
        assertEquals(2, scheduler.posted.size());
    }

    @Test
    public void updaterTickWithoutSurfaceDoesNotReschedule() {
        RecordingScheduler scheduler = new RecordingScheduler();
        FixedHost host = new FixedHost();
        DesktopHudController controller = new DesktopHudController(
                host, () -> "实例未运行", scheduler, null, null, null);
        controller.start();

        // Surface not wired yet: the tick exits without re-arming the 500 ms delay.
        scheduler.posted.get(0).run();
        assertTrue(scheduler.delayed.isEmpty());
        assertFalse(host.finishing);
    }

    @Test
    public void stopPreventsAnyFurtherTickWork() {
        RecordingScheduler scheduler = new RecordingScheduler();
        FixedHost host = new FixedHost();
        DesktopHudController controller = new DesktopHudController(
                host, () -> "实例未运行", scheduler, null, null, null);
        controller.start();
        controller.stop();
        // A tick that raced past stop must be inert (isHudUpdating already false).
        scheduler.posted.get(0).run();
        assertTrue(scheduler.delayed.isEmpty());
    }
}
