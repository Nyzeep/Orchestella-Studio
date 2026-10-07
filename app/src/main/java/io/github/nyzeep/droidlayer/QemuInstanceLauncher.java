package io.github.nyzeep.droidlayer;

import android.content.Context;
import android.net.LocalSocket;
import android.util.Log;
import io.github.nyzeep.droidlayer.runtime.contract.BridgeClient;
import io.github.nyzeep.droidlayer.runtime.contract.BridgeControl;
import io.github.nyzeep.droidlayer.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.droidlayer.runtime.engineqemu.QemuLaunchSpec;
import io.github.nyzeep.droidlayer.runtime.engineqemu.QemuProcessLauncher;
import io.github.nyzeep.droidlayer.runtime.engineqemu.RuntimeArtifactManifest;
import io.github.nyzeep.droidlayer.runtime.engineqemu.VirglJniBridge;
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
    public static final class Session implements ExecutionSession {
        private final String controlSocketName;
        private final String terminalSocketName;
        private final QemuProcessLauncher.ProcessHandle handle;
        private final BridgeConnector connector = new BridgeConnector();
        private final Object controlLock = new Object();
        private final Object terminalLock = new Object();
        private LocalSocket controlSocket;
        private LocalSocket terminalSocket;
        private BridgeClient bridgeClient;
        private volatile BridgeControl.Clipboard clipboardBridge;
        private final AtomicBoolean isBridgeServing = new AtomicBoolean(false);

        Session(String controlSocketName, String terminalSocketName,
                QemuProcessLauncher.ProcessHandle handle) {
            this.controlSocketName = controlSocketName;
            this.terminalSocketName = terminalSocketName;
            this.handle = handle;
        }

        @Override
        public int waitFor() throws InterruptedException {
            return handle.waitFor();
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
            return control().getOutputStream();
        }

        @Override
        public InputStream controlOutput() throws IOException {
            return control().getInputStream();
        }

        @Override
        public void close() {
            synchronized (controlLock) {
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
        public synchronized BridgeClient bridgeClient() {
            if (bridgeClient == null) {
                try {
                    bridgeClient = new BridgeClient(controlOutput(), controlInput());
                } catch (IOException e) {
                    return null;
                }
            }
            return bridgeClient;
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
            return isBridgeServing.compareAndSet(false, true);
        }


        private LocalSocket control() throws IOException {
            synchronized (controlLock) {
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

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public QemuInstanceLauncher(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Blocking bootstrap+launch; call on a worker thread. */
    public ExecutionSession launch(InstanceStrategyLauncher.LaunchParameters parameters)
            throws IOException, InterruptedException {
        RuntimeArtifactManifest manifest;
        try (InputStream in = context.getAssets().open(RuntimeArtifactManifest.SCHEMA_FILE_NAME)) {
            manifest = RuntimeArtifactManifest.parse(in);
        }
        QemuRuntimeBootstrapper bootstrapper = new QemuRuntimeBootstrapper(manifest,
                name -> {
                    try {
                        return context.getAssets().open(name);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                name -> QemuInstanceLauncher.class.getResourceAsStream("/" + name),
                Paths.get(parameters.runtimeDirectory),
                Paths.get(parameters.distroInputsDirectory));
        QemuRuntimeBootstrapper.RuntimePaths paths =
                bootstrapper.bootstrap(parameters.rootfsFileName, parameters.bridgeToken,
                        parameters.guestPassword,
                        Paths.get(parameters.instanceRootVolume));

        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        String controlSocket = "droidlayer-control-" + parameters.instanceId;
        String terminalSocket = "droidlayer-terminal-" + parameters.instanceId;

        VmHardwareConfig config = VmHardwareConfig.defaultConfig();
        if (config.gpuAccelerationEnabled()) {
            Log.i("DroidLayerQemu", "Launching QEMU with virtio-gpu-gl-device (GPU acceleration enabled)");
            VirglJniBridge.init(1024, true);
        } else {
            Log.i("DroidLayerQemu", "Launching QEMU with virtio-gpu-device (software fallback)");
        }

        String[] spec = QemuLaunchSpec.build(
                Paths.get(nativeLibraryDir, "libdroidlayer_qemu.so").toString(),
                paths.kernel.toString(),
                paths.initramfs.toString(),
                parameters.instanceRootVolume,
                controlSocket,
                terminalSocket,
                config);

        String[] sanitizedSpec = sanitizeSpecForTargetBinary(spec);
        Log.i("DroidLayerQemu", "Launching QEMU process with sanitized arguments");

        QemuProcessLauncher.ProcessFactory factory = currentSpec -> {
            Process process = new ProcessBuilder(currentSpec).redirectErrorStream(true).start();
            Thread logger = new Thread(() -> {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d("DroidLayerQemu", line);
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
                public void destroy() {
                    process.destroy();
                }
            };
        };

        QemuProcessLauncher launcher = new QemuProcessLauncher(factory);
        QemuProcessLauncher.Launched launched = launcher.launch(sanitizedSpec);
        QemuProcessLauncher.ProcessHandle handle = launched.handle();
        Thread watcher = new Thread(() -> {
            try {
                handle.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                VirglJniBridge.cleanup();
            }
        }, "qemu-exit-" + parameters.instanceId);
        watcher.setDaemon(true);
        watcher.start();
        Session session = new Session(controlSocket, terminalSocket, handle);
        ExecutionSessionRegistry.register(parameters.instanceId, session);
        return session;
    }

    /**
     * Adapts abstract hypervisor arguments to the physical arm64 QEMU binary packaged in APK:
     * 1. virtio-gpu-device is used for VirGL acceleration in this binary build.
     * 2. SLIRP (user netdev) is omitted if unsupported to prevent immediate exit.
     */
    static String[] sanitizeSpecForTargetBinary(String[] spec) {
        if (spec == null) {
            return new String[0];
        }
        java.util.List<String> list = new java.util.ArrayList<>();
        for (int i = 0; i < spec.length; i++) {
            String arg = spec[i];
            if ("-netdev".equals(arg)) {
                // Skip -netdev and its parameter because user/slirp is not compiled into packaged binary
                i++;
                continue;
            }
            if ("-device".equals(arg) && i + 1 < spec.length && spec[i + 1].startsWith("virtio-net-")) {
                // Skip -device virtio-net-* when netdev is omitted
                i++;
                continue;
            }
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
