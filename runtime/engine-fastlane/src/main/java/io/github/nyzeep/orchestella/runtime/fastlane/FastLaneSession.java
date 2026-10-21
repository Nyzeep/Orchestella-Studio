package io.github.nyzeep.orchestella.runtime.fastlane;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Host-side owner of one Fast Lane VM and its four vsock channels.
 *
 * <p>One session owns the platform handle. Stop is bounded and idempotent;
 * channels are closed before the platform stop so a blocked read cannot delay
 * it. An UNCONFIRMED stop stays visible and never becomes a normal exit.
 */
public final class FastLaneSession implements Closeable {
    private final FastLaneVirtualMachineApi api;
    private final FastLaneHandle handle;
    private final FastLaneLaunchSpec spec;
    private final Object stopLock = new Object();
    private final Map<Integer, OwnedChannel> channels = new HashMap<>();

    private boolean channelsClosed;
    private volatile FastLaneStopResult stopResult;

    public FastLaneSession(
            FastLaneVirtualMachineApi api, FastLaneHandle handle, FastLaneLaunchSpec spec) {
        this.api = Objects.requireNonNull(api, "api");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    public String instanceId() {
        return handle.instanceId();
    }

    public FastLaneLaunchSpec spec() {
        return spec;
    }

    public FastLaneChannel controlChannel() throws FastLaneException {
        return channel(spec.controlPort());
    }

    public FastLaneChannel terminalChannel() throws FastLaneException {
        return channel(spec.terminalPort());
    }

    public FastLaneChannel proxyChannel() throws FastLaneException {
        return channel(spec.proxyPort());
    }

    public FastLaneChannel distccChannel() throws FastLaneException {
        return channel(spec.distccPort());
    }

    /** Opens (or returns) one channel; a closed session never opens a new one. */
    public FastLaneChannel channel(int port) throws FastLaneException {
        if (!isKnownPort(port)) {
            throw new FastLaneException(
                    FastLaneException.CONFIG_INVALID, "vsock port is outside this launch spec");
        }
        synchronized (this) {
            ensureOpen();
            OwnedChannel existing = channels.get(port);
            if (existing != null) {
                return existing;
            }
        }

        FastLaneChannel raw = api.connect(handle, port);
        if (raw == null) {
            throw new FastLaneException(FastLaneException.VSOCK_FAILED, "no vsock channel returned");
        }
        OwnedChannel owned = new OwnedChannel(raw, port);
        synchronized (this) {
            if (!channelsClosed) {
                OwnedChannel raced = channels.get(port);
                if (raced != null) {
                    closeQuietly(owned);
                    return raced;
                }
                channels.put(port, owned);
                return owned;
            }
        }
        closeQuietly(owned);
        throw new FastLaneException(FastLaneException.VSOCK_FAILED, "session is closed");
    }

    public boolean isAlive() {
        int status = handle.status();
        if (status == 1) {
            return true;
        }
        if (status == 0 || status == 2) {
            return false;
        }
        return stopResult != FastLaneStopResult.CONFIRMED
                && stopResult != FastLaneStopResult.ALREADY_STOPPED;
    }

    public FastLaneExitInfo waitForExit() throws InterruptedException, FastLaneException {
        FastLaneExitInfo info = handle.waitForExit();
        if (info.kind() == FastLaneExitInfo.Kind.STOP_REQUESTED
                && stopResult == FastLaneStopResult.UNCONFIRMED) {
            throw new FastLaneException(
                    FastLaneException.STOP_UNCONFIRMED,
                    "platform reported a requested stop without confirming it");
        }
        return info;
    }

    /**
     * Bounded, idempotent owner stop. The first call closes channels and asks
     * the platform; a settled CONFIRMED/ALREADY_STOPPED is never requested
     * twice. An UNCONFIRMED result may be retried by the same owner.
     */
    public FastLaneStopResult stopRuntime(long graceMs) {
        synchronized (stopLock) {
            if (stopResult == FastLaneStopResult.CONFIRMED
                    || stopResult == FastLaneStopResult.ALREADY_STOPPED) {
                return stopResult;
            }
            closeChannels();
            FastLaneStopResult result;
            try {
                result = handle.stopRuntime(Math.max(0L, graceMs));
            } catch (RuntimeException failure) {
                result = FastLaneStopResult.UNCONFIRMED;
            }
            stopResult = result;
            return result;
        }
    }

    /** Best-effort close; never throws. */
    @Override
    public void close() {
        stopRuntime(0L);
    }

    public List<String> notes() {
        return handle.notes();
    }

    public FastLaneStopResult stopResult() {
        synchronized (stopLock) {
            return stopResult;
        }
    }

    private boolean isKnownPort(int port) {
        return port == spec.controlPort()
                || port == spec.terminalPort()
                || port == spec.proxyPort()
                || port == spec.distccPort();
    }

    private synchronized void ensureOpen() throws FastLaneException {
        if (channelsClosed) {
            throw new FastLaneException(FastLaneException.VSOCK_FAILED, "session channels are closed");
        }
    }

    private void closeChannels() {
        List<OwnedChannel> closing;
        synchronized (this) {
            if (channelsClosed) {
                return;
            }
            channelsClosed = true;
            closing = new ArrayList<>(channels.values());
            channels.clear();
        }
        for (OwnedChannel channel : closing) {
            closeQuietly(channel);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort teardown; the platform stop is the authority.
        }
    }

    private final class OwnedChannel implements FastLaneChannel {
        private final FastLaneChannel raw;
        private final int port;
        private boolean closed;

        private OwnedChannel(FastLaneChannel raw, int port) {
            this.raw = raw;
            this.port = port;
        }

        @Override
        public java.io.InputStream inputStream() {
            return raw.inputStream();
        }

        @Override
        public java.io.OutputStream outputStream() {
            return raw.outputStream();
        }

        @Override
        public void close() throws IOException {
            synchronized (FastLaneSession.this) {
                if (closed) {
                    return;
                }
                closed = true;
                channels.remove(port, this);
            }
            raw.close();
        }
    }
}
