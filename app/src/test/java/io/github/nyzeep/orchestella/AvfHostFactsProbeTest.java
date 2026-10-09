package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator;
import java.util.List;
import org.junit.Test;

/**
 * The pure probe assembly: every Android-boundary read is a supplied lambda,
 * and a throwing read degrades to the honest default (null / false) instead
 * of guessing a capability.
 */
public final class AvfHostFactsProbeTest {
    @Test
    public void assemblesFactsFromTheSuppliedReads() {
        AvfCapabilityEvaluator.AvfHostFacts facts = AvfHostFactsProbe.probe(
                35,
                new String[] {"arm64-v8a", "armeabi-v7a"},
                "MT6991",
                "Test Device",
                () -> "true",
                feature -> AvfCapabilityEvaluator.VIRTUALIZATION_FEATURE.equals(feature),
                permission ->
                        AvfCapabilityEvaluator.MANAGE_VIRTUAL_MACHINE_PERMISSION.equals(
                                permission));

        assertEquals(35, facts.sdkInt());
        assertEquals(List.of("arm64-v8a", "armeabi-v7a"), facts.supportedAbis());
        assertEquals("true", facts.virtualizationProperty());
        assertTrue(facts.virtualizationFrameworkFeature());
        assertTrue(facts.manageVirtualMachinePermission());
        assertEquals("MT6991", facts.socModel());
        assertEquals("Test Device", facts.deviceModel());
    }

    @Test
    public void failingReadsDegradeToHonestDefaults() {
        AvfCapabilityEvaluator.AvfHostFacts facts = AvfHostFactsProbe.probe(
                33,
                new String[] {"arm64-v8a"},
                null,
                null,
                () -> {
                    throw new IllegalStateException("hidden api blocked");
                },
                feature -> {
                    throw new RuntimeException("stub");
                },
                permission -> {
                    throw new RuntimeException("stub");
                });

        assertNull(facts.virtualizationProperty());
        assertFalse(facts.virtualizationFrameworkFeature());
        assertFalse(facts.manageVirtualMachinePermission());
        assertNull(facts.socModel());
    }

    @Test
    public void reflectiveSystemPropertyReadFailsClosedOnTheJvm() {
        // android.os.SystemProperties is not a real JVM class (or is a throwing
        // stub in unit tests); the probe must return null either way.
        assertNull(AvfHostFactsProbe.readSystemProperty("ro.virtualization.supported"));
    }

    @Test
    public void probeResultFeedsTheEvaluatorUnchanged() {
        AvfCapabilityEvaluator.AvfReadiness readiness = AvfCapabilityEvaluator.evaluate(
                AvfHostFactsProbe.probe(
                        35,
                        new String[] {"arm64-v8a"},
                        "SM8750",
                        "Blocked Device",
                        () -> "true",
                        feature -> true,
                        permission -> true));

        assertEquals(
                AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED,
                readiness.status());
        assertFalse(readiness.wizardEligible());
    }
}
