package io.github.nyzeep.orchestella;

import android.content.Context;
import android.net.LocalSocket;
import android.util.Log;
import io.github.nyzeep.orchestella.RuntimeProcessSupervisor.StopResult;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.SessionCapabilities;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuLaunchSpec;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest;
import io.github.nyzeep.orchestella.runtime.engineqemu.VirglJniBridge;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts one instance's packaged QEMU from {@code nativeLibraryDir} with the
 * fixed argument array (policy section 4: no shell, no writable-directory
 * execution), wires the exit watcher to the normal-guest-stop transition, and
 * registers the neutral session carrying both abstract-socket channels
 * (bridge control + terminal) for {@link TerminalActivity}.
 */
public final class QemuInstanceLauncher {
    /** Live QEMU session exposing only the strategy-neutral surface. */
    public static final class Session implements ExecutionSession, RuntimeOwnedSession {
        private final String controlSocketName;
        private final String terminalSocketName;
        private final String instanceId;
        private final QemuProcessLauncher.ProcessHandle handle;
        private final RuntimeProcessSupervisor supervisor;
        private final LaunchTimeline timeline;
        private final BridgeConnector connector = new BridgeConnector();
        private final Object controlLock = new Object();
        private final Object terminalLock = new Object();
        private LocalSocket controlSocket;
        private boolean controlClosed;
        private LocalSocket terminalSocket;
        private BridgeClient bridgeClient;
        private volatile BridgeControl.Clipboard clipboardBridge;
        private final String proxySocketName;
        private LocalSocket proxySocket;
        private io.github.nyzeep.orchestella.runtime.network.BridgeProxyGateway proxyGateway;
        private final RfbDisplaySession displayChannel;
        private final AtomicBoolean isBridgeServing = new AtomicBoolean(false);
        private final java.util.Set<SessionServiceForward> serviceForwards =
                new java.util.concurrent.CopyOnWriteArraySet<>();

        /**
         * One live forward. {@link #close()} keeps the API contract for a single
         * user-visible forward (it must unexpose); {@link #releaseForStop()} is the
         * stop fast path, where the gateway process exit closes the listener and a
         * remote round-trip per forward would only burn the stop deadline.
         */
        private final class SessionServiceForward implements ExecutionSession.ServiceForward {
            private final io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule rule;
            private final int hostPort;
            private final PortForwardingPresenter.LiveEngine engine;
            private final AtomicBoolean active = new AtomicBoolean(true);

            SessionServiceForward(io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule rule,
                    int hostPort, PortForwardingPresenter.LiveEngine engine) {
                this.rule = rule;
                this.hostPort = hostPort;
                this.engine = engine;
            }

            @Override public int hostPort() { return hostPort; }

            @Override public boolean isActive() {
                return active.get() && engine.isRunning() && engine.isApplied(rule);
            }

            @Override public void close() throws IOException {
                if (!active.get()) return;
                if (engine.isRunning()) engine.removeRule(rule);
                active.set(false);
                serviceForwards.remove(this);
            }

            /**
             * Stops tracking the forward without pretending it is released: the
             * listener remains owned by gvproxy until the gateway process exits.
             * {@link #isActive()} already checks gateway liveness, so an
             * UNCONFIRMED stop keeps reporting the truth instead of a false
             * "inactive" state.
             */
            void releaseForStop() {
                serviceForwards.remove(this);
            }
        }

        Session(String controlSocketName, String terminalSocketName,
                QemuProcessLauncher.ProcessHandle handle) {
            this(controlSocketName, terminalSocketName,
                    QemuLaunchSpec.defaultProxySocketName(controlSocketName), handle);
        }

        Session(String controlSocketName, String terminalSocketName, String proxySocketName,
                QemuProcessLauncher.ProcessHandle handle) {
            this(controlSocketName, terminalSocketName, proxySocketName, null, null, handle, null);
        }

        Session(String controlSocketName, String terminalSocketName,
                QemuProcessLauncher.ProcessHandle handle, String instanceId) {
            this(controlSocketName, terminalSocketName,
                    QemuLaunchSpec.defaultProxySocketName(controlSocketName), null, null, handle, instanceId);
        }

        Session(String controlSocketName, String terminalSocketName, String proxySocketName,
                QemuProcessLauncher.ProcessHandle handle, String instanceId) {
            this(controlSocketName, terminalSocketName, proxySocketName, null, null, handle, instanceId);
        }

        Session(String controlSocketName, String terminalSocketName, String proxySocketName,
                String displaySocketPath, String token, QemuProcessLauncher.ProcessHandle handle) {
            this(controlSocketName, terminalSocketName, proxySocketName, displaySocketPath, token, handle, null);
        }

        Session(String controlSocketName, String terminalSocketName, String proxySocketName,
                String displaySocketPath, String token, QemuProcessLauncher.ProcessHandle handle,
                String instanceId, NetworkGatewayDaemon.Running gateway) {
            this(controlSocketName, terminalSocketName, proxySocketName, displaySocketPath, token,
                    handle, instanceId, (RuntimeGateway) gateway, null);
        }

        Session(String controlSocketName, String terminalSocketName, String proxySocketName,
                String displaySocketPath, String token, QemuProcessLauncher.ProcessHandle handle,
                String instanceId, NetworkGatewayDaemon.Running gateway, LaunchTimeline timeline) {
            this(controlSocketName, terminalSocketName, proxySocketName, displaySocketPath, token,
                    handle, instanceId, (RuntimeGateway) gateway, timeline);
        }

        private Session(String controlSocketName, String terminalSocketName, String proxySocketName,
                String displaySocketPath, String token, QemuProcessLauncher.ProcessHandle handle,
                String instanceId, RuntimeGateway gateway) {
            this(controlSocketName, terminalSocketName, proxySocketName, displaySocketPath, token,
                    handle, instanceId, gateway, null);
        }

        private Session(String controlSocketName, String terminalSocketName, String proxySocketName,
                String displaySocketPath, String token, QemuProcessLauncher.ProcessHandle handle,
                String instanceId, RuntimeGateway gateway, LaunchTimeline timeline) {
            this.controlSocketName = controlSocketName;
            this.terminalSocketName = terminalSocketName;
            this.instanceId = instanceId;
            this.proxySocketName = proxySocketName;
            this.handle = handle;
            this.timeline = timeline;
            this.supervisor = new RuntimeProcessSupervisor(handle, gateway, this::closeChannels);
            this.displayChannel = displaySocketPath == null ? null : new RfbDisplaySession(() ->
                    openDisplayStreams(displaySocketPath), token, this::bridgeClient);
            startProxyGateway();
        }

        private static final long DISPLAY_CONNECT_TIMEOUT_MS = 15_000L;
        private static final long CLOSE_GRACE_MS = 1000L;
        private static final long TERMINATE_GRACE_MS = 3000L;
        private static final long EXIT_GRACE_MS = 3000L;
        private static final java.util.concurrent.ExecutorService DISPLAY_CONNECT_EXECUTOR =
                new java.util.concurrent.ThreadPoolExecutor(0, 2, 30L, java.util.concurrent.TimeUnit.SECONDS,
                        new java.util.concurrent.SynchronousQueue<>(), runnable -> {
                            Thread thread = new Thread(runnable, "qemu-display-connect");
                            thread.setDaemon(true);
                            return thread;
                        });

        Session(String controlSocketName, String terminalSocketName, String proxySocketName,
                String displaySocketPath, String token, QemuProcessLauncher.ProcessHandle handle, String instanceId) {
            this(controlSocketName, terminalSocketName, proxySocketName, displaySocketPath, token,
                    handle, instanceId, (RuntimeGateway) null);
        }

        private static io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel.Streams
                openDisplayStreams(String displaySocketPath) throws IOException {
            LocalSocket socket = new LocalSocket();
            InputStream input;
            OutputStream output;
            try {
                input = socket.getInputStream();
                output = socket.getOutputStream();
            } catch (IOException | RuntimeException failure) {
                closeQuietly(socket);
                throw failure;
            }
            return QemuInstanceLauncher.openDisplayStreams(new DisplaySocket() {
                @Override public void connect() throws IOException {
                    socket.connect(new android.net.LocalSocketAddress(displaySocketPath,
                            android.net.LocalSocketAddress.Namespace.FILESYSTEM));
                }
                @Override public InputStream input() { return input; }
                @Override public OutputStream output() { return output; }
                @Override public void close() throws IOException { socket.close(); }
            }, DISPLAY_CONNECT_EXECUTOR, DISPLAY_CONNECT_TIMEOUT_MS);
        }

        @Override public boolean connectDisplay(boolean keyboard, boolean unicodeText, boolean pointer) {
            return displayChannel != null && displayChannel.connect(
                    new io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel.InputCapabilities(
                            keyboard, unicodeText, pointer));
        }

        @Override public boolean reconnectDisplay(boolean keyboard, boolean unicodeText, boolean pointer) {
            return displayChannel != null && displayChannel.connect(
                    new io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel.InputCapabilities(
                            keyboard, unicodeText, pointer), true);
        }

        @Override public String displayStatus() {
            return displayChannel == null ? "显示数据面未配置" : displayChannel.message();
        }

        private volatile boolean closing;

        @Override public synchronized ServiceForward openServiceForward(int guestPort) throws IOException {
            if (closing) throw new IOException("session is closing");
            if (guestPort != 18080) throw new IOException("service port is not approved");
            if (instanceId == null) throw new IOException("service owner is unavailable");
            PortForwardingPresenter.LiveEngine engine = NetworkGatewayDaemon.forwardingEngine(instanceId);
            if (engine == null || !engine.isRunning()) throw new IOException("gateway forwarding is unavailable");
            int port;
            try (java.net.ServerSocket candidate = new java.net.ServerSocket(0, 1,
                    java.net.InetAddress.getByName("127.0.0.1"))) { port = candidate.getLocalPort(); }
            io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule rule =
                    io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule.defaultRule(
                            java.util.UUID.randomUUID().toString(),
                            io.github.nyzeep.orchestella.runtime.contract.Protocol.TCP, port, guestPort);
            SessionServiceForward forward = new SessionServiceForward(rule, port, engine);
            serviceForwards.add(forward);
            try {
                engine.addRule(rule);
                if (!engine.isApplied(rule)) throw new IOException("service forwarding was not confirmed");
                return forward;
            } catch (IOException failure) {
                try { forward.close(); } catch (IOException removal) {
                    failure.addSuppressed(removal);
                    failure = new IOException("本地转发撤销未确认，请停止实例以关闭监听", failure);
                }
                throw failure;
            }
        }

        /** Package-visible for stop-path tests: forwards still tracked by this session. */
        int trackedServiceForwardCount() {
            return serviceForwards.size();
        }

        private void startProxyGateway() {
            if (proxySocketName == null) return;
            Thread proxyThread = new Thread(() -> {
                try {
                    LocalSocket socket = connect(proxySocketName);
                    synchronized (Session.this) {
                        if (closing) {
                            closeQuietly(socket);
                            return;
                        }
                        this.proxySocket = socket;
                        this.proxyGateway = new io.github.nyzeep.orchestella.runtime.network.BridgeProxyGateway(
                                socket.getInputStream(), socket.getOutputStream());
                        this.proxyGateway.startAsync();
                    }
                    Log.i("OrchestellaQemu", "Virtio-serial netproxy gateway active on " + proxySocketName);
                } catch (Exception e) {
                    Log.w("OrchestellaQemu", "Could not connect to netproxy socket: " + e.getMessage());
                }
            }, "orchestella-proxy-gateway-" + controlSocketName);
            proxyThread.setDaemon(true);
            proxyThread.start();
        }

        @Override
        public int waitFor() throws InterruptedException {
            return handle.waitFor();
        }

        @Override
        public void terminate() throws IOException {
            if (stopRuntime(TERMINATE_GRACE_MS) == StopResult.UNCONFIRMED) {
                throw new IOException("virtual machine stop was not confirmed");
            }
        }

        @Override
        public StopResult stopRuntime(long graceMs) {
            synchronized (this) { closing = true; }
            if (timeline != null) timeline.mark(LaunchTimeline.Stage.STOP_REQUESTED);
            StopResult stop;
            try {
                stop = supervisor.stop(graceMs);
            } catch (RuntimeException | Error failure) {
                if (timeline != null) timeline.finish();
                throw failure;
            }
            if (timeline != null) {
                timeline.markStopConfirmed(stop);
                timeline.finish();
            }
            if (stop == StopResult.UNCONFIRMED) {
                RuntimeProcessSupervisor.Diagnostics diagnostics = supervisor.diagnostics();
                Log.w(TAG, "runtime-stop-unconfirmed stage=" + diagnostics.stage()
                        + " pid=" + diagnostics.pid() + " forced=" + diagnostics.forced()
                        + " graceMs=" + diagnostics.graceMs()
                        + " exitCode=" + diagnostics.exitCode());
            }
            return stop;
        }

        @Override
        public boolean isRuntimeAlive() {
            return supervisor.isRuntimeAlive();
        }

        /** The watcher and every pool path use the same idempotent supervisor stop. */
        StopResult confirmRuntimeStopped() {
            return stopRuntime(EXIT_GRACE_MS);
        }

        @Override
        public OutputStream terminalInput() throws IOException {
            return terminal().getOutputStream();
        }

        @Override
        public InputStream terminalOutput() throws IOException {
            return terminal().getInputStream();
        }

        @Override
        public OutputStream controlInput() throws IOException {
            synchronized (controlLock) {
                return control().getOutputStream();
            }
        }

        @Override
        public InputStream controlOutput() throws IOException {
            synchronized (controlLock) {
                return control().getInputStream();
            }
        }

        @Override
        public void close() {
            stopRuntime(CLOSE_GRACE_MS);
        }

        /** Closes host channels exactly once; the supervisor owns when this runs. */
        private void closeChannels() {
            closing = true;
            // Stop fast path: release forwards locally. The gvproxy process is stopped
            // immediately afterwards and its exit closes every listener; issuing
            // refresh/unexpose/refresh per forward here would cost up to 4.5 s each.
            for (SessionServiceForward forward : serviceForwards) {
                forward.releaseForStop();
            }
            serviceForwards.clear();
            if (displayChannel != null) {
                displayChannel.close();
            }
            if (proxyGateway != null) {
                proxyGateway.close();
                proxyGateway = null;
            }
            if (proxySocket != null) {
                closeQuietly(proxySocket);
                proxySocket = null;
            }
            synchronized (controlLock) {
                controlClosed = true;
                if (controlSocket != null) {
                    closeQuietly(controlSocket);
                    controlSocket = null;
                }
            }
            synchronized (terminalLock) {
                if (terminalSocket != null) {
                    closeQuietly(terminalSocket);
                    terminalSocket = null;
                }
            }
        }

        @Override
        public io.github.nyzeep.orchestella.runtime.contract.DisplayControl displayControl() {
            return displayChannel;
        }

        @Override
        public io.github.nyzeep.orchestella.runtime.contract.WaylandChannel waylandChannel() {
            return displayChannel;
        }

        /**
         * Ticket 04: the QEMU matrix is projected from the display and forwards
         * this session actually constructed. It is never the software baseline
         * (which would read a live RFB desktop as having no graphics).
         *
         * <p>Ticket 10 boundary: audio playback, clipboard bridging and Android
         * pointer capture are driven by {@code DesktopActivity}/UI, not by this
         * session, so they stay absent here until a session-owned adapter exists.
         */
        @Override
        public FullLinuxContract.CapabilityMatrix capabilityMatrix() {
            return FullLinuxContract.CapabilityMatrix.from(
                    SessionCapabilities.builder(
                                    FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION)
                            .ownership(SessionCapabilities.Ownership.RUNTIME_OWNED)
                            .terminal(true)
                            .bridge(true)
                            .display(displayChannel == null
                                    ? SessionCapabilities.DisplayTransport.NONE
                                    : SessionCapabilities.DisplayTransport.RFB)
                            .serviceForward(!serviceForwards.isEmpty())
                            .build());
        }

        @Override
        public BridgeClient bridgeClient() {
            synchronized (controlLock) {
                if (controlClosed) return null;
                if (bridgeClient == null) {
                    try {
                        bridgeClient = new BridgeClient(controlOutput(), controlInput());
                    } catch (IOException e) {
                        return null;
                    }
                }
                return bridgeClient;
            }
        }

        @Override
        public boolean recoverControlChannel() {
            BridgeClient failedClient;
            synchronized (controlLock) {
                failedClient = bridgeClient;
                if (controlClosed || failedClient == null
                        || !"failed".equals(failedClient.readinessState()) || !hasLiveTerminal()) {
                    return false;
                }
            }
            // Never hold a session lock while joining the old reader. The
            // failed transport must be fully retired before another can read.
            try {
                if (!failedClient.awaitDisconnectedReader(5000L)) return false;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            synchronized (controlLock) {
                if (controlClosed || bridgeClient != failedClient
                        || !"failed".equals(failedClient.readinessState()) || !hasLiveTerminal()) {
                    return false;
                }
                if (controlSocket != null) {
                    try {
                        controlSocket.close();
                    } catch (IOException unavailable) {
                        return false;
                    }
                    controlSocket = null;
                }
                bridgeClient = null;
                isBridgeServing.set(false);
                return true;
            }
        }

        private boolean hasLiveTerminal() {
            PersistentTerminalSessionPool.SessionHolder holder =
                    PersistentTerminalSessionPool.getInstance().getHolder(instanceId);
            return holder != null && holder.rawSession() == this && holder.isAlive();
        }

        @Override
        public void registerClipboardBridge(BridgeControl.Clipboard clipboard) {
            this.clipboardBridge = clipboard;
        }

        @Override
        public BridgeControl.Clipboard clipboardBridge() {
            return this.clipboardBridge;
        }

        @Override
        public boolean markBridgeServing() {
            synchronized (controlLock) {
                if (controlClosed || (bridgeClient != null
                        && "failed".equals(bridgeClient.readinessState()))) return false;
                return isBridgeServing.compareAndSet(false, true);
            }
        }


        private LocalSocket control() throws IOException {
            synchronized (controlLock) {
                if (controlClosed) throw new IOException("Control channel is closed");
                if (controlSocket == null) {
                    controlSocket = connect(controlSocketName);
                }
                return controlSocket;
            }
        }

        private LocalSocket terminal() throws IOException {
            synchronized (terminalLock) {
                if (terminalSocket == null) {
                    terminalSocket = connect(terminalSocketName);
                }
                return terminalSocket;
            }
        }

        private LocalSocket connect(String socketName) throws IOException {
            try {
                return connector.connectTerminal(socketName);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while connecting " + socketName, interrupted);
            }
        }

        private static void closeQuietly(LocalSocket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // channel cleanup is best-effort at teardown
            }
        }
    }

    private static final long INSTANCE_MEMORY_MB = 512;
    private static final String TAG = "OrchestellaQemu";
    /**
     * Cleanup outcome of the most recent failed launch for an instance. The
     * caller needs it to separate "definitely gone" from "stop unconfirmed"
     * before releasing RUNNING metadata (ADR-0020). Consumed once by
     * {@link #takeLastCleanupResult(String)} and cleared at the next launch.
     */
    private static final java.util.concurrent.ConcurrentMap<String, StopResult> LAST_FAILED_CLEANUP =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public QemuInstanceLauncher(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Consumes the cleanup outcome of the last failed launch for this instance,
     * or null when this launch did not fail after starting QEMU.
     */
    public static StopResult takeLastCleanupResult(String instanceId) {
        return instanceId == null ? null : LAST_FAILED_CLEANUP.remove(instanceId);
    }

    /** Blocking bootstrap+launch; call on a worker thread. */
    public ExecutionSession launch(InstanceStrategyLauncher.LaunchParameters parameters)
            throws IOException, InterruptedException {
        LAST_FAILED_CLEANUP.remove(parameters.instanceId);
        VmHardwareConfig config = parameters.hardwareConfig != null
                ? parameters.hardwareConfig
                : VmHardwareConfig.defaultConfig();
        LaunchTimeline timeline = LaunchTimeline.open(Paths.get(parameters.runtimeDirectory),
                parameters.instanceId, resolveDistroId(parameters.rootfsFileName),
                config.ramMegabytes(), config.cpuCores());
        timeline.mark(LaunchTimeline.Stage.LAUNCH_START);

        RuntimeArtifactManifest manifest;
        try (InputStream in = PackagedAssetStreams.openAsset(context, RuntimeArtifactManifest.SCHEMA_FILE_NAME)) {
            manifest = RuntimeArtifactManifest.parse(in);
        } catch (IOException | RuntimeException | Error failure) {
            // A launch attempt that cannot even read its manifest must still leave
            // one honest record; it never reaches asset verification.
            timeline.finish();
            throw failure;
        }

        QemuRuntimeBootstrapper bootstrapper = new QemuRuntimeBootstrapper(manifest,
                name -> {
                    try {
                        return PackagedAssetStreams.openAsset(context, name);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                name -> {
                    try {
                        return PackagedAssetStreams.openResource(context, name);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                Paths.get(parameters.runtimeDirectory),
                Paths.get(parameters.distroInputsDirectory));
        // Consume persisted guest evidence, not an in-memory instance: the caller may
        // hold a stale snapshot, and only the store's current record may unlock the
        // credentials-only path. Missing/ambiguous evidence stays on the full path.
        boolean credentialsOnly = credentialsOnlyLaunch(
                context.getFilesDir().toPath().resolve("instances"),
                parameters.instanceId, parameters.rootfsFileName);
        QemuRuntimeBootstrapper.RuntimePaths paths;
        try {
            paths = bootstrapper.bootstrap(parameters.rootfsFileName, parameters.bridgeToken,
                    parameters.guestPassword,
                    Paths.get(parameters.instanceRootVolume),
                    parameters.mirrorConfigFileName,
                    parameters.mirrorConfigFileContent,
                    parameters.firstBootScript,
                    parameters.hostname, parameters.guestUser, parameters.hasSudo, timeline,
                    credentialsOnly);
        } catch (IOException | RuntimeException | Error failure) {
            timeline.finish();
            throw failure;
        }

        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        String controlSocket = "droidlayer-control-" + parameters.instanceId;
        String terminalSocket = "droidlayer-terminal-" + parameters.instanceId;

        // Rootless network plane: gvproxy bridges the guest onto the host stack (the
        // packaged QEMU has no slirp; passt is blocked by Android SELinux). Guest
        // addressing rides the kernel command line so distros without a DHCP client
        // still come up deterministically.
        NetworkGatewayDaemon.Running gateway;
        try {
            gateway = NetworkGatewayDaemon.start(context, parameters.instanceId,
                    Paths.get(parameters.runtimeDirectory).toFile());
        } catch (IOException | RuntimeException | Error failure) {
            timeline.finish();
            throw failure;
        }
        timeline.mark(LaunchTimeline.Stage.GATEWAY_READY);
        java.util.concurrent.atomic.AtomicReference<QemuProcessLauncher.ProcessHandle> startingHandle =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Session> startingSession =
                new java.util.concurrent.atomic.AtomicReference<>();
        return completeLaunch(() -> {
        String guestKernelArgs = "orchestella.guest_ip=" + NetworkGatewayDaemon.GUEST_IP
                + " orchestella.guest_gw=" + NetworkGatewayDaemon.GUEST_GATEWAY
                + " orchestella.guest_mask=" + NetworkGatewayDaemon.GUEST_NETMASK
                + " orchestella.guest_dns=" + NetworkGatewayDaemon.guestDnsFor(context)
                + " orchestella.proxy_enabled=0";

        Log.i(TAG, "Packaged renderer: virtio-gpu 2D; guest GPU acceleration is unverified");
        java.nio.file.Path displayDirectory = context.getFilesDir().toPath().resolve("display");
        java.nio.file.Files.createDirectories(displayDirectory);
        String displaySocketPath = displayDirectory.resolve(
                java.util.UUID.nameUUIDFromBytes(parameters.instanceId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString().substring(0, 16) + ".sock").toString();

        String[] spec = QemuLaunchSpec.buildWithPasst(
                Paths.get(nativeLibraryDir, "libdroidlayer_qemu.so").toString(),
                paths.kernel.toString(),
                paths.initramfs.toString(),
                parameters.instanceRootVolume,
                controlSocket,
                terminalSocket,
                config,
                String.valueOf(gateway.tcpPort),
                guestKernelArgs);

        String[] sanitizedSpec = QemuLaunchSpec.withDisplayChannel(sanitizeSpecForTargetBinary(spec), displaySocketPath);
        Log.i(TAG, "Launching QEMU process with sanitized arguments");

        QemuProcessLauncher.ProcessFactory factory = currentSpec -> {
            Process process = new ProcessBuilder(currentSpec).redirectErrorStream(true).start();
            Thread logger = new Thread(() -> {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d("OrchestellaQemu", line);
                    }
                } catch (IOException ignored) {
                }
            }, "qemu-log-" + parameters.instanceId);
            logger.setDaemon(true);
            logger.start();

            return new QemuProcessLauncher.ProcessHandle() {
                @Override
                public int waitFor() throws InterruptedException {
                    return process.waitFor();
                }

                @Override
                public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
                    return process.waitFor(timeout, unit);
                }

                @Override
                public void destroy() {
                    process.destroy();
                }

                @Override
                public void destroyForcibly() {
                    process.destroyForcibly();
                }

                @Override
                public boolean isAlive() {
                    return process.isAlive();
                }

                @Override
                public long pid() {
                    // android.jar's java.lang.Process does not expose pid(); diagnostics stay optional.
                    return -1L;
                }
            };
        };

        QemuProcessLauncher launcher = new QemuProcessLauncher(factory);
        QemuProcessLauncher.Launched launched = launcher.launch(sanitizedSpec);
        timeline.mark(LaunchTimeline.Stage.QEMU_STARTED);
        QemuProcessLauncher.ProcessHandle handle = launched.handle();
        startingHandle.set(handle);
        Session session = new Session(controlSocket, terminalSocket,
                QemuLaunchSpec.defaultProxySocketName(controlSocket), displaySocketPath,
                parameters.bridgeToken, handle, parameters.instanceId, gateway, timeline);
        startingSession.set(session);
        Thread watcher = new Thread(() -> {
            try {
                handle.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                VirglJniBridge.cleanup();
                session.confirmRuntimeStopped();
            }
        }, "qemu-exit-" + parameters.instanceId);
        watcher.setDaemon(true);
        watcher.start();
        ExecutionSessionRegistry.register(parameters.instanceId, session,
                createProvisioningRecorder(parameters.instanceId, parameters.rootfsFileName));
        timeline.mark(LaunchTimeline.Stage.SESSION_REGISTERED);
        return session;
        }, () -> {
            StopResult cleanupResult = cleanupFailedLaunch(
                    startingSession.get(), startingHandle.get(), gateway);
            LAST_FAILED_CLEANUP.put(parameters.instanceId, cleanupResult);
            timeline.finish();
        });
    }

    /** Non-secret distro id for telemetry; unknown or ambiguous filenames stay honest. */
    static String resolveDistroId(String rootfsFileName) {
        if (rootfsFileName == null || rootfsFileName.isEmpty()) return "unknown";
        String matched = null;
        for (DistroManifest manifest : DistroCatalogRegistry.createDefault().listAll()) {
            if (!rootfsFileName.equals(manifest.rootfsFileName())) continue;
            if (matched != null && !matched.equals(manifest.id())) return "unknown";
            matched = manifest.id();
        }
        return matched == null ? "unknown" : matched;
    }

    /**
     * Failure cleanup shared by launch bootstrap, Session construction and
     * registration. Returns the bounded outcome so the caller can keep RUNNING
     * metadata when the runtime could not be confirmed stopped.
     */
    static StopResult cleanupFailedLaunch(RuntimeOwnedSession session,
            QemuProcessLauncher.ProcessHandle handle, RuntimeGateway gateway) {
        if (session != null) {
            try {
                StopResult result = session.stopRuntime(2000L);
                if (result == StopResult.UNCONFIRMED) {
                    Log.w(TAG, "launch-cleanup-stop-unconfirmed");
                }
                return result;
            } catch (RuntimeException failure) {
                // A throwing stop is not evidence that the process exited.
                Log.w(TAG, "launch-cleanup-stop-failed");
                return StopResult.UNCONFIRMED;
            }
        }
        boolean processStopped;
        try {
            processStopped = stopProcessBounded(handle);
        } catch (RuntimeException failure) {
            processStopped = false;
        }
        boolean gatewayStopped;
        try {
            gatewayStopped = gateway == null || gateway.stop(2000L);
        } catch (RuntimeException failure) {
            gatewayStopped = false;
        }
        if (!processStopped || !gatewayStopped) {
            Log.w(TAG, "launch-cleanup-unconfirmed process=" + processStopped
                    + " gateway=" + gatewayStopped);
            return StopResult.UNCONFIRMED;
        }
        return StopResult.CONFIRMED;
    }

    /** Bounded fallback for a launch that failed before a Session existed. */
    private static boolean stopProcessBounded(QemuProcessLauncher.ProcessHandle handle) {
        if (handle == null) return true;
        try {
            if (!handle.isAlive()) return true;
            handle.destroy();
            if (!handle.waitFor(1000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                handle.destroyForcibly();
                return handle.waitFor(2000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                        || !handle.isAlive();
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return !handle.isAlive();
        }
    }

    /**
     * Resolves the pinned distro rootfs digest from the catalog by exact file name.
     *
     * <p>Unknown or ambiguous names resolve to {@code null}; the caller must then skip recording
     * rather than guess a digest.
     */
    static String resolveRootfsSha256(String rootfsFileName) {
        if (rootfsFileName == null || rootfsFileName.isEmpty()) {
            return null;
        }
        String resolved = null;
        for (DistroManifest manifest : DistroCatalogRegistry.createDefault().listAll()) {
            if (rootfsFileName.equals(manifest.rootfsFileName())) {
                if (resolved != null && !resolved.equals(manifest.sha256())) {
                    return null;
                }
                resolved = manifest.sha256();
            }
        }
        return resolved;
    }

    /**
     * Whether the freshly loaded instance record proves provisioning from exactly the
     * catalog's current digest. Any load/parse failure is a conservative false.
     */
    static boolean isProvisionedForInstance(
            java.nio.file.Path instancesDirectory, String instanceId, String rootfsSha256) {
        if (instancesDirectory == null || instanceId == null || instanceId.isEmpty()
                || rootfsSha256 == null) {
            return false;
        }
        try {
            InstanceLifecycle.Instance instance =
                    new InstanceLifecycle.FileStore(instancesDirectory).load(instanceId);
            return instance.isProvisionedFor(rootfsSha256);
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    /** Resolves the catalog digest for the launch filename, then checks persisted evidence. */
    static boolean credentialsOnlyLaunch(
            java.nio.file.Path instancesDirectory, String instanceId, String rootfsFileName) {
        String rootfsSha256 = resolveRootfsSha256(rootfsFileName);
        return rootfsSha256 != null
                && isProvisionedForInstance(instancesDirectory, instanceId, rootfsSha256);
    }

    /**
     * Builds the best-effort evidence recorder for one instance.
     *
     * <p>Returns {@code null} when the digest or controller is unavailable, which keeps the
     * instance on the conservative "not provisioned" reading.
     */
    static PersistentTerminalSessionPool.ProvisioningListener provisioningRecorder(
            InstanceLifecycle.Controller controller, String instanceId, String rootfsSha256) {
        if (controller == null || instanceId == null || rootfsSha256 == null) {
            return null;
        }
        return (reportedInstanceId, markerVersion) -> {
            if (!instanceId.equals(reportedInstanceId)) {
                return;
            }
            try {
                controller.recordProvisioning(
                        reportedInstanceId,
                        new InstanceLifecycle.ProvisioningEvidence(
                                markerVersion, rootfsSha256, System.currentTimeMillis()));
            } catch (IOException | RuntimeException failed) {
                // Evidence is advisory: never let metadata persistence break the pump thread.
                Log.w(TAG, "provisioning-record-failed");
            }
        };
    }

    private PersistentTerminalSessionPool.ProvisioningListener createProvisioningRecorder(
            String instanceId, String rootfsFileName) {
        String rootfsSha256 = resolveRootfsSha256(rootfsFileName);
        if (rootfsSha256 == null) {
            Log.w(TAG, "provisioning-evidence-skipped reason=unresolved-rootfs");
            return null;
        }
        try {
            InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(
                    context.getFilesDir().toPath().resolve("instances"));
            return provisioningRecorder(
                    new InstanceLifecycle.Controller(store, Clock.systemUTC()),
                    instanceId, rootfsSha256);
        } catch (IOException unavailable) {
            Log.w(TAG, "provisioning-evidence-skipped reason=store-unavailable");
            return null;
        }
    }

    interface DisplaySocket extends AutoCloseable {
        void connect() throws IOException;
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
        @Override void close() throws IOException;
    }

    static io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel.Streams
            openDisplayStreams(DisplaySocket socket, ExecutorService connections, long timeoutMs)
            throws IOException {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        Runnable closeOwned = () -> {
            if (closed.compareAndSet(false, true)) {
                try { socket.close(); } catch (IOException ignored) { }
            }
        };
        java.util.concurrent.Future<?> connection;
        try {
            connection = connections.submit(() -> {
                try {
                    if (cancelled.get()) throw new IOException("display connection cancelled");
                    socket.connect();
                    if (cancelled.get()) throw new IOException("display connection cancelled");
                    return null;
                } catch (IOException | RuntimeException | Error failure) {
                    closeOwned.run();
                    throw failure;
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            closeOwned.run();
            throw new IOException("display connection capacity unavailable", unavailable);
        }
        try {
            connection.get(Math.max(1L, Math.min(15_000L, timeoutMs)), java.util.concurrent.TimeUnit.MILLISECONDS);
            return new io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel.Streams(
                    socket.input(), socket.output(), closeOwned::run);
        } catch (java.util.concurrent.TimeoutException timeout) {
            cancelled.set(true);
            closeOwned.run();
            connection.cancel(true);
            throw new IOException("display connection timed out", timeout);
        } catch (InterruptedException interrupted) {
            cancelled.set(true);
            closeOwned.run();
            connection.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("display connection interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException failure) {
            closeOwned.run();
            Throwable cause = failure.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("display connection failed", cause);
        } catch (IOException | RuntimeException | Error failure) {
            closeOwned.run();
            throw failure;
        }
    }

    interface LaunchOperation<T> {
        T run() throws IOException, InterruptedException;
    }

    static <T> T completeLaunch(LaunchOperation<T> operation, Runnable cleanup)
            throws IOException, InterruptedException {
        try {
            return operation.run();
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            try { cleanup.run(); } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Adapts abstract hypervisor arguments to the physical arm64 QEMU binary packaged in APK:
     * virtio-gpu-gl-device is bound to virtio-gpu-device in this binary build. The passt
     * -netdev/-device pair is no longer stripped: the passt stream backend is the network plane.
     */
    static String[] sanitizeSpecForTargetBinary(String[] spec) {
        if (spec == null) {
            return new String[0];
        }
        java.util.List<String> list = new java.util.ArrayList<>();
        for (int i = 0; i < spec.length; i++) {
            String arg = spec[i];
            if ("virtio-gpu-gl-device".equals(arg)) {
                // In packaged arm64 binary, virgl acceleration is bound to virtio-gpu-device
                list.add("virtio-gpu-device");
                continue;
            }
            list.add(arg);
        }
        return list.toArray(new String[0]);
    }
}
