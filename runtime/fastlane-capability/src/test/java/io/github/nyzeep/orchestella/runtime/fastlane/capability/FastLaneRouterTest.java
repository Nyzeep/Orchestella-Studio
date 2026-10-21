package io.github.nyzeep.orchestella.runtime.fastlane.capability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FastLaneRouterTest {

    @Test
    public void protectedOnlyDeviceRunsInteractiveShellOnFastLane() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.protectedOnly(), Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.FAST, decision.lane());
        assertEquals(FastLaneReason.FAST_LANE_AVAILABLE, decision.reason());
        assertTrue(decision.fastLaneCapabilities().terminal());
        assertTrue(decision.fastLaneCapabilities().hardwareCpu());
        assertTrue(decision.fastLaneCapabilities().encryptedStorage());
        assertTrue(decision.explanation().contains("不是 Full Linux"));
    }

    @Test
    public void compileWorkloadUsesSealedToolboxWithoutRequiringDistcc() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.protectedOnly(), Workload.COMPILE, null);
        assertEquals(Lane.FAST, decision.lane());
        assertTrue(decision.fastLaneCapabilities().sealedToolbox());
        assertTrue(decision.fastLaneCapabilities().distccOffload());
    }

    @Test
    public void noAvfFallsBackToFullWithTypedReason() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.noAvf(), Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.NO_AVF, decision.reason());
        assertFalse(decision.fastLaneCapabilities().hardwareCpu());
    }

    @Test
    public void hiddenApiBlockFallsBackToFull() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.hiddenApiBlockedDevice(), Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.HIDDEN_API_BLOCKED, decision.reason());
    }

    @Test
    public void permissionDenialFallsBackToFull() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.permissionDenied(), Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.PERMISSION_DENIED, decision.reason());
    }

    @Test
    public void missingEncryptedStorageFallsBackWhenStorageIsRequired() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.withoutEncryptedStorage(), Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.STORAGE_UNAVAILABLE, decision.reason());
    }

    @Test
    public void exportImportRequirementFallsBackWithoutStorage() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.withoutEncryptedStorage(),
                Workload.INTERACTIVE_SHELL,
                RequirementProfile.builder().exportImport(true).build());
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.STORAGE_UNAVAILABLE, decision.reason());
    }

    @Test
    public void protectedVmUnsupportedFallsBackToFull() {
        DeviceFacts facts = DeviceFacts.protectedOnly().toBuilder()
                .protectedVmSupported(Support.NO)
                .build();
        FastLaneDecision decision = FastLaneRouter.decide(
                facts, Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.PROTECTED_UNAVAILABLE, decision.reason());
    }

    @Test
    public void unknownHiddenApiFactIsConservative() {
        DeviceFacts facts = DeviceFacts.protectedOnly().toBuilder()
                .hiddenApiBlocked(Support.UNKNOWN)
                .build();
        FastLaneDecision decision = FastLaneRouter.decide(
                facts, Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.UNKNOWN_DEVICE, decision.reason());
    }

    @Test
    public void unknownFullLaneAvailabilityStillKeepsFull() {
        DeviceFacts facts = DeviceFacts.noAvf().toBuilder()
                .fullLaneAvailable(Support.UNKNOWN)
                .build();
        FastLaneDecision decision = FastLaneRouter.decide(
                facts, Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.NO_AVF, decision.reason());
    }

    @Test
    public void missingProxyFallsBackOnlyWhenProxyIsRequired() {
        FastLaneDecision required = FastLaneRouter.decide(
                DeviceFacts.withoutTcpProxy(),
                Workload.INTERACTIVE_SHELL,
                RequirementProfile.builder().terminal(true).tcpProxy(true).build());
        assertEquals(Lane.FULL, required.lane());
        assertEquals(FastLaneReason.PROXY_UNAVAILABLE, required.reason());

        FastLaneDecision optional = FastLaneRouter.decide(
                DeviceFacts.withoutTcpProxy(), Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.FAST, optional.lane());
        assertFalse(optional.fastLaneCapabilities().tcpProxy());
    }

    @Test
    public void everyFullLinuxRequirementForcesFull() {
        assertFullLinux(RequirementProfile.builder().systemd(true).build());
        assertFullLinux(RequirementProfile.builder().docker(true).build());
        assertFullLinux(RequirementProfile.builder().nativeNetwork(true).build());
        assertFullLinux(RequirementProfile.builder().gpu(true).build());
        assertFullLinux(RequirementProfile.builder().nativePackageInstall(true).build());
    }

    @Test
    public void fullLinuxWorkloadForcesFullEvenWithoutExplicitRequirements() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.protectedOnly(), Workload.FULL_LINUX, RequirementProfile.none());
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.REQUIRES_FULL_LINUX, decision.reason());
    }

    @Test
    public void explicitFullLinuxRequirementWinsOverUnknownWorkload() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.protectedOnly(),
                Workload.UNKNOWN,
                RequirementProfile.builder().systemd(true).build());
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.REQUIRES_FULL_LINUX, decision.reason());
    }

    @Test
    public void fastLaneWorksEvenWhenTheFullLaneIsAbsent() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.withoutFullLane(), Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.FAST, decision.lane());
        assertEquals(FastLaneReason.FAST_LANE_AVAILABLE, decision.reason());
    }

    @Test
    public void hiddenApiWithoutFullLaneIsUnsupported() {
        DeviceFacts facts = DeviceFacts.hiddenApiBlockedDevice().toBuilder()
                .fullLaneAvailable(Support.NO)
                .build();
        FastLaneDecision decision = FastLaneRouter.decide(
                facts, Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.UNSUPPORTED, decision.lane());
        assertEquals(FastLaneReason.HIDDEN_API_BLOCKED, decision.reason());
    }

    @Test
    public void unknownStorageFactIsConservative() {
        DeviceFacts facts = DeviceFacts.protectedOnly().toBuilder()
                .encryptedStorageAvailable(Support.UNKNOWN)
                .build();
        FastLaneDecision decision = FastLaneRouter.decide(
                facts, Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.UNKNOWN_DEVICE, decision.reason());
    }

    @Test
    public void missingToolboxFallsBackWhenToolboxIsRequired() {
        DeviceFacts facts = DeviceFacts.protectedOnly().toBuilder()
                .sealedToolboxAvailable(Support.NO)
                .build();
        FastLaneDecision decision = FastLaneRouter.decide(
                facts,
                Workload.COMPILE,
                RequirementProfile.compile());
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.TOOLBOX_UNAVAILABLE, decision.reason());
    }

    @Test
    public void unknownDeviceStaysOnFull() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.unknown(), Workload.INTERACTIVE_SHELL, RequirementProfile.none());
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.UNKNOWN_DEVICE, decision.reason());
    }

    @Test
    public void unknownWorkloadStaysOnFull() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.protectedOnly(), Workload.UNKNOWN, null);
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.UNKNOWN_WORKLOAD, decision.reason());
    }

    @Test
    public void nullWorkloadIsUnknown() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.protectedOnly(), null, null);
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.UNKNOWN_WORKLOAD, decision.reason());
    }

    @Test
    public void unknownDistccFactBlocksDistccOffloadRequirement() {
        DeviceFacts facts = DeviceFacts.builder()
                .avfPresent(Support.YES)
                .protectedVmSupported(Support.YES)
                .managePermissionGranted(Support.YES)
                .hiddenApiBlocked(Support.NO)
                .encryptedStorageAvailable(Support.YES)
                .tcpProxyAvailable(Support.YES)
                .sealedToolboxAvailable(Support.YES)
                .distccOffloadAvailable(Support.UNKNOWN)
                .fullLaneAvailable(Support.YES)
                .build();
        FastLaneDecision decision = FastLaneRouter.decide(
                facts, Workload.COMPILE, RequirementProfile.compileWithDistccOffload());
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.UNKNOWN_DEVICE, decision.reason());
    }

    @Test
    public void unavailableDistccBlocksDistccOffloadRequirement() {
        DeviceFacts facts = DeviceFacts.builder()
                .avfPresent(Support.YES)
                .protectedVmSupported(Support.YES)
                .managePermissionGranted(Support.YES)
                .hiddenApiBlocked(Support.NO)
                .encryptedStorageAvailable(Support.YES)
                .tcpProxyAvailable(Support.YES)
                .sealedToolboxAvailable(Support.YES)
                .distccOffloadAvailable(Support.NO)
                .fullLaneAvailable(Support.YES)
                .build();
        FastLaneDecision decision = FastLaneRouter.decide(
                facts, Workload.COMPILE, RequirementProfile.compileWithDistccOffload());
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.DISTCC_UNAVAILABLE, decision.reason());
    }

    @Test
    public void unsupportedWhenNeitherLaneIsAvailable() {
        FastLaneDecision noAvf = FastLaneRouter.decide(
                DeviceFacts.noAvfWithoutFullLane(), Workload.INTERACTIVE_SHELL, null);
        assertEquals(Lane.UNSUPPORTED, noAvf.lane());
        assertEquals(FastLaneReason.NO_AVF, noAvf.reason());

        FastLaneDecision fullLinux = FastLaneRouter.decide(
                DeviceFacts.withoutFullLane(), Workload.FULL_LINUX, null);
        assertEquals(Lane.UNSUPPORTED, fullLinux.lane());
        assertEquals(FastLaneReason.REQUIRES_FULL_LINUX, fullLinux.reason());
    }

    private static void assertFullLinux(RequirementProfile requirements) {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.protectedOnly(), Workload.INTERACTIVE_SHELL, requirements);
        assertEquals(Lane.FULL, decision.lane());
        assertEquals(FastLaneReason.REQUIRES_FULL_LINUX, decision.reason());
    }
}
