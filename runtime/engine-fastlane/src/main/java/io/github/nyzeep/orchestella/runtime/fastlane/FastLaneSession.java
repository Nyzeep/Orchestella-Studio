package io.github.nyzeep.orchestella.runtime.fastlane;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Host-side owner of one Fast Lane VM and every vsock channel opened for it.
 *
 * <p>One session owns the platform handle. Stop is bounded and idempotent;
 * channels are closed before the platform stop so a blocked read cannot delay
 * it. An UNCONFIRMED stop stays visible and never becomes a normal exit.
 *
 * <p>vsock provides one independent stream per {@code connectVsock} call.
 * {@link #openChannel(int)} is the general factory and must be used for proxy
 * and distcc connections, where each client needs its own stream. The cached
 * {@link #channel(int)} and the four named convenience methods share one
 * channel per port; they fit a single control or terminal mux connection but
 * must not be used to serve concurrent proxy or distcc clients.
 */
public final class FastLaneSession implements Closeable {
    private final FastLaneVirtualMachineApi api;
    private final FastLaneHandle handle;
    private final FastLaneLaunchSpec spec;
    private final Object stopLock = new Object();
    private final Map<Integer, OwnedChannel> sharedChannels = new HashMap<>();
    private final Set<OwnedChannel> ownedChannels = new HashSet<>();

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

    /** True once the payload reported readiness; see {@link FastLaneHandle#payloadReady()}. */
    public boolean payloadReady() {
        return handle.payloadReady();
    }

    /** Cached control channel; one control mux per VM. */
    public FastLaneChannel controlChannel() throws FastLaneException {
        return channel(spec.controlPort());
    }

    /** Cached terminal channel; one terminal mux per VM. */
    public FastLaneChannel terminalChannel() throws FastLaneException {
        return channel(spec.terminalPort());
    }

    /**
     * Cached proxy channel. Concurrent proxy clients must use
     * {@link #openChannel(int)} instead; one shared stream cannot carry
     * independent SOCKS5/HTTP tunnels without another multiplexing layer.
     */
    public FastLaneChannel proxyChannel() throws FastLaneException {
        return channel(spec.proxyPort());
    }

    /**
     * Cached distcc channel. Concurrent distcc jobs must use
     * {@link #openChannel(int)}; each job owns one vsock stream.
     */
    public FastLaneChannel distccChannel() throws FastLaneException {
        return channel(spec.distccPort());
    }

    /**
     * Opens a new, independently owned connection on {@code port}.
     *
     * <p>Every call returns a distinct stream. The session tracks all of them
     * and closes the remainder during {@link #stopRuntime(long)} or
     * {@link #close()}. A closed session never opens another channel.
     */
    public FastLaneChannel openChannel(int port) throws FastLaneException {
        return openOwnedChannel(port);
    }

    /**
     * Returns the cached channel for {@code port}, opening it when absent.
     *
     * <p>This is a convenience for a single consumer per port. Use
     * {@link #openChannel(int)} when several clients share a port.
     */
    public FastLaneChannel channel(int port) throws FastLaneException {
        requireKnownPort(port);
        synchronized (this) {
            ensureOpen();
            OwnedChannel existing = sharedChannels.get(port);
            if (existing != null && !existing.closed) {
                return existing;
            }
        }

        OwnedChannel opened = openOwnedChannel(port);
        synchronized (this) {
            if (channelsClosed) {
                closeQuietly(opened);
                throw new FastLaneException(FastLaneException.VSOCK_FAILED, "session is closed");
            }
            OwnedChannel raced = sharedChannels.get(port);
            if (raced != null && !raced.closed) {
                closeQuietly(opened);
                return raced;
            }
            sharedChannels.put(port, opened);
            return opened;
        }
    }

    private OwnedChannel openOwnedChannel(int port) throws FastLaneException {
        requireKnownPort(port);
        synchronized (this) {
            ensureOpen();
        }

        FastLaneChannel raw = api.connect(handle, port);
        if (raw == null) {
            throw new FastLaneException(FastLaneException.VSOCK_FAILED, "no vsock channel returned");
        }
        OwnedChannel owned = new OwnedChannel(raw, port);
        synchronized (this) {
            if (!channelsClosed) {
                ownedChannels.add(owned);
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

    private void requireKnownPort(int port) throws FastLaneException {
        if (port != spec.controlPort()
                && port != spec.terminalPort()
                && port != spec.proxyPort()
                && port != spec.distccPort()) {
            throw new FastLaneException(
                    FastLaneException.CONFIG_INVALID, "vsock port is outside this launch spec");
        }
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
            closing = new ArrayList<>(ownedChannels);
            ownedChannels.clear();
            sharedChannels.clear();
        }
        for (OwnedChannel channel : closing) {
            closeQuietly(channel);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException | RuntimeException ignored) {
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
        public InputStream inputStream() {
            return new FilterInputStream(raw.inputStream()) {
                @Override
                public void close() throws IOException {
                    OwnedChannel.this.close();
                }
            };
        }

        @Override
        public OutputStream outputStream() {
            return new FilterOutputStream(raw.outputStream()) {
                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                }

                @Override
                public void close() throws IOException {
                    OwnedChannel.this.close();
                }
            };
        }

        @Override
        public void close() throws IOException {
            synchronized (FastLaneSession.this) {
                if (closed) {
                    return;
                }
                closed = true;
                ownedChannels.remove(this);
                sharedChannels.remove(port, this);
            }
            raw.close();
        }
    }
}
