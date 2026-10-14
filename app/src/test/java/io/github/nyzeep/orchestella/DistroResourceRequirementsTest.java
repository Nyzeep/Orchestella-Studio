package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import org.junit.Test;

public class DistroResourceRequirementsTest {
    private static final long MIB = 1024L * 1024L;
    private static final long ARCH_INPUT_BYTES = 829367415L;

    @Test
    public void pinnedArchNeeds2304MbForAssemblyAndSuggests3072Mb() {
        assertEquals(ARCH_INPUT_BYTES, DistroCatalogRegistry.getInstance().require("arch").sizeBytes());
        assertEquals(2304, DistroResourceRequirements.minimumBootRamMb(ARCH_INPUT_BYTES));
        assertEquals(3072, DistroResourceRequirements.recommendedBootRamMb(ARCH_INPUT_BYTES));
    }

    @Test
    public void smallUbuntuInputUsesAssemblyPeakRatherThanAptExperienceBaseline() {
        assertEquals(768, DistroResourceRequirements.minimumBootRamMb(30L * MIB));
        DistroManifest ubuntu = DistroCatalogRegistry.getInstance().require("ubuntu");
        assertEquals(768, DistroResourceRequirements.minimumBootRamMb(ubuntu.sizeBytes()));
        DistroResourceRequirements.requireBootRam("ubuntu", 768);
    }

    @Test
    public void roundsUpOnlyAfterExact256MbPeakBoundary() {
        // Two copies of a 128 MiB input + 512 MiB reserve exactly fill 768 MiB.
        assertEquals(768, DistroResourceRequirements.minimumBootRamMb(128L * MIB - 1L));
        assertEquals(768, DistroResourceRequirements.minimumBootRamMb(128L * MIB));
        assertEquals(1024, DistroResourceRequirements.minimumBootRamMb(128L * MIB + 1L));
        assertEquals(768, DistroResourceRequirements.minimumBootRamMb(1L));
    }

    @Test
    public void rejectsArchLowRamWithActionableMessageAndAcceptsExactMinimum() {
        for (int ram : new int[] {512, 1024, 2048, 2303}) {
            IllegalArgumentException rejection = rejected(
                    () -> DistroResourceRequirements.requireBootRam("arch", ram));
            assertTrue(rejection.getMessage().contains("首次装配至少需要 2304 MB"));
            assertTrue(rejection.getMessage().contains("当前 " + ram + " MB"));
            assertTrue(rejection.getMessage().contains("调整硬件后启动"));
        }
        DistroResourceRequirements.requireBootRam("arch", 2304);
        DistroResourceRequirements.requireBootRam("arch", 3072);
    }

    @Test
    public void rechecksCurrentRamAfterAdvancedHardwareEditLowersIt() {
        DistroResourceRequirements.requireBootRam("arch", 3072);
        rejected(() -> DistroResourceRequirements.requireBootRam("arch", 512));
    }

    @Test
    public void raisesOnlyInsufficientWizardRamAndKeepsSufficientUserAllocation() {
        assertEquals(3072, DistroResourceRequirements.suggestedWizardRamMb(
                ARCH_INPUT_BYTES, 1024, 6144));
        assertEquals(2560, DistroResourceRequirements.suggestedWizardRamMb(
                ARCH_INPUT_BYTES, 1024, 2560));
        assertEquals(2304, DistroResourceRequirements.suggestedWizardRamMb(
                ARCH_INPUT_BYTES, 2304, 6144));
        assertEquals(2401, DistroResourceRequirements.suggestedWizardRamMb(
                ARCH_INPUT_BYTES, 2401, 6144));
        assertEquals(4096, DistroResourceRequirements.suggestedWizardRamMb(
                30L * MIB, 4096, 6144));
    }

    @Test
    public void neverLowersAssemblyMinimumToFitAnUnsafeHostBudget() {
        int ram = DistroResourceRequirements.suggestedWizardRamMb(
                ARCH_INPUT_BYTES, 512, 2048);
        assertEquals(2304, ram);
        HostHardwareProfile fourGbHost = new HostHardwareProfile(
                4, 4096L * MIB, 2048L * MIB, 256L * MIB, false);
        assertEquals(HostHardwareProfile.SafetyZone.RED, fourGbHost.evaluateRamZone(ram));
    }

    @Test
    public void rejectsNegativeZeroAndOverflowingSizesInsteadOfWrappingToSafeRam() {
        long additionOverflow = (Long.MAX_VALUE - 512L * MIB) / 2L + 1L;
        for (long size : new long[] {
                -1L, Long.MIN_VALUE, 0L, Long.MAX_VALUE, Long.MAX_VALUE / 2L, additionOverflow
        }) {
            rejected(() -> DistroResourceRequirements.minimumBootRamMb(size));
            rejected(() -> DistroResourceRequirements.requireBootRam(size, 8192));
        }
    }

    @Test
    public void rejectsMbResultOverflowAtTheFirstUnrepresentableStep() {
        long largestAlignedMb = (Integer.MAX_VALUE / 256L) * 256L;
        long largestInput = (largestAlignedMb * MIB - 512L * MIB) / 2L;
        assertEquals((int) largestAlignedMb,
                DistroResourceRequirements.minimumBootRamMb(largestInput));
        rejected(() -> DistroResourceRequirements.minimumBootRamMb(largestInput + 1L));
        rejected(() -> DistroResourceRequirements.recommendedBootRamMb(largestInput));
    }

    @Test
    public void rejectsUnknownAndMalformedDistroIdsWithoutUbuntuFallback() {
        for (String id : new String[] {null, "", " ", "unknown", "../arch", "arch\n", "arch?ubuntu"}) {
            IllegalArgumentException rejection = rejected(
                    () -> DistroResourceRequirements.requireBootRam(id, 8192));
            assertTrue(rejection.getMessage().contains("无法确认"));
        }
    }

    @Test
    public void everyPinnedManifestUsesItsOwnSizeAndRejectsRamBelowItsGuard() {
        for (DistroManifest manifest : DistroCatalogRegistry.getInstance().listAll()) {
            int minimum = DistroResourceRequirements.minimumBootRamMb(manifest.sizeBytes());
            assertTrue(minimum >= 512);
            assertEquals(0, minimum % 256);
            assertTrue(minimum * MIB >= 2L * manifest.sizeBytes() + 512L * MIB);
            DistroResourceRequirements.requireBootRam(manifest.id(), minimum);
            rejected(() -> DistroResourceRequirements.requireBootRam(manifest.id(), minimum - 1));
        }
    }

    private static IllegalArgumentException rejected(Runnable action) {
        try {
            action.run();
            fail("Expected fail-closed resource rejection");
            return null;
        } catch (IllegalArgumentException expected) {
            return expected;
        }
    }
}
