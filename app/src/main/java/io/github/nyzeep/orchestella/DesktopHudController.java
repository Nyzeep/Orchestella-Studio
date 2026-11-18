package io.github.nyzeep.orchestella;

import android.content.Context;
import android.os.Handler;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import java.util.function.Supplier;

/**
 * HUD text-plane controller split out of DesktopActivity (G-03): builds the badge
 * overlay views, owns the 500 ms refresh loop and the negotiation-failure line.
 *
 * <p>{@link #REFRESH_INTERVAL_MS} is the seam G-10 throttling will tighten; the refresh
 * semantics stay unchanged this round (badge update + status suffix append per tick).
 */
public final class DesktopHudController {

    /** HUD refresh cadence; G-10's throttle seam (not tightened in this ticket). */
    public static final long REFRESH_INTERVAL_MS = 500L;

    /** Host liveness the refresh loop checks before re-scheduling itself. */
    public interface Host {
        boolean isFinishing();
    }

    /** Main-thread scheduling seam; production wraps a Handler, tests record directly. */
    public interface UiScheduler {
        void post(Runnable task);

        void postDelayed(Runnable task, long delayMs);

        void removeCallbacks(Runnable task);
    }

    public static UiScheduler onHandler(Handler handler) {
        return new UiScheduler() {
            @Override public void post(Runnable task) {
                handler.post(task);
            }

            @Override public void postDelayed(Runnable task, long delayMs) {
                handler.postDelayed(task, delayMs);
            }

            @Override public void removeCallbacks(Runnable task) {
                handler.removeCallbacks(task);
            }
        };
    }

    private final UiScheduler scheduler;
    private final Host host;
    private final Supplier<String> displayStatusSupplier;
    private final LinearLayout hudContainer;
    private final DesktopHudOverlay hudOverlay;
    private final TextView hudTextView;
    private WaylandSurfaceView surfaceView;
    private boolean isHudUpdating;

    /** Production constructor: builds the badge container and monospace HUD text. */
    public DesktopHudController(Context context, Host host, Supplier<String> displayStatusSupplier,
            UiScheduler scheduler, FullLinuxContract.CapabilityMatrix capabilityMatrix) {
        this.host = host;
        this.displayStatusSupplier = displayStatusSupplier;
        this.scheduler = scheduler;
        this.hudTextView = buildHudTextView(context);
        this.hudContainer = buildHudContainer(context);
        this.hudContainer.addView(this.hudTextView);
        this.hudOverlay = new DesktopHudOverlay(this.hudContainer, this.hudTextView, capabilityMatrix);
    }

    /** Test constructor: null views are allowed; only the loop gating and copy are exercised. */
    DesktopHudController(Host host, Supplier<String> displayStatusSupplier, UiScheduler scheduler,
            DesktopHudOverlay hudOverlay, TextView hudTextView, LinearLayout hudContainer) {
        this.host = host;
        this.displayStatusSupplier = displayStatusSupplier;
        this.scheduler = scheduler;
        this.hudOverlay = hudOverlay;
        this.hudTextView = hudTextView;
        this.hudContainer = hudContainer;
    }

    /** The status suffix appended below the badge on every refresh tick. */
    public static String composeStatusSuffix(String displayStatus) {
        return "\nWayland CPU 拷贝实验通路 · " + displayStatus
                + "\n触摸使用鼠标回退；Unicode 输入未验证。帧流连接不代表 GPU 加速或设备验收";
    }

    /** Badge container: semi-transparent collapsible pill inside the top bar. */
    public static LinearLayout buildHudContainer(Context context) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setMinimumHeight(dp(context, 44));
        container.setBackground(context.getDrawable(R.drawable.bg_status_badge));
        container.setClickable(true);
        container.setFocusable(true);
        int side = context.getResources().getDimensionPixelSize(R.dimen.oe_space_sm);
        int vertical = context.getResources().getDimensionPixelSize(R.dimen.oe_icon_inline_gap);
        container.setPadding(side, vertical, side, vertical);
        return container;
    }

    /** Monospace HUD text with the classic standby line as its initial copy. */
    public static TextView buildHudTextView(Context context) {
        TextView text = new TextView(context);
        text.setTextColor(0xFF00FF66);
        text.setTextSize(12f);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setText("60 FPS | 1920x1080 @ 1.0x | CONNECTED");
        return text;
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /** The overlay container handed to the top bar. */
    public LinearLayout hudContainer() {
        return hudContainer;
    }

    /** The HUD text view (also the failure-line surface). */
    public TextView hudTextView() {
        return hudTextView;
    }

    /** The surface feeding {@link WaylandSurfaceController.HudBadgeInfo}; set once after assembly. */
    public void setSurfaceView(WaylandSurfaceView surfaceView) {
        this.surfaceView = surfaceView;
    }

    /** Starts the 500 ms refresh loop exactly once. */
    public void start() {
        if (!isHudUpdating) {
            isHudUpdating = true;
            scheduler.post(hudUpdater);
        }
    }

    /** Stops the refresh loop and drops pending ticks. */
    public void stop() {
        isHudUpdating = false;
        scheduler.removeCallbacks(hudUpdater);
    }

    /** Negotiation-failure line: replaces the HUD copy outright. */
    public void showNegotiationFailure(String message) {
        if (hudTextView != null) {
            hudTextView.setText(message);
        }
    }

    private final Runnable hudUpdater = new Runnable() {
        @Override
        public void run() {
            if (isHudUpdating && surfaceView != null && !host.isFinishing()) {
                hudOverlay.update(surfaceView.getHudBadgeInfo());
                hudTextView.append(composeStatusSuffix(displayStatusSupplier.get()));
                scheduler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }
    };
}
