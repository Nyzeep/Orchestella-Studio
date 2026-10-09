package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.SparseDiskResizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Pure JVM deep module for safely editing and dynamically adjusting hardware specifications
 * of virtual machine instances (Phase 5.0 Task 3 / ADR-0019).
 *
 * <p>Enforces:
 * <ul>
 *   <li>Lifecycle state machine safety: only instances in {@link InstanceLifecycle.State#STOPPED}
 *       or {@link InstanceLifecycle.State#CREATED} states can be modified;</li>
 *   <li>CPU core boundaries: constrained to 1..8 cores and bounded by host physical core count;</li>
 *   <li>Host RAM safety guardrails: strictly rejects Red Zone allocations threatening host OS stability;</li>
 *   <li>Disk expansion preflight: rejects disk shrinking or over-quota sizes, and checks host free space;</li>
 *   <li>Atomic persistence: updates metadata via {@link InstanceLifecycle.Controller} and sparse disk resizer.</li>
 * </ul>
 */
public final class InstanceHardwareConfigEditor {

    public static final String NOTICE_NEXT_LAUNCH = "硬件配置已成功更新，将在实例下次启动时生效。";

    /**
     * Value object representing the outcome of a hardware edit operation.
     */
    public static final class EditResult {
        private final InstanceLifecycle.Instance updatedInstance;
        private final boolean diskExpanded;
        private final long diskCapacityBytes;
        private final String noticeMessage;

        public EditResult(
                InstanceLifecycle.Instance updatedInstance,
                boolean diskExpanded,
                long diskCapacityBytes,
                String noticeMessage) {
            this.updatedInstance = Objects.requireNonNull(updatedInstance, "updatedInstance");
            this.diskExpanded = diskExpanded;
            this.diskCapacityBytes = diskCapacityBytes;
            this.noticeMessage = Objects.requireNonNull(noticeMessage, "noticeMessage");
        }

        public InstanceLifecycle.Instance getUpdatedInstance() {
            return updatedInstance;
        }

        public boolean isDiskExpanded() {
            return diskExpanded;
        }

        public long getDiskCapacityBytes() {
            return diskCapacityBytes;
        }

        public String getNoticeMessage() {
            return noticeMessage;
        }
    }

    private InstanceHardwareConfigEditor() {}

    /**
     * Validates that the instance is in a modifiable lifecycle state.
     */
    public static void validateEditable(InstanceLifecycle.Instance instance) {
        Objects.requireNonNull(instance, "instance");
        if (instance.state() != InstanceLifecycle.State.STOPPED
                && instance.state() != InstanceLifecycle.State.CREATED) {
            throw new IllegalStateException(
                    "Cannot modify hardware configuration while instance is in state: " + instance.state()
                            + " (must be STOPPED or CREATED)");
        }
    }

    /**
     * Validates vCPU core count against hardware limits and host physical capabilities.
     */
    public static void validateCpuCores(int cores, HostHardwareProfile hostProfile) {
        if (cores < VmHardwareConfig.MIN_CPU_CORES || cores > VmHardwareConfig.MAX_CPU_CORES) {
            throw new IllegalArgumentException(
                    "cpuCores must be between " + VmHardwareConfig.MIN_CPU_CORES
                            + " and " + VmHardwareConfig.MAX_CPU_CORES + ", got: " + cores);
        }
        if (hostProfile != null && cores > hostProfile.totalCores()) {
            throw new IllegalArgumentException(
                    "Requested cores (" + cores + ") exceed host total physical cores ("
                            + hostProfile.totalCores() + ")");
        }
    }

    /**
     * Validates RAM allocation against hardware limits and host safety zones.
     */
    public static void validateRam(int ramMb, HostHardwareProfile hostProfile) {
        if (ramMb < VmHardwareConfig.MIN_RAM_MEGABYTES || ramMb > VmHardwareConfig.MAX_RAM_MEGABYTES) {
            throw new IllegalArgumentException(
                    "ramMegabytes must be between " + VmHardwareConfig.MIN_RAM_MEGABYTES
                            + " and " + VmHardwareConfig.MAX_RAM_MEGABYTES + ", got: " + ramMb);
        }
        if (hostProfile != null) {
            HostHardwareProfile.SafetyZone zone = hostProfile.evaluateRamZone(ramMb);
            if (zone == HostHardwareProfile.SafetyZone.RED) {
                throw new IllegalStateException(
                        "Configured RAM (" + ramMb + " MB) exceeds host safety boundary (Red Zone), risking Out-Of-Memory termination");
            }
        }
    }

    /**
     * Validates disk expansion request against current volume size and host storage constraints.
     */
    public static void validateDiskExpansion(
            Path rootVolume,
            InstanceLifecycle.State state,
            Long targetDiskBytes,
            StorageContract.StorageSpaceChecker spaceChecker) throws IOException {
        if (targetDiskBytes == null) {
            return;
        }
        if (!Files.isRegularFile(rootVolume)) {
            throw new IOException("Root volume does not exist: " + rootVolume);
        }

        long currentSizeBytes = Files.size(rootVolume);
        if (targetDiskBytes < currentSizeBytes) {
            throw new IllegalArgumentException(
                    "Disk shrinking is not supported (current: " + currentSizeBytes
                            + " B, requested: " + targetDiskBytes + " B)");
        }

        if (targetDiskBytes > StorageContract.MAX_ROOT_VOLUME_BYTES) {
            throw new IllegalArgumentException(
                    "Target disk size (" + targetDiskBytes + " B) exceeds maximum supported limit ("
                            + StorageContract.MAX_ROOT_VOLUME_BYTES + " B)");
        }

        if (targetDiskBytes > currentSizeBytes) {
            long deltaBytes = targetDiskBytes - currentSizeBytes;
            StorageContract.StorageSpaceChecker checker = spaceChecker != null
                    ? spaceChecker
                    : new StorageContract.DefaultStorageSpaceChecker();
            long usableSpace = checker.getUsableSpace(rootVolume);
            if (usableSpace < deltaBytes) {
                throw new StorageContract.InsufficientStorageException(rootVolume, deltaBytes, usableSpace);
            }
        }
    }

    /**
     * Atomically validates, applies, and persists hardware configuration adjustments to the instance.
     *
     * @param instance instance to configure
     * @param newConfig target hardware configuration
     * @param targetDiskBytes optional new disk capacity in bytes (null to keep current)
     * @param controller lifecycle controller for persisting changes
     * @param hostProfile host hardware profile for boundary checks
     * @param spaceChecker space checker for disk expansion preflight
     * @return result containing updated instance and notice details
     * @throws IOException on storage or persistence error
     */
    public static EditResult apply(
            InstanceLifecycle.Instance instance,
            VmHardwareConfig newConfig,
            Long targetDiskBytes,
            InstanceLifecycle.Controller controller,
            HostHardwareProfile hostProfile,
            StorageContract.StorageSpaceChecker spaceChecker) throws IOException {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(newConfig, "newConfig");
        Objects.requireNonNull(controller, "controller");

        // 1. Validate lifecycle state
        validateEditable(instance);

        // 2. Validate CPU cores
        validateCpuCores(newConfig.cpuCores(), hostProfile);

        // 3. Validate RAM allocation
        validateRam(newConfig.ramMegabytes(), hostProfile);

        // 4. Validate and execute disk expansion if requested
        boolean diskExpanded = false;
        long finalDiskCapacityBytes = newConfig.diskCapacityBytes();

        if (targetDiskBytes != null) {
            validateDiskExpansion(instance.rootVolume(), instance.state(), targetDiskBytes, spaceChecker);
            long currentVolumeSize = Files.size(instance.rootVolume());
            if (targetDiskBytes > currentVolumeSize) {
                SparseDiskResizer.resize(instance.rootVolume(), instance.state(), targetDiskBytes, spaceChecker);
                diskExpanded = true;
                finalDiskCapacityBytes = targetDiskBytes;
            }
        }

        // 5. Construct effective configuration
        VmHardwareConfig effectiveConfig = VmHardwareConfig.builder()
                .cpuCores(newConfig.cpuCores())
                .ramMegabytes(newConfig.ramMegabytes())
                .diskCapacityBytes(finalDiskCapacityBytes)
                .gpuAccelerationEnabled(newConfig.gpuAccelerationEnabled())
                .extraKernelArguments(newConfig.extraKernelArguments())
                .build();

        // 6. Atomically persist to instance store
        InstanceLifecycle.Instance updatedInstance = controller.updateHardwareConfig(instance.id(), effectiveConfig);

        return new EditResult(
                updatedInstance,
                diskExpanded,
                finalDiskCapacityBytes,
                NOTICE_NEXT_LAUNCH
        );
    }
}
