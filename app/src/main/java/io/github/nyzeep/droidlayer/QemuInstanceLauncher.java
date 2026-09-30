package io.github.nyzeep.droidlayer;

import android.content.Context;
import io.github.nyzeep.droidlayer.runtime.engineqemu.QemuLaunchSpec;
import io.github.nyzeep.droidlayer.runtime.engineqemu.QemuProcessLauncher;
import io.github.nyzeep.droidlayer.runtime.engineqemu.RuntimeArtifactManifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts one instance's packaged QEMU from {@code nativeLibraryDir} with the
 * fixed argument array (policy section 4: no shell, no writable-directory
 * execution), wires the exit watcher to the normal-guest-stop transition, and
 * connects the Bridge control channel. Terminal traffic is served by
 * {@link TerminalActivity} over the second abstract socket.
 */
public final class QemuInstanceLauncher {
    /** Live session handles for one running instance. */
    public static final class Session {
        public final String controlSocketName;
        public final String terminalSocketName;
        public final QemuProcessLauncher.ProcessHandle handle;

        Session(String controlSocketName, String terminalSocketName,
                QemuProcessLauncher.ProcessHandle handle) {
            this.controlSocketName = controlSocketName;
            this.terminalSocketName = terminalSocketName;
            this.handle = handle;
        }
    }

    private static final long INSTANCE_MEMORY_MB = 512;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public QemuInstanceLauncher(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Blocking bootstrap+launch; call on a worker thread. */
    public Session launch(String instanceId, String bridgeToken, String rootfsFileName,
            String instanceRootVolume, String runtimeDirectory, String distroInputsDirectory)
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
                Paths.get(runtimeDirectory),
                Paths.get(distroInputsDirectory));
        QemuRuntimeBootstrapper.RuntimePaths paths =
                bootstrapper.bootstrap(rootfsFileName, bridgeToken,
                        Paths.get(instanceRootVolume));

        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        String controlSocket = "droidlayer-control-" + instanceId;
        String terminalSocket = "droidlayer-terminal-" + instanceId;
        String[] spec = QemuLaunchSpec.build(
                Paths.get(nativeLibraryDir, "libdroidlayer_qemu.so").toString(),
                paths.kernel.toString(),
                paths.initramfs.toString(),
                instanceRootVolume,
                controlSocket,
                terminalSocket,
                INSTANCE_MEMORY_MB);

        QemuProcessLauncher launcher = new QemuProcessLauncher();
        QemuProcessLauncher.Launched launched = launcher.launch(spec);
        QemuProcessLauncher.ProcessHandle handle = launched.handle();
        Thread watcher = new Thread(() -> {
            try {
                handle.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "qemu-exit-" + instanceId);
        watcher.setDaemon(true);
        watcher.start();
        return new Session(controlSocket, terminalSocket, handle);
    }
}
