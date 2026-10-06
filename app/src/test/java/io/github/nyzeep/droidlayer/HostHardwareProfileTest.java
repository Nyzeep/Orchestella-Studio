package io.github.nyzeep.droidlayer;

import io.github.nyzeep.droidlayer.runtime.contract.VmHardwareConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Pure JVM unit tests for {@link HostHardwareProfile} safe allocation calculation model. */
public final class HostHardwareProfileTest {

    @Test
    public void testHighEndDevice16GbRam8Cores() {
        // 8 cores, 16 GB total RAM, 10 GB avail RAM
        long totalBytes = 16L * 1024L * 1024L * 1024L;
        long availBytes = 10L * 1024L * 1024L * 1024L;
        long thresholdBytes = 512L * 1024L * 1024L;

        HostHardwareProfile profile = new HostHardwareProfile(8, totalBytes, availBytes, thresholdBytes, false);

        assertEquals(8, profile.totalCores());
        assertEquals(16384, profile.totalRamMb());
        assertEquals(10240, profile.availRamMb());

        // Recommended vCPU: max(1, 8 - 1) = 7
        assertEquals(7, profile.recommendedCpuCores());

        // Recommended RAM: min(16384 - 2048 = 14336, 10240 * 0.75 = 7680) = 7680 MB
        assertEquals(7680, profile.rawRecommendedRamMb());
        assertEquals(7680, profile.recommendedRamMegabytes());

        // Safety Zone Evaluations for RAM
        assertEquals(HostHardwareProfile.SafetyZone.GREEN, profile.evaluateRamZone(4096));
        assertEquals(HostHardwareProfile.SafetyZone.GREEN, profile.evaluateRamZone(7680));
        assertEquals(HostHardwareProfile.SafetyZone.YELLOW, profile.evaluateRamZone(8192));
        // Violating host 2048MB reservation: 16384 - 14500 = 1884 < 2048MB -> RED
        assertEquals(HostHardwareProfile.SafetyZone.RED, profile.evaluateRamZone(14500));
        // Out of VmHardwareConfig bounds: < 512MB or > 8192MB -> RED
        assertEquals(HostHardwareProfile.SafetyZone.RED, profile.evaluateRamZone(256));

        // Safety Zone Evaluations for CPU
        assertEquals(HostHardwareProfile.SafetyZone.GREEN, profile.evaluateCpuZone(4));
        assertEquals(HostHardwareProfile.SafetyZone.GREEN, profile.evaluateCpuZone(7));
        assertEquals(HostHardwareProfile.SafetyZone.YELLOW, profile.evaluateCpuZone(8));
        assertEquals(HostHardwareProfile.SafetyZone.RED, profile.evaluateCpuZone(9));
        assertEquals(HostHardwareProfile.SafetyZone.RED, profile.evaluateCpuZone(0));
    }

    @Test
    public void testMidRangeDevice6GbRam8Cores() {
        // 8 cores, 6 GB total RAM, 2.5 GB avail RAM
        long totalBytes = 6L * 1024L * 1024L * 1024L;
        long availBytes = 2560L * 1024L * 1024L;
        long thresholdBytes = 400L * 1024L * 1024L;

        HostHardwareProfile profile = new HostHardwareProfile(8, totalBytes, availBytes, thresholdBytes, false);

        assertEquals(6144, profile.totalRamMb());
        assertEquals(2560, profile.availRamMb());

        // Recommended vCPU = 7
        assertEquals(7, profile.recommendedCpuCores());

        // Recommended RAM: min(6144 - 2048 = 4096, 2560 * 0.75 = 1920) = 1920 MB
        assertEquals(1920, profile.rawRecommendedRamMb());
        assertEquals(1920, profile.recommendedRamMegabytes());

        assertEquals(HostHardwareProfile.SafetyZone.GREEN, profile.evaluateRamZone(1024));
        assertEquals(HostHardwareProfile.SafetyZone.GREEN, profile.evaluateRamZone(1920));
        assertEquals(HostHardwareProfile.SafetyZone.YELLOW, profile.evaluateRamZone(3072));
        // Leaves 6144 - 4200 = 1944 < 2048MB -> RED
        assertEquals(HostHardwareProfile.SafetyZone.RED, profile.evaluateRamZone(4200));
    }

    @Test
    public void testCompactLowEndDevice3GbRam4Cores() {
        // 4 cores, 3 GB total RAM, 1.2 GB avail RAM
        long totalBytes = 3L * 1024L * 1024L * 1024L;
        long availBytes = 1200L * 1024L * 1024L;
        long thresholdBytes = 300L * 1024L * 1024L;

        HostHardwareProfile profile = new HostHardwareProfile(4, totalBytes, availBytes, thresholdBytes, false);

        assertEquals(3072, profile.totalRamMb());
        assertEquals(1200, profile.availRamMb());

        // Recommended vCPU: max(1, 4 - 1) = 3
        assertEquals(3, profile.recommendedCpuCores());

        // min(3072 - 2048 = 1024, 1200 * 0.75 = 900) = 900 MB
        assertEquals(900, profile.rawRecommendedRamMb());
        assertEquals(900, profile.recommendedRamMegabytes());
        assertEquals(768, profile.recommendedRamMegabytesAligned(256));

        assertEquals(HostHardwareProfile.SafetyZone.GREEN, profile.evaluateRamZone(512));
        assertEquals(HostHardwareProfile.SafetyZone.GREEN, profile.evaluateRamZone(768));
        assertEquals(HostHardwareProfile.SafetyZone.YELLOW, profile.evaluateRamZone(1024));
        assertEquals(HostHardwareProfile.SafetyZone.RED, profile.evaluateRamZone(1100));
    }

    @Test
    public void testSingleCoreEdgeCase() {
        long totalBytes = 4L * 1024L * 1024L * 1024L;
        long availBytes = 2L * 1024L * 1024L * 1024L;

        HostHardwareProfile profile = new HostHardwareProfile(1, totalBytes, availBytes, 200L * 1024L * 1024L, false);

        // max(1, 1 - 1) = 1
        assertEquals(1, profile.recommendedCpuCores());
        assertEquals(HostHardwareProfile.SafetyZone.GREEN, profile.evaluateCpuZone(1));
        assertEquals(HostHardwareProfile.SafetyZone.RED, profile.evaluateCpuZone(2));
    }

    @Test
    public void testProducesValidVmHardwareConfig() {
        long totalBytes = 8L * 1024L * 1024L * 1024L;
        long availBytes = 4L * 1024L * 1024L * 1024L;

        HostHardwareProfile profile = new HostHardwareProfile(8, totalBytes, availBytes, 500L * 1024L * 1024L, false);
        VmHardwareConfig config = profile.createRecommendedHardwareConfig();

        assertNotNull(config);
        assertEquals(profile.recommendedCpuCores(), config.cpuCores());
        assertEquals(profile.recommendedRamMegabytes(), config.ramMegabytes());
        assertTrue(config.diskCapacityBytes() >= VmHardwareConfig.MIN_DISK_CAPACITY_BYTES);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectsZeroCores() {
        new HostHardwareProfile(0, 1024, 512, 128, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectsNegativeRam() {
        new HostHardwareProfile(4, -1, 512, 128, false);
    }
}
