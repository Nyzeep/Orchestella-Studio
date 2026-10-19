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
    public void customDenialIsReadIndependentlyFromManageGrant() {
        AvfCapabilityEvaluator.AvfHostFacts facts = AvfHostFactsProbe.probe(
                35, new String[] {"arm64-v8a"}, "QA", "QA", () -> "true", feature -> true,
                permission -> AvfCapabilityEvaluator.MANAGE_VIRTUAL_MACHINE_PERMISSION.equals(permission));
        assertEquals(AvfCapabilityEvaluator.PermissionState.GRANTED, facts.manageVirtualMachinePermissionState());
        assertEquals(AvfCapabilityEvaluator.PermissionState.DENIED, facts.useCustomVirtualMachinePermissionState());
        assertEquals(AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
                AvfCapabilityEvaluator.evaluate(facts).status());
    }

    @Test
    public void recheckReplacesOldDenialAndLaterReadFailure() {
        int[] stage = {0};
        java.util.function.Predicate<String> permissions = permission -> {
            if (AvfCapabilityEvaluator.MANAGE_VIRTUAL_MACHINE_PERMISSION.equals(permission)) return true;
            if (stage[0] == 2) throw new IllegalStateException("synthetic permission read failure");
            return stage[0] == 1;
        };
        java.util.function.Supplier<AvfCapabilityEvaluator.AvfHostFacts> read = () ->
                AvfHostFactsProbe.probe(35, new String[] {"arm64-v8a"}, "QA", "QA",
                        () -> "true", feature -> true, permissions);
        assertEquals(AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
                AvfCapabilityEvaluator.evaluate(read.get()).status());
        stage[0] = 1;
        AvfCapabilityEvaluator.AvfHostFacts granted = read.get();
        assertEquals(AvfCapabilityEvaluator.AvfStatus.READY,
                AvfCapabilityEvaluator.evaluate(granted).status());
        assertEquals("已授权", AvfHostFactsProbe.permissionLabel(granted.useCustomVirtualMachinePermissionState()));
        stage[0] = 2;
        AvfCapabilityEvaluator.AvfHostFacts unknown = read.get();
        assertEquals(AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
                AvfCapabilityEvaluator.evaluate(unknown).status());
        assertEquals(AvfCapabilityEvaluator.PermissionState.UNKNOWN, unknown.useCustomVirtualMachinePermissionState());
        assertEquals("读取失败或尚未确认",
                AvfHostFactsProbe.permissionLabel(unknown.useCustomVirtualMachinePermissionState()));
    }

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
        assertFalse(facts.useCustomVirtualMachinePermission());
        assertEquals(AvfCapabilityEvaluator.PermissionState.UNKNOWN, facts.manageVirtualMachinePermissionState());
        assertEquals(AvfCapabilityEvaluator.PermissionState.UNKNOWN, facts.useCustomVirtualMachinePermissionState());
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

    @Test
    public void capabilityBitsMapToVmModeFacts() {
        AvfCapabilityEvaluator.AvfModeFacts protectedOnly =
                AvfHostFactsProbe.modeFactsFromCapabilities(
                        AvfHostFactsProbe.CAPABILITY_PROTECTED_VM,
                        "VirtualMachineManager.getCapabilities", "app", 1234L, "test-rom");
        assertEquals(AvfCapabilityEvaluator.VmSupport.SUPPORTED, protectedOnly.protectedVm());
        assertEquals(AvfCapabilityEvaluator.VmSupport.UNSUPPORTED, protectedOnly.nonProtectedVm());

        AvfCapabilityEvaluator.AvfModeFacts both =
                AvfHostFactsProbe.modeFactsFromCapabilities(
                        AvfHostFactsProbe.CAPABILITY_PROTECTED_VM
                                | AvfHostFactsProbe.CAPABILITY_NON_PROTECTED_VM,
                        "VirtualMachineManager.getCapabilities", "app", 1234L, "test-rom");
        assertEquals(AvfCapabilityEvaluator.VmSupport.SUPPORTED, both.nonProtectedVm());
    }

    @Test
    public void protectedOnlyModeFactsReachTheEvaluatorAsModeUnsupported() {
        AvfCapabilityEvaluator.AvfModeFacts protectedOnly =
                AvfHostFactsProbe.modeFactsFromCapabilities(
                        AvfHostFactsProbe.CAPABILITY_PROTECTED_VM,
                        "VirtualMachineManager.getCapabilities", "app", 1234L, "test-rom");
        AvfCapabilityEvaluator.AvfReadiness readiness = AvfCapabilityEvaluator.evaluate(
                AvfHostFactsProbe.probe(
                        35,
                        new String[] {"arm64-v8a"},
                        "MT6991",
                        "Test Device",
                        () -> "true",
                        feature -> true,
                        permission -> true,
                        protectedOnly));

        assertEquals(AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED, readiness.status());
        assertFalse(readiness.wizardEligible());
    }

    @Test
    public void unreadableModeFactsAreUnknownNotAuthorization() {
        AvfCapabilityEvaluator.AvfModeFacts unknown = AvfCapabilityEvaluator.AvfModeFacts.unknown(
                "VirtualMachineManager.getCapabilities", "app", 1234L, "test-rom");
        AvfCapabilityEvaluator.AvfReadiness readiness = AvfCapabilityEvaluator.evaluate(
                AvfHostFactsProbe.probe(
                        35,
                        new String[] {"arm64-v8a"},
                        "MT6991",
                        "Test Device",
                        () -> "true",
                        feature -> true,
                        permission -> true,
                        unknown));

        assertEquals(AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN, readiness.status());
        assertFalse(readiness.wizardEligible());
    }
}
