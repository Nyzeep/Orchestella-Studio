package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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

/**
 * AX-02 + PVM-01: exhaustive, pure truth table plus stable JSON contract.
 *
 * <p>v2 adds the PVM_PAYLOAD backend: the policy evaluates backends in order
 * (PVM_PAYLOAD → CUSTOM_VM → HOST_NATIVE) and the decision carries the chosen
 * backend plus every rejection it passed on the way. The v1 rows are kept as a
 * regression subset; only the three rows whose facts name no backend candidate
 * at all now end in {@code ACCEL_UNAVAILABLE_NO_BACKEND} — the old
 * {@code ACCEL_UNAVAILABLE_NO_NON_PROTECTED} verdict is preserved verbatim in
 * the rejection list of those decisions.
 */
@RunWith(Parameterized.class)
public final class AcceleratorPolicyTest {
    private static final String HOST_TRIPLE = "x86_64-linux-gnu";
    private static final String PAYLOAD_VERSION = "clang-20.1.0";
    private static final String OTHER_VERSION = "clang-19.1.0";

    private final String name;
    private final AcceleratorFacts facts;
    private final String requiredTriple;
    private final String requiredVersion;
    private final AcceleratorMode expectedMode;
    private final AcceleratorReasonCode expectedReason;
    private final CompileBackend expectedBackend;

    public AcceleratorPolicyTest(
            String name,
            AcceleratorFacts facts,
            String requiredTriple,
            String requiredVersion,
            AcceleratorMode expectedMode,
            AcceleratorReasonCode expectedReason,
            CompileBackend expectedBackend) {
        this.name = name;
        this.facts = facts;
        this.requiredTriple = requiredTriple;
        this.requiredVersion = requiredVersion;
        this.expectedMode = expectedMode;
        this.expectedReason = expectedReason;
        this.expectedBackend = expectedBackend;
    }

    @Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        List<Object[]> cases = new ArrayList<>();
        // --- v1 regression subset (16 rows; three updated verdicts) -------------
        add(cases, "all-facts-satisfied", ready().build(), HOST_TRIPLE, null,
                AcceleratorMode.QEMU_PLUS_ACCEL, AcceleratorReasonCode.ACCEL_AVAILABLE,
                CompileBackend.CUSTOM_VM);
        add(cases, "charging-battery-allowed", ready().batteryState(BatteryState.CHARGING).build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_PLUS_ACCEL,
                AcceleratorReasonCode.ACCEL_AVAILABLE, CompileBackend.CUSTOM_VM);
        add(cases, "light-thermal-allowed", ready().thermalState(ThermalState.LIGHT).build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_PLUS_ACCEL,
                AcceleratorReasonCode.ACCEL_AVAILABLE, CompileBackend.CUSTOM_VM);
        // v1 expected ACCEL_UNAVAILABLE_NO_NON_PROTECTED here; v2 names the honest
        // terminal state (no backend candidate) while keeping the old verdict in
        // the rejection list.
        add(cases, "no-avf", ready().avfPresent(false).build(), HOST_TRIPLE, null,
                AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_BACKEND, CompileBackend.NONE);
        add(cases, "protected-only",
                ready().nonProtectedSupported(false).protectedOnly(true).build(), HOST_TRIPLE,
                null, AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_BACKEND, CompileBackend.NONE);
        add(cases, "non-protected-unsupported",
                ready().nonProtectedSupported(false).build(), HOST_TRIPLE, null,
                AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_BACKEND, CompileBackend.NONE);
        add(cases, "image-missing", ready().acceleratorImagePresent(false).build(), HOST_TRIPLE,
                null, AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.ACCEL_IMAGE_MISSING,
                CompileBackend.CUSTOM_VM);
        add(cases, "vsock-missing", ready().vsockSupported(false).build(), HOST_TRIPLE, null,
                AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.ACCEL_VSOCK_UNAVAILABLE,
                CompileBackend.CUSTOM_VM);
        add(cases, "compiler-triple-mismatch",
                ready().compilerTriple("aarch64-linux-gnu").build(), HOST_TRIPLE, null,
                AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.COMPILER_MISMATCH,
                CompileBackend.CUSTOM_VM);
        add(cases, "compiler-triple-empty", ready().compilerTriple("").build(), HOST_TRIPLE, null,
                AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.COMPILER_MISMATCH,
                CompileBackend.CUSTOM_VM);
        add(cases, "required-triple-empty", ready().build(), "", null,
                AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.COMPILER_MISMATCH,
                CompileBackend.CUSTOM_VM);
        add(cases, "thermal-moderate", ready().thermalState(ThermalState.MODERATE).build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.THERMAL_BUDGET, CompileBackend.CUSTOM_VM);
        add(cases, "thermal-severe", ready().thermalState(ThermalState.SEVERE).build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.THERMAL_BUDGET, CompileBackend.CUSTOM_VM);
        add(cases, "thermal-unknown", ready().thermalState(ThermalState.UNKNOWN).build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.THERMAL_BUDGET, CompileBackend.CUSTOM_VM);
        add(cases, "battery-low", ready().batteryState(BatteryState.LOW).build(), HOST_TRIPLE,
                null, AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.THERMAL_BUDGET,
                CompileBackend.CUSTOM_VM);
        add(cases, "battery-unknown", ready().batteryState(BatteryState.UNKNOWN).build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.THERMAL_BUDGET, CompileBackend.CUSTOM_VM);
        // --- v2: the PVM_PAYLOAD backend ----------------------------------------
        add(cases, "protected-only-payload-selected",
                protectedOnly().payloadCompilerPresent(true)
                        .payloadCompilerVersion(PAYLOAD_VERSION)
                        .payloadTargetTriple(HOST_TRIPLE)
                        .build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_PLUS_ACCEL,
                AcceleratorReasonCode.ACCEL_AVAILABLE, CompileBackend.PVM_PAYLOAD);
        add(cases, "protected-only-payload-image-missing",
                protectedOnly().payloadCompilerPresent(false).build(), HOST_TRIPLE, null,
                AcceleratorMode.QEMU_ONLY, AcceleratorReasonCode.PAYLOAD_IMAGE_MISSING,
                CompileBackend.PVM_PAYLOAD);
        add(cases, "payload-priority-over-custom-vm", ready()
                .protectedVmSupported(true)
                .payloadCompilerPresent(true)
                .payloadCompilerVersion(PAYLOAD_VERSION)
                .payloadTargetTriple(HOST_TRIPLE)
                .build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_PLUS_ACCEL,
                AcceleratorReasonCode.ACCEL_AVAILABLE, CompileBackend.PVM_PAYLOAD);
        add(cases, "payload-unavailable-custom-vm-fallback", ready()
                .protectedVmSupported(false)
                .payloadCompilerPresent(false)
                .build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_PLUS_ACCEL,
                AcceleratorReasonCode.ACCEL_AVAILABLE, CompileBackend.CUSTOM_VM);
        add(cases, "protected-only-payload-triple-mismatch",
                protectedOnly().payloadCompilerPresent(true)
                        .payloadCompilerVersion(PAYLOAD_VERSION)
                        .payloadTargetTriple("aarch64-linux-gnu")
                        .build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.PAYLOAD_COMPILER_MISMATCH, CompileBackend.PVM_PAYLOAD);
        add(cases, "protected-only-payload-version-missing",
                protectedOnly().payloadCompilerPresent(true)
                        .payloadCompilerVersion("")
                        .payloadTargetTriple(HOST_TRIPLE)
                        .build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.PAYLOAD_COMPILER_MISMATCH, CompileBackend.PVM_PAYLOAD);
        add(cases, "protected-only-payload-version-mismatch-required",
                protectedOnly().payloadCompilerPresent(true)
                        .payloadCompilerVersion(OTHER_VERSION)
                        .payloadTargetTriple(HOST_TRIPLE)
                        .build(),
                HOST_TRIPLE, PAYLOAD_VERSION, AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.PAYLOAD_COMPILER_MISMATCH, CompileBackend.PVM_PAYLOAD);
        add(cases, "protected-only-payload-thermal",
                protectedOnly().payloadCompilerPresent(true)
                        .payloadCompilerVersion(PAYLOAD_VERSION)
                        .payloadTargetTriple(HOST_TRIPLE)
                        .thermalState(ThermalState.SEVERE)
                        .build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.THERMAL_BUDGET, CompileBackend.PVM_PAYLOAD);
        add(cases, "both-backends-thermal", ready()
                .protectedVmSupported(true)
                .payloadCompilerPresent(true)
                .payloadCompilerVersion(PAYLOAD_VERSION)
                .payloadTargetTriple(HOST_TRIPLE)
                .thermalState(ThermalState.MODERATE)
                .build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_ONLY,
                AcceleratorReasonCode.THERMAL_BUDGET, CompileBackend.CUSTOM_VM);
        add(cases, "charging-payload-selected",
                protectedOnly().payloadCompilerPresent(true)
                        .payloadCompilerVersion(PAYLOAD_VERSION)
                        .payloadTargetTriple(HOST_TRIPLE)
                        .batteryState(BatteryState.CHARGING)
                        .build(),
                HOST_TRIPLE, null, AcceleratorMode.QEMU_PLUS_ACCEL,
                AcceleratorReasonCode.ACCEL_AVAILABLE, CompileBackend.PVM_PAYLOAD);
        return cases;
    }

    @Test
    public void truthTableMatchesTheGoldenDecision() throws IOException {
        AcceleratorDecision decision = decide(facts, requiredTriple, requiredVersion);

        assertEquals(name, expectedMode, decision.mode());
        assertEquals(name, expectedReason, decision.reasonCode());
        assertEquals(name, expectedBackend, decision.backend());
        if (expectedMode == AcceleratorMode.QEMU_ONLY) {
            assertEquals(name, List.of("accelerator", "distcc"), decision.degradedCapabilities());
        } else {
            assertTrue(name, decision.degradedCapabilities().isEmpty());
        }
        assertTrue("case missing from golden JSON: " + name,
                goldenJson().contains("\"case\":\"" + name + "\""));
        assertTrue("decision missing from golden JSON: " + decision.toJson(),
                goldenJson().contains(decision.toJson()));
    }

    @Test
    public void hostNativeIsNeverSelectedThisRound() throws IOException {
        assertNotEquals(CompileBackend.HOST_NATIVE, decide(facts, requiredTriple,
                requiredVersion).backend());
    }

    @Test
    public void concreteDecisionJsonExamples() {
        assertEquals(
                "{\"mode\":\"QEMU_PLUS_ACCEL\",\"reasonCode\":\"ACCEL_AVAILABLE\","
                        + "\"backend\":\"CUSTOM_VM\",\"degradedCapabilities\":[],"
                        + "\"rejections\":[]}",
                AcceleratorDecision.available().toJson());
        assertEquals(
                "{\"mode\":\"QEMU_ONLY\",\"reasonCode\":\"ACCEL_IMAGE_MISSING\","
                        + "\"backend\":\"NONE\",\"degradedCapabilities\":[\"accelerator\",\"distcc\"],"
                        + "\"rejections\":[]}",
                AcceleratorDecision.qemuOnly(
                        AcceleratorReasonCode.ACCEL_IMAGE_MISSING).toJson());
        assertEquals(
                "{\"mode\":\"QEMU_PLUS_ACCEL\",\"reasonCode\":\"ACCEL_AVAILABLE\","
                        + "\"backend\":\"PVM_PAYLOAD\",\"degradedCapabilities\":[],"
                        + "\"rejections\":[]}",
                AcceleratorDecision.available(CompileBackend.PVM_PAYLOAD).toJson());
        assertEquals(
                "{\"mode\":\"QEMU_ONLY\",\"reasonCode\":\"PAYLOAD_IMAGE_MISSING\","
                        + "\"backend\":\"PVM_PAYLOAD\",\"degradedCapabilities\":[\"accelerator\",\"distcc\"],"
                        + "\"rejections\":[{\"backend\":\"PVM_PAYLOAD\","
                        + "\"reasonCode\":\"PAYLOAD_IMAGE_MISSING\"}]}",
                AcceleratorDecision.qemuOnly(
                        CompileBackend.PVM_PAYLOAD,
                        AcceleratorReasonCode.PAYLOAD_IMAGE_MISSING,
                        List.of(new BackendDecision(
                                CompileBackend.PVM_PAYLOAD,
                                AcceleratorReasonCode.PAYLOAD_IMAGE_MISSING))).toJson());
    }

    @Test
    public void rejectionsStayCompleteAndOrdered() {
        // The payload backend is not a candidate; the custom VM image is missing.
        // Both rejections survive: the verdict never drops earlier information.
        AcceleratorDecision imageMissing = AcceleratorPolicy.decide(
                ready().acceleratorImagePresent(false).build(), HOST_TRIPLE);
        assertEquals(
                List.of(
                        new BackendDecision(
                                CompileBackend.PVM_PAYLOAD,
                                AcceleratorReasonCode.PAYLOAD_UNAVAILABLE_NO_PROTECTED),
                        new BackendDecision(
                                CompileBackend.CUSTOM_VM,
                                AcceleratorReasonCode.ACCEL_IMAGE_MISSING)),
                imageMissing.rejections());
        assertEquals(CompileBackend.CUSTOM_VM, imageMissing.backend());

        // A protected-only device with a missing payload image: the payload
        // rejection is the evaluated one and wins, the custom VM verdict follows.
        AcceleratorDecision payloadMissing = AcceleratorPolicy.decide(
                protectedOnly().payloadCompilerPresent(false).build(), HOST_TRIPLE);
        assertEquals(
                List.of(
                        new BackendDecision(
                                CompileBackend.PVM_PAYLOAD,
                                AcceleratorReasonCode.PAYLOAD_IMAGE_MISSING),
                        new BackendDecision(
                                CompileBackend.CUSTOM_VM,
                                AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_NON_PROTECTED)),
                payloadMissing.rejections());
        assertEquals(AcceleratorReasonCode.PAYLOAD_IMAGE_MISSING, payloadMissing.reasonCode());
        assertEquals(CompileBackend.PVM_PAYLOAD, payloadMissing.backend());
    }

    @Test
    public void noBackendCandidateEndsWithoutABackend() {
        AcceleratorDecision decision = AcceleratorPolicy.decide(
                AcceleratorFacts.unknown(), HOST_TRIPLE);

        assertEquals(AcceleratorMode.QEMU_ONLY, decision.mode());
        assertEquals(AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_BACKEND, decision.reasonCode());
        assertEquals(CompileBackend.NONE, decision.backend());
        assertEquals(
                List.of(
                        new BackendDecision(
                                CompileBackend.PVM_PAYLOAD,
                                AcceleratorReasonCode.PAYLOAD_UNAVAILABLE_NO_PROTECTED),
                        new BackendDecision(
                                CompileBackend.CUSTOM_VM,
                                AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_NON_PROTECTED)),
                decision.rejections());
    }

    @Test
    public void payloadVersionIsOnlyComparedWhenItIsRequired() {
        AcceleratorFacts facts = protectedOnly().payloadCompilerPresent(true)
                .payloadCompilerVersion(OTHER_VERSION)
                .payloadTargetTriple(HOST_TRIPLE)
                .build();

        // The 2-arg contract predates payload compiler versioning: without a
        // required version there is nothing to compare against, so the presence
        // of a non-blank version is enough.
        assertEquals(CompileBackend.PVM_PAYLOAD,
                AcceleratorPolicy.decide(facts, HOST_TRIPLE).backend());
        // With a required version the identity must match exactly.
        assertEquals(AcceleratorReasonCode.PAYLOAD_COMPILER_MISMATCH,
                AcceleratorPolicy.decide(facts, HOST_TRIPLE, PAYLOAD_VERSION).reasonCode());
        assertEquals(CompileBackend.PVM_PAYLOAD,
                AcceleratorPolicy.decide(facts, HOST_TRIPLE, OTHER_VERSION).backend());
    }

    @Test
    public void factsPrecedenceIsFixed() {
        AcceleratorFacts noAvf = ready().avfPresent(false)
                .acceleratorImagePresent(false)
                .thermalState(ThermalState.CRITICAL)
                .build();
        assertEquals(AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_BACKEND,
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

        // Inside the payload backend, identity precedes the thermal budget.
        AcceleratorFacts payloadMismatch = protectedOnly().payloadCompilerPresent(true)
                .payloadCompilerVersion(PAYLOAD_VERSION)
                .payloadTargetTriple("aarch64-linux-gnu")
                .thermalState(ThermalState.CRITICAL)
                .build();
        assertEquals(AcceleratorReasonCode.PAYLOAD_COMPILER_MISMATCH,
                AcceleratorPolicy.decide(payloadMismatch, HOST_TRIPLE).reasonCode());
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
        try {
            decision.rejections().add(new BackendDecision(
                    CompileBackend.HOST_NATIVE, AcceleratorReasonCode.JOB_TIMEOUT));
            throw new AssertionError("expected immutable rejections");
        } catch (UnsupportedOperationException expected) {
            // Expected: the decision owns an immutable copy.
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
        try {
            new AcceleratorDecision(
                    AcceleratorMode.QEMU_PLUS_ACCEL,
                    AcceleratorReasonCode.ACCEL_AVAILABLE,
                    List.of(),
                    CompileBackend.NONE,
                    List.of());
            throw new AssertionError("expected backend rejection");
        } catch (IllegalArgumentException expected) {
            // Expected: an armed decision must name the backend that was chosen.
        }
        // The legacy 3-arg constructor keeps working and infers the pre-PVM
        // default backend: the non-protected custom VM.
        assertEquals(CompileBackend.CUSTOM_VM, new AcceleratorDecision(
                AcceleratorMode.QEMU_PLUS_ACCEL,
                AcceleratorReasonCode.ACCEL_AVAILABLE,
                List.of()).backend());
    }

    private static AcceleratorDecision decide(
            AcceleratorFacts facts, String requiredTriple, String requiredVersion) {
        if (requiredVersion == null) {
            return AcceleratorPolicy.decide(facts, requiredTriple);
        }
        return AcceleratorPolicy.decide(facts, requiredTriple, requiredVersion);
    }

    private static void add(
            List<Object[]> cases,
            String name,
            AcceleratorFacts facts,
            String requiredTriple,
            String requiredVersion,
            AcceleratorMode mode,
            AcceleratorReasonCode reason,
            CompileBackend backend) {
        cases.add(new Object[] {name, facts, requiredTriple, requiredVersion, mode, reason,
                backend});
    }

    /** Non-protected facts: the pre-PVM v1 shape of a usable accelerator. */
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

    /** Protected-only facts: today's device class, the PVM_PAYLOAD home ground. */
    private static AcceleratorFacts.Builder protectedOnly() {
        return AcceleratorFacts.builder()
                .avfPresent(true)
                .nonProtectedSupported(false)
                .protectedOnly(true)
                .protectedVmSupported(true)
                .acceleratorImagePresent(false)
                .vsockSupported(false)
                .compilerTriple("")
                .thermalState(ThermalState.NOMINAL)
                .batteryState(BatteryState.OK);
    }

    private static String goldenJson() throws IOException {
        try (InputStream input = AcceleratorPolicyTest.class.getResourceAsStream(
                "/accelerator/truth-table-v2.json")) {
            if (input == null) {
                throw new IOException("golden JSON is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
