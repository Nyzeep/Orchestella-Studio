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
        /** RFB frames and input over the engine transport; never labelled Wayland. */
        GRAPHICS_RFB,
        /** Only a real Wayland/DLFR adapter may declare this. */
        GRAPHICS_WAYLAND,
        AUDIO_OUTPUT,
        CLIPBOARD_SYNC,
        POINTER_CAPTURE,
        /** A read-only 9p share server actually serving this session. */
        SHARED_DIRECTORY,
        GPU_HARDWARE_ACCELERATION;

        /** Compatibility alias for callers that use a transport-neutral name. */
        public static final Capability GRAPHICS_DISPLAY = GRAPHICS_RFB;
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

        /** The one projection from what a launch assembled to what callers may show. */
        public static CapabilityMatrix from(SessionCapabilities descriptor) {
            Objects.requireNonNull(descriptor, "descriptor");
            EnumSet<Capability> declared = EnumSet.noneOf(Capability.class);
            if (descriptor.terminal()) {
                declared.add(Capability.TERMINAL);
            }
            if (descriptor.bridge()) {
                declared.add(Capability.BRIDGE_HANDSHAKE);
            }
            if (descriptor.hardwareAcceleration()) {
                declared.add(Capability.HARDWARE_ACCELERATION);
            }
            switch (descriptor.display()) {
                case RFB -> declared.add(Capability.GRAPHICS_RFB);
                case WAYLAND -> declared.add(Capability.GRAPHICS_WAYLAND);
                case NONE -> { }
            }
            if (descriptor.audioOutput()) {
                declared.add(Capability.AUDIO_OUTPUT);
            }
            if (descriptor.clipboardSync()) {
                declared.add(Capability.CLIPBOARD_SYNC);
            }
            if (descriptor.pointerCapture()) {
                declared.add(Capability.POINTER_CAPTURE);
            }
            if (descriptor.sharedDirectory()) {
                declared.add(Capability.SHARED_DIRECTORY);
            }
            if (descriptor.gpuAcceleration()) {
                declared.add(Capability.GPU_HARDWARE_ACCELERATION);
            }
            return new CapabilityMatrix(descriptor.executionStrategy(), declared);
        }

        /**
         * Legacy data preset. Production derives its matrix with
         * {@link #from(SessionCapabilities)}; this factory remains for old
         * fixtures and must not be used to declare a live session.
         */
        public static CapabilityMatrix softwareVirtualizationWithGraphics() {
            return from(SessionCapabilities.builder(ExecutionStrategy.SOFTWARE_VIRTUALIZATION)
                    .terminal(true)
                    .bridge(true)
                    .display(SessionCapabilities.DisplayTransport.RFB)
                    .build());
        }

        /**
         * Legacy data preset. Production derives its matrix with
         * {@link #from(SessionCapabilities)}; this factory remains for old
         * fixtures and must not be used to declare a live session.
         */
        public static CapabilityMatrix softwareVirtualizationWithPeripherals() {
            return from(SessionCapabilities.builder(ExecutionStrategy.SOFTWARE_VIRTUALIZATION)
                    .terminal(true)
                    .bridge(true)
                    .display(SessionCapabilities.DisplayTransport.RFB)
                    .audioOutput(true)
                    .clipboardSync(true)
                    .pointerCapture(true)
                    .build());
        }

        public static CapabilityMatrix hardwareVirtualizationBaseline() {
            return new CapabilityMatrix(
                    ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                    EnumSet.of(
                            Capability.TERMINAL,
                            Capability.BRIDGE_HANDSHAKE,
                            Capability.HARDWARE_ACCELERATION));
        }

        /**
         * Legacy data preset. Production derives its matrix with
         * {@link #from(SessionCapabilities)}; this factory remains for old
         * fixtures and must not be used to declare a live session.
         */
        public static CapabilityMatrix hardwareVirtualizationWithGraphics() {
            return from(SessionCapabilities.builder(ExecutionStrategy.HARDWARE_VIRTUALIZATION)
                    .terminal(true)
                    .bridge(true)
                    .hardwareAcceleration(true)
                    .display(SessionCapabilities.DisplayTransport.RFB)
                    .build());
        }

        /**
         * Legacy data preset. Production derives its matrix with
         * {@link #from(SessionCapabilities)}; this factory remains for old
         * fixtures and must not be used to declare a live session.
         */
        public static CapabilityMatrix hardwareVirtualizationWithPeripherals() {
            return from(SessionCapabilities.builder(ExecutionStrategy.HARDWARE_VIRTUALIZATION)
                    .terminal(true)
                    .bridge(true)
                    .hardwareAcceleration(true)
                    .display(SessionCapabilities.DisplayTransport.RFB)
                    .audioOutput(true)
                    .clipboardSync(true)
                    .pointerCapture(true)
                    .build());
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
            if (supports(Capability.GRAPHICS_RFB)) {
                return executionStrategy == ExecutionStrategy.SOFTWARE_VIRTUALIZATION
                        ? "RFB 显示（本地套接字）"
                        : "RFB 显示（vsock CPU 拷贝）";
            }
            if (!supports(Capability.GRAPHICS_WAYLAND)) {
                return "无图形通道";
            }
            return executionStrategy == ExecutionStrategy.SOFTWARE_VIRTUALIZATION
                    ? "Wayland 原生通道（共享内存）"
                    : "Wayland 原生通道（vsock 硬件加速）";
        }

        public boolean supportsRfbDisplay() {
            return supports(Capability.GRAPHICS_RFB);
        }

        public boolean supportsSharedDirectory() {
            return supports(Capability.SHARED_DIRECTORY);
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
