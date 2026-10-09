package io.github.nyzeep.orchestella;

import android.content.Context;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfLaunchSpec;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfVirtualMachineApi;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfVirtualMachineEngine;
import io.github.nyzeep.orchestella.runtime.engineavf.ReflectiveAvfVirtualMachineApi;
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Launches one instance through the AVF execution strategy. The verified
 * runtime assets (kernel Image, embedded-provision initramfs) and the raw
 * root volume are prepared by the exact same bootstrapper the QEMU strategy
 * uses — only the VM assembly differs (ADR-0001 asset interop). The AVF SDK
 * surface is driven reflectively inside {@code runtime:engine-avf}; the
 * session handed to the UX layer is the neutral {@link ExecutionSession}.
 */
public final class AvfInstanceLauncher {
    /** Live AVF session exposing only the strategy-neutral surface. */
    public static final class AvfSession implements ExecutionSession {
        private final AvfVirtualMachineEngine.AvfVmSession session;
        private BridgeClient bridgeClient;
        private volatile BridgeControl.Clipboard clipboardBridge;
        private final AtomicBoolean isBridgeServing = new AtomicBoolean(false);

        AvfSession(AvfVirtualMachineEngine.AvfVmSession session) {
            this.session = session;
        }

        @Override
        public int waitFor() throws InterruptedException {
            return session.waitFor();
        }

        @Override
        public OutputStream terminalInput() throws IOException {
            return terminalChannel().outputStream();
        }

        @Override
        public InputStream terminalOutput() throws IOException {
            return terminalChannel().inputStream();
        }

        @Override
        public OutputStream controlInput() throws IOException {
            return controlChannel().outputStream();
        }

        @Override
        public InputStream controlOutput() throws IOException {
            return controlChannel().inputStream();
        }

        @Override
        public void close() {
            try {
                session.closeChannels();
            } catch (IOException ignored) {
                // channel cleanup is best-effort at teardown
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


        private AvfVirtualMachineApi.AvfVsockChannel terminalChannel() throws IOException {
            try {
                return session.terminalChannel();
            } catch (AvfEngineException failure) {
                throw new IOException(
                        "terminal channel unavailable: " + failure.getMessage(), failure);
            }
        }

        private AvfVirtualMachineApi.AvfVsockChannel controlChannel() throws IOException {
            try {
                return session.controlChannel();
            } catch (AvfEngineException failure) {
                throw new IOException(
                        "control channel unavailable: " + failure.getMessage(), failure);
            }
        }
    }

    private static final long INSTANCE_MEMORY_MB = 512;

    private final Context context;

    public AvfInstanceLauncher(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Blocking bootstrap+launch; call on a worker thread. */
    public ExecutionSession launch(InstanceStrategyLauncher.LaunchParameters parameters)
            throws IOException {
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
                name -> AvfInstanceLauncher.class.getResourceAsStream("/" + name),
                Paths.get(parameters.runtimeDirectory),
                Paths.get(parameters.distroInputsDirectory));
        QemuRuntimeBootstrapper.RuntimePaths paths =
                bootstrapper.bootstrap(parameters.rootfsFileName, parameters.bridgeToken,
                        parameters.guestPassword,
                        Paths.get(parameters.instanceRootVolume));

        AvfLaunchSpec spec = AvfLaunchSpec.builder(
                        parameters.instanceId,
                        paths.kernel.toString(),
                        paths.initramfs.toString(),
                        parameters.instanceRootVolume)
                .memoryMb(INSTANCE_MEMORY_MB)
                .build();

        ReflectiveAvfVirtualMachineApi api = new ReflectiveAvfVirtualMachineApi(
                context, context.getClass().getClassLoader());
        AvfVirtualMachineEngine engine = new AvfVirtualMachineEngine(api);
        AvfSession session = new AvfSession(engine.launch(spec));
        ExecutionSessionRegistry.register(parameters.instanceId, session);
        return session;
    }
}
