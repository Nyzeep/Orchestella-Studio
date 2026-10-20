package io.github.nyzeep.orchestella.runtime.capability;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.SessionCapabilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * AVF hardware-virtualization readiness evaluation for the L2 upgrade path
 * (ADR-0003). Combines the platform virtualization declaration, the SDK
 * floor, the ABI, the {@code MANAGE_VIRTUAL_MACHINE} authorization state and
 * caller-supplied device facts into one honest product-level verdict.
 * Detection itself remains an adapter concern; callers only pass pre-read
 * host facts. There is no built-in SoC blocklist: a model name alone is not
 * evidence, and every capability comes from the platform or the caller.
 */
public final class AvfCapabilityEvaluator {
    /** Android 13+ is the AVF era floor for the authorization wizard. */
    public static final int MINIMUM_SDK = 33;
    public static final String VIRTUALIZATION_FEATURE = "android.software.virtualization_framework";
    public static final String MANAGE_VIRTUAL_MACHINE_PERMISSION =
            "android.permission.MANAGE_VIRTUAL_MACHINE";
    public static final String USE_CUSTOM_VIRTUAL_MACHINE_PERMISSION =
            "android.permission.USE_CUSTOM_VIRTUAL_MACHINE";

    /** A failed permission read is distinct from a confirmed denial. */
    public enum PermissionState { GRANTED, DENIED, UNKNOWN }

    /** Whether the platform declares one VM protection mode. */
    public enum VmSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

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
        boolean explicitlyDisabled = false;
        String property = facts.virtualizationProperty();
        if (property != null && !property.isBlank()) {
            String p = property.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(p) || "1".equals(p) || "yes".equals(p) || "on".equals(p)) {
                declared = true;
            } else if ("false".equals(p) || "0".equals(p) || "no".equals(p) || "off".equals(p)) {
                declared = false;
                explicitlyDisabled = true;
            } else {
                declared = facts.virtualizationFrameworkFeature();
            }
        } else {
            declared = facts.virtualizationFrameworkFeature();
        }
        // A platform that answered the capability read is stronger evidence than
        // an omitted OEM feature string. A permission/reflection failure proves
        // nothing about the platform, and an explicit false property is honored.
        // A present manager service is also platform evidence: some OEM ROMs
        // omit the feature string while shipping the service, and a permission
        // denial must lead to authorization, not to "no platform declared".
        if (!declared && !explicitlyDisabled) {
            if (facts.platformFacts() != null) {
                AvfPlatformFacts.AccessState access = facts.platformFacts().accessState();
                declared = access == AvfPlatformFacts.AccessState.AVAILABLE
                        || access == AvfPlatformFacts.AccessState.MODE_UNSUPPORTED
                        || facts.platformFacts().managerPresent();
            }
        }
        if (!declared) {
            return AvfReadiness.unsupported(List.of("设备未声明虚拟化框架支持"));
        }
        AvfPlatformFacts platformFacts = facts.platformFacts();
        AvfCapabilityEvaluator.PermissionState manageState = platformFacts != null
                ? platformFacts.managePermission()
                : facts.manageVirtualMachinePermissionState();
        AvfCapabilityEvaluator.PermissionState customState = platformFacts != null
                ? platformFacts.customPermission()
                : facts.useCustomVirtualMachinePermissionState();
        if (platformFacts != null) {
            switch (platformFacts.accessState()) {
                case HIDDEN_API_BLOCKED -> {
                    return AvfReadiness.hiddenApiBlocked(List.of(
                            "系统隐藏 API 限制了平台能力读取；本进程豁免未生效或不可用",
                            "这不是权限问题，授权不会改变该状态",
                            "平台访问：" + platformFacts.summary()));
                }
                case REFLECTION_UNAVAILABLE -> {
                    return AvfReadiness.reflectionUnavailable(List.of(
                            "当前系统未提供应用可访问的虚拟化平台接口",
                            "平台访问：" + platformFacts.summary()));
                }
                case MODE_UNSUPPORTED -> {
                    return AvfReadiness.modeUnsupported(List.of(
                            "当前系统未声明本应用所需的非保护型虚拟机模式",
                            "VM 模式：" + platformFacts.modeFacts().summary()));
                }
                case MODE_UNKNOWN -> {
                    return AvfReadiness.modeUnknown(List.of(
                            "无法读取当前系统支持的虚拟机模式",
                            "VM 模式：" + platformFacts.modeFacts().summary()));
                }
                case PERMISSION_DENIED -> {
                    List<String> reasons = new ArrayList<>();
                    permissionReason(reasons, MANAGE_VIRTUAL_MACHINE_PERMISSION, manageState);
                    permissionReason(reasons, USE_CUSTOM_VIRTUAL_MACHINE_PERMISSION, customState);
                    if (reasons.isEmpty()) {
                        reasons.add("两项权限已授予，但系统策略仍拒绝读取虚拟化能力");
                    }
                    return AvfReadiness.needsAuthorization(reasons);
                }
                case AVAILABLE -> {
                    // The typed facts already cover permissions; a contradictory
                    // mode observation is still refused rather than read as READY.
                    if (platformFacts.modeFacts().nonProtectedVm() == VmSupport.UNSUPPORTED) {
                        return AvfReadiness.modeUnsupported(List.of(
                                "当前系统未声明本应用所需的非保护型虚拟机模式",
                                "VM 模式：" + platformFacts.modeFacts().summary()));
                    }
                    if (platformFacts.modeFacts().nonProtectedVm() == VmSupport.UNKNOWN) {
                        return AvfReadiness.modeUnknown(List.of(
                                "无法读取当前系统支持的虚拟机模式",
                                "VM 模式：" + platformFacts.modeFacts().summary()));
                    }
                }
            }
        } else {
            AvfModeFacts modeFacts = facts.modeFacts();
            if (modeFacts != null) {
                if (modeFacts.nonProtectedVm() == VmSupport.UNSUPPORTED) {
                    return AvfReadiness.modeUnsupported(List.of(
                            "当前系统未声明本应用所需的非保护型虚拟机模式",
                            "VM 模式：" + modeFacts.summary()));
                }
                if (modeFacts.nonProtectedVm() == VmSupport.UNKNOWN) {
                    return AvfReadiness.modeUnknown(List.of(
                            "无法读取当前系统支持的虚拟机模式",
                            "VM 模式：" + modeFacts.summary()));
                }
            }
        }
        List<String> authorizationReasons = new ArrayList<>();
        permissionReason(authorizationReasons, MANAGE_VIRTUAL_MACHINE_PERMISSION, manageState);
        permissionReason(authorizationReasons, USE_CUSTOM_VIRTUAL_MACHINE_PERMISSION, customState);
        if (!authorizationReasons.isEmpty()) {
            return AvfReadiness.needsAuthorization(authorizationReasons);
        }
        return AvfReadiness.ready(hardwareMatrix());
    }

    private static void permissionReason(List<String> reasons, String name, PermissionState state) {
        if (state != PermissionState.GRANTED) {
            reasons.add(name + (state == PermissionState.UNKNOWN ? "：读取失败或尚未确认" : "：未授权"));
        }
    }

    /**
     * Matches only caller-supplied device facts. The evaluator owns no SoC
     * list, so a marketing/model string can never silently disqualify a
     * device that the platform reports as supported.
     */
    private static String blockedHintMatch(AvfHostFacts facts) {
        String soc = normalize(facts.socModel());
        String device = normalize(facts.deviceModel());
        for (String extra : facts.extraBlockedHints()) {
            String hint = normalize(extra);
            if (hint == null) {
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

    /**
     * The pre-launch AVF upper bound. It mirrors the engine's wired data plane
     * (terminal, bridge, RFB display) and never advertises audio, clipboard,
     * pointer capture or GPU acceleration.
     */
    private static FullLinuxContract.CapabilityMatrix hardwareMatrix() {
        return FullLinuxContract.CapabilityMatrix.from(
                SessionCapabilities.builder(FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION)
                        .terminal(true)
                        .bridge(true)
                        .hardwareAcceleration(true)
                        .display(SessionCapabilities.DisplayTransport.RFB)
                        .build());
    }

    /** The honest fallback matrix: a software session with terminal and bridge only. */
    private static FullLinuxContract.CapabilityMatrix softwareFallbackMatrix() {
        return FullLinuxContract.CapabilityMatrix.from(
                SessionCapabilities.builder(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION)
                        .terminal(true)
                        .bridge(true)
                        .build());
    }

    /** Immutable host-fact input for one AVF readiness check. */
    public static final class AvfHostFacts {
        private final int sdkInt;
        private final List<String> supportedAbis;
        private final String virtualizationProperty;
        private final boolean virtualizationFrameworkFeature;
        private final PermissionState manageVirtualMachinePermission;
        private final PermissionState useCustomVirtualMachinePermission;
        private final String socModel;
        private final String deviceModel;
        private final List<String> extraBlockedHints;
        private final AvfModeFacts modeFacts;
        private final AvfPlatformFacts platformFacts;

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
            this.useCustomVirtualMachinePermission = builder.useCustomVirtualMachinePermission;
            this.socModel = builder.socModel;
            this.deviceModel = builder.deviceModel;
            this.extraBlockedHints = Collections.unmodifiableList(copiedHints);
            this.modeFacts = builder.modeFacts;
            this.platformFacts = builder.platformFacts;
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
            return manageVirtualMachinePermission == PermissionState.GRANTED;
        }

        public boolean useCustomVirtualMachinePermission() {
            return useCustomVirtualMachinePermission == PermissionState.GRANTED;
        }

        public PermissionState manageVirtualMachinePermissionState() {
            return manageVirtualMachinePermission;
        }

        public PermissionState useCustomVirtualMachinePermissionState() {
            return useCustomVirtualMachinePermission;
        }

        public boolean hasBothVirtualMachinePermissions() {
            return manageVirtualMachinePermission() && useCustomVirtualMachinePermission();
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

        /** Null for legacy callers that have not read VM mode capabilities yet. */
        public AvfModeFacts modeFacts() {
            return modeFacts;
        }

        /** Null for legacy callers that do not supply the shared platform access observation. */
        public AvfPlatformFacts platformFacts() {
            return platformFacts;
        }

        public static final class Builder {
            private final int sdkInt;
            private final List<String> supportedAbis;
            private final String virtualizationProperty;
            private final boolean virtualizationFrameworkFeature;
            private final PermissionState manageVirtualMachinePermission;
        private final PermissionState useCustomVirtualMachinePermission;
            private String socModel;
            private String deviceModel;
            private List<String> extraBlockedHints = Collections.emptyList();
            private AvfModeFacts modeFacts;
            private AvfPlatformFacts platformFacts;

            /** Compatibility input: a MANAGE-only caller has not established CUSTOM. */
            public Builder(int sdkInt, List<String> supportedAbis, String virtualizationProperty,
                    boolean virtualizationFrameworkFeature, boolean managePermission) {
                this(sdkInt, supportedAbis, virtualizationProperty, virtualizationFrameworkFeature,
                        managePermission ? PermissionState.GRANTED : PermissionState.DENIED,
                        PermissionState.UNKNOWN);
            }

            public Builder(int sdkInt, List<String> supportedAbis, String virtualizationProperty,
                    boolean virtualizationFrameworkFeature, boolean managePermission, boolean customPermission) {
                this(sdkInt, supportedAbis, virtualizationProperty, virtualizationFrameworkFeature,
                        managePermission ? PermissionState.GRANTED : PermissionState.DENIED,
                        customPermission ? PermissionState.GRANTED : PermissionState.DENIED);
            }

            public Builder(int sdkInt, List<String> supportedAbis, String virtualizationProperty,
                    boolean virtualizationFrameworkFeature, PermissionState managePermission,
                    PermissionState customPermission) {
                this.sdkInt = sdkInt;
                this.supportedAbis = supportedAbis;
                this.virtualizationProperty = virtualizationProperty;
                this.virtualizationFrameworkFeature = virtualizationFrameworkFeature;
                this.manageVirtualMachinePermission = Objects.requireNonNull(managePermission, "managePermission");
                this.useCustomVirtualMachinePermission = Objects.requireNonNull(customPermission, "customPermission");
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

            public Builder modeFacts(AvfModeFacts modeFacts) {
                this.modeFacts = modeFacts;
                return this;
            }

            public Builder platformFacts(AvfPlatformFacts platformFacts) {
                this.platformFacts = platformFacts;
                return this;
            }

            public AvfHostFacts build() {
                return new AvfHostFacts(this);
            }
        }
    }

    /**
     * One immutable observation of the platform's declared VM modes. The
     * source/actor/time/ROM fields keep a later UI from presenting a stale or
     * actor-mismatched capability as the current app's own capability.
     */
    public static final class AvfModeFacts {
        private final VmSupport protectedVm;
        private final VmSupport nonProtectedVm;
        private final String source;
        private final String actor;
        private final long sampledAtMillis;
        private final String romBinding;

        public AvfModeFacts(
                VmSupport protectedVm,
                VmSupport nonProtectedVm,
                String source,
                String actor,
                long sampledAtMillis,
                String romBinding) {
            this.protectedVm = Objects.requireNonNull(protectedVm, "protectedVm");
            this.nonProtectedVm = Objects.requireNonNull(nonProtectedVm, "nonProtectedVm");
            this.source = requireNonBlank(source, "source");
            this.actor = requireNonBlank(actor, "actor");
            if (sampledAtMillis < 0) {
                throw new IllegalArgumentException("sampledAtMillis must not be negative");
            }
            this.sampledAtMillis = sampledAtMillis;
            this.romBinding = requireNonBlank(romBinding, "romBinding");
        }

        public static AvfModeFacts unknown(
                String source, String actor, long sampledAtMillis, String romBinding) {
            return new AvfModeFacts(
                    VmSupport.UNKNOWN, VmSupport.UNKNOWN, source, actor, sampledAtMillis, romBinding);
        }

        public VmSupport protectedVm() {
            return protectedVm;
        }

        public VmSupport nonProtectedVm() {
            return nonProtectedVm;
        }

        public String source() {
            return source;
        }

        public String actor() {
            return actor;
        }

        public long sampledAtMillis() {
            return sampledAtMillis;
        }

        public String romBinding() {
            return romBinding;
        }

        /** Human-readable, backend-invisible summary for the honest status page. */
        public String summary() {
            return "protected=" + supportLabel(protectedVm)
                    + "；non-protected=" + supportLabel(nonProtectedVm)
                    + "；来源=" + source
                    + "；actor=" + actor
                    + "；采样=" + sampledAtMillis
                    + "；ROM=已绑定（本地，不展示原始值）";
        }

        private static String supportLabel(VmSupport support) {
            return switch (support) {
                case SUPPORTED -> "支持";
                case UNSUPPORTED -> "不支持";
                case UNKNOWN -> "未知";
            };
        }

        private static String requireNonBlank(String value, String field) {
            Objects.requireNonNull(value, field);
            if (value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }

    /** Verdict status; UI never branches on any execution engine name. */
    public enum AvfStatus {
        READY,
        NEEDS_AUTHORIZATION,
        HIDDEN_API_BLOCKED,
        REFLECTION_UNAVAILABLE,
        MODE_UNSUPPORTED,
        MODE_UNKNOWN,
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
                    AvfStatus.NEEDS_AUTHORIZATION, reasons, softwareFallbackMatrix());
        }

        static AvfReadiness hiddenApiBlocked(List<String> reasons) {
            return new AvfReadiness(
                    AvfStatus.HIDDEN_API_BLOCKED, reasons, softwareFallbackMatrix());
        }

        static AvfReadiness reflectionUnavailable(List<String> reasons) {
            return new AvfReadiness(
                    AvfStatus.REFLECTION_UNAVAILABLE, reasons, softwareFallbackMatrix());
        }

        static AvfReadiness modeUnsupported(List<String> reasons) {
            return new AvfReadiness(
                    AvfStatus.MODE_UNSUPPORTED, reasons, softwareFallbackMatrix());
        }

        static AvfReadiness modeUnknown(List<String> reasons) {
            return new AvfReadiness(
                    AvfStatus.MODE_UNKNOWN, reasons, softwareFallbackMatrix());
        }

        static AvfReadiness unsupported(List<String> reasons) {
            return new AvfReadiness(
                    AvfStatus.UNSUPPORTED, reasons, softwareFallbackMatrix());
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
            return status == AvfStatus.READY || status == AvfStatus.NEEDS_AUTHORIZATION;
        }

        public FullLinuxContract.CapabilityMatrix capabilityMatrix() {
            return capabilityMatrix;
        }

        public String performanceBadge() {
            return status == AvfStatus.READY
                    ? "完整 Linux · 权限预检满足（启动待验证）"
                    : capabilityMatrix.performanceBadge();
        }
    }
}
