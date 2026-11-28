package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public final class UmlBootPlanResolverTest {
    private static final Path VOL_A = Paths.get("qa-uml-debian.raw");
    private static final Path VOL_B = Paths.get("other-volume.raw");

    @Test
    public void firstCreateWithoutProvisioningEvidenceRequiresRootfsSeed() {
        UmlBootPlan plan = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("debian", "full-smp-arm64", VOL_A),
                UmlBootPlanResolver.PersistedMetadata.absent(),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.EMPTY_OR_PLACEHOLDER, 0L, "zero-byte"),
                true);

        assertEquals(UmlBootPlan.Status.ALLOWED, plan.status());
        assertEquals(UmlBootPlan.ResolutionKind.DETERMINISTIC, plan.resolutionKind());
        assertEquals(UmlBootPlan.BootMode.FIRST_PROVISION, plan.bootMode());
        assertTrue(plan.requireRootfsSeed());
        assertEquals("/sbin/init", plan.runtimeProfile().nativeInitPath());
        assertEquals("full-smp-arm64", plan.runtimeProfile().profileDescriptor().profileId());
    }

    @Test
    public void assembledExt4VolumeRebootsWithoutSeedRequirement() {
        UmlBootPlan plan = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("debian", null, VOL_A),
                new UmlBootPlanResolver.PersistedMetadata(
                        true,
                        "debian",
                        "full-smp-arm64",
                        VOL_A,
                        ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                                FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                                StorageContract.RootVolumeFormat.RAW_SPARSE),
                        true),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.EXT4_READY, 4096L, "ext4"),
                true);

        assertEquals(UmlBootPlan.Status.ALLOWED, plan.status());
        assertEquals(UmlBootPlan.ResolutionKind.DETERMINISTIC, plan.resolutionKind());
        assertEquals(UmlBootPlan.BootMode.ASSEMBLED_REBOOT, plan.bootMode());
        assertFalse(plan.requireRootfsSeed());
        assertEquals("/sbin/init", plan.runtimeProfile().nativeInitPath());
    }

    @Test
    public void provisionedInstanceWithMissingVolumeRefusesSilentReprovisionAndMarksRecovery() {
        UmlBootPlan plan = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("debian", "full-smp-arm64", VOL_A),
                new UmlBootPlanResolver.PersistedMetadata(
                        true,
                        "debian",
                        "full-smp-arm64",
                        VOL_A,
                        ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                                FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                                StorageContract.RootVolumeFormat.RAW_SPARSE),
                        true),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.ABSENT, 0L, "absent"),
                true);

        assertEquals(UmlBootPlan.Status.REFUSED, plan.status());
        assertEquals(UmlBootPlan.ResolutionKind.REFUSED_RECOVERY_REQUIRED, plan.resolutionKind());
        assertEquals(UmlBootPlan.RefusalCode.MISSING_ASSEMBLED_ROOT_VOLUME, plan.refusalCode());
        assertNull(plan.bootMode());
        assertFalse(plan.recoveryGuidance().isBlank());
    }

    @Test
    public void corruptOrConflictingVolumeRefusesWithoutTouchingMetadata() {
        UmlBootPlan plan = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("alpine", "fast-up-arm64", VOL_A),
                UmlBootPlanResolver.PersistedMetadata.absent(),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.UNRECOGNIZED_NON_EMPTY, 4096L, "garbage"),
                true);

        assertEquals(UmlBootPlan.Status.REFUSED, plan.status());
        assertEquals(UmlBootPlan.ResolutionKind.REFUSED_RECOVERY_REQUIRED, plan.resolutionKind());
        assertEquals(UmlBootPlan.RefusalCode.INVALID_ROOT_VOLUME, plan.refusalCode());
    }

    @Test
    public void fullProfileRefusesOutsideIsolatedQaChannel() {
        UmlBootPlan plan = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("debian", "full-smp-arm64", VOL_A),
                UmlBootPlanResolver.PersistedMetadata.absent(),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.EXT4_READY, 4096L, "ext4"),
                false);

        assertEquals(UmlBootPlan.Status.REFUSED, plan.status());
        assertEquals(UmlBootPlan.ResolutionKind.REFUSED_DETERMINISTIC, plan.resolutionKind());
        assertEquals(UmlBootPlan.RefusalCode.CHANNEL_NOT_ALLOWED, plan.refusalCode());
        assertTrue(plan.detail().contains("full-smp-arm64"));
    }

    @Test
    public void refusesExplicitVsPersistedProfileConflict() {
        UmlBootPlan conflict = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("debian", "fast-up-arm64", VOL_A),
                persistedDebianFull(VOL_A, true),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.EXT4_READY, 4096L, "ext4"),
                true);
        assertEquals(UmlBootPlan.RefusalCode.CONFIG_MISMATCH, conflict.refusalCode());
    }

    @Test
    public void refusesRootVolumePathAndDistroMismatches() {
        UmlBootPlan pathMismatch = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("debian", "full-smp-arm64", VOL_A),
                persistedDebianFull(VOL_B, true),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.EXT4_READY, 4096L, "ext4"),
                true);
        assertEquals(UmlBootPlan.RefusalCode.CONFIG_MISMATCH, pathMismatch.refusalCode());

        UmlBootPlan distroMismatch = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("alpine", "full-smp-arm64", VOL_A),
                persistedDebianFull(VOL_A, true),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.EXT4_READY, 4096L, "ext4"),
                true);
        assertEquals(UmlBootPlan.RefusalCode.CONFIG_MISMATCH, distroMismatch.refusalCode());
    }

    @Test
    public void nullRequestDistroRebootsFromPersistedDebianMetadataWithoutSeed() {
        UmlBootPlan plan = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs(null, null, VOL_A),
                persistedDebianFull(VOL_A, true),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.EXT4_READY, 4096L, "ext4"),
                true);
        assertTrue(plan.isAllowed());
        assertEquals(UmlBootPlan.BootMode.ASSEMBLED_REBOOT, plan.bootMode());
        assertFalse(plan.requireRootfsSeed());
        assertEquals("debian", plan.runtimeProfile().distroId());
        assertEquals("/sbin/init", plan.runtimeProfile().nativeInitPath());
    }

    @Test
    public void refusesUnknownStrategyUnrecognizedVolumeUnknownProfileAndUnsupportedDistro() {
        UmlBootPlan unknownStrategy = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("alpine", null, VOL_A),
                new UmlBootPlanResolver.PersistedMetadata(
                        true,
                        "alpine",
                        "fast-up-arm64",
                        VOL_A,
                        ExecutionPolicyContract.InstanceExecutionIdentity.of(
                                ExecutionPolicyContract.StrategyDeclaration.parse("UNKNOWN_ENGINE"),
                                ExecutionPolicyContract.VolumeFormatDeclaration.of(
                                        StorageContract.RootVolumeFormat.RAW_SPARSE)),
                        true),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.EXT4_READY, 4096L, "ext4"),
                true);
        assertEquals(UmlBootPlan.RefusalCode.CONFIG_MISMATCH, unknownStrategy.refusalCode());
        assertEquals(UmlBootPlan.ResolutionKind.REFUSED_RECOVERY_REQUIRED, unknownStrategy.resolutionKind());

        UmlBootPlan unknownFormat = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("alpine", null, VOL_A),
                new UmlBootPlanResolver.PersistedMetadata(
                        true,
                        "alpine",
                        "fast-up-arm64",
                        VOL_A,
                        ExecutionPolicyContract.InstanceExecutionIdentity.of(
                                ExecutionPolicyContract.StrategyDeclaration.of(
                                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL),
                                ExecutionPolicyContract.VolumeFormatDeclaration.parse("QCOW2")),
                        true),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.EXT4_READY, 4096L, "ext4"),
                true);
        assertEquals(UmlBootPlan.RefusalCode.CONFIG_MISMATCH, unknownFormat.refusalCode());
        assertEquals(UmlBootPlan.ResolutionKind.REFUSED_RECOVERY_REQUIRED, unknownFormat.resolutionKind());

        UmlBootPlan unknownProfile = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("alpine", "experimental-next", VOL_A),
                UmlBootPlanResolver.PersistedMetadata.absent(),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.EXT4_READY, 4096L, "ext4"),
                true);
        assertEquals(UmlBootPlan.RefusalCode.UNKNOWN_KERNEL_PROFILE, unknownProfile.refusalCode());
        assertEquals(UmlBootPlan.ResolutionKind.REFUSED_RECOVERY_REQUIRED, unknownProfile.resolutionKind());

        UmlBootPlan unsupportedDistro = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("debian", "fast-up-arm64", VOL_A),
                UmlBootPlanResolver.PersistedMetadata.absent(),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.ABSENT, 0L, "missing"),
                true);
        assertEquals(UmlBootPlan.RefusalCode.DISTRO_UNSUPPORTED_ON_PROFILE, unsupportedDistro.refusalCode());
    }

    @Test
    public void keepsLegacyFastDefaultReadable() {
        UmlBootPlan legacyFast = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs("alpine", null, VOL_A),
                new UmlBootPlanResolver.PersistedMetadata(
                        true,
                        "alpine",
                        null,
                        VOL_A,
                        ExecutionPolicyContract.InstanceExecutionIdentity.legacyAbsent(),
                        false),
                new UmlVolumeInspector.VolumeInspection(
                        UmlVolumeInspector.VolumeState.EXT4_READY, 4096L, "ext4"),
                false);
        assertEquals(UmlBootPlan.Status.ALLOWED, legacyFast.status());
        assertEquals(UmlBootPlan.ResolutionKind.LEGACY_DEFAULT_PROFILE, legacyFast.resolutionKind());
        assertEquals(UmlBootPlan.BootMode.ASSEMBLED_REBOOT, legacyFast.bootMode());
        assertNotNull(legacyFast.runtimeProfile());
        assertEquals("fast-up-arm64", legacyFast.runtimeProfile().profileDescriptor().profileId());
    }

    private static UmlBootPlanResolver.PersistedMetadata persistedDebianFull(
            Path rootVolume, boolean hasProvisioningEvidence) {
        return new UmlBootPlanResolver.PersistedMetadata(
                true,
                "debian",
                "full-smp-arm64",
                rootVolume,
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE),
                hasProvisioningEvidence);
    }
}
