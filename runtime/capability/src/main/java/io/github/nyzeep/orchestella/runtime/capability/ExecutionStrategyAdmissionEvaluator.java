package io.github.nyzeep.orchestella.runtime.capability;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.SessionCapabilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-JVM single source of truth for W3-05 (R-05) standalone UML promotion,
 * device support floor enforcement, capability-matrix intersection gating,
 * and preflight incomplete/timeout fallback decisions.
 *
 * <p>Strictly fail-closed: standalone UML opt-in or conditional recommendation is
 * unlocked only for matching CLI workloads after Phase 1 sign-off, 4 KB and 16 KB
 * physical host-page matrices, ACR approval, full-package payload + license closure,
 * and paired cold/warm benchmarks meeting every p95 and >=20% QEMU speedup threshold
 * with zero unexplained timeouts. Never reads ride-along compile face state.
 */
public final class ExecutionStrategyAdmissionEvaluator {
    public static final int MIN_SAMPLE_PAIRS_PER_BUCKET = 30;
    public static final long COLD_P95_LIMIT_MS = 10_000L;
    public static final long WARM_P95_LIMIT_MS = 3_000L;
    public static final double MIN_QEMU_SPEEDUP_RATIO = 0.20d;

    private ExecutionStrategyAdmissionEvaluator() {}

    /** Aggregated paired UML vs QEMU benchmark evidence for one readiness bucket. */
    public record PairedPerformanceBucket(
            String bucketName,
            int samplePairs,
            long umlP50Ms,
            long umlP95Ms,
            long qemuP50Ms,
            long qemuP95Ms,
            int umlTimeoutCount,
            int qemuTimeoutCount,
            int unexplainedTimeoutCount) {
        public PairedPerformanceBucket {
            bucketName = bucketName == null ? "" : bucketName.trim();
        }

        /**
         * Relative p95 improvement over QEMU: {@code (qemuP95Ms - umlP95Ms) / qemuP95Ms}.
         * Returns {@code Double.NaN} when either p95 denominator/numerator is invalid.
         */
        public double speedupOverQemuRatio() {
            if (qemuP95Ms <= 0L || umlP95Ms <= 0L) {
                return Double.NaN;
            }
            return (qemuP95Ms - umlP95Ms) / (double) qemuP95Ms;
        }
    }

    /** Release-level evidence bundle governing whether standalone UML may be offered. */
    public record PromotionEvidence(
            boolean phase1SignedOff,
            boolean host4kMatrixPassed,
            boolean host16kMatrixPassed,
            boolean acrApproved,
            String acrReference,
            boolean fullPkgPayloadGatePassed,
            boolean noticeAttributionClosed,
            Set<Integer> verifiedHostPageSizes,
            PairedPerformanceBucket coldProvisioned,
            PairedPerformanceBucket warmBoot) {
        public PromotionEvidence {
            acrReference = acrReference == null ? "" : acrReference.trim();
            verifiedHostPageSizes = verifiedHostPageSizes == null
                    ? Collections.emptySet()
                    : Set.copyOf(verifiedHostPageSizes);
        }
    }

    /** Standalone UML preflight status — intentionally decoupled from compile ride-along status. */
    public enum StandalonePreflightStatus {
        READY,
        INCOMPLETE,
        TIMED_OUT,
        ASSETS_MISSING,
        UNSUPPORTED_HOST_PAGE_SIZE,
        UNAVAILABLE
    }

    /** Ephemeral standalone UML runtime preflight result for one device/session check. */
    public record StandaloneUmlPreflight(
            StandalonePreflightStatus status,
            int hostPageSizeBytes,
            long elapsedMs,
            String detail) {
        public StandaloneUmlPreflight {
            Objects.requireNonNull(status, "status");
            detail = detail == null ? "" : detail.trim();
        }
    }

    /** Requested workload and distro requirements for a new instance. */
    public record WorkloadRequirements(
            String distroId,
            boolean distroCreatableOnUml,
            boolean requiresSystemd,
            boolean requiresContainer,
            boolean requiresGpu,
            boolean requiresDesktop,
            boolean requiresSharedDirectory,
            boolean requiresEditor) {
        public WorkloadRequirements {
            distroId = distroId == null ? "" : distroId.trim().toLowerCase(Locale.ROOT);
        }

        public boolean isCliOnly() {
            return !requiresSystemd
                    && !requiresContainer
                    && !requiresGpu
                    && !requiresDesktop
                    && !requiresSharedDirectory
                    && !requiresEditor;
        }
    }

    /** Product-facing exposure state for standalone UML on a new instance. */
    public enum OfferMode {
        LOCKED,
        OPT_IN,
        CONDITIONAL_RECOMMENDED
    }

    /** Machine-readable reason why UML opt-in or recommendation is locked or refused. */
    public enum GateBlockerCode {
        DEVICE_FLOOR_UNSUPPORTED,
        PHASE1_NOT_SIGNED_OFF,
        HOST_4K_MATRIX_MISSING,
        HOST_16K_MATRIX_MISSING,
        ACR_NOT_APPROVED,
        PAYLOAD_GATE_FAILED,
        NOTICE_ATTRIBUTION_OPEN,
        BENCHMARK_EVIDENCE_MISSING,
        INSUFFICIENT_SAMPLE_PAIRS,
        COLD_P95_EXCEEDED,
        WARM_P95_EXCEEDED,
        SPEEDUP_BELOW_THRESHOLD,
        UNEXPLAINED_TIMEOUT,
        HOST_PAGE_SIZE_NOT_VERIFIED,
        UML_PREFLIGHT_INCOMPLETE,
        UML_PREFLIGHT_TIMEOUT,
        UML_ASSETS_MISSING,
        UML_UNAVAILABLE,
        DISTRO_UNSUPPORTED_ON_UML,
        REQUIRES_SYSTEMD,
        REQUIRES_CONTAINER,
        REQUIRES_GPU,
        REQUIRES_DESKTOP,
        REQUIRES_SHARED_DIRECTORY,
        REQUIRES_EDITOR,
        RECOMMENDATION_REVOKED
    }

    public record GateBlocker(GateBlockerCode code, String reason) {
        public GateBlocker {
            Objects.requireNonNull(code, "code");
            reason = reason == null ? "" : reason;
        }
    }

    /** Single immutable admission verdict consumed by creation wizard, creator, and details UI. */
    public record AdmissionVerdict(
            boolean canCreateInstance,
            String creationRefusalReason,
            OfferMode offerMode,
            boolean recommendationActive,
            FullLinuxContract.ExecutionStrategy defaultStrategy,
            List<FullLinuxContract.ExecutionStrategy> selectableStrategies,
            FullLinuxContract.CapabilityMatrix existingStrategyCapabilities,
            FullLinuxContract.CapabilityMatrix umlCapabilityMatrix,
            ExecutionPolicyContract.PreflightVerdict preflightVerdict,
            ExecutionPolicyContract.FallbackReason fallbackReason,
            List<GateBlocker> blockers,
            String userVisibleNotice) {
        public AdmissionVerdict {
            Objects.requireNonNull(offerMode, "offerMode");
            Objects.requireNonNull(existingStrategyCapabilities, "existingStrategyCapabilities");
            Objects.requireNonNull(umlCapabilityMatrix, "umlCapabilityMatrix");
            Objects.requireNonNull(preflightVerdict, "preflightVerdict");
            Objects.requireNonNull(fallbackReason, "fallbackReason");
            selectableStrategies = List.copyOf(Objects.requireNonNull(selectableStrategies, "selectableStrategies"));
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
            userVisibleNotice = userVisibleNotice == null ? "" : userVisibleNotice;
        }

        public boolean umlSelectable() {
            return selectableStrategies.contains(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL);
        }
    }

    /** Projects the verified capability matrix for standalone UML when its promotion gate passes. */
    public static FullLinuxContract.CapabilityMatrix verifiedUmlCapabilityMatrix(
            boolean promotionGatePassed) {
        if (!promotionGatePassed) {
            return FullLinuxContract.CapabilityMatrix.userModeKernelBaseline();
        }
        return FullLinuxContract.CapabilityMatrix.from(
                SessionCapabilities.builder(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL)
                        .terminal(true)
                        .bridge(true)
                        .portForwarding(true)
                        .build(),
                FullLinuxContract.CapabilityMatrix.USER_MODE_KERNEL_ALLOWED_CAPABILITIES);
    }

    /** Evaluates creation eligibility and strategy offering for one new instance request. */
    public static AdmissionVerdict evaluate(
            DeviceCapabilityEvaluator.Assessment deviceFloor,
            FullLinuxContract.ExecutionStrategy existingApplicableStrategy,
            PromotionEvidence evidence,
            StandaloneUmlPreflight umlPreflight,
            WorkloadRequirements workload,
            boolean allowConditionalRecommendation,
            boolean recommendationRevoked) {
        Objects.requireNonNull(deviceFloor, "deviceFloor");
        FullLinuxContract.ExecutionStrategy existing = existingApplicableStrategy == null
                        || existingApplicableStrategy == FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL
                ? FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION
                : existingApplicableStrategy;
        FullLinuxContract.CapabilityMatrix existingCapabilities =
                existing == FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION
                        ? FullLinuxContract.CapabilityMatrix.hardwareVirtualizationBaseline()
                        : deviceFloor.capabilityMatrix();

        // 1. Device support floor is an honest hard refusal on instance creation.
        if (!deviceFloor.canCreateFullLinuxInstance()) {
            String reason = deviceFloor.refusalReason() != null
                    ? deviceFloor.refusalReason()
                    : "设备未达到 Full Linux 最低支持地板";
            return new AdmissionVerdict(
                    false,
                    reason,
                    OfferMode.LOCKED,
                    false,
                    existing,
                    Collections.emptyList(),
                    existingCapabilities,
                    FullLinuxContract.CapabilityMatrix.userModeKernelBaseline(),
                    new ExecutionPolicyContract.PreflightVerdict(
                            ExecutionPolicyContract.PreflightStatus.REFUSED,
                            ExecutionPolicyContract.PreflightCode.DEVICE_FLOOR_UNSUPPORTED,
                            reason),
                    ExecutionPolicyContract.FallbackReason.NONE,
                    List.of(new GateBlocker(GateBlockerCode.DEVICE_FLOOR_UNSUPPORTED, reason)),
                    "设备未满足最低支持条件（" + reason + "），无法创建实例。");
        }

        // 2. Evaluate release, governance, matrix, payload, and paired performance evidence.
        List<GateBlocker> promotionBlockers = evaluatePromotionEvidence(evidence, umlPreflight);
        if (recommendationRevoked) {
            promotionBlockers.add(new GateBlocker(
                    GateBlockerCode.RECOMMENDATION_REVOKED,
                    "UML 新建推荐已撤回，新建实例沿用既有策略；既有显式实例仍保留启动、停止与导出路径。"));
        }
        boolean promotionGatePassed = promotionBlockers.isEmpty();
        FullLinuxContract.CapabilityMatrix umlMatrix = verifiedUmlCapabilityMatrix(promotionGatePassed);

        if (!promotionGatePassed) {
            String summary = summarizeBlockers(promotionBlockers);
            return new AdmissionVerdict(
                    true,
                    null,
                    OfferMode.LOCKED,
                    false,
                    existing,
                    List.of(existing),
                    existingCapabilities,
                    umlMatrix,
                    new ExecutionPolicyContract.PreflightVerdict(
                            ExecutionPolicyContract.PreflightStatus.ALLOWED,
                            ExecutionPolicyContract.PreflightCode.PROMOTION_GATE_LOCKED,
                            summary),
                    ExecutionPolicyContract.FallbackReason.NONE,
                    promotionBlockers,
                    "当前继续使用既有适用策略（独立 UML 准入门禁未全部满足：" + summary + "）。");
        }

        // 3. Evaluate workload and distro capability intersection.
        List<GateBlocker> workloadBlockers = evaluateWorkloadIntersection(workload);
        if (!workloadBlockers.isEmpty()) {
            String summary = summarizeBlockers(workloadBlockers);
            return new AdmissionVerdict(
                    true,
                    null,
                    OfferMode.LOCKED,
                    false,
                    existing,
                    List.of(existing),
                    existingCapabilities,
                    umlMatrix,
                    new ExecutionPolicyContract.PreflightVerdict(
                            ExecutionPolicyContract.PreflightStatus.ALLOWED,
                            ExecutionPolicyContract.PreflightCode.CAPABILITY_INTERSECTION_UNSATISFIED,
                            summary),
                    ExecutionPolicyContract.FallbackReason.NONE,
                    workloadBlockers,
                    "当前配置需要独立 UML 尚未验证的能力（" + summary + "），已保留既有适用策略。");
        }

        // 4. Evaluate standalone UML runtime preflight (strictly independent from ride-along face).
        if (umlPreflight == null || umlPreflight.status() != StandalonePreflightStatus.READY) {
            return preflightFallbackVerdict(existing, existingCapabilities, umlMatrix, umlPreflight);
        }

        // 5. All gates passed, capability intersection matched, and standalone preflight ready.
        if (allowConditionalRecommendation) {
            return new AdmissionVerdict(
                    true,
                    null,
                    OfferMode.CONDITIONAL_RECOMMENDED,
                    true,
                    FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                    List.of(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL, existing),
                    existingCapabilities,
                    umlMatrix,
                    new ExecutionPolicyContract.PreflightVerdict(
                            ExecutionPolicyContract.PreflightStatus.ALLOWED,
                            ExecutionPolicyContract.PreflightCode.READY,
                            "standalone UML verified for CLI capability intersection"),
                    ExecutionPolicyContract.FallbackReason.NONE,
                    Collections.emptyList(),
                    "当前 CLI 配置满足已验证能力交集，已开放独立用户态内核（无 systemd、容器、GPU、桌面与共享目录）。");
        }
        return new AdmissionVerdict(
                true,
                null,
                OfferMode.OPT_IN,
                false,
                existing,
                List.of(existing, FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL),
                existingCapabilities,
                umlMatrix,
                new ExecutionPolicyContract.PreflightVerdict(
                        ExecutionPolicyContract.PreflightStatus.ALLOWED,
                        ExecutionPolicyContract.PreflightCode.READY,
                        "standalone UML opt-in verified for CLI capability intersection"),
                ExecutionPolicyContract.FallbackReason.NONE,
                Collections.emptyList(),
                "当前 CLI 配置可选用独立用户态内核（仅支持终端、Bridge 与回环端口转发；默认保留既有策略）。");
    }

    private static List<GateBlocker> evaluatePromotionEvidence(
            PromotionEvidence evidence, StandaloneUmlPreflight umlPreflight) {
        List<GateBlocker> blockers = new ArrayList<>();
        if (evidence == null) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.BENCHMARK_EVIDENCE_MISSING, "缺少 W3-05 性能与治理验收证据"));
            return blockers;
        }
        if (!evidence.phase1SignedOff()) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.PHASE1_NOT_SIGNED_OFF, "Phase 1（R-00 至 R-04）尚未全部签收"));
        }
        if (!evidence.host4kMatrixPassed()) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.HOST_4K_MATRIX_MISSING, "4 KB 宿主页真机矩阵未通过"));
        }
        if (!evidence.host16kMatrixPassed()) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.HOST_16K_MATRIX_MISSING, "16 KB 宿主页物理真机矩阵待补"));
        }
        if (!evidence.acrApproved()) {
            String ref = evidence.acrReference().isEmpty() ? "ACR" : evidence.acrReference();
            blockers.add(new GateBlocker(
                    GateBlockerCode.ACR_NOT_APPROVED, ref + " 尚未批准产品承诺"));
        }
        if (!evidence.fullPkgPayloadGatePassed()) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.PAYLOAD_GATE_FAILED, "实装 APK --fullpkg 载荷门禁未通过"));
        }
        if (!evidence.noticeAttributionClosed()) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.NOTICE_ATTRIBUTION_OPEN, "NOTICE.UML 辅助二进制归属或对应源码交付仍含未决项"));
        }
        evaluateBucket("冷启", evidence.coldProvisioned(), COLD_P95_LIMIT_MS,
                GateBlockerCode.COLD_P95_EXCEEDED, blockers);
        evaluateBucket("热启", evidence.warmBoot(), WARM_P95_LIMIT_MS,
                GateBlockerCode.WARM_P95_EXCEEDED, blockers);
        if (umlPreflight != null
                && umlPreflight.hostPageSizeBytes() > 0
                && !evidence.verifiedHostPageSizes().contains(umlPreflight.hostPageSizeBytes())) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.HOST_PAGE_SIZE_NOT_VERIFIED,
                    "当前宿主页桶位（" + umlPreflight.hostPageSizeBytes() + " B）尚未通过真机验证"));
        }
        return blockers;
    }

    private static void evaluateBucket(
            String label,
            PairedPerformanceBucket bucket,
            long p95LimitMs,
            GateBlockerCode p95Code,
            List<GateBlocker> blockers) {
        if (bucket == null) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.BENCHMARK_EVIDENCE_MISSING, "缺少" + label + "成对性能证据"));
            return;
        }
        if (bucket.samplePairs() < MIN_SAMPLE_PAIRS_PER_BUCKET) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.INSUFFICIENT_SAMPLE_PAIRS,
                    label + "有效成对样本不足（" + bucket.samplePairs() + " < "
                            + MIN_SAMPLE_PAIRS_PER_BUCKET + "）"));
        }
        if (bucket.unexplainedTimeoutCount() > 0) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.UNEXPLAINED_TIMEOUT,
                    label + "存在 " + bucket.unexplainedTimeoutCount() + " 起未解释超时"));
        }
        if (bucket.umlP95Ms() <= 0L || bucket.qemuP95Ms() <= 0L) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.BENCHMARK_EVIDENCE_MISSING, label + "缺少有效 p95 对比分母"));
            return;
        }
        if (bucket.umlP95Ms() > p95LimitMs) {
            blockers.add(new GateBlocker(
                    p95Code,
                    label + " UML p95=" + bucket.umlP95Ms() + "ms 超过门槛 " + p95LimitMs + "ms"));
        }
        double speedup = bucket.speedupOverQemuRatio();
        if (Double.isNaN(speedup) || speedup < MIN_QEMU_SPEEDUP_RATIO) {
            String pct = Double.isNaN(speedup)
                    ? "N/A"
                    : String.format(Locale.ROOT, "%.1f%%", speedup * 100.0d);
            blockers.add(new GateBlocker(
                    GateBlockerCode.SPEEDUP_BELOW_THRESHOLD,
                    label + "较 QEMU p95 提速不足 20%（当前 " + pct + "）"));
        }
    }

    private static List<GateBlocker> evaluateWorkloadIntersection(WorkloadRequirements workload) {
        List<GateBlocker> blockers = new ArrayList<>();
        if (workload == null) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.DISTRO_UNSUPPORTED_ON_UML, "缺少目标发行版与工作负载需求声明"));
            return blockers;
        }
        if (!workload.distroCreatableOnUml()) {
            blockers.add(new GateBlocker(
                    GateBlockerCode.DISTRO_UNSUPPORTED_ON_UML,
                    "发行版 " + workload.distroId() + " 未通过 UML fast-profile 能力门禁"));
        }
        if (workload.requiresSystemd()) {
            blockers.add(new GateBlocker(GateBlockerCode.REQUIRES_SYSTEMD, "systemd 尚未验证"));
        }
        if (workload.requiresContainer()) {
            blockers.add(new GateBlocker(GateBlockerCode.REQUIRES_CONTAINER, "容器运行时（Podman/Docker）尚未验证"));
        }
        if (workload.requiresGpu()) {
            blockers.add(new GateBlocker(GateBlockerCode.REQUIRES_GPU, "GPU 加速尚未验证"));
        }
        if (workload.requiresDesktop()) {
            blockers.add(new GateBlocker(GateBlockerCode.REQUIRES_DESKTOP, "桌面会话尚未验证"));
        }
        if (workload.requiresSharedDirectory()) {
            blockers.add(new GateBlocker(GateBlockerCode.REQUIRES_SHARED_DIRECTORY, "共享目录（9p）尚未验证"));
        }
        if (workload.requiresEditor()) {
            blockers.add(new GateBlocker(GateBlockerCode.REQUIRES_EDITOR, "非 CLI 编辑器档案尚未验证"));
        }
        return blockers;
    }

    private static AdmissionVerdict preflightFallbackVerdict(
            FullLinuxContract.ExecutionStrategy existing,
            FullLinuxContract.CapabilityMatrix existingCapabilities,
            FullLinuxContract.CapabilityMatrix umlMatrix,
            StandaloneUmlPreflight preflight) {
        boolean timedOut = preflight != null && preflight.status() == StandalonePreflightStatus.TIMED_OUT;
        ExecutionPolicyContract.PreflightCode preflightCode = timedOut
                ? ExecutionPolicyContract.PreflightCode.UML_PREFLIGHT_TIMEOUT
                : ExecutionPolicyContract.PreflightCode.UML_PREFLIGHT_INCOMPLETE;
        ExecutionPolicyContract.FallbackReason fallbackReason = timedOut
                ? ExecutionPolicyContract.FallbackReason.UML_PREFLIGHT_TIMEOUT
                : ExecutionPolicyContract.FallbackReason.UML_PREFLIGHT_INCOMPLETE;
        GateBlockerCode blockerCode = preflight == null
                ? GateBlockerCode.UML_PREFLIGHT_INCOMPLETE
                : switch (preflight.status()) {
                    case TIMED_OUT -> GateBlockerCode.UML_PREFLIGHT_TIMEOUT;
                    case ASSETS_MISSING -> GateBlockerCode.UML_ASSETS_MISSING;
                    case UNSUPPORTED_HOST_PAGE_SIZE -> GateBlockerCode.HOST_PAGE_SIZE_NOT_VERIFIED;
                    case UNAVAILABLE -> GateBlockerCode.UML_UNAVAILABLE;
                    case INCOMPLETE, READY -> GateBlockerCode.UML_PREFLIGHT_INCOMPLETE;
                };
        String detail = preflight == null || preflight.detail().isEmpty()
                ? (timedOut ? "UML 独立预检超时" : "UML 独立预检不完整")
                : preflight.detail();
        String notice = timedOut
                ? "设备满足支持地板，但独立 UML 预检超时（" + detail + "），已沿用既有适用策略。"
                : "设备满足支持地板，但独立 UML 预检未完成（" + detail + "），已沿用既有适用策略。";
        return new AdmissionVerdict(
                true,
                null,
                OfferMode.LOCKED,
                false,
                existing,
                List.of(existing),
                existingCapabilities,
                umlMatrix,
                new ExecutionPolicyContract.PreflightVerdict(
                        ExecutionPolicyContract.PreflightStatus.FALLBACK_ALLOWED,
                        preflightCode,
                        detail),
                fallbackReason,
                List.of(new GateBlocker(blockerCode, detail)),
                notice);
    }

    private static String summarizeBlockers(List<GateBlocker> blockers) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < blockers.size(); i++) {
            if (i > 0) out.append("；");
            out.append(blockers.get(i).reason());
        }
        return out.toString();
    }
}
