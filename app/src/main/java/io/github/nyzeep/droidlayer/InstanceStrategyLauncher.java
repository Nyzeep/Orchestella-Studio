package io.github.nyzeep.droidlayer;

import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import io.github.nyzeep.droidlayer.runtime.engineavf.AvfEngineException;
import java.io.IOException;
import java.util.Objects;

/**
 * The AC-3 transparent strategy chain for one launch: when the AVF strategy
 * is preferred and its initialization or start fails, the launch falls back
 * to the QEMU strategy automatically and every fallback is recorded with its
 * precise reason. Failures of the shared asset bootstrap are NOT degraded —
 * they would fail the QEMU path identically, so they surface honestly.
 *
 * <p>Per ADR-0002, the {@link Outcome} exposes only the strategy-neutral
 * {@link ExecutionSession} plus the strategy identifier; the UX layer never
 * sees a strategy-specific type.
 */
public final class InstanceStrategyLauncher {
    /** Both engines launch from the same caller-supplied parameter set. */
    public static final class LaunchParameters {
        public final String instanceId;
        public final String bridgeToken;
        public final String rootfsFileName;
        public final String instanceRootVolume;
        public final String runtimeDirectory;
        public final String distroInputsDirectory;
        public final String guestPassword;
        public final io.github.nyzeep.droidlayer.runtime.contract.VmHardwareConfig hardwareConfig;

        public LaunchParameters(
                String instanceId,
                String bridgeToken,
                String rootfsFileName,
                String instanceRootVolume,
                String runtimeDirectory,
                String distroInputsDirectory) {
            this(instanceId, bridgeToken, rootfsFileName, instanceRootVolume, runtimeDirectory, distroInputsDirectory, null, null);
        }

        public LaunchParameters(
                String instanceId,
                String bridgeToken,
                String rootfsFileName,
                String instanceRootVolume,
                String runtimeDirectory,
                String distroInputsDirectory,
                String guestPassword) {
            this(instanceId, bridgeToken, rootfsFileName, instanceRootVolume, runtimeDirectory, distroInputsDirectory, guestPassword, null);
        }

        public LaunchParameters(
                String instanceId,
                String bridgeToken,
                String rootfsFileName,
                String instanceRootVolume,
                String runtimeDirectory,
                String distroInputsDirectory,
                String guestPassword,
                io.github.nyzeep.droidlayer.runtime.contract.VmHardwareConfig hardwareConfig) {
            this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
            this.bridgeToken = Objects.requireNonNull(bridgeToken, "bridgeToken");
            this.rootfsFileName = Objects.requireNonNull(rootfsFileName, "rootfsFileName");
            this.instanceRootVolume =
                    Objects.requireNonNull(instanceRootVolume, "instanceRootVolume");
            this.runtimeDirectory = Objects.requireNonNull(runtimeDirectory, "runtimeDirectory");
            this.distroInputsDirectory =
                    Objects.requireNonNull(distroInputsDirectory, "distroInputsDirectory");
            this.guestPassword = guestPassword;
            this.hardwareConfig = hardwareConfig;
        }
    }

    /** Strategy-specific launch seam. */
    public interface AvfLaunch {
        ExecutionSession launch(LaunchParameters parameters)
                throws IOException, AvfEngineException;
    }

    /** Strategy-specific launch seam. */
    public interface QemuLaunch {
        ExecutionSession launch(LaunchParameters parameters)
                throws IOException, InterruptedException;
    }

    /** Receives every AVF→QEMU degradation with its reason. */
    public interface DegradationSink {
        void record(AvfEngineException failure) throws IOException;
    }

    /** Result of one launch through the strategy chain. */
    public static final class Outcome {
        public final FullLinuxContract.ExecutionStrategy strategy;
        /** The strategy-neutral live session backing this launch. */
        public final ExecutionSession session;
        public final String degradationReason;
        public final String degradationDetail;

        Outcome(
                FullLinuxContract.ExecutionStrategy strategy,
                ExecutionSession session,
                String degradationReason,
                String degradationDetail) {
            this.strategy = strategy;
            this.session = session;
            this.degradationReason = degradationReason;
            this.degradationDetail = degradationDetail;
        }

        public boolean hardwareAccelerated() {
            return strategy == FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION;
        }
    }

    private final AvfLaunch avfLaunch;
    private final QemuLaunch qemuLaunch;
    private final DegradationSink degradationSink;

    public InstanceStrategyLauncher(AvfLaunch avfLaunch, QemuLaunch qemuLaunch,
            DegradationSink degradationSink) {
        this.avfLaunch = Objects.requireNonNull(avfLaunch, "avfLaunch");
        this.qemuLaunch = Objects.requireNonNull(qemuLaunch, "qemuLaunch");
        this.degradationSink = Objects.requireNonNull(degradationSink, "degradationSink");
    }

    /**
     * Launches through the preferred strategy; an AVF failure degrades to
     * QEMU with the reason recorded before the fallback starts.
     */
    public Outcome launch(LaunchParameters parameters, boolean preferAvf)
            throws IOException, InterruptedException {
        if (preferAvf) {
            try {
                return new Outcome(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                        avfLaunch.launch(parameters), null, null);
            } catch (AvfEngineException failure) {
                degradationSink.record(failure);
                return new Outcome(
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        qemuLaunch.launch(parameters),
                        failure.reason(),
                        failure.getMessage());
            }
        }
        return new Outcome(
                FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                qemuLaunch.launch(parameters),
                null,
                null);
    }
}
