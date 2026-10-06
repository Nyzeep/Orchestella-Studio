package io.github.nyzeep.droidlayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Pure JVM tests for {@link HostHardwareDetector} and adapter seam. */
public final class HostHardwareDetectorTest {

    @Test
    public void testDetectCpuCores() {
        int cores = HostHardwareDetector.detectCpuCores();
        assertTrue("detected CPU cores must be at least 1", cores >= 1);
    }

    @Test
    public void testFromMemoryInfo() {
        long totalBytes = 8L * 1024L * 1024L * 1024L;
        long availBytes = 4L * 1024L * 1024L * 1024L;
        long thresholdBytes = 500L * 1024L * 1024L;

        HostHardwareProfile profile = HostHardwareDetector.fromMemoryInfo(
                8, totalBytes, availBytes, thresholdBytes, false);

        assertNotNull(profile);
        assertEquals(8, profile.totalCores());
        assertEquals(totalBytes, profile.totalRamBytes());
        assertEquals(availBytes, profile.availRamBytes());
        assertEquals(thresholdBytes, profile.thresholdRamBytes());
        assertFalse(profile.isLowMemory());
    }

    @Test
    public void testDetectorInterfaceSeam() {
        HostHardwareProfile mockProfile = new HostHardwareProfile(
                12,
                16L * 1024L * 1024L * 1024L,
                8L * 1024L * 1024L * 1024L,
                1L * 1024L * 1024L * 1024L,
                false);

        HostHardwareDetector.Detector mockDetector = () -> mockProfile;

        HostHardwareProfile detected = mockDetector.detect();
        assertEquals(mockProfile, detected);
        assertEquals(8, detected.recommendedCpuCores()); // max(1, 12 - 1) clamped to MAX_CPU_CORES (8)
    }
}
