package io.github.nyzeep.droidlayer.runtime.contract;

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
        HARDWARE_ACCELERATION
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

        public ExecutionStrategy executionStrategy() {
            return executionStrategy;
        }

        public Set<Capability> capabilities() {
            return capabilities;
        }

        public boolean supports(Capability capability) {
            return capabilities.contains(Objects.requireNonNull(capability, "capability"));
        }

        public String performanceBadge() {
            return executionStrategy == ExecutionStrategy.SOFTWARE_VIRTUALIZATION
                    ? "完整 Linux · 软件模拟（较慢）"
                    : "完整 Linux · 硬件加速";
        }
    }
}
