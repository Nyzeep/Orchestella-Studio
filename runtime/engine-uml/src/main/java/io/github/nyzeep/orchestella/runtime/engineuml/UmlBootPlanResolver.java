package io.github.nyzeep.orchestella.runtime.engineuml;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure-JVM single-pass admission and boot-mode resolver for standalone UML launches.
 *
 * <p>Separates first-time provisioning evidence (trustworthy distro seed required) from
 * assembled-volume reboot evidence (valid ext4 root disk + immutable persisted identity, no seed
 * tarball needed) while enforcing channel isolation and preserving existing volumes/metadata on
 * every refusal.
 */
public final class UmlBootPlanResolver {

    public record RequestInputs(
            String distroId,
            String explicitKernelProfile,
            Path rootVolumePath) {}

    public record PersistedMetadata(
            boolean present,
            String distroId,
            String kernelProfile,
            Path rootVolumePath,
            ExecutionPolicyContract.InstanceExecutionIdentity executionIdentity,
            boolean hasProvisioningEvidence) {

        public static PersistedMetadata absent() {
            return new PersistedMetadata(
                    false,
                    null,
                    null,
                    null,
                    ExecutionPolicyContract.InstanceExecutionIdentity.legacyAbsent(),
                    false);
        }
    }

    private UmlBootPlanResolver() {}

    public static UmlBootPlan resolve(
            RequestInputs request,
            PersistedMetadata persisted,
            UmlVolumeInspector.VolumeInspection volumeInspection,
            boolean isolatedQaChannelAllowed) {
        Objects.requireNonNull(request, "request");
        PersistedMetadata meta = persisted != null ? persisted : PersistedMetadata.absent();
        Objects.requireNonNull(volumeInspection, "volumeInspection");

        String requestDistro = normalize(request.distroId());
        String persistedDistro = meta.present() ? normalize(meta.distroId()) : null;

        UmlBootPlan metadataRefusal = validatePersistedMetadata(request, meta, requestDistro, persistedDistro);
        if (metadataRefusal != null) {
            return metadataRefusal;
        }

        String effectiveDistro = persistedDistro != null ? persistedDistro : requestDistro;
        if (effectiveDistro == null) {
            effectiveDistro = "alpine";
        }

        ProfileResolution profileResolution = resolveEffectiveProfile(request, meta);
        if (profileResolution.refusal() != null) {
            return profileResolution.refusal();
        }

        UmlDistroRuntimeProfile runtimeProfile =
                UmlDistroCapabilityCatalog.resolveRuntimeProfile(effectiveDistro, profileResolution.profile());
        if (runtimeProfile.requiresIsolatedQaChannel() && !isolatedQaChannelAllowed) {
            return refused(
                    UmlBootPlan.ResolutionKind.REFUSED_DETERMINISTIC,
                    UmlBootPlan.RefusalCode.CHANNEL_NOT_ALLOWED,
                    runtimeProfile,
                    "kernel profile "
                            + runtimeProfile.profileDescriptor().profileId()
                            + " is restricted to isolated QA builds",
                    "完整档内核仅允许在隔离 QA 通道运行，已保留实例元数据与原盘。");
        }

        if (!runtimeProfile.verdict().isCreatable()) {
            return refused(
                    UmlBootPlan.ResolutionKind.REFUSED_DETERMINISTIC,
                    UmlBootPlan.RefusalCode.DISTRO_UNSUPPORTED_ON_PROFILE,
                    runtimeProfile,
                    runtimeProfile.verdict().summary(),
                    "");
        }

        return buildVolumePlan(meta, volumeInspection, runtimeProfile, profileResolution.legacyDefault());
    }

    private record ProfileResolution(
            UmlDistroCapabilityCatalog.KernelProfile profile,
            boolean legacyDefault,
            UmlBootPlan refusal) {}

    private static UmlBootPlan validatePersistedMetadata(
            RequestInputs request,
            PersistedMetadata meta,
            String requestDistro,
            String persistedDistro) {
        if (!meta.present()) {
            return null;
        }
        ExecutionPolicyContract.InstanceExecutionIdentity identity =
                meta.executionIdentity() != null
                        ? meta.executionIdentity()
                        : ExecutionPolicyContract.InstanceExecutionIdentity.legacyAbsent();
        if (identity.hasUnknownStrategy()) {
            return refused(
                    UmlBootPlan.ResolutionKind.REFUSED_RECOVERY_REQUIRED,
                    UmlBootPlan.RefusalCode.CONFIG_MISMATCH,
                    null,
                    "unknown persisted execution strategy: " + identity.strategy().rawValue(),
                    "持久化元数据包含未知执行策略，已保留原盘与元数据；请核对版本或导出备份。");
        }
        if (identity.hasUnrecognizedVolumeFormat()) {
            return refused(
                    UmlBootPlan.ResolutionKind.REFUSED_RECOVERY_REQUIRED,
                    UmlBootPlan.RefusalCode.CONFIG_MISMATCH,
                    null,
                    "unrecognized persisted volume format: " + identity.volumeFormat().rawValue(),
                    "持久化元数据包含未知根盘格式，已保留原盘与元数据。");
        }
        if (!identity.strategy().isAbsent()
                && identity.strategy().strategy() != FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL) {
            return refused(
                    UmlBootPlan.ResolutionKind.REFUSED_DETERMINISTIC,
                    UmlBootPlan.RefusalCode.CONFIG_MISMATCH,
                    null,
                    "persisted execution strategy is not USER_MODE_KERNEL: " + identity.strategy().strategy(),
                    "");
        }
        if (!identity.volumeFormat().isAbsent()
                && identity.volumeFormat().format() != StorageContract.RootVolumeFormat.RAW_SPARSE) {
            return refused(
                    UmlBootPlan.ResolutionKind.REFUSED_DETERMINISTIC,
                    UmlBootPlan.RefusalCode.CONFIG_MISMATCH,
                    null,
                    "persisted volume format is not RAW_SPARSE: " + identity.volumeFormat().format(),
                    "");
        }
        if (requestDistro != null && persistedDistro != null && !requestDistro.equals(persistedDistro)) {
            return refused(
                    UmlBootPlan.ResolutionKind.REFUSED_DETERMINISTIC,
                    UmlBootPlan.RefusalCode.CONFIG_MISMATCH,
                    null,
                    "distro mismatch: request=" + requestDistro + ", persisted=" + persistedDistro,
                    "");
        }
        if (request.rootVolumePath() != null
                && meta.rootVolumePath() != null
                && !normalizePath(request.rootVolumePath()).equals(normalizePath(meta.rootVolumePath()))) {
            return refused(
                    UmlBootPlan.ResolutionKind.REFUSED_DETERMINISTIC,
                    UmlBootPlan.RefusalCode.CONFIG_MISMATCH,
                    null,
                    "rootVolume mismatch: request="
                            + request.rootVolumePath()
                            + ", persisted="
                            + meta.rootVolumePath(),
                    "");
        }
        return null;
    }

    private static ProfileResolution resolveEffectiveProfile(RequestInputs request, PersistedMetadata meta) {
        String explicitProfile = normalize(request.explicitKernelProfile());
        String persistedProfile = meta.present() ? normalize(meta.kernelProfile()) : null;
        if (explicitProfile != null
                && persistedProfile != null
                && !explicitProfile.equals(persistedProfile)) {
            return new ProfileResolution(
                    null,
                    false,
                    refused(
                            UmlBootPlan.ResolutionKind.REFUSED_DETERMINISTIC,
                            UmlBootPlan.RefusalCode.CONFIG_MISMATCH,
                            null,
                            "Conflicting kernelProfile: request="
                                    + explicitProfile
                                    + ", persisted="
                                    + persistedProfile,
                            ""));
        }
        String effectiveProfileId = explicitProfile != null ? explicitProfile : persistedProfile;
        if (effectiveProfileId == null) {
            return new ProfileResolution(
                    UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64, meta.present(), null);
        }
        try {
            return new ProfileResolution(
                    UmlKernelPayloadContract.fromProfileId(effectiveProfileId), false, null);
        } catch (IllegalArgumentException unknown) {
            return new ProfileResolution(
                    null,
                    false,
                    refused(
                            UmlBootPlan.ResolutionKind.REFUSED_RECOVERY_REQUIRED,
                            UmlBootPlan.RefusalCode.UNKNOWN_KERNEL_PROFILE,
                            null,
                            "unknown UML kernel profileId: " + effectiveProfileId,
                            "实例配置了当前版本不支持的内核档位（" + effectiveProfileId + "），已保留元数据与原盘。"));
        }
    }

    private static UmlBootPlan buildVolumePlan(
            PersistedMetadata meta,
            UmlVolumeInspector.VolumeInspection volumeInspection,
            UmlDistroRuntimeProfile runtimeProfile,
            boolean legacyDefault) {
        if (!volumeInspection.isExt4Ready() && !volumeInspection.isReadyToProvision()) {
            return refused(
                    UmlBootPlan.ResolutionKind.REFUSED_RECOVERY_REQUIRED,
                    UmlBootPlan.RefusalCode.INVALID_ROOT_VOLUME,
                    runtimeProfile,
                    "refusing launch over invalid root volume ("
                            + volumeInspection.state()
                            + "): "
                            + volumeInspection.detail(),
                    "根盘格式冲突或已损坏，已保留原盘与元数据，未执行覆盖或重装。");
        }

        if (volumeInspection.isExt4Ready()) {
            return new UmlBootPlan(
                    UmlBootPlan.Status.ALLOWED,
                    legacyDefault
                            ? UmlBootPlan.ResolutionKind.LEGACY_DEFAULT_PROFILE
                            : UmlBootPlan.ResolutionKind.DETERMINISTIC,
                    UmlBootPlan.BootMode.ASSEMBLED_REBOOT,
                    UmlBootPlan.RefusalCode.NONE,
                    runtimeProfile,
                    "assembled ext4 root volume verified; rootfs seed not required",
                    "");
        }

        if (meta.present() && meta.hasProvisioningEvidence()) {
            return refused(
                    UmlBootPlan.ResolutionKind.REFUSED_RECOVERY_REQUIRED,
                    UmlBootPlan.RefusalCode.MISSING_ASSEMBLED_ROOT_VOLUME,
                    runtimeProfile,
                    "missing or empty root volume for previously provisioned instance ("
                            + volumeInspection.state()
                            + ")",
                    "已装配实例的原根盘缺失或为空，已保留元数据并拒绝静默重装；请使用恢复导出备份或显式重新创建。");
        }

        return new UmlBootPlan(
                UmlBootPlan.Status.ALLOWED,
                legacyDefault
                        ? UmlBootPlan.ResolutionKind.LEGACY_DEFAULT_PROFILE
                        : UmlBootPlan.ResolutionKind.DETERMINISTIC,
                UmlBootPlan.BootMode.FIRST_PROVISION,
                UmlBootPlan.RefusalCode.NONE,
                runtimeProfile,
                "first-time provisioning requires verified rootfs seed",
                "");
    }

    private static UmlBootPlan refused(
            UmlBootPlan.ResolutionKind kind,
            UmlBootPlan.RefusalCode code,
            UmlDistroRuntimeProfile profile,
            String detail,
            String guidance) {
        return new UmlBootPlan(
                UmlBootPlan.Status.REFUSED,
                kind,
                null,
                code,
                profile,
                detail,
                guidance);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Path normalizePath(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
