package android.system.virtualmachine;

import android.content.Context;
import java.util.Objects;

/** Official configuration shape: VM settings wrap a separate custom image config. */
public final class VirtualMachineConfig {
    public static final int CPU_TOPOLOGY_ONE_CPU = 0;
    public static final int CPU_TOPOLOGY_MATCH_HOST = 1;
    private final VirtualMachineCustomImageConfig customImage;
    private final boolean protectedVm;
    private final long memoryBytes;
    private final int cpuTopology;

    private VirtualMachineConfig(Builder builder) {
        customImage = Objects.requireNonNull(builder.customImage);
        protectedVm = builder.protectedVm;
        memoryBytes = builder.memoryBytes;
        cpuTopology = builder.cpuTopology;
    }

    public VirtualMachineCustomImageConfig getCustomImageConfig() { return customImage; }
    public boolean isProtectedVm() { return protectedVm; }
    public long getMemoryBytes() { return memoryBytes; }
    public int getCpuTopology() { return cpuTopology; }
    public long getEncryptedStorageBytes() { return 0; }
    public boolean shouldUseHugepages() { return false; }
    /** Official identity compatibility deliberately does not compare RAM, CPU or custom image. */
    public boolean isCompatibleWith(VirtualMachineConfig other) {
        return protectedVm == other.protectedVm;
    }

    public static final class Builder {
        private VirtualMachineCustomImageConfig customImage;
        private boolean protectedVm;
        private boolean protectedSet;
        private long memoryBytes;
        private int cpuTopology = CPU_TOPOLOGY_ONE_CPU;

        public Builder(Context context) { Objects.requireNonNull(context); }
        public Builder setCustomImageConfig(VirtualMachineCustomImageConfig image) {
            customImage = image;
            return this;
        }
        public Builder setProtectedVm(boolean value) {
            protectedVm = value;
            protectedSet = true;
            return this;
        }
        public Builder setMemoryBytes(long value) {
            if (value <= 0) throw new IllegalArgumentException("memory must be positive");
            memoryBytes = value;
            return this;
        }
        public Builder setCpuTopology(int topology) {
            if (topology != CPU_TOPOLOGY_ONE_CPU && topology != CPU_TOPOLOGY_MATCH_HOST) {
                throw new IllegalArgumentException("unsupported CPU topology");
            }
            cpuTopology = topology;
            return this;
        }
        public VirtualMachineConfig build() {
            if (!protectedSet) throw new IllegalStateException("setProtectedVm required");
            return new VirtualMachineConfig(this);
        }
    }
}
