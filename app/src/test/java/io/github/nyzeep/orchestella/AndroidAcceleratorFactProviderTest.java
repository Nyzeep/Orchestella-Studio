package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.PowerManager;
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator;
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator.AvfStatus;
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator.PermissionState;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorImageManifest;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.BatteryState;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.ThermalState;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** AX-08: the Android fact provider maps existing seams and never guesses. */
public final class AndroidAcceleratorFactProviderTest {
    private static final Optional<AcceleratorImageManifest> IMAGE =
            Optional.of(AcceleratorImageManifest.of("aarch64-linux-gnu"));

    @Test
    public void onlyReadyClaimsNonProtectedSupport() {
        for (AvfStatus status : AvfStatus.values()) {
            AcceleratorFacts facts = facts(status);

            if (status == AvfStatus.READY) {
                assertTrue(status.name(), facts.nonProtectedSupported());
                assertTrue(status.name(), facts.avfPresent());
                assertFalse(status.name(), facts.protectedOnly());
            } else {
                assertFalse(status.name(), facts.nonProtectedSupported());
            }
        }
    }

    @Test
    public void modeUnsupportedIsProtectedOnlyAndNeverAnAccelerator() {
        AcceleratorFacts facts = facts(AvfStatus.MODE_UNSUPPORTED);

        assertTrue(facts.avfPresent());
        assertTrue(facts.protectedOnly());
        assertFalse(facts.nonProtectedSupported());
    }

    @Test
    public void authorizationAndHiddenApiStatesClaimPresenceButNotSupport() {
        for (AvfStatus status : List.of(
                AvfStatus.NEEDS_AUTHORIZATION, AvfStatus.HIDDEN_API_BLOCKED)) {
            AcceleratorFacts facts = facts(status);

            assertTrue(status.name(), facts.avfPresent());
            assertFalse(status.name(), facts.nonProtectedSupported());
            assertFalse(status.name(), facts.protectedOnly());
        }
    }

    @Test
    public void unknownStatesClaimNothing() {
        for (AvfStatus status : List.of(
                AvfStatus.UNSUPPORTED, AvfStatus.REFLECTION_UNAVAILABLE, AvfStatus.MODE_UNKNOWN)) {
            AcceleratorFacts facts = facts(status);

            assertFalse(status.name(), facts.avfPresent());
            assertFalse(status.name(), facts.nonProtectedSupported());
        }
    }

    @Test
    public void missingVerdictClaimsNothing() {
        AcceleratorFacts facts = AndroidAcceleratorFactProvider.fromStatus(
                null, IMAGE, true, ThermalState.NOMINAL, BatteryState.OK);

        assertFalse(facts.avfPresent());
        assertFalse(facts.nonProtectedSupported());
    }

    @Test
    public void manifestSuppliesPresenceAndCompilerTriple() {
        AcceleratorFacts present = facts(AvfStatus.READY);

        assertTrue(present.acceleratorImagePresent());
        assertEquals("aarch64-linux-gnu", present.compilerTriple());

        AcceleratorFacts absent = AndroidAcceleratorFactProvider.fromStatus(
                AvfStatus.READY, Optional.empty(), true,
                ThermalState.NOMINAL, BatteryState.OK);

        assertFalse(absent.acceleratorImagePresent());
        assertEquals("", absent.compilerTriple());
    }

    @Test
    public void vsockAndBudgetsPassThrough() {
        AcceleratorFacts facts = AndroidAcceleratorFactProvider.fromStatus(
                AvfStatus.READY, IMAGE, false, ThermalState.MODERATE, BatteryState.LOW);

        assertFalse(facts.vsockSupported());
        assertEquals(ThermalState.MODERATE, facts.thermalState());
        assertEquals(BatteryState.LOW, facts.batteryState());
    }

    @Test
    public void thermalStatusMappingCoversPlatformCodes() {
        assertEquals(ThermalState.NOMINAL,
                AndroidAcceleratorFactProvider.mapThermalStatus(PowerManager.THERMAL_STATUS_NONE));
        assertEquals(ThermalState.LIGHT,
                AndroidAcceleratorFactProvider.mapThermalStatus(PowerManager.THERMAL_STATUS_LIGHT));
        assertEquals(ThermalState.MODERATE,
                AndroidAcceleratorFactProvider.mapThermalStatus(PowerManager.THERMAL_STATUS_MODERATE));
        assertEquals(ThermalState.SEVERE,
                AndroidAcceleratorFactProvider.mapThermalStatus(PowerManager.THERMAL_STATUS_SEVERE));
        assertEquals(ThermalState.CRITICAL,
                AndroidAcceleratorFactProvider.mapThermalStatus(PowerManager.THERMAL_STATUS_CRITICAL));
        assertEquals(ThermalState.UNKNOWN, AndroidAcceleratorFactProvider.mapThermalStatus(99));
    }

    @Test
    public void batteryMappingChargingAlwaysAllowsAndLowCapacityRefuses() {
        assertEquals(BatteryState.CHARGING, AndroidAcceleratorFactProvider.mapBattery(true, 3));
        assertEquals(BatteryState.OK, AndroidAcceleratorFactProvider.mapBattery(false, 100));
        assertEquals(BatteryState.OK,
                AndroidAcceleratorFactProvider.mapBattery(
                        false, AndroidAcceleratorFactProvider.BATTERY_OK_PERCENT));
        assertEquals(BatteryState.LOW,
                AndroidAcceleratorFactProvider.mapBattery(
                        false, AndroidAcceleratorFactProvider.BATTERY_OK_PERCENT - 1));
        assertEquals(BatteryState.CRITICAL,
                AndroidAcceleratorFactProvider.mapBattery(
                        false, AndroidAcceleratorFactProvider.BATTERY_CRITICAL_PERCENT - 1));
        assertEquals(BatteryState.UNKNOWN, AndroidAcceleratorFactProvider.mapBattery(false, -1));
    }

    @Test
    public void everyFailingSourceCollapsesToConservativeFacts() {
        AndroidAcceleratorFactProvider provider = new AndroidAcceleratorFactProvider(
                () -> {
                    throw new IllegalStateException("avf read failed");
                },
                () -> {
                    throw new IllegalStateException("manifest read failed");
                },
                () -> {
                    throw new IllegalStateException("vsock read failed");
                },
                () -> {
                    throw new IllegalStateException("thermal read failed");
                },
                () -> {
                    throw new IllegalStateException("battery read failed");
                });

        AcceleratorFacts facts = provider.read();

        assertFalse(facts.avfPresent());
        assertFalse(facts.nonProtectedSupported());
        assertFalse(facts.protectedOnly());
        assertFalse(facts.acceleratorImagePresent());
        assertFalse(facts.vsockSupported());
        assertEquals("", facts.compilerTriple());
        assertEquals(ThermalState.UNKNOWN, facts.thermalState());
        assertEquals(BatteryState.UNKNOWN, facts.batteryState());
    }

    @Test
    public void nullSourcesCollapseToUnknownFacts() {
        AndroidAcceleratorFactProvider provider = new AndroidAcceleratorFactProvider(
                () -> null, () -> null, () -> false, () -> null, () -> null);

        AcceleratorFacts facts = provider.read();

        assertFalse(facts.avfPresent());
        assertFalse(facts.acceleratorImagePresent());
        assertEquals(ThermalState.UNKNOWN, facts.thermalState());
        assertEquals(BatteryState.UNKNOWN, facts.batteryState());
    }

    /** The real evaluator verdict, not a hand-built status, drives the mapping. */
    @Test
    public void realEvaluatorReadyVerdictYieldsNonProtectedSupport() {
        AvfCapabilityEvaluator.AvfReadiness readiness = AvfCapabilityEvaluator.evaluate(
                new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                        33, List.of("arm64-v8a"), "true", true,
                        PermissionState.GRANTED, PermissionState.GRANTED)
                        .build());

        assertEquals(AvfStatus.READY, readiness.status());
        AcceleratorFacts facts = AndroidAcceleratorFactProvider.fromReadiness(
                readiness, IMAGE, true, ThermalState.NOMINAL, BatteryState.OK);

        assertTrue(facts.avfPresent());
        assertTrue(facts.nonProtectedSupported());
        assertFalse(facts.protectedOnly());
    }

    @Test
    public void realEvaluatorMissingPermissionRefusesTheLane() {
        AvfCapabilityEvaluator.AvfReadiness readiness = AvfCapabilityEvaluator.evaluate(
                new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                        33, List.of("arm64-v8a"), "true", true,
                        PermissionState.DENIED, PermissionState.DENIED)
                        .build());

        assertEquals(AvfStatus.NEEDS_AUTHORIZATION, readiness.status());
        AcceleratorFacts facts = AndroidAcceleratorFactProvider.fromReadiness(
                readiness, IMAGE, true, ThermalState.NOMINAL, BatteryState.OK);

        assertTrue(facts.avfPresent());
        assertFalse(facts.nonProtectedSupported());
    }

    private static AcceleratorFacts facts(AvfStatus status) {
        return AndroidAcceleratorFactProvider.fromStatus(
                status, IMAGE, true, ThermalState.NOMINAL, BatteryState.OK);
    }
}
