package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import java.io.IOException;
import java.util.Objects;

/**
 * Strategy-neutral-facing AVF engine adapter: assembles the same verified
 * kernel/initramfs/raw-root assets as the QEMU strategy into one non-protected
 * custom VM, then exposes the two vsock channels (bridge control + terminal)
 * the host-side launch flow consumes. All device specifics stay behind
 * {@link AvfVirtualMachineApi}; nothing in here names the host SDK surface.
 */
public final class AvfVirtualMachineEngine {
    private static final int CONNECT_ATTEMPTS = 100;
    private static final long CONNECT_BACKOFF_MS = 200;

    /**
     * Returns the strategy-neutral capability matrix reflecting AVF hardware
     * virtualization with full Wayland graphics, audio output, clipboard sync,
     * and pointer capture peripheral parity.
     */
    public static FullLinuxContract.CapabilityMatrix capabilityMatrix() {
        return FullLinuxContract.CapabilityMatrix.hardwareVirtualizationWithPeripherals();
    }

    private final AvfVirtualMachineApi api;
    private final int connectAttempts;
    private final long connectBackoffMs;

    public AvfVirtualMachineEngine(AvfVirtualMachineApi api) {
        this(api, CONNECT_ATTEMPTS, CONNECT_BACKOFF_MS);
    }

    /** Retry-tunable variant for tests; production uses the defaults. */
    AvfVirtualMachineEngine(AvfVirtualMachineApi api, int connectAttempts, long connectBackoffMs) {
        this.api = Objects.requireNonNull(api, "api");
        if (connectAttempts <= 0) {
            throw new IllegalArgumentException("connectAttempts must be positive");
        }
        if (connectBackoffMs < 0) {
            throw new IllegalArgumentException("connectBackoffMs must not be negative");
        }
        this.connectAttempts = connectAttempts;
        this.connectBackoffMs = connectBackoffMs;
    }

    /** Blocking launch; call on a worker thread. Channels are opened lazily. */
    public AvfVmSession launch(AvfLaunchSpec spec) throws AvfEngineException {
        Objects.requireNonNull(spec, "spec");
        AvfVirtualMachineApi.AvfVirtualMachineHandle handle = api.startCustomVm(spec);
        return new AvfVmSession(api, handle, spec, connectAttempts, connectBackoffMs);
    }

    /** One live AVF-backed instance session. */
    public static final class AvfVmSession {
        private final AvfVirtualMachineApi api;
        private final AvfVirtualMachineApi.AvfVirtualMachineHandle handle;
        private final AvfLaunchSpec spec;
        private final int connectAttempts;
        private final long connectBackoffMs;
        private AvfVirtualMachineApi.AvfVsockChannel controlChannel;
        private AvfVirtualMachineApi.AvfVsockChannel terminalChannel;
        private AvfVirtualMachineApi.AvfVsockChannel shareChannel;
        private AvfVirtualMachineApi.AvfVsockChannel graphicsChannel;
        private AvfVirtualMachineApi.AvfVsockChannel audioChannel;
        private AvfAudioChannel avfAudioChannel;

        private AvfVmSession(
                AvfVirtualMachineApi api,
                AvfVirtualMachineApi.AvfVirtualMachineHandle handle,
                AvfLaunchSpec spec,
                int connectAttempts,
                long connectBackoffMs) {
            this.api = api;
            this.handle = handle;
            this.spec = spec;
            this.connectAttempts = connectAttempts;
            this.connectBackoffMs = connectBackoffMs;
        }

        public String instanceId() {
            return handle.instanceId();
        }

        /** Reflection diagnostics from the underlying adapter. */
        public java.util.List<String> reflectionNotes() {
            return handle.reflectionNotes();
        }

        /** The bridge control channel (retry-connected while the guest boots). */
        public synchronized AvfVirtualMachineApi.AvfVsockChannel controlChannel()
                throws AvfEngineException {
            if (controlChannel == null) {
                controlChannel = connectWithRetry(spec.controlVsockPort());
            }
            return controlChannel;
        }

        /**
         * The terminal console channel (retry-connected while the guest boots).
         */
        public synchronized AvfVirtualMachineApi.AvfVsockChannel terminalChannel()
                throws AvfEngineException {
            if (terminalChannel == null) {
                terminalChannel = connectWithRetry(spec.terminalVsockPort());
            }
            return terminalChannel;
        }

        /**
         * Opens one additional guest-bound vsock channel (retry-connected),
         * e.g. for the {@link AvfVsockPortForwarder} to reach the in-guest
         * forward proxy on a forwarded service port. Distinct from the two
         * dedicated bridge channels, which stay single-open.
         */
        public AvfVirtualMachineApi.AvfVsockChannel openChannel(int port)
                throws AvfEngineException {
            return connectWithRetry(port);
        }

        /**
         * The 9p data-plane channel (retry-connected while the guest boots):
         * the guest agent listens on the rendezvous port announced by the
         * spec's cmdline and the host connects to serve the read-only 9p
         * file system over it (ADR-0009 data plane). Requires a share on the
         * launch spec.
         */
        public synchronized AvfVirtualMachineApi.AvfVsockChannel shareChannel()
                throws AvfEngineException {
            if (spec.sharedDirectory() == null) {
                throw new AvfEngineException(
                        AvfEngineException.VSOCK_CONNECT_FAILED,
                        "no shared directory is configured for this launch");
            }
            if (shareChannel == null) {
                shareChannel = connectWithRetry(spec.sharedDirectoryVsockPort());
            }
            return shareChannel;
        }

        /**
         * Starts the read-only 9p2000.L server over the share data channel in
         * one daemon thread. The returned server stays alive until the
         * channel closes (guest shutdown) or {@link #closeChannels()} runs.
         *
         * @return the started server (for lifecycle observation and tests)
         */
        public synchronized Avf9pShareServer startShareServer() throws AvfEngineException {
            Avf9pShareServer server = new Avf9pShareServer(spec.sharedDirectory());
            AvfVirtualMachineApi.AvfVsockChannel channel = shareChannel();
            Thread thread = new Thread(() -> {
                try {
                    server.serve(channel.inputStream(), channel.outputStream());
                } catch (IOException ignored) {
                    // the share channel ended: the guest went away or closed
                }
            }, "avf-9p-share-" + handle.instanceId());
            thread.setDaemon(true);
            thread.start();
            return server;
        }

        /** Launch specification that started this session. */
        public AvfLaunchSpec spec() {
            return spec;
        }

        /**
         * The graphics data-plane vsock channel (port 9103, retry-connected).
         */
        public synchronized AvfVirtualMachineApi.AvfVsockChannel graphicsChannel()
                throws AvfEngineException {
            if (graphicsChannel == null) {
                graphicsChannel = connectWithRetry(spec.graphicsVsockPort());
            }
            return graphicsChannel;
        }

        /**
         * Creates an authenticated Wayland graphics and control channel over vsock.
         */
        public AvfWaylandChannel waylandChannel(String instanceToken) {
            return new AvfWaylandChannel(this, instanceToken);
        }

        /** Dedicated vsock audio port announced by this session's spec. */
        public int vsockAudioPort() {
            return spec.audioVsockPort();
        }

        /**
         * The raw audio data-plane vsock channel (port 9104, retry-connected).
         */
        public synchronized AvfVirtualMachineApi.AvfVsockChannel audioVsockChannel()
                throws AvfEngineException {
            if (audioChannel == null) {
                audioChannel = connectWithRetry(spec.audioVsockPort());
            }
            return audioChannel;
        }

        /**
         * Returns the high-performance PCM audio stream channel over vsock (port 9104).
         */
        public synchronized AvfAudioChannel audioChannel() throws AvfEngineException {
            if (avfAudioChannel == null) {
                avfAudioChannel = new AvfAudioChannel(this);
            }
            return avfAudioChannel;
        }

        /** Blocks until the guest exits; exit observation is best-effort. */
        public int waitFor() throws InterruptedException {
            return handle.waitFor();
        }

        /** Best-effort host-side stop; guests own their lifecycle otherwise. */
        public void stop() {
            handle.stop();
        }

        private AvfVirtualMachineApi.AvfVsockChannel connectWithRetry(int port)
                throws AvfEngineException {
            AvfEngineException last = null;
            for (int attempt = 0; attempt < connectAttempts; attempt++) {
                try {
                    return api.connectVsock(handle, port);
                } catch (AvfEngineException failure) {
                    last = failure;
                }
                try {
                    Thread.sleep(connectBackoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AvfEngineException(
                            AvfEngineException.VSOCK_CONNECT_FAILED,
                            "interrupted while connecting vsock port " + port);
                }
            }
            throw new AvfEngineException(
                    last.reason(),
                    "vsock port " + port + " unreachable after " + connectAttempts
                            + " attempts",
                    last);
        }

        /** Closes any channels opened through this session. */
        public synchronized void closeChannels() throws IOException {
            if (controlChannel != null) {
                controlChannel.close();
                controlChannel = null;
            }
            if (terminalChannel != null) {
                terminalChannel.close();
                terminalChannel = null;
            }
            if (shareChannel != null) {
                shareChannel.close();
                shareChannel = null;
            }
            if (graphicsChannel != null) {
                graphicsChannel.close();
                graphicsChannel = null;
            }
            if (avfAudioChannel != null) {
                avfAudioChannel.close();
                avfAudioChannel = null;
            }
            if (audioChannel != null) {
                audioChannel.close();
                audioChannel = null;
            }
        }
    }
}
