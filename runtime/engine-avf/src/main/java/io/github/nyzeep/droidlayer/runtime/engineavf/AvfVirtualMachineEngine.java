package io.github.nyzeep.droidlayer.runtime.engineavf;

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

        /** The terminal console channel (retry-connected while the guest boots). */
        public synchronized AvfVirtualMachineApi.AvfVsockChannel terminalChannel()
                throws AvfEngineException {
            if (terminalChannel == null) {
                terminalChannel = connectWithRetry(spec.terminalVsockPort());
            }
            return terminalChannel;
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
        }
    }
}
