package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class UmlDistroCapabilityCatalogTest {

    @Test
    public void alpineIsCreatableOnFastUpArm64WithExactPinnedEntry() throws Exception {
        UmlDistroCapabilityCatalog.DistroGateVerdict verdict =
                UmlDistroCapabilityCatalog.evaluate(
                        "alpine", UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64);

        assertEquals(UmlDistroCapabilityCatalog.DistroProfileStatus.CREATABLE, verdict.status());
        assertEquals("alpine", verdict.distroId());
        assertEquals("3.22.2", verdict.version());
        assertEquals("alpine-minirootfs-3.22.2-aarch64.tar.gz", verdict.expectedFileName());
        assertEquals(
                "6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489",
                verdict.expectedSha256());
        assertEquals(3_981_043L, verdict.expectedSizeBytes());
        assertTrue(verdict.missingRequirements().isEmpty());

        UmlDistroCapabilityCatalog.DistroGateVerdict required =
                UmlDistroCapabilityCatalog.requireCreatable(
                        "ALPINE", UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64);
        assertEquals(verdict, required);
    }

    @Test
    public void debian13IsRefusedWithSystemdAndCgroupRequirements() {
        UmlDistroCapabilityCatalog.DistroGateVerdict verdict =
                UmlDistroCapabilityCatalog.evaluate(
                        "debian", UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64);

        assertEquals(UmlDistroCapabilityCatalog.DistroProfileStatus.REFUSED, verdict.status());
        assertEquals("debian", verdict.distroId());
        assertEquals("13", verdict.version());
        assertEquals("debian-13-rootfs.tar.xz", verdict.expectedFileName());
        assertEquals(
                "e4d504e13e43a384227639b74391ef164a67d916aa8f8a9871ecd73dc577db91",
                verdict.expectedSha256());
        assertEquals(90_346_224L, verdict.expectedSizeBytes());
        assertTrue(
                verdict.missingRequirements()
                        .containsAll(
                                List.of(
                                        UmlDistroCapabilityCatalog.KernelRequirementGap
                                                .MISSING_CGROUP_MEMCG,
                                        UmlDistroCapabilityCatalog.KernelRequirementGap
                                                .MISSING_CGROUP_PIDS,
                                        UmlDistroCapabilityCatalog.KernelRequirementGap
                                                .MISSING_USER_NS,
                                        UmlDistroCapabilityCatalog.KernelRequirementGap
                                                .MISSING_PID_NS,
                                        UmlDistroCapabilityCatalog.KernelRequirementGap
                                                .MISSING_AUTOFS_FS,
                                        UmlDistroCapabilityCatalog.KernelRequirementGap
                                                .MISSING_BPF_SYSCALL,
                                        UmlDistroCapabilityCatalog.KernelRequirementGap
                                                .MISSING_PSI,
                                        UmlDistroCapabilityCatalog.KernelRequirementGap
                                                .MISSING_BUSYBOX_INIT_CONTRACT)));

        UmlDistroCapabilityCatalog.DistroNotCreatableException ex =
                assertThrows(
                        UmlDistroCapabilityCatalog.DistroNotCreatableException.class,
                        () ->
                                UmlDistroCapabilityCatalog.requireCreatable(
                                        "debian",
                                        UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64));
        assertEquals(verdict, ex.verdict());
        assertFalse(ex.getMessage().isBlank());
    }

    @Test
    public void archIsRefusedWithLandlockAndSystemdGaps() {
        UmlDistroCapabilityCatalog.DistroGateVerdict verdict =
                UmlDistroCapabilityCatalog.evaluate(
                        "arch", UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64);

        assertEquals(UmlDistroCapabilityCatalog.DistroProfileStatus.REFUSED, verdict.status());
        assertEquals("arch", verdict.distroId());
        assertEquals("rolling", verdict.version());
        assertEquals("ArchLinuxARM-aarch64-latest.tar.gz", verdict.expectedFileName());
        assertEquals(
                "42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319",
                verdict.expectedSha256());
        assertEquals(829_367_415L, verdict.expectedSizeBytes());
        assertTrue(
                verdict.missingRequirements()
                        .contains(
                                UmlDistroCapabilityCatalog.KernelRequirementGap
                                        .MISSING_SECURITY_LANDLOCK));
        assertTrue(
                verdict.missingRequirements()
                        .contains(
                                UmlDistroCapabilityCatalog.KernelRequirementGap
                                        .MISSING_CGROUP_PIDS));
        assertThrows(
                UmlDistroCapabilityCatalog.DistroNotCreatableException.class,
                () ->
                        UmlDistroCapabilityCatalog.requireCreatable(
                                "arch", UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64));
    }

    @Test
    public void unverifiedOrUnknownDistrosAreRefused() {
        for (String id : List.of("ubuntu", "fedora", "opensuse", "almalinux", "rocky", "gentoo")) {
            UmlDistroCapabilityCatalog.DistroGateVerdict verdict =
                    UmlDistroCapabilityCatalog.evaluate(
                            id, UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64);
            assertEquals(UmlDistroCapabilityCatalog.DistroProfileStatus.REFUSED, verdict.status());
            assertTrue(
                    verdict.missingRequirements()
                            .contains(
                                    UmlDistroCapabilityCatalog.KernelRequirementGap
                                            .UNVERIFIED_ON_PROFILE));
        }
    }

    @Test
    public void fullSmpArm64SupportsAlpineAndHasNoKernelConfigGapsForDebianOrArch()
            throws Exception {
        UmlDistroCapabilityCatalog.DistroGateVerdict alpine =
                UmlDistroCapabilityCatalog.requireCreatable(
                        "alpine", UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64);
        assertEquals(UmlDistroCapabilityCatalog.DistroProfileStatus.CREATABLE, alpine.status());
        assertEquals("alpine-minirootfs-3.22.2-aarch64.tar.gz", alpine.expectedFileName());
        assertTrue(alpine.missingRequirements().isEmpty());

        UmlDistroCapabilityCatalog.DistroGateVerdict debian =
                UmlDistroCapabilityCatalog.evaluate(
                        "debian", UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64);
        assertEquals(UmlDistroCapabilityCatalog.DistroProfileStatus.REFUSED, debian.status());
        assertEquals("debian-13-rootfs.tar.xz", debian.expectedFileName());
        assertEquals(
                "e4d504e13e43a384227639b74391ef164a67d916aa8f8a9871ecd73dc577db91",
                debian.expectedSha256());
        assertEquals(90_346_224L, debian.expectedSizeBytes());
        assertEquals(
                List.of(UmlDistroCapabilityCatalog.KernelRequirementGap.UNVERIFIED_ON_PROFILE),
                debian.missingRequirements());

        UmlDistroCapabilityCatalog.DistroGateVerdict arch =
                UmlDistroCapabilityCatalog.evaluate(
                        "arch", UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64);
        assertEquals(UmlDistroCapabilityCatalog.DistroProfileStatus.REFUSED, arch.status());
        assertEquals(
                List.of(UmlDistroCapabilityCatalog.KernelRequirementGap.UNVERIFIED_ON_PROFILE),
                arch.missingRequirements());
    }
}
