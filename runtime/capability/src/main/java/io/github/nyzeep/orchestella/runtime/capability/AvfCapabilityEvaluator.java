package io.github.nyzeep.orchestella.runtime.capability;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * AVF hardware-virtualization readiness evaluation for the L2 upgrade path
 * (ADR-0003). Combines the platform virtualization declaration, the SDK
 * floor, the ABI, the {@code MANAGE_VIRTUAL_MACHINE} authorization state and
 * a hardware blocklist into one honest product-level verdict. Detection
 * itself remains an adapter concern; callers only pass pre-read host facts.
 */
public final class AvfCapabilityEvaluator {
    /** Android 13+ is the AVF era floor for the authorization wizard. */
    public static final int MINIMUM_SDK = 33;
    public static final String VIRTUALIZATION_FEATURE = "android.software.virtualization_framework";
    public static final String MANAGE_VIRTUAL_MACHINE_PERMISSION =
            "android.permission.MANAGE_VIRTUAL_MACHINE";

    /**
     * SoCs whose firmware blocks non-protected (custom) VMs even when the
     * platform declares virtualization support. Snapdragon 8 Elite (SM8750,
     * Gunyah) is the confirmed case; custom VM creation fails at the
     * hardware/firmware level there, so the hint must win over the property.
     */
    static final List<String> BLOCKED_SOC_HINTS = List.of("sm8750");

    private AvfCapabilityEvaluator() {}

    public static AvfReadiness evaluate(AvfHostFacts facts) {
        Objects.requireNonNull(facts, "facts");
        if (facts.sdkInt() < MINIMUM_SDK) {
            return AvfReadiness.unsupported(List.of("需要 Android 13（API 33）或更高版本"));
        }
        if (!facts.supportedAbis().contains("arm64-v8a")) {
            return AvfReadiness.unsupported(List.of("仅支持 arm64-v8a 的设备"));
        }
        String blockedHint = blockedHintMatch(facts);
        if (blockedHint != null) {
            return AvfReadiness.unsupported(List.of(
                    "该机型芯片在硬件层面不开放非保护虚拟机，已自动保持在软件模拟模式"));
        }
        boolean declared = false;
        String property = facts.virtualizationProperty();
        if (property != null && !property.isBlank()) {
            String p = property.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(p) || "1".equals(p) || "yes".equals(p) || "on".equals(p)) {
                declared = true;
            } else if ("false".equals(p) || "0".equals(p) || "no".equals(p) || "off".equals(p)) {
                declared = false;
            } else {
                declared = facts.virtualizationFrameworkFeature();
            }
        } else {
            declared = facts.virtualizationFrameworkFeature();
        }
        if (!declared) {
            return AvfReadiness.unsupported(List.of("设备未声明虚拟化框架支持"));
        }
        if (!facts.manageVirtualMachinePermission()) {
            return AvfReadiness.needsAuthorization(
                    List.of("需要一次开发级授权才能启用硬件加速"));
        }
        return AvfReadiness.ready(hardwareMatrix());
    }

    private static String blockedHintMatch(AvfHostFacts facts) {
        String soc = normalize(facts.socModel());
        String device = normalize(facts.deviceModel());
        List<String> hints = new ArrayList<>(BLOCKED_SOC_HINTS);
        for (String extra : facts.extraBlockedHints()) {
            hints.add(normalize(extra));
        }
        for (String hint : hints) {
            if (hint.isBlank()) {
                continue;
            }
            if ((soc != null && soc.contains(hint)) || (device != null && device.contains(hint))) {
                return hint;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static FullLinuxContract.CapabilityMatrix hardwareMatrix() {
        return new FullLinuxContract.CapabilityMatrix(
                FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                Set.of(
                        FullLinuxContract.Capability.TERMINAL,
                        FullLinuxContract.Capability.BRIDGE_HANDSHAKE,
                        FullLinuxContract.Capability.HARDWARE_ACCELERATION));
    }

    /** Immutable host-fact input for one AVF readiness check. */
    public static final class AvfHostFacts {
        private final int sdkInt;
        private final List<String> supportedAbis;
        private final String virtualizationProperty;
        private final boolean virtualizationFrameworkFeature;
        private final boolean manageVirtualMachinePermission;
        private final String socModel;
        private final String deviceModel;
        private final List<String> extraBlockedHints;

        private AvfHostFacts(Builder builder) {
            if (builder.sdkInt < 0) {
                throw new IllegalArgumentException("sdkInt must not be negative");
            }
            List<String> copiedAbis = new ArrayList<>(
                    Objects.requireNonNull(builder.supportedAbis, "supportedAbis"));
            if (copiedAbis.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("supportedAbis must not contain null");
            }
            List<String> copiedHints = new ArrayList<>(
                    Objects.requireNonNull(builder.extraBlockedHints, "extraBlockedHints"));
            if (copiedHints.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("extraBlockedHints must not contain null");
            }
            this.sdkInt = builder.sdkInt;
            this.supportedAbis = Collections.unmodifiableList(copiedAbis);
            this.virtualizationProperty = builder.virtualizationProperty;
            this.virtualizationFrameworkFeature = builder.virtualizationFrameworkFeature;
            this.manageVirtualMachinePermission = builder.manageVirtualMachinePermission;
            this.socModel = builder.socModel;
            this.deviceModel = builder.deviceModel;
            this.extraBlockedHints = Collections.unmodifiableList(copiedHints);
        }

        public int sdkInt() {
            return sdkInt;
        }

        public List<String> supportedAbis() {
            return supportedAbis;
        }

        /** Raw {@code ro.virtualization.supported} value; null when unreadable. */
        public String virtualizationProperty() {
            return virtualizationProperty;
        }

        public boolean virtualizationFrameworkFeature() {
            return virtualizationFrameworkFeature;
        }

        public boolean manageVirtualMachinePermission() {
            return manageVirtualMachinePermission;
        }

        public String socModel() {
            return socModel;
        }

        public String deviceModel() {
            return deviceModel;
        }

        public List<String> extraBlockedHints() {
            return extraBlockedHints;
        }

        public static final class Builder {
            private final int sdkInt;
            private final List<String> supportedAbis;
            private final String virtualizationProperty;
            private final boolean virtualizationFrameworkFeature;
            private final boolean manageVirtualMachinePermission;
            private String socModel;
            private String deviceModel;
            private List<String> extraBlockedHints = Collections.emptyList();

            public Builder(
                    int sdkInt,
                    List<String> supportedAbis,
                    String virtualizationProperty,
                    boolean virtualizationFrameworkFeature,
                    boolean manageVirtualMachinePermission) {
                this.sdkInt = sdkInt;
                this.supportedAbis = supportedAbis;
                this.virtualizationProperty = virtualizationProperty;
                this.virtualizationFrameworkFeature = virtualizationFrameworkFeature;
                this.manageVirtualMachinePermission = manageVirtualMachinePermission;
            }

            public Builder socModel(String socModel) {
                this.socModel = socModel;
                return this;
            }

            public Builder deviceModel(String deviceModel) {
                this.deviceModel = deviceModel;
                return this;
            }

            public Builder extraBlockedHints(List<String> extraBlockedHints) {
                this.extraBlockedHints = extraBlockedHints;
                return this;
            }

            public AvfHostFacts build() {
                return new AvfHostFacts(this);
            }
        }
    }

    /** Verdict status; UI never branches on any execution engine name. */
    public enum AvfStatus {
        READY,
        NEEDS_AUTHORIZATION,
        UNSUPPORTED
    }

    /** Product-facing readiness consumed by the wizard and the launch flow. */
    public static final class AvfReadiness {
        private final AvfStatus status;
        private final List<String> reasons;
        private final FullLinuxContract.CapabilityMatrix capabilityMatrix;

        private AvfReadiness(
                AvfStatus status,
                List<String> reasons,
                FullLinuxContract.CapabilityMatrix capabilityMatrix) {
            this.status = Objects.requireNonNull(status, "status");
            this.reasons = Collections.unmodifiableList(
                    new ArrayList<>(Objects.requireNonNull(reasons, "reasons")));
            this.capabilityMatrix =
                    Objects.requireNonNull(capabilityMatrix, "capabilityMatrix");
        }

        static AvfReadiness ready(FullLinuxContract.CapabilityMatrix hardwareMatrix) {
            return new AvfReadiness(AvfStatus.READY, List.of(), hardwareMatrix);
        }

        static AvfReadiness needsAuthorization(List<String> reasons) {
            return new AvfReadiness(
                    AvfStatus.NEEDS_AUTHORIZATION, reasons,
                    FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline());
        }

        static AvfReadiness unsupported(List<String> reasons) {
            return new AvfReadiness(
                    AvfStatus.UNSUPPORTED, reasons,
                    FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline());
        }

        public AvfStatus status() {
            return status;
        }

        /** Human-readable, backend-invisible reasons; empty when READY. */
        public List<String> reasons() {
            return reasons;
        }

        /**
         * Whether the device may enter the authorization/upgrade flow at all.
         * Hardware-blocked and floor-refused devices silently close the wizard
         * (ADR-0003): only they report {@code false}.
         */
        public boolean wizardEligible() {
            return status != AvfStatus.UNSUPPORTED;
        }

        public FullLinuxContract.CapabilityMatrix capabilityMatrix() {
            return capabilityMatrix;
        }

        public String performanceBadge() {
            return capabilityMatrix.performanceBadge();
        }
    }
}
