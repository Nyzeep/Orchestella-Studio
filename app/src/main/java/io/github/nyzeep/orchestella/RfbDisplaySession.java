package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.DisplayControl;
import io.github.nyzeep.orchestella.runtime.contract.DisplayListener;
import io.github.nyzeep.orchestella.runtime.contract.DisplayMode;
import io.github.nyzeep.orchestella.runtime.contract.InputEvent;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The neutral RFB display session shared by QEMU and AVF: authenticated
 * CPU-copy frames plus keyboard/pointer input over an engine-supplied stream.
 * The name carries the real protocol; RFB is never presented as Wayland.
 */
public final class RfbDisplaySession implements WaylandChannel, DisplayControl {
    private final RfbDisplayChannel.StreamConnector connector;
    private final String token;
    private final Supplier<BridgeClient> control;
    private final Set<StateListener> states = new CopyOnWriteArraySet<>();
    private final Set<FrameListener> frames = new CopyOnWriteArraySet<>();
    private final Set<DisplayListener> displays = new CopyOnWriteArraySet<>();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "guest-display-mode");
                thread.setDaemon(true);
                return thread;
            });
    private volatile RfbDisplayChannel stream;
    private volatile FrameData latestFrame;
    private volatile boolean closed;
    private volatile String message = "显示尚未启动";
    private volatile int width = 1280, height = 720;
    private volatile double scale = 1.0;

    public RfbDisplaySession(RfbDisplayChannel.StreamConnector connector, String token,
            Supplier<BridgeClient> control) {
        this.connector = connector;
        this.token = token;
        this.control = control;
    }

    public synchronized boolean connect(RfbDisplayChannel.InputCapabilities capabilities) {
        return connect(capabilities, false);
    }

    public synchronized boolean connect(RfbDisplayChannel.InputCapabilities capabilities, boolean force) {
        if (closed) return false;
        if (!force && stream != null && stream.isConnected()) return true;
        latestFrame = null;
        RfbDisplayChannel previous = stream;
        stream = null;
        if (previous != null) previous.close();
        RfbDisplayChannel next = new RfbDisplayChannel(connector, token, capabilities);
        stream = next;
        next.addStateListener((state, detail) -> {
            synchronized (this) {
                if (stream != next || closed) return;
                message = detail;
            }
            for (StateListener listener : states) {
                if (stream != next || closed) break;
                listener.onStateChanged(state, detail);
            }
        });
        next.registerFrameListener(frame -> {
            synchronized (this) {
                if (stream != next || closed) return;
                latestFrame = frame;
                width = frame.width();
                height = frame.height();
            }
            for (FrameListener listener : frames) {
                if (stream != next || closed) break;
                listener.onFrameAvailable(frame);
            }
        });
        return next.connect();
    }

    public String message() { return message; }
    @Override public ConnectionState state() {
        RfbDisplayChannel current = stream;
        return closed || current == null ? ConnectionState.DISCONNECTED : current.state();
    }
    @Override public void addStateListener(StateListener listener) {
        if (listener != null) { states.add(listener); listener.onStateChanged(state(), message); }
    }
    @Override public void removeStateListener(StateListener listener) { states.remove(listener); }
    @Override public void registerFrameListener(FrameListener listener) {
        if (listener != null) {
            frames.add(listener);
            FrameData frame = latestFrame;
            if (frame != null && isConnected()) listener.onFrameAvailable(frame);
        }
    }
    @Override public void unregisterFrameListener(FrameListener listener) { frames.remove(listener); }
    @Override public boolean sendInputEvent(InputEvent event) {
        RfbDisplayChannel current = stream;
        return !closed && current != null && current.sendInputEvent(event);
    }
    @Override public DisplayMode currentMode() { return new DisplayMode(width, height, scale, 160, 15); }
    @Override public boolean resizeDisplay(int width, int height) { return setMode(width, height, scale); }
    @Override public boolean setMode(int width, int height, double scale) {
        if (closed || width < 100 || height < 100 || width > 4096 || height > 4096
                || (long) width * height > 4_194_304 || !Double.isFinite(scale) || scale < 0.1 || scale > 5)
            return false;
        BridgeClient client = control.get();
        if (client == null || !client.isReady()) return false;
        try {
            requests.execute(() -> {
                try {
                    if (closed || control.get() != client) return;
                    BridgeClient.Result result = client.displayMode(width, height, scale);
                    if (closed || control.get() != client) return;
                    if (result.accepted()) {
                        this.scale = scale;
                        for (DisplayListener listener : displays) listener.onModeChanged(currentMode());
                    } else {
                        message = result.responseKind();
                    }
                } catch (Exception failure) { message = "显示尺寸协商失败"; }
            });
            return true;
        } catch (RejectedExecutionException busy) { return false; }
    }
    @Override public boolean updateScale(double scale) { return setMode(width, height, scale); }
    @Override public boolean updateTargetFps(int fps) { return false; }
    @Override public void addDisplayListener(DisplayListener listener) { if (listener != null) displays.add(listener); }
    @Override public void removeDisplayListener(DisplayListener listener) { displays.remove(listener); }
    @Override public synchronized void close() {
        closed = true;
        RfbDisplayChannel previous = stream;
        stream = null;
        if (previous != null) previous.close();
        requests.getQueue().clear();
        // An in-flight command uses the shared authenticated control client; do not interrupt it.
        requests.shutdown();
        latestFrame = null;
        frames.clear(); states.clear(); displays.clear();
    }
}
