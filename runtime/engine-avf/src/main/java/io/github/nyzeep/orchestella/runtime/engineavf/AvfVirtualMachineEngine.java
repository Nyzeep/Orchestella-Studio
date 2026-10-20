package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.SessionCapabilities;
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
     * The no-session upper bound for AVF: terminal, bridge, hardware
     * acceleration and the RFB display are the only wired data plane. Audio,
     * clipboard, pointer capture, 9p and forwarding appear only when a live
     * session actually assembles them.
     */
    public static FullLinuxContract.CapabilityMatrix capabilityMatrix() {
        return FullLinuxContract.CapabilityMatrix.from(
                SessionCapabilities.builder(FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION)
                        .terminal(true)
                        .bridge(true)
                        .hardwareAcceleration(true)
                        .display(SessionCapabilities.DisplayTransport.RFB)
                        .build());
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
        return launch(spec, AvfLaunchTrace.NONE);
    }

    /**
     * Blocking launch with ordered lifecycle diagnostics. The trace receives
     * launch/create/run boundaries here; stop and exit stages are appended by
     * the returned session. The trace never carries credentials or user data.
     */
    public AvfVmSession launch(AvfLaunchSpec spec, AvfLaunchTrace trace)
            throws AvfEngineException {
        Objects.requireNonNull(spec, "spec");
        AvfLaunchTrace stages = trace == null ? AvfLaunchTrace.NONE : trace;
        stages.mark(AvfLaunchTrace.Stage.LAUNCH_REQUESTED);
        AvfVirtualMachineApi.AvfVirtualMachineHandle handle = api.startCustomVm(spec, stages);
        return new AvfVmSession(api, handle, spec, connectAttempts, connectBackoffMs, stages);
    }

    /** One live AVF-backed instance session. */
    public static final class AvfVmSession {
        private final AvfVirtualMachineApi api;
        private final AvfVirtualMachineApi.AvfVirtualMachineHandle handle;
        private final AvfLaunchSpec spec;
        private final int connectAttempts;
        private final long connectBackoffMs;
        private final AvfLaunchTrace trace;
        private final java.util.Map<Integer, java.util.concurrent.CompletableFuture<AvfVirtualMachineApi.AvfVsockChannel>>
                lazyChannels = new java.util.HashMap<>();
        private final java.util.Set<AvfVirtualMachineApi.AvfVsockChannel> ownedChannels =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private final java.util.concurrent.CountDownLatch closedSignal = new java.util.concurrent.CountDownLatch(1);
        private final Object stopLock = new Object();
        private final Object shareLock = new Object();
        private Avf9pShareServer shareServer;
        private volatile Thread shareThread;
        private volatile boolean channelsClosed;
        private volatile boolean stopRequested;
        private boolean stopping;
        private boolean stopSettled;
        private volatile AvfStopResult stopResult;

        private AvfVmSession(
                AvfVirtualMachineApi api,
                AvfVirtualMachineApi.AvfVirtualMachineHandle handle,
                AvfLaunchSpec spec,
                int connectAttempts,
                long connectBackoffMs,
                AvfLaunchTrace trace) {
            this.api = api;
            this.handle = handle;
            this.spec = spec;
            this.connectAttempts = connectAttempts;
            this.connectBackoffMs = connectBackoffMs;
            this.trace = trace;
        }

        public String instanceId() {
            return handle.instanceId();
        }

        /** Reflection diagnostics from the underlying adapter. */
        public java.util.List<String> reflectionNotes() {
            return handle.reflectionNotes();
        }

        /** The bridge control channel (retry-connected while the guest boots). */
        public AvfVirtualMachineApi.AvfVsockChannel controlChannel() throws AvfEngineException {
            return lazyChannel(spec.controlVsockPort());
        }

        /**
         * The terminal console channel (retry-connected while the guest boots).
         */
        public AvfVirtualMachineApi.AvfVsockChannel terminalChannel() throws AvfEngineException {
            return lazyChannel(spec.terminalVsockPort());
        }

        /**
         * Opens one additional guest-bound vsock channel (retry-connected).
         * Distinct from the two dedicated bridge channels, which stay
         * single-open. AVF v1 has no production consumer for non-bridge
         * channels; forwarding stays UNSUPPORTED until a guest relay exists.
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
        public AvfVirtualMachineApi.AvfVsockChannel shareChannel()
                throws AvfEngineException {
            if (spec.sharedDirectory() == null) {
                throw new AvfEngineException(
                        AvfEngineException.VSOCK_CONNECT_FAILED,
                        "no shared directory is configured for this launch");
            }
            return lazyChannel(spec.sharedDirectoryVsockPort());
        }

        /**
         * Starts the read-only 9p2000.L server over the share data channel in
         * one daemon thread. The returned server stays alive until the
         * channel closes (guest shutdown) or {@link #closeChannels()} runs.
         *
         * @return the started server (for lifecycle observation and tests)
         */
        public Avf9pShareServer startShareServer() throws AvfEngineException {
            synchronized (shareLock) {
                // One owner per session: a retry after an UNCONFIRMED stop must
                // never start a second 9p server (it would double-serve the
                // share and leak a thread/channel).
                if (channelsClosed) throw closedFailure();
                if (shareServer != null) return shareServer;
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
                shareThread = thread;
                thread.start();
                shareServer = server;
                return server;
            }
        }

        /** True while the one 9p server thread is still serving this session. */
        public boolean isShareServing() {
            Thread thread = shareThread;
            return thread != null && thread.isAlive();
        }

        /** Launch specification that started this session. */
        public AvfLaunchSpec spec() {
            return spec;
        }

        /**
         * The graphics data-plane vsock channel (port 9103, retry-connected).
         */
        public AvfVirtualMachineApi.AvfVsockChannel graphicsChannel() throws AvfEngineException {
            return lazyChannel(spec.graphicsVsockPort());
        }

        /** Blocks until the guest exits; exit observation is best-effort. */
        public int waitFor() throws InterruptedException {
            return waitForExit().exitCode();
        }

        /**
         * Typed exit observation. A host-requested stop is its own kind; a
         * crash and a DELETED VM are non-zero; a stop that was never confirmed
         * is a typed failure, not a normal exit.
         */
        public AvfExitInfo waitForExit() throws InterruptedException {
            AvfExitInfo info = handle.waitForExit();
            if (info.kind() == AvfExitInfo.Kind.CRASHED
                    && info.exitCode() == AvfNetworkFailure.NETWORK_BACKEND_EXIT_STATUS
                    && spec.networkPlan().enabled()
                    && info.elapsedMs() >= 0L
                    && info.elapsedMs() < AvfNetworkFailure.EARLY_STOP_WINDOW_MS) {
                // crosvm_minimal has no net feature: a network-enabled VM that
                // exits 35 immediately is a backend failure, never a normal exit.
                info = AvfExitInfo.networkBackendUnavailable(info.exitCode(), info.elapsedMs());
            }
            if (info.kind() == AvfExitInfo.Kind.STOP_REQUESTED && stopResult == AvfStopResult.UNCONFIRMED) {
                throw new AvfEngineException(AvfEngineException.VM_STOP_UNCONFIRMED,
                        "VM stop was not confirmed by the platform");
            }
            trace.mark(AvfLaunchTrace.Stage.EXIT_OBSERVED);
            return info;
        }

        /** True while the platform still reports RUNNING or status is unknown. */
        public boolean isRuntimeAlive() {
            int status = handle.status();
            if (status == 1) return true;
            if (status == 0 || status == 2) return false;
            return stopResult != AvfStopResult.CONFIRMED && stopResult != AvfStopResult.ALREADY_STOPPED;
        }

        /**
         * Bounded, idempotent owner stop. The first call owns the platform
         * request; later calls return the settled result without a second
         * platform stop, while an UNCONFIRMED result may be retried by the same
         * owner. Channels are closed before the platform call so a blocked
         * vsock read cannot delay it.
         */
        public AvfStopResult stopRuntime(long graceMs) {
            synchronized (stopLock) {
                if (stopSettled) return stopResult;
                if (stopping) {
                    // A second caller joins the in-flight owner instead of issuing
                    // a parallel platform stop.
                    long deadline = System.nanoTime()
                            + (Math.max(0L, graceMs) + 1_000L) * 1_000_000L;
                    while (stopping) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0L) break;
                        try {
                            stopLock.wait(Math.max(1L,
                                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    return stopResult == null ? AvfStopResult.UNCONFIRMED : stopResult;
                }
                if (!stopRequested) {
                    stopRequested = true;
                    trace.mark(AvfLaunchTrace.Stage.STOP_REQUESTED);
                }
                stopping = true;
            }

            CloseBatch closing = claimClose();
            rejectPending(closing);
            AvfStopResult result;
            try {
                result = handle.stopRuntime(Math.max(0L, graceMs));
            } catch (RuntimeException failure) {
                // A throwing platform call is not evidence that the VM exited.
                result = AvfStopResult.UNCONFIRMED;
            }
            try {
                closeAll(closing.resources());
            } catch (IOException ignored) {
                // Best-effort channel teardown; the platform stop already ran.
            }
            synchronized (stopLock) {
                stopResult = result;
                stopSettled = result != AvfStopResult.UNCONFIRMED;
                stopping = false;
                stopLock.notifyAll();
            }
            trace.mark(result == AvfStopResult.UNCONFIRMED
                    ? AvfLaunchTrace.Stage.STOP_UNCONFIRMED
                    : AvfLaunchTrace.Stage.STOP_CONFIRMED);
            return result;
        }

        /** Best-effort compatibility stop: bounded but with no grace window. */
        public void stop() {
            stopRuntime(0L);
        }

        private AvfVirtualMachineApi.AvfVsockChannel lazyChannel(int port) {
            java.util.concurrent.CompletableFuture<AvfVirtualMachineApi.AvfVsockChannel> pending;
            boolean connector;
            synchronized (this) {
                ensureOpen();
                pending = lazyChannels.get(port);
                connector = pending == null;
                if (connector) {
                    pending = new java.util.concurrent.CompletableFuture<>();
                    lazyChannels.put(port, pending);
                }
            }
            if (connector) {
                try {
                    AvfVirtualMachineApi.AvfVsockChannel channel = connectWithRetry(port);
                    // Closing can reject pending while the API call is still blocked.
                    if (!pending.complete(channel)) channel.close();
                } catch (RuntimeException | IOException failure) {
                    pending.completeExceptionally(failure);
                    synchronized (this) { lazyChannels.remove(port, pending); }
                }
            }
            try {
                AvfVirtualMachineApi.AvfVsockChannel channel = pending.get();
                ensureOpen();
                return channel;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AvfEngineException(AvfEngineException.VSOCK_CONNECT_FAILED,
                        "interrupted while waiting for vsock port " + port, failure);
            } catch (java.util.concurrent.ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof AvfEngineException engineFailure) throw engineFailure;
                throw new AvfEngineException(AvfEngineException.VSOCK_CONNECT_FAILED,
                        "vsock port connection failed", cause);
            }
        }

        private AvfVirtualMachineApi.AvfVsockChannel connectWithRetry(int port)
                throws AvfEngineException {
            if (port < 1024 || port > 65535) {
                throw new AvfEngineException(AvfEngineException.VSOCK_CONNECT_FAILED,
                        "vsock port outside launch contract");
            }
            AvfEngineException last = null;
            for (int attempt = 0; attempt < connectAttempts; attempt++) {
                ensureOpen();
                if (Thread.currentThread().isInterrupted()) {
                    throw new AvfEngineException(AvfEngineException.VSOCK_CONNECT_FAILED,
                            "interrupted while connecting vsock port " + port);
                }
                AvfVirtualMachineApi.AvfVsockChannel raw;
                try {
                    // Official connectVsock can block. No session monitor is held here.
                    raw = api.connectVsock(handle, port);
                    if (raw == null) throw new AvfEngineException(AvfEngineException.VSOCK_CONNECT_FAILED,
                            "vsock adapter returned no channel");
                } catch (AvfEngineException failure) {
                    ensureOpen();
                    if (!AvfEngineException.VSOCK_CONNECT_FAILED.equals(failure.reason())) throw failure;
                    last = failure;
                    if (attempt + 1 < connectAttempts) awaitRetry(port);
                    continue;
                }
                SessionChannel owned = new SessionChannel(raw);
                synchronized (this) {
                    if (!channelsClosed) {
                        ownedChannels.add(owned);
                        return owned;
                    }
                }
                AvfEngineException closed = closedFailure();
                try { owned.close(); }
                catch (IOException | RuntimeException cleanup) { closed.addSuppressed(cleanup); }
                throw closed;
            }
            throw new AvfEngineException(last.reason(),
                    "vsock port " + port + " unreachable after " + connectAttempts + " attempts", last);
        }

        private void awaitRetry(int port) {
            try { closedSignal.await(connectBackoffMs, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AvfEngineException(AvfEngineException.VSOCK_CONNECT_FAILED,
                        "interrupted while connecting vsock port " + port, interrupted);
            }
            ensureOpen();
        }

        private void ensureOpen() {
            if (channelsClosed) throw closedFailure();
        }

        private static AvfEngineException closedFailure() {
            return new AvfEngineException(AvfEngineException.VSOCK_CONNECT_FAILED,
                    "session channels are closed");
        }

        /** Ensures caller close and session teardown do not both close the same descriptor. */
        private final class SessionChannel implements AvfVirtualMachineApi.AvfVsockChannel {
            private final AvfVirtualMachineApi.AvfVsockChannel raw;
            private boolean closed;
            SessionChannel(AvfVirtualMachineApi.AvfVsockChannel raw) { this.raw = raw; }
            public java.io.InputStream inputStream() { return raw.inputStream(); }
            public java.io.OutputStream outputStream() { return raw.outputStream(); }
            public void close() throws IOException {
                synchronized (AvfVmSession.this) {
                    if (closed) return;
                    closed = true;
                    ownedChannels.remove(this);
                }
                raw.close();
            }
        }

        /** Closes any channels opened through this session. */
        public void closeChannels() throws IOException {
            CloseBatch closing = claimClose();
            rejectPending(closing);
            closeAll(closing.resources());
        }

        private synchronized CloseBatch claimClose() {
            if (channelsClosed) return new CloseBatch(java.util.List.of(), java.util.List.of());
            channelsClosed = true;
            closedSignal.countDown();
            java.util.List<AutoCloseable> closing = new java.util.ArrayList<>(ownedChannels);
            java.util.List<java.util.concurrent.CompletableFuture<AvfVirtualMachineApi.AvfVsockChannel>>
                    pending = new java.util.ArrayList<>(lazyChannels.values());
            ownedChannels.clear();
            lazyChannels.clear();
            return new CloseBatch(closing, pending);
        }

        private record CloseBatch(java.util.List<AutoCloseable> resources,
                java.util.List<java.util.concurrent.CompletableFuture<AvfVirtualMachineApi.AvfVsockChannel>> pending) {}

        private static void rejectPending(CloseBatch closing) {
            for (var future : closing.pending()) future.completeExceptionally(closedFailure());
        }

        private static void closeAll(java.util.List<AutoCloseable> closing) throws IOException {
            IOException first = null;
            for (AutoCloseable channel : closing) {
                try { channel.close(); }
                catch (Exception failure) {
                    IOException io = failure instanceof IOException value ? value
                            : new IOException("AVF channel cleanup failed", failure);
                    if (first == null) first = io;
                    else if (first != io) first.addSuppressed(io);
                }
            }
            if (first != null) throw first;
        }
    }
}
