package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.DisplayChannel;
import io.github.nyzeep.orchestella.runtime.contract.display.DisplayViewportBudget;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Control-plane coordinator split out of DesktopActivity (G-03): launch negotiation
 * ({@code desktopLaunch} → STATUS polling → {@code applyDesktopEvidence} →
 * {@code displayMode}), evidence polling scheduling, the frame-confirmation path over
 * the presentation gate, and the headless redirect judgment.
 *
 * <p>The {@code displayGeneration}/{@code isCurrentDisplay} guard semantics are preserved
 * verbatim from the pre-split activity: a foreground flip, a generation bump, a session
 * or client replacement, or host teardown invalidates every in-flight negotiation,
 * polling round and frame confirmation. All collaborators are seams — {@link ExecutionSession}
 * (interface), {@link DesktopControlClient} (BridgeClient command surface) and
 * {@link DisplayChannel} (G-02 naming) — so the negotiation state machine runs on the JVM.
 */
public final class DesktopSessionCoordinator {

    /** Host liveness the guard needs from the activity; the activity passes itself through. */
    public interface Host {
        boolean isFinishing();

        boolean isDestroyed();
    }

    /**
     * Negotiation failure surface. The sink runs on the negotiation worker thread and must
     * re-check liveness on the UI thread (via {@link #isCurrentDisplay}) before showing
     * anything — identical to the pre-split activity's posted failure path.
     */
    public interface NegotiationFailureSink {
        void onNegotiationFailed(ExecutionSession target, DesktopControlClient client, long lifecycle, String message);
    }

    private final ExecutionSession session;
    private final String instanceId;
    private final DisplayChannel waylandChannel;
    private final int screenWidth;
    private final int screenHeight;
    private final double targetScale;
    private final Host host;
    private final NegotiationFailureSink failureSink;
    private final ScheduledExecutorService displayEvidence;
    private final Function<ExecutionSession, DesktopControlClient> controlClientResolver;
    private final DesktopPresentationGate presentationGate = new DesktopPresentationGate();
    private volatile boolean displayForeground;
    private volatile long displayGeneration;

    /** Production constructor: the control client is re-derived from the session at each entry point. */
    public DesktopSessionCoordinator(ExecutionSession session, String instanceId, DisplayChannel waylandChannel,
            int screenWidth, int screenHeight, double targetScale, Host host, NegotiationFailureSink failureSink,
            ScheduledExecutorService displayEvidence) {
        this(session, instanceId, waylandChannel, screenWidth, screenHeight, targetScale, host, failureSink,
                displayEvidence, target -> DesktopControlClient.of(target.bridgeClient()));
    }

    /** Test constructor: the resolver injects a controllable control-client seam. */
    public DesktopSessionCoordinator(ExecutionSession session, String instanceId, DisplayChannel waylandChannel,
            int screenWidth, int screenHeight, double targetScale, Host host, NegotiationFailureSink failureSink,
            ScheduledExecutorService displayEvidence,
            Function<ExecutionSession, DesktopControlClient> controlClientResolver) {
        this.session = session;
        this.instanceId = instanceId;
        this.waylandChannel = waylandChannel;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.targetScale = targetScale;
        this.host = host;
        this.failureSink = failureSink;
        this.displayEvidence = displayEvidence;
        this.controlClientResolver = controlClientResolver;
    }

    /**
     * Foreground entry: bumps the generation and arms the guard. The pre-split activity
     * ran the negotiation right after these two writes ({@code onResume}); call
     * {@link #runStartupNegotiation()} separately to preserve that order.
     */
    public void onForeground() {
        displayGeneration++;
        displayForeground = true;
    }

    /** Background exit: disarms the guard and invalidates every in-flight presentation ticket. */
    public void onBackground() {
        displayForeground = false;
        displayGeneration++;
        presentationGate.invalidate();
    }

    /** Activity teardown: stops the shared evidence executor and invalidates the gate. */
    public void onActivityDestroyed() {
        displayForeground = false;
        displayEvidence.shutdown();
        presentationGate.invalidate();
    }

    /**
     * Headless redirect judgment: an instance without a Wayland channel cannot host the
     * graphical desktop, so the activity offers the terminal hand-off instead.
     */
    public boolean isHeadlessInstance() {
        return session == null || session.displayChannel() == null;
    }

    /** The frame-confirmation observer for the surface renderer; owns the presentation gate semantics. */
    public void confirmFrame(DisplayChannel.FrameData frame) {
        ExecutionSession target = session;
        DesktopControlClient client = target != null ? controlClientResolver.apply(target) : null;
        long lifecycle = displayGeneration;
        if (client == null || !isCurrentDisplay(target, client, lifecycle)) return;
        DesktopPresentationGate.Confirmation ticket = presentationGate.begin(frame);
        if (ticket == null) return;
        try {
            displayEvidence.execute(() -> {
                boolean accepted = false;
                try {
                    if (isCurrentDisplay(target, client, lifecycle) && presentationGate.isCurrent(ticket)) {
                        accepted = client.confirmDesktopFrame(ticket.generation()).accepted()
                                && isCurrentDisplay(target, client, lifecycle);
                    }
                } catch (Exception ignored) {
                    // The failed confirm is not silent: finish(ticket, accepted=false)
                    // below is the failure surface the presentation gate already records.
                }
                presentationGate.finish(ticket, accepted);
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            presentationGate.finish(ticket, false);
        }
    }

    /** Starts the 1 s STATUS evidence polling loop on the shared evidence executor. */
    public void startEvidencePolling() {
        displayEvidence.scheduleWithFixedDelay(this::pollOnce, 1, 1, TimeUnit.SECONDS);
    }

    /** One polling round; also the test seam for the scheduled loop body. */
    void pollOnce() {
        ExecutionSession target = session;
        DesktopControlClient client = target != null ? controlClientResolver.apply(target) : null;
        long lifecycle = displayGeneration;
        if (client == null || !client.isReady() || !isCurrentDisplay(target, client, lifecycle)) return;
        try {
            BridgeClient.Result state = client.feature("STATUS", "desktop-labwc");
            if (state.accepted())
                applyDesktopEvidence(new org.json.JSONObject(state.payload()), target, client, lifecycle);
        } catch (Exception ignored) {
            // Polling probe: a failed round only skips one evidence refresh and the
            // next tick retries, so no error path or state reset belongs here.
        }
    }

    /** Re-runs the launch negotiation on a dedicated worker thread, once per foreground entry. */
    public void runStartupNegotiation() {
        ExecutionSession target = session;
        long lifecycle = displayGeneration;
        if (target == null) return;
        new Thread(() -> negotiateOnce(target, lifecycle), "desktop-launch-and-mode-negotiation").start();
    }

    /** The negotiation body; split out of the thread spawn so JVM tests drive it directly. */
    void negotiateOnce(ExecutionSession target, long lifecycle) {
        DesktopControlClient client = controlClientResolver.apply(target);
        if (client == null) return;
        try {
            long startupDeadline = System.nanoTime() + TimeUnit.MILLISECONDS
                    .toNanos(BridgeClient.DEFAULT_STARTUP_TIMEOUT_MS);
            while (isCurrentDisplay(target, client, lifecycle) && !client.isReady()
                    && System.nanoTime() < startupDeadline) {
                client.awaitReady(1000L);
            }
            if (!isCurrentDisplay(target, client, lifecycle) || !client.isReady()) return;
            if (waylandChannel == null) throw new IOException("显示通道在当前实例不可用");
            if (!waylandChannel.isConnected()) {
                BridgeClient.Result launched = client.desktopLaunch();
                if (!launched.accepted()) throw new IOException(launched.responseKind());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
                boolean attached = false;
                while (isCurrentDisplay(target, client, lifecycle) && System.nanoTime() < deadline) {
                    BridgeClient.Result result = client.feature("STATUS", "desktop-labwc");
                    if (!result.accepted()) throw new IOException(result.responseKind());
                    org.json.JSONObject guest = new org.json.JSONObject(result.payload());
                    String state = guest.optString("state");
                    if ("failed".equals(state) || "cancelled".equals(state))
                        throw new IOException(guest.optString("reason", "桌面启动失败"));
                    if ("awaiting-frame".equals(state) || "ready".equals(state)) {
                        attached = applyDesktopEvidence(guest, target, client, lifecycle);
                        if (!attached && isCurrentDisplay(target, client, lifecycle))
                            throw new IOException("显示连接请求未受理");
                        break;
                    }
                    Thread.sleep(500);
                }
                if (!isCurrentDisplay(target, client, lifecycle)) return;
                if (!attached) throw new IOException("桌面启动超过期限，请返回终端查看 guest 状态");
            }
            if (!isCurrentDisplay(target, client, lifecycle)) return;
            DisplayViewportBudget.FittedViewport fitted =
                    DesktopSurfaceController.negotiateViewport(screenWidth, screenHeight, targetScale);
            BridgeClient.Result mode = client.displayMode(
                    fitted.width(), fitted.height(), fitted.displayScale());
            if (!mode.accepted()) throw new IOException(mode.responseKind());
        } catch (Exception failure) {
            failureSink.onNegotiationFailed(target, client, lifecycle,
                    "桌面连接失败：" + failure.getMessage() + "。可返回终端查看日志。");
        }
    }

    /**
     * The display guard, preserved verbatim from the pre-split activity: foreground flag,
     * generation match, session identity (field and registry), client identity and host
     * liveness must all agree, or every in-flight operation aborts.
     */
    public boolean isCurrentDisplay(ExecutionSession target, DesktopControlClient client, long lifecycle) {
        return displayForeground && displayGeneration == lifecycle && session == target
                && ExecutionSessionRegistry.get(instanceId) == target && target.bridgeClient() == client.raw()
                && !host.isFinishing() && !host.isDestroyed();
    }

    /**
     * Applies one STATUS evidence round: only fresh, verified guest capture generations
     * drive a display reconnect; anything else invalidates the presentation gate.
     */
    synchronized boolean applyDesktopEvidence(org.json.JSONObject guest, ExecutionSession target,
            DesktopControlClient client, long lifecycle) {
        if (!isCurrentDisplay(target, client, lifecycle)) return false;
        String state = guest.optString("state");
        if (!"awaiting-frame".equals(state) && !"ready".equals(state)) {
            presentationGate.invalidate();
            return false;
        }
        boolean changed = presentationGate.observe(guest.optString("generation"), guest.optString("capture_generation"),
                guest.optInt("capture_width"), guest.optInt("capture_height"), System.nanoTime());
        if (changed) {
            org.json.JSONObject inputs = guest.optJSONObject("input");
            return session.reconnectDisplay(inputs != null && inputs.optBoolean("keyboard"),
                    inputs != null && inputs.optBoolean("unicodeText"), inputs != null && inputs.optBoolean("pointer"));
        }
        return session.displayChannel() != null;
    }

    /** Test seam: the current display generation. */
    long generationForTest() {
        return displayGeneration;
    }

    /** Test seam: the guest generation the presentation gate currently holds, or null. */
    String observedGenerationForTest() {
        return presentationGate.observedGenerationForTest();
    }
}
