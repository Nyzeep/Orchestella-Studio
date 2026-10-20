package io.github.nyzeep.orchestella;

import android.content.Context;
import io.github.nyzeep.orchestella.RuntimeProcessSupervisor.StopResult;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfLaunchSpec;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfLaunchTrace;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfStopResult;
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
    /** Bounded stop window for close/terminate and failed-registration cleanup. */
    private static final long CLOSE_GRACE_MS = 3000L;
    private static final long TERMINATE_GRACE_MS = 3000L;
    static final long REGISTRATION_CLEANUP_GRACE_MS = 2000L;

    /** Live AVF session exposing only the strategy-neutral surface. */
    public static class AvfSession implements ExecutionSession, RuntimeOwnedSession {
        private final AvfVirtualMachineEngine.AvfVmSession session;
        private BridgeClient bridgeClient;
        private volatile BridgeControl.Clipboard clipboardBridge;
        private final AtomicBoolean isBridgeServing = new AtomicBoolean(false);
        private final QemuWaylandChannel display;
        private final LaunchTimeline timeline;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        AvfSession(AvfVirtualMachineEngine.AvfVmSession session) { this(session, null, null); }

        AvfSession(AvfVirtualMachineEngine.AvfVmSession session, String token) {
            this(session, token, null);
        }

        AvfSession(AvfVirtualMachineEngine.AvfVmSession session, String token, LaunchTimeline timeline) {
            this.session = session;
            this.timeline = timeline;
            this.display = token == null ? null : new QemuWaylandChannel(() -> {
                try {
                    AvfVirtualMachineApi.AvfVsockChannel channel = session.openChannel(9103);
                    return new io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel.Streams(
                            channel.inputStream(), channel.outputStream(), channel);
                } catch (AvfEngineException failure) { throw new IOException("AVF display transport unavailable", failure); }
            }, token, this::bridgeClient);
        }

        @Override public io.github.nyzeep.orchestella.runtime.contract.DisplayControl displayControl() { return display; }
        @Override public io.github.nyzeep.orchestella.runtime.contract.WaylandChannel waylandChannel() { return display; }
        @Override public boolean connectDisplay(boolean keyboard, boolean unicodeText, boolean pointer) {
            return display != null && display.connect(new io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel.InputCapabilities(
                    keyboard, unicodeText, pointer));
        }
        @Override public boolean reconnectDisplay(boolean keyboard, boolean unicodeText, boolean pointer) {
            return display != null && display.connect(new io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel.InputCapabilities(
                    keyboard, unicodeText, pointer), true);
        }
        @Override public String displayStatus() { return display == null ? "AVF 显示未配置" : display.message(); }
        @Override public io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract.CapabilityMatrix capabilityMatrix() {
            return io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract.CapabilityMatrix.hardwareVirtualizationBaseline();
        }

        @Override
        public int waitFor() throws InterruptedException {
            return session.waitFor();
        }

        @Override
        public void terminate() throws IOException {
            if (stopRuntime(TERMINATE_GRACE_MS) == StopResult.UNCONFIRMED) {
                throw new IOException("AVF VM stop was not confirmed");
            }
        }

        /**
         * The single owner stop for close, terminate and the session pool.
         * The engine session serializes the platform request, so a repeated
         * call cannot create a second stop owner.
         */
        @Override
        public StopResult stopRuntime(long graceMs) {
            AvfStopResult result = session.stopRuntime(graceMs);
            StopResult mapped = switch (result) {
                case CONFIRMED -> StopResult.CONFIRMED;
                case ALREADY_STOPPED -> StopResult.ALREADY_STOPPED;
                case UNCONFIRMED -> StopResult.UNCONFIRMED;
            };
            if (timeline != null) {
                if (mapped == StopResult.UNCONFIRMED) timeline.markStopUnconfirmed();
                else timeline.markStopConfirmed(mapped);
                timeline.finish();
            }
            return mapped;
        }

        @Override
        public boolean isRuntimeAlive() {
            return session.isRuntimeAlive();
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
            if (!closed.compareAndSet(false, true)) return;
            // close() is an owner stop, not a channel-only teardown (ADR-0021).
            stopRuntime(CLOSE_GRACE_MS);
            if (display != null) display.close();
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
        public boolean recoverControlChannel() {
            // AvfVmSession has no control-only cache invalidation API. Its
            // closeChannels() also closes the live terminal and data channels.
            return false;
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
        int ramMb = parameters.hardwareConfig != null
                ? parameters.hardwareConfig.ramMegabytes() : (int) INSTANCE_MEMORY_MB;
        int vcpuCount = parameters.hardwareConfig != null
                ? parameters.hardwareConfig.cpuCores() : 1;
        LaunchTimeline timeline = LaunchTimeline.open(Paths.get(parameters.runtimeDirectory),
                parameters.instanceId, "unknown", ramMb, vcpuCount);
        timeline.mark(LaunchTimeline.Stage.LAUNCH_START);
        AvfLaunchTrace trace = stage -> markStage(timeline, stage);
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

        AvfLaunchSpec.Builder specBuilder = AvfLaunchSpec.builder(
                        parameters.instanceId,
                        paths.kernel.toString(),
                        paths.initramfs.toString(),
                        parameters.instanceRootVolume);
        if (parameters.hardwareConfig != null) {
            specBuilder.memoryMb(parameters.hardwareConfig.ramMegabytes());
            specBuilder.cpuCount(parameters.hardwareConfig.cpuCores());
        } else {
            specBuilder.memoryMb(INSTANCE_MEMORY_MB);
        }
        specBuilder.networkSpec(io.github.nyzeep.orchestella.runtime.contract.NetworkContract.OutboundNetworkSpec
                .withDns(NetworkGatewayDaemon.guestDnsFor(context)));
        AvfLaunchSpec spec = specBuilder.build();

        ReflectiveAvfVirtualMachineApi api = new ReflectiveAvfVirtualMachineApi(
                context, context.getClass().getClassLoader());
        AvfVirtualMachineEngine engine = new AvfVirtualMachineEngine(api);
        AvfSession session = new AvfSession(engine.launch(spec, trace), parameters.bridgeToken, timeline);
        try {
            ExecutionSessionRegistry.register(parameters.instanceId, session);
        } catch (IOException registrationFailure) {
            cleanupFailedRegistration(session, registrationFailure);
        } catch (RuntimeException registrationFailure) {
            cleanupFailedRegistration(session,
                    new IOException("session registration failed", registrationFailure));
        }
        timeline.mark(LaunchTimeline.Stage.SESSION_REGISTERED);
        return session;
    }

    /**
     * Stops a VM whose session could not be registered and fails the launch.
     * A confirmed stop is reported as a clean launch failure; an UNCONFIRMED
     * stop keeps its own diagnostic so callers never mistake a possibly-live
     * VM for an orphan-free failure.
     */
    static void cleanupFailedRegistration(RuntimeOwnedSession session, IOException failure)
            throws IOException {
        StopResult result;
        try {
            result = session.stopRuntime(REGISTRATION_CLEANUP_GRACE_MS);
        } catch (RuntimeException stopFailure) {
            failure.addSuppressed(stopFailure);
            result = StopResult.UNCONFIRMED;
        }
        if (result == StopResult.UNCONFIRMED) {
            throw new IOException(
                    "session registration failed; AVF VM stop unconfirmed", failure);
        }
        throw new IOException(
                "session registration failed; AVF VM stop confirmed", failure);
    }

    /** Maps engine lifecycle stages onto the App's append-only launch record. */
    private static void markStage(LaunchTimeline timeline, AvfLaunchTrace.Stage stage) {
        switch (stage) {
            case LAUNCH_REQUESTED -> timeline.mark(LaunchTimeline.Stage.LAUNCH_START);
            case VM_CREATED -> timeline.mark(LaunchTimeline.Stage.VM_CREATED);
            case RUN_RETURNED -> timeline.mark(LaunchTimeline.Stage.RUN_RETURNED);
            case STOP_REQUESTED -> timeline.mark(LaunchTimeline.Stage.STOP_REQUESTED);
            case STOP_CONFIRMED -> { /* recorded with its typed result by stopRuntime */ }
            case STOP_UNCONFIRMED -> { /* recorded with its typed result by stopRuntime */ }
            case EXIT_OBSERVED -> timeline.mark(LaunchTimeline.Stage.EXIT_OBSERVED);
        }
    }
}
