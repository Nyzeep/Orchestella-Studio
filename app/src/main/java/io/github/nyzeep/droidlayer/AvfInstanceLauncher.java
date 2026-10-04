package io.github.nyzeep.droidlayer;

import android.content.Context;
import io.github.nyzeep.droidlayer.runtime.engineavf.AvfLaunchSpec;
import io.github.nyzeep.droidlayer.runtime.engineavf.AvfVirtualMachineEngine;
import io.github.nyzeep.droidlayer.runtime.engineavf.ReflectiveAvfVirtualMachineApi;
import io.github.nyzeep.droidlayer.runtime.engineqemu.RuntimeArtifactManifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;

/**
 * Launches one instance through the AVF execution strategy. The verified
 * runtime assets (kernel Image, embedded-provision initramfs) and the raw
 * root volume are prepared by the exact same bootstrapper the QEMU strategy
 * uses — only the VM assembly differs (ADR-0001 asset interop). The AVF SDK
 * surface is driven reflectively inside {@code runtime:engine-avf}; nothing
 * here names it.
 */
public final class AvfInstanceLauncher {
    /** Live AVF session for one running instance. */
    public static final class AvfSession {
        public final AvfVirtualMachineEngine.AvfVmSession session;

        AvfSession(AvfVirtualMachineEngine.AvfVmSession session) {
            this.session = session;
        }
    }

    private static final long INSTANCE_MEMORY_MB = 512;

    private final Context context;

    public AvfInstanceLauncher(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Blocking bootstrap+launch; call on a worker thread. */
    public AvfSession launch(String instanceId, String bridgeToken, String rootfsFileName,
            String instanceRootVolume, String runtimeDirectory, String distroInputsDirectory)
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
                Paths.get(runtimeDirectory),
                Paths.get(distroInputsDirectory));
        QemuRuntimeBootstrapper.RuntimePaths paths =
                bootstrapper.bootstrap(rootfsFileName, bridgeToken,
                        Paths.get(instanceRootVolume));

        AvfLaunchSpec spec = AvfLaunchSpec.builder(
                        instanceId,
                        paths.kernel.toString(),
                        paths.initramfs.toString(),
                        instanceRootVolume)
                .memoryMb(INSTANCE_MEMORY_MB)
                .build();

        ReflectiveAvfVirtualMachineApi api = new ReflectiveAvfVirtualMachineApi(
                context, context.getClass().getClassLoader());
        AvfVirtualMachineEngine engine = new AvfVirtualMachineEngine(api);
        AvfVirtualMachineEngine.AvfVmSession session = engine.launch(spec);
        AvfTerminalStreamsRegistry.register(instanceId, session);
        return new AvfSession(session);
    }
}
