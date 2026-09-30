package io.github.nyzeep.droidlayer.runtime.capability;

import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Strategy-neutral support-floor and capability-matrix evaluation for Full Linux Runtime. */
public final class DeviceCapabilityEvaluator {
    public static final int MINIMUM_SDK = 26;
    public static final long MINIMUM_MEMORY_BYTES = 3L * 1024L * 1024L * 1024L;

    private DeviceCapabilityEvaluator() {}

    /**
     * Evaluates immutable device facts supplied by the host platform. Detection itself remains an
     * adapter concern; callers only receive product-level facts and messages.
     */
    public static Assessment evaluate(DeviceProfile profile) {
        Objects.requireNonNull(profile, "profile");
        String refusalReason = null;
        if (profile.sdkInt() < MINIMUM_SDK) {
            refusalReason = "Android 8（API 26）或更高版本";
        } else if (!profile.supportedAbis().contains("arm64-v8a")) {
            refusalReason = "仅支持 arm64-v8a";
        } else if (profile.totalMemoryBytes() < MINIMUM_MEMORY_BYTES) {
            refusalReason = "至少需要 3 GiB 内存";
        }
        String emulatorNotice =
                profile.emulator()
                        ? "检测到 Android 模拟器：Ticket 26 的安装到 shell 验收仍需 arm64 真机。"
                        : null;
        return new Assessment(
                refusalReason == null,
                refusalReason,
                FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline(),
                emulatorNotice);
    }

    /** Immutable host-fact input. It deliberately does not name any execution implementation. */
    public static final class DeviceProfile {
        private final int sdkInt;
        private final List<String> supportedAbis;
        private final long totalMemoryBytes;
        private final boolean emulator;

        public DeviceProfile(
                int sdkInt, List<String> supportedAbis, long totalMemoryBytes, boolean emulator) {
            if (sdkInt < 0) {
                throw new IllegalArgumentException("sdkInt must not be negative");
            }
            if (totalMemoryBytes < 0) {
                throw new IllegalArgumentException("totalMemoryBytes must not be negative");
            }
            List<String> copiedAbis = new ArrayList<>(Objects.requireNonNull(supportedAbis, "supportedAbis"));
            if (copiedAbis.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("supportedAbis must not contain null");
            }
            this.sdkInt = sdkInt;
            this.supportedAbis = Collections.unmodifiableList(copiedAbis);
            this.totalMemoryBytes = totalMemoryBytes;
            this.emulator = emulator;
        }

        public int sdkInt() {
            return sdkInt;
        }

        public List<String> supportedAbis() {
            return supportedAbis;
        }

        public long totalMemoryBytes() {
            return totalMemoryBytes;
        }

        public boolean emulator() {
            return emulator;
        }
    }

    /** Product-facing result consumed by UI and instance creation flows. */
    public static final class Assessment {
        private final boolean canCreateFullLinuxInstance;
        private final String refusalReason;
        private final FullLinuxContract.CapabilityMatrix capabilityMatrix;
        private final String emulatorNotice;

        private Assessment(
                boolean canCreateFullLinuxInstance,
                String refusalReason,
                FullLinuxContract.CapabilityMatrix capabilityMatrix,
                String emulatorNotice) {
            this.canCreateFullLinuxInstance = canCreateFullLinuxInstance;
            this.refusalReason = refusalReason;
            this.capabilityMatrix = capabilityMatrix;
            this.emulatorNotice = emulatorNotice;
        }

        public boolean canCreateFullLinuxInstance() {
            return canCreateFullLinuxInstance;
        }

        /** Null only when the device meets every support-floor condition. */
        public String refusalReason() {
            return refusalReason;
        }

        public FullLinuxContract.CapabilityMatrix capabilityMatrix() {
            return capabilityMatrix;
        }

        public String performanceBadge() {
            return capabilityMatrix.performanceBadge();
        }

        /** Null when the host is not an emulator. This is a disclosure, never a true-device claim. */
        public String emulatorNotice() {
            return emulatorNotice;
        }
    }
}
