package android.system.virtualmachine;

import android.content.Context;
import java.util.Objects;

/** Official app-payload configuration shape with call-count probes for tests. */
public final class VirtualMachineConfig {
    public static final int CPU_TOPOLOGY_ONE_CPU = 0;
    public static final int CPU_TOPOLOGY_MATCH_HOST = 1;
    public static final int DEBUG_LEVEL_NONE = 0;
    public static final int DEBUG_LEVEL_FULL = 1;

    private final String payloadBinaryName;
    private final boolean protectedVm;
    private final long memoryBytes;
    private final int cpuTopology;
    private final long encryptedStorageBytes;
    private final int debugLevel;
    private final Builder origin;
    private final String apkPath;
    private final int payloadSetCalls;
    private final int protectedSetCalls;
    private final int memorySetCalls;
    private final int cpuSetCalls;
    private final int storageSetCalls;
    private final int debugSetCalls;

    private VirtualMachineConfig(Builder builder) {
        this(builder, null);
    }

    private VirtualMachineConfig(Builder builder, String apkPathOverride) {
        payloadBinaryName = builder.payloadBinaryName;
        protectedVm = builder.protectedVm;
        memoryBytes = builder.memoryBytes;
        cpuTopology = builder.cpuTopology;
        encryptedStorageBytes = builder.encryptedStorageBytes;
        debugLevel = builder.debugLevel;
        origin = builder;
        apkPath = apkPathOverride;
        payloadSetCalls = builder.payloadSetCalls;
        protectedSetCalls = builder.protectedSetCalls;
        memorySetCalls = builder.memorySetCalls;
        cpuSetCalls = builder.cpuSetCalls;
        storageSetCalls = builder.storageSetCalls;
        debugSetCalls = builder.debugSetCalls;
    }

    public String getPayloadBinaryName() {
        return payloadBinaryName;
    }

    public boolean isProtectedVm() {
        return protectedVm;
    }

    public long getMemoryBytes() {
        return memoryBytes;
    }

    public int getCpuTopology() {
        return cpuTopology;
    }

    public long getEncryptedStorageBytes() {
        return encryptedStorageBytes;
    }

    public int getDebugLevel() {
        return debugLevel;
    }

    public boolean isCompatibleWith(VirtualMachineConfig other) {
        return protectedVm == other.protectedVm
                && Objects.equals(payloadBinaryName, other.payloadBinaryName)
                && Objects.equals(debugLevel, other.debugLevel);
    }

    public String getApkPath() {
        return apkPath;
    }

    /**
     * Simulates the platform-side drift of a two-package install: the extension
     * APK was reinstalled, so the stored config now points at a path that no
     * longer exists. The request's own config is unchanged — there is still no
     * Builder.setApkPath on this fake, which is what the redirect test relies on.
     */
    public VirtualMachineConfig withApkPathForTest(String path) {
        return new VirtualMachineConfig(origin, path);
    }

    public int payloadSetCallsForTest() {
        return payloadSetCalls;
    }

    public int protectedSetCallsForTest() {
        return protectedSetCalls;
    }

    public int memorySetCallsForTest() {
        return memorySetCalls;
    }

    public int cpuSetCallsForTest() {
        return cpuSetCalls;
    }

    public int storageSetCallsForTest() {
        return storageSetCalls;
    }

    public int debugSetCallsForTest() {
        return debugSetCalls;
    }

    public static final class Builder {
        private final String packageName;
        private String payloadBinaryName;
        private boolean protectedVm;
        private boolean protectedSet;
        private long memoryBytes;
        private int cpuTopology = CPU_TOPOLOGY_ONE_CPU;
        private long encryptedStorageBytes;
        private int debugLevel = DEBUG_LEVEL_NONE;
        private int payloadSetCalls;
        private int protectedSetCalls;
        private int memorySetCalls;
        private int cpuSetCalls;
        private int storageSetCalls;
        private int debugSetCalls;

        public Builder(Context context) {
            packageName = Objects.requireNonNull(context, "context").getPackageName();
        }

        public String packageNameForTest() {
            return packageName;
        }

        public Builder setPayloadBinaryName(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("payload name must not be a path");
            }
            payloadBinaryName = name;
            payloadSetCalls++;
            return this;
        }

        public Builder setProtectedVm(boolean value) {
            protectedVm = value;
            protectedSet = true;
            protectedSetCalls++;
            return this;
        }

        public Builder setMemoryBytes(long value) {
            if (value <= 0) {
                throw new IllegalArgumentException("memory must be positive");
            }
            memoryBytes = value;
            memorySetCalls++;
            return this;
        }

        public Builder setCpuTopology(int topology) {
            if (topology != CPU_TOPOLOGY_ONE_CPU && topology != CPU_TOPOLOGY_MATCH_HOST) {
                throw new IllegalArgumentException("unsupported CPU topology");
            }
            cpuTopology = topology;
            cpuSetCalls++;
            return this;
        }

        public Builder setEncryptedStorageBytes(long value) {
            if (value <= 0) {
                throw new IllegalArgumentException("encrypted storage must be positive");
            }
            encryptedStorageBytes = value;
            storageSetCalls++;
            return this;
        }

        public Builder setDebugLevel(int level) {
            if (level != DEBUG_LEVEL_NONE && level != DEBUG_LEVEL_FULL) {
                throw new IllegalArgumentException("unsupported debug level");
            }
            debugLevel = level;
            debugSetCalls++;
            return this;
        }

        public VirtualMachineConfig build() {
            if (payloadBinaryName == null) {
                throw new IllegalStateException("setPayloadBinaryName must be called");
            }
            if (!protectedSet) {
                throw new IllegalStateException("setProtectedVm must be called");
            }
            return new VirtualMachineConfig(this);
        }
    }
}
