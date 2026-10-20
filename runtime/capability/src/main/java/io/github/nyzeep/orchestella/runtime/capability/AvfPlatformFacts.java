package io.github.nyzeep.orchestella.runtime.capability;

import java.util.Objects;

/**
 * One immutable observation of the platform access surface: whether the
 * virtualization classes/methods were readable, why not when they were not,
 * the two runtime permission states and the declared VM modes. The App probe
 * and the AVF engine consume this same value; neither keeps a second set of
 * capability bits or failure branches.
 */
public final class AvfPlatformFacts {
    /** The one typed answer to "can this process read the platform surface?". */
    public enum AccessState {
        AVAILABLE,
        HIDDEN_API_BLOCKED,
        PERMISSION_DENIED,
        REFLECTION_UNAVAILABLE,
        MODE_UNSUPPORTED,
        MODE_UNKNOWN
    }

    /** Whether the one-shot hidden-API exemption was installed for this process. */
    public enum ExemptionState {
        NOT_ATTEMPTED,
        INSTALLED,
        FAILED
    }

    private final AccessState accessState;
    private final ExemptionState exemptionState;
    private final AvfCapabilityEvaluator.PermissionState managePermission;
    private final AvfCapabilityEvaluator.PermissionState customPermission;
    private final AvfCapabilityEvaluator.AvfModeFacts modeFacts;

    public AvfPlatformFacts(
            AccessState accessState,
            ExemptionState exemptionState,
            AvfCapabilityEvaluator.PermissionState managePermission,
            AvfCapabilityEvaluator.PermissionState customPermission,
            AvfCapabilityEvaluator.AvfModeFacts modeFacts) {
        this.accessState = Objects.requireNonNull(accessState, "accessState");
        this.exemptionState = Objects.requireNonNull(exemptionState, "exemptionState");
        this.managePermission = Objects.requireNonNull(managePermission, "managePermission");
        this.customPermission = Objects.requireNonNull(customPermission, "customPermission");
        this.modeFacts = Objects.requireNonNull(modeFacts, "modeFacts");
    }

    public AccessState accessState() {
        return accessState;
    }

    public ExemptionState exemptionState() {
        return exemptionState;
    }

    public AvfCapabilityEvaluator.PermissionState managePermission() {
        return managePermission;
    }

    public AvfCapabilityEvaluator.PermissionState customPermission() {
        return customPermission;
    }

    public AvfCapabilityEvaluator.AvfModeFacts modeFacts() {
        return modeFacts;
    }

    /** True only when the platform answered the capability read itself. */
    public boolean readable() {
        return accessState == AccessState.AVAILABLE;
    }

    /** Human-readable, backend-invisible summary for the status page. */
    public String summary() {
        return "访问=" + accessLabel(accessState)
                + "；豁免=" + exemptionLabel(exemptionState)
                + "；" + modeFacts.summary();
    }

    public static String accessLabel(AccessState state) {
        return switch (state) {
            case AVAILABLE -> "可用";
            case HIDDEN_API_BLOCKED -> "hidden API 受限";
            case PERMISSION_DENIED -> "权限未授予或平台拒绝";
            case REFLECTION_UNAVAILABLE -> "平台接口缺失";
            case MODE_UNSUPPORTED -> "不支持非保护型 VM";
            case MODE_UNKNOWN -> "无法读取";
        };
    }

    public static String exemptionLabel(ExemptionState state) {
        return switch (state) {
            case NOT_ATTEMPTED -> "未尝试";
            case INSTALLED -> "已安装";
            case FAILED -> "安装失败";
        };
    }
}
