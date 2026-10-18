package io.github.nyzeep.orchestella;

import android.content.Context;
import android.net.LocalSocket;
import android.util.Log;
import io.github.nyzeep.orchestella.RuntimeProcessSupervisor.StopResult;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuLaunchSpec;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest;
import io.github.nyzeep.orchestella.runtime.engineqemu.VirglJniBridge;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
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
        private final QemuWaylandChannel waylandChannel;
        private final AtomicBoolean isBridgeServing = new AtomicBoolean(false);
        private final java.util.Set<ExecutionSession.ServiceForward> serviceForwards = new java.util.concurrent.CopyOnWriteArraySet<>();

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
                    handle, instanceId, (RuntimeGateway) gateway);
        }

        private Session(String controlSocketName, String terminalSocketName, String proxySocketName,
                String displaySocketPath, String token, QemuProcessLauncher.ProcessHandle handle,
                String instanceId, RuntimeGateway gateway) {
            this.controlSocketName = controlSocketName;
            this.terminalSocketName = terminalSocketName;
            this.instanceId = instanceId;
            this.proxySocketName = proxySocketName;
            this.handle = handle;
            this.supervisor = new RuntimeProcessSupervisor(handle, gateway, this::closeChannels);
            this.waylandChannel = displaySocketPath == null ? null : new QemuWaylandChannel(() ->
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
            return waylandChannel != null && waylandChannel.connect(
                    new io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel.InputCapabilities(
                            keyboard, unicodeText, pointer));
        }

        @Override public boolean reconnectDisplay(boolean keyboard, boolean unicodeText, boolean pointer) {
            return waylandChannel != null && waylandChannel.connect(
                    new io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel.InputCapabilities(
                            keyboard, unicodeText, pointer), true);
        }

        @Override public String displayStatus() {
            return waylandChannel == null ? "显示数据面未配置" : waylandChannel.message();
        }

        private boolean closing;

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
            ServiceForward forward = new ServiceForward() {
                private final AtomicBoolean active = new AtomicBoolean(true);
                @Override public int hostPort() { return port; }
                @Override public boolean isActive() { return active.get() && engine.isRunning() && engine.isApplied(rule); }
                @Override public void close() throws IOException {
                    if (active.get()) {
                        if (engine.isRunning()) engine.removeRule(rule);
                        active.set(false);
                        serviceForwards.remove(this);
                    }
                }
            };
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
            StopResult stop = supervisor.stop(graceMs);
            if (stop == StopResult.UNCONFIRMED) {
                RuntimeProcessSupervisor.Diagnostics diagnostics = supervisor.diagnostics();
                Log.w(TAG, "runtime-stop-unconfirmed stage=" + diagnostics.stage()
                        + " pid=" + diagnostics.pid() + " forced=" + diagnostics.forced()
                        + " graceMs=" + diagnostics.graceMs());
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
            for (ServiceForward forward : serviceForwards) {
                try { forward.close(); } catch (IOException ignored) { }
            }
            if (waylandChannel != null) {
                waylandChannel.close();
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
            return waylandChannel;
        }

        @Override
        public io.github.nyzeep.orchestella.runtime.contract.WaylandChannel waylandChannel() {
            return waylandChannel;
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

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public QemuInstanceLauncher(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Blocking bootstrap+launch; call on a worker thread. */
    public ExecutionSession launch(InstanceStrategyLauncher.LaunchParameters parameters)
            throws IOException, InterruptedException {
        RuntimeArtifactManifest manifest;
        try (InputStream in = PackagedAssetStreams.openAsset(context, RuntimeArtifactManifest.SCHEMA_FILE_NAME)) {
            manifest = RuntimeArtifactManifest.parse(in);
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
        QemuRuntimeBootstrapper.RuntimePaths paths =
                bootstrapper.bootstrap(parameters.rootfsFileName, parameters.bridgeToken,
                        parameters.guestPassword,
                        Paths.get(parameters.instanceRootVolume),
                        parameters.mirrorConfigFileName,
                        parameters.mirrorConfigFileContent,
                        parameters.firstBootScript,
                        parameters.hostname, parameters.guestUser, parameters.hasSudo);

        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        String controlSocket = "droidlayer-control-" + parameters.instanceId;
        String terminalSocket = "droidlayer-terminal-" + parameters.instanceId;

        VmHardwareConfig config = parameters.hardwareConfig != null
                ? parameters.hardwareConfig
                : VmHardwareConfig.defaultConfig();

        // Rootless network plane: gvproxy bridges the guest onto the host stack (the
        // packaged QEMU has no slirp; passt is blocked by Android SELinux). Guest
        // addressing rides the kernel command line so distros without a DHCP client
        // still come up deterministically.
        NetworkGatewayDaemon.Running gateway =
                NetworkGatewayDaemon.start(context, parameters.instanceId,
                        Paths.get(parameters.runtimeDirectory).toFile());
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
        QemuProcessLauncher.ProcessHandle handle = launched.handle();
        startingHandle.set(handle);
        Session session = new Session(controlSocket, terminalSocket,
                QemuLaunchSpec.defaultProxySocketName(controlSocket), displaySocketPath,
                parameters.bridgeToken, handle, parameters.instanceId, gateway);
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
        ExecutionSessionRegistry.register(parameters.instanceId, session);
        return session;
        }, () -> {
            Session unpublished = startingSession.get();
            if (unpublished != null) {
                unpublished.close();
                return;
            }
            try {
                stopProcessBounded(startingHandle.get());
            } finally {
                gateway.stop(2000L);
            }
        });
    }

    /** Bounded fallback for a launch that failed before a Session existed. */
    private static void stopProcessBounded(QemuProcessLauncher.ProcessHandle handle) {
        if (handle == null) return;
        try {
            if (!handle.isAlive()) return;
            handle.destroy();
            if (!handle.waitFor(1000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                handle.destroyForcibly();
                handle.waitFor(2000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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
