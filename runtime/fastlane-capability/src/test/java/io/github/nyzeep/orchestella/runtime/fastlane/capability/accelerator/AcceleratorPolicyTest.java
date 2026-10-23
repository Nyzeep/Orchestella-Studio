package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/** AX-02: exhaustive, pure truth table plus stable JSON contract. */
@RunWith(Parameterized.class)
public final class AcceleratorPolicyTest {
    private static final String HOST_TRIPLE = "x86_64-linux-gnu";

    private final String name;
    private final AcceleratorFacts facts;
    private final String requiredTriple;
    private final AcceleratorMode expectedMode;
    private final AcceleratorReasonCode expectedReason;

    public AcceleratorPolicyTest(
            String name,
            AcceleratorFacts facts,
            String requiredTriple,
            AcceleratorMode expectedMode,
            AcceleratorReasonCode expectedReason) {
        this.name = name;
        this.facts = facts;
        this.requiredTriple = requiredTriple;
        this.expectedMode = expectedMode;
        this.expectedReason = expectedReason;
    }

    @Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        List<Object[]> cases = new ArrayList<>();
        add(cases, "all-facts-satisfied", ready().build(), HOST_TRIPLE,
                AcceleratorMode.QEMU_PLUS_ACCEL, AcceleratorReasonCode.ACCEL_AVAILABLE);
        add(cases, "charging-battery-allowed", ready().batteryState(BatteryState.CHARGING).build(),
                HOST_TRIPLE, AcceleratorMode.QEMU_PLUS_ACCEL,
                AcceleratorReasonCode.ACCEL_AVAILABLE);
        add(cases, "light-thermal-allowed", ready().thermalState(ThermalState.LIGHT).build(),
                HOST_TRIPLE, AcceleratorMode.QEMU_PLUS_ACCEL,
                AcceleratorReasonCode.ACCEL_AVAILABLE);
        add(cases, "no-avf", ready().avfPresent(false).build(), HOST_TRIPLE,
                AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_NON_PROTECTED);
        add(cases, "protected-only",
                ready().nonProtectedSupported(false).protectedOnly(true).build(), HOST_TRIPLE,
                AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_NON_PROTECTED);
        add(cases, "non-protected-unsupported",
                ready().nonProtectedSupported(false).build(), HOST_TRIPLE,
                AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_NON_PROTECTED);
        add(cases, "image-missing", ready().acceleratorImagePresent(false).build(), HOST_TRIPLE,
                AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.ACCEL_IMAGE_MISSING);
        add(cases, "vsock-missing", ready().vsockSupported(false).build(), HOST_TRIPLE,
                AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.ACCEL_VSOCK_UNAVAILABLE);
        add(cases, "compiler-triple-mismatch",
                ready().compilerTriple("aarch64-linux-gnu").build(), HOST_TRIPLE,
                AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.COMPILER_MISMATCH);
        add(cases, "compiler-triple-empty", ready().compilerTriple("").build(), HOST_TRIPLE,
                AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.COMPILER_MISMATCH);
        add(cases, "required-triple-empty", ready().build(), "",
                AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.COMPILER_MISMATCH);
        add(cases, "thermal-moderate", ready().thermalState(ThermalState.MODERATE).build(),
                HOST_TRIPLE, AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.THERMAL_BUDGET);
        add(cases, "thermal-severe", ready().thermalState(ThermalState.SEVERE).build(),
                HOST_TRIPLE, AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.THERMAL_BUDGET);
        add(cases, "thermal-unknown", ready().thermalState(ThermalState.UNKNOWN).build(),
                HOST_TRIPLE, AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.THERMAL_BUDGET);
        add(cases, "battery-low", ready().batteryState(BatteryState.LOW).build(),
                HOST_TRIPLE, AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.THERMAL_BUDGET);
        add(cases, "battery-unknown", ready().batteryState(BatteryState.UNKNOWN).build(),
                HOST_TRIPLE, AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.THERMAL_BUDGET);
        return cases;
    }

    @Test
    public void truthTableMatchesTheGoldenDecision() throws IOException {
        AcceleratorDecision decision = AcceleratorPolicy.decide(facts, requiredTriple);

        assertEquals(expectedMode, decision.mode());
        assertEquals(expectedReason, decision.reasonCode());
        if (expectedMode == AcceleratorMode.QEMU_ONLY) {
            assertEquals(List.of("accelerator", "distcc"), decision.degradedCapabilities());
        } else {
            assertTrue(decision.degradedCapabilities().isEmpty());
        }
        assertTrue("case missing from golden JSON: " + name,
                goldenJson().contains("\"case\":\"" + name + "\""));
        assertTrue("decision missing from golden JSON: " + decision.toJson(),
                goldenJson().contains(decision.toJson()));
    }

    @Test
    public void decisionJsonIsStableAndCompact() {
        AcceleratorDecision decision = AcceleratorPolicy.decide(facts, requiredTriple);

        String expected = "{\"mode\":\"" + expectedMode.name()
                + "\",\"reasonCode\":\"" + expectedReason.name()
                + "\",\"degradedCapabilities\":"
                + (expectedMode == AcceleratorMode.QEMU_ONLY
                        ? "[\"accelerator\",\"distcc\"]"
                        : "[]")
                + "}";
        assertEquals(expected, decision.toJson());
    }

    @Test
    public void concreteDecisionJsonExamples() {
        assertEquals(
                "{\"mode\":\"QEMU_PLUS_ACCEL\",\"reasonCode\":\"ACCEL_AVAILABLE\","
                        + "\"degradedCapabilities\":[]}",
                AcceleratorDecision.available().toJson());
        assertEquals(
                "{\"mode\":\"QEMU_ONLY\",\"reasonCode\":\"ACCEL_IMAGE_MISSING\","
                        + "\"degradedCapabilities\":[\"accelerator\",\"distcc\"]}",
                AcceleratorDecision.qemuOnly(
                        AcceleratorReasonCode.ACCEL_IMAGE_MISSING).toJson());
    }

    @Test
    public void factsPrecedenceIsFixed() {
        AcceleratorFacts noAvf = ready().avfPresent(false)
                .acceleratorImagePresent(false)
                .thermalState(ThermalState.CRITICAL)
                .build();
        assertEquals(AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_NON_PROTECTED,
                AcceleratorPolicy.decide(noAvf, HOST_TRIPLE).reasonCode());

        AcceleratorFacts noImage = ready().acceleratorImagePresent(false)
                .thermalState(ThermalState.CRITICAL)
                .build();
        assertEquals(AcceleratorReasonCode.ACCEL_IMAGE_MISSING,
                AcceleratorPolicy.decide(noImage, HOST_TRIPLE).reasonCode());

        AcceleratorFacts noVsock = ready().vsockSupported(false)
                .compilerTriple("aarch64-linux-gnu")
                .build();
        assertEquals(AcceleratorReasonCode.ACCEL_VSOCK_UNAVAILABLE,
                AcceleratorPolicy.decide(noVsock, HOST_TRIPLE).reasonCode());

        AcceleratorFacts mismatch = ready().compilerTriple("aarch64-linux-gnu")
                .thermalState(ThermalState.CRITICAL)
                .build();
        assertEquals(AcceleratorReasonCode.COMPILER_MISMATCH,
                AcceleratorPolicy.decide(mismatch, HOST_TRIPLE).reasonCode());
    }

    @Test
    public void factProviderIsAFakeableSeam() {
        AcceleratorFacts facts = ready().build();
        AcceleratorFactProvider fake = () -> facts;

        assertEquals(facts, fake.read());
        assertEquals(AcceleratorMode.QEMU_PLUS_ACCEL,
                AcceleratorPolicy.decide(fake.read(), HOST_TRIPLE).mode());
    }

    @Test
    public void degradedCapabilitiesAreImmutableAndValidated() {
        AcceleratorDecision decision = AcceleratorDecision.qemuOnly(
                AcceleratorReasonCode.THERMAL_BUDGET);
        try {
            decision.degradedCapabilities().add("gpu");
            throw new AssertionError("expected immutable list");
        } catch (UnsupportedOperationException expected) {
            // Expected: the decision owns an immutable copy.
        }
        try {
            new AcceleratorDecision(
                    AcceleratorMode.QEMU_ONLY,
                    AcceleratorReasonCode.THERMAL_BUDGET,
                    List.of("gpu"));
            throw new AssertionError("expected invalid capability rejection");
        } catch (IllegalArgumentException expected) {
            // Expected: only fixed capability tokens are allowed.
        }
    }

    @Test
    public void invalidModesAndReasonsAreRejected() {
        try {
            new AcceleratorDecision(
                    AcceleratorMode.QEMU_PLUS_ACCEL,
                    AcceleratorReasonCode.ACCEL_IMAGE_MISSING,
                    List.of());
            throw new AssertionError("expected reason rejection");
        } catch (IllegalArgumentException expected) {
            // Expected: available mode requires ACCEL_AVAILABLE.
        }
        try {
            new AcceleratorDecision(
                    AcceleratorMode.QEMU_ONLY,
                    AcceleratorReasonCode.ACCEL_AVAILABLE,
                    List.of("distcc"));
            throw new AssertionError("expected reason rejection");
        } catch (IllegalArgumentException expected) {
            // Expected: QEMU_ONLY requires a fallback reason.
        }
    }

    private static void add(
            List<Object[]> cases,
            String name,
            AcceleratorFacts facts,
            String requiredTriple,
            AcceleratorMode mode,
            AcceleratorReasonCode reason) {
        cases.add(new Object[] {name, facts, requiredTriple, mode, reason});
    }

    private static AcceleratorFacts.Builder ready() {
        return AcceleratorFacts.builder()
                .avfPresent(true)
                .nonProtectedSupported(true)
                .protectedOnly(false)
                .acceleratorImagePresent(true)
                .vsockSupported(true)
                .compilerTriple(HOST_TRIPLE)
                .thermalState(ThermalState.NOMINAL)
                .batteryState(BatteryState.OK);
    }

    private static String goldenJson() throws IOException {
        try (InputStream input = AcceleratorPolicyTest.class.getResourceAsStream(
                "/accelerator/truth-table-v1.json")) {
            if (input == null) {
                throw new IOException("golden JSON is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
