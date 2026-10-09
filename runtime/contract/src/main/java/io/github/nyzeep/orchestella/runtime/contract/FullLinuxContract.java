package io.github.nyzeep.orchestella.runtime.contract;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Strategy-neutral vocabulary and capability data for Full Linux Runtime callers. */
public final class FullLinuxContract {
    private FullLinuxContract() {}

    /** The only Runtime Class delivered by Ticket 26. */
    public enum RuntimeClass {
        FULL_LINUX
    }

    /** An internal execution choice; it is not a Runtime Class and is not a QEMU-specific type. */
    public enum ExecutionStrategy {
        SOFTWARE_VIRTUALIZATION,
        HARDWARE_VIRTUALIZATION
    }

    /**
     * A data declaration, not a feature flag. A capability may be presented only after its adapter
     * supplies the corresponding contract behavior.
     */
    public enum Capability {
        TERMINAL,
        BRIDGE_HANDSHAKE,
        HARDWARE_ACCELERATION,
        GRAPHICS_WAYLAND,
        AUDIO_OUTPUT,
        CLIPBOARD_SYNC,
        POINTER_CAPTURE,
        GPU_HARDWARE_ACCELERATION
    }

    /** Immutable strategy declaration consumed by callers instead of strategy-specific branches. */
    public static final class CapabilityMatrix {
        private static final Set<Capability> SOFTWARE_VIRTUALIZATION_BASELINE =
                Collections.unmodifiableSet(
                        EnumSet.of(Capability.TERMINAL, Capability.BRIDGE_HANDSHAKE));

        private final ExecutionStrategy executionStrategy;
        private final Set<Capability> capabilities;

        public CapabilityMatrix(ExecutionStrategy executionStrategy, Set<Capability> capabilities) {
            this.executionStrategy = Objects.requireNonNull(executionStrategy, "executionStrategy");
            EnumSet<Capability> copy = EnumSet.noneOf(Capability.class);
            copy.addAll(Objects.requireNonNull(capabilities, "capabilities"));
            if (executionStrategy == ExecutionStrategy.SOFTWARE_VIRTUALIZATION
                    && !copy.containsAll(SOFTWARE_VIRTUALIZATION_BASELINE)) {
                throw new IllegalArgumentException("software virtualization baseline is incomplete");
            }
            if (executionStrategy == ExecutionStrategy.SOFTWARE_VIRTUALIZATION
                    && copy.contains(Capability.HARDWARE_ACCELERATION)) {
                throw new IllegalArgumentException("software virtualization cannot claim hardware acceleration");
            }
            this.capabilities = Collections.unmodifiableSet(copy);
        }

        public static CapabilityMatrix softwareVirtualizationBaseline() {
            return new CapabilityMatrix(
                    ExecutionStrategy.SOFTWARE_VIRTUALIZATION, SOFTWARE_VIRTUALIZATION_BASELINE);
        }

        public static CapabilityMatrix softwareVirtualizationWithGraphics() {
            return new CapabilityMatrix(
                    ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                    EnumSet.of(
                            Capability.TERMINAL,
                            Capability.BRIDGE_HANDSHAKE,
                            Capability.GRAPHICS_WAYLAND));
        }

        public static CapabilityMatrix softwareVirtualizationWithPeripherals() {
            return new CapabilityMatrix(
                    ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                    EnumSet.of(
                            Capability.TERMINAL,
                            Capability.BRIDGE_HANDSHAKE,
                            Capability.GRAPHICS_WAYLAND,
                            Capability.AUDIO_OUTPUT,
                            Capability.CLIPBOARD_SYNC,
                            Capability.POINTER_CAPTURE));
        }

        public static CapabilityMatrix hardwareVirtualizationBaseline() {
            return new CapabilityMatrix(
                    ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                    EnumSet.of(
                            Capability.TERMINAL,
                            Capability.BRIDGE_HANDSHAKE,
                            Capability.HARDWARE_ACCELERATION));
        }

        public static CapabilityMatrix hardwareVirtualizationWithGraphics() {
            return new CapabilityMatrix(
                    ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                    EnumSet.of(
                            Capability.TERMINAL,
                            Capability.BRIDGE_HANDSHAKE,
                            Capability.HARDWARE_ACCELERATION,
                            Capability.GRAPHICS_WAYLAND));
        }

        public static CapabilityMatrix hardwareVirtualizationWithPeripherals() {
            return new CapabilityMatrix(
                    ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                    EnumSet.of(
                            Capability.TERMINAL,
                            Capability.BRIDGE_HANDSHAKE,
                            Capability.HARDWARE_ACCELERATION,
                            Capability.GRAPHICS_WAYLAND,
                            Capability.AUDIO_OUTPUT,
                            Capability.CLIPBOARD_SYNC,
                            Capability.POINTER_CAPTURE));
        }

        public ExecutionStrategy executionStrategy() {
            return executionStrategy;
        }

        public Set<Capability> capabilities() {
            return capabilities;
        }

        public boolean supports(Capability capability) {
            return capabilities.contains(Objects.requireNonNull(capability, "capability"));
        }

        public boolean supportsAudio() {
            return supports(Capability.AUDIO_OUTPUT);
        }

        public boolean supportsClipboard() {
            return supports(Capability.CLIPBOARD_SYNC);
        }

        public boolean supportsPointerCapture() {
            return supports(Capability.POINTER_CAPTURE);
        }

        public String performanceBadge() {
            return executionStrategy == ExecutionStrategy.SOFTWARE_VIRTUALIZATION
                    ? "完整 Linux · 软件模拟（较慢）"
                    : "完整 Linux · 硬件加速";
        }

        public String graphicsBadge() {
            if (!supports(Capability.GRAPHICS_WAYLAND)) {
                return "无图形通道";
            }
            return executionStrategy == ExecutionStrategy.SOFTWARE_VIRTUALIZATION
                    ? "Wayland 原生通道（共享内存）"
                    : "Wayland 原生通道（vsock 硬件加速）";
        }

        public String audioBadge() {
            if (!supports(Capability.AUDIO_OUTPUT)) {
                return "无音频输出";
            }
            return executionStrategy == ExecutionStrategy.SOFTWARE_VIRTUALIZATION
                    ? "低延迟 PCM 音频（本地套接字 48kHz）"
                    : "低延迟 PCM 音频（vsock 硬件加速 48kHz）";
        }

        public String clipboardBadge() {
            if (!supports(Capability.CLIPBOARD_SYNC)) {
                return "无剪贴板同步";
            }
            return executionStrategy == ExecutionStrategy.SOFTWARE_VIRTUALIZATION
                    ? "剪贴板双向同步（本地通道）"
                    : "剪贴板双向同步（vsock 硬件通道）";
        }

        public String pointerCaptureBadge() {
            if (!supports(Capability.POINTER_CAPTURE)) {
                return "无指针捕获";
            }
            return "相对指针捕获（无边界）";
        }

        public boolean supportsGpuAcceleration() {
            return supports(Capability.GPU_HARDWARE_ACCELERATION);
        }

        public String gpuBadge() {
            if (!supports(Capability.GPU_HARDWARE_ACCELERATION)) {
                return "无 GPU 加速（软件光栅）";
            }
            return "GPU 硬件加速（VirGL 3D）";
        }
    }

    /**
     * Strategy-neutral contract instance representation attaching identity and hardware configuration.
     */
    public static final class Instance {
        private final String id;
        private final VmHardwareConfig hardwareConfig;

        public Instance(String id, VmHardwareConfig hardwareConfig) {
            this.id = Objects.requireNonNull(id, "id");
            this.hardwareConfig = Objects.requireNonNull(hardwareConfig, "hardwareConfig");
        }

        public String id() {
            return id;
        }

        public VmHardwareConfig hardwareConfig() {
            return hardwareConfig;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Instance that = (Instance) o;
            return Objects.equals(id, that.id) && Objects.equals(hardwareConfig, that.hardwareConfig);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, hardwareConfig);
        }

        @Override
        public String toString() {
            return "FullLinuxContract.Instance{id='" + id + "', hardwareConfig=" + hardwareConfig + "}";
        }
    }
}
