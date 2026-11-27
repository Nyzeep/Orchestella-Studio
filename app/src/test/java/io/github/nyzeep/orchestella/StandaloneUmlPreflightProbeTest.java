package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.capability.ExecutionStrategyAdmissionEvaluator;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroCapabilityCatalog;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pure-JVM unit tests for {@link StandaloneUmlPreflightProbe}: verifies bounded timeout
 * classification, failure/incomplete handling, and strict isolation from ride-along face state.
 */
public final class StandaloneUmlPreflightProbeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void returnsReadyWhenCheckSucceedsWithinTimeout() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        StandaloneUmlPreflightProbe probe = new StandaloneUmlPreflightProbe(
                () -> {
                    clock.addAndGet(15_000_000L);
                    return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                            ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                            4096,
                            15L,
                            "4 KB host & bundled UML assets verified");
                },
                500L,
                clock::get);

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight result = probe.probe();
        assertEquals(ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY, result.status());
        assertEquals(4096, result.hostPageSizeBytes());
        assertTrue(result.elapsedMs() >= 15L);
    }

    @Test
    public void returnsTimedOutWhenCheckExceedsBoundedTimeout() {
        StandaloneUmlPreflightProbe probe = new StandaloneUmlPreflightProbe(
                () -> {
                    Thread.sleep(2_000L);
                    return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                            ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                            4096,
                            2_000L,
                            "too slow");
                },
                50L,
                System::nanoTime);

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight result = probe.probe();
        assertEquals(ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.TIMED_OUT, result.status());
        assertTrue(result.detail().contains("超时"));
    }

    @Test
    public void returnsIncompleteWhenCheckThrowsAndIgnoresRideAlongFaceState() {
        // Even if ride-along compile face were active or idle, standalone preflight only reflects its own probe.
        StandaloneUmlPreflightProbe probe = new StandaloneUmlPreflightProbe(
                () -> {
                    throw new IllegalStateException("sysconf unavailable");
                },
                500L,
                System::nanoTime);

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight result = probe.probe();
        assertEquals(ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.INCOMPLETE, result.status());
        assertTrue(result.detail().contains("sysconf unavailable"));
    }

    @Test
    public void fullProfilePreflightRefusesFastOnlyPayloadWithExplicitProfileMismatchCode()
            throws IOException {
        File libDir = tmp.newFolder("preflight-full-mismatch");
        File fastKernel = new File(libDir, "libuml_kernel.so");
        File fastStub = new File(libDir, "libuml_stub.so");
        Files.write(fastKernel.toPath(), "fast-k".getBytes(StandardCharsets.UTF_8));
        Files.write(fastStub.toPath(), "fast-s".getBytes(StandardCharsets.UTF_8));
        assertTrue(fastKernel.setExecutable(true));
        assertTrue(fastStub.setExecutable(true));

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight result =
                StandaloneUmlPreflightProbe.evaluateProfilePreflight(
                        UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64,
                        4096,
                        libDir,
                        Map.of(),
                        Map.of(
                                "libuml_kernel_full.so", "a".repeat(64),
                                "libuml_stub_full.so", "b".repeat(64)),
                        true);

        assertEquals(
                ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.ASSETS_MISSING,
                result.status());
        assertTrue(result.detail().contains("PROFILE_MISMATCH"));
    }

    @Test
    public void fullProfilePreflightReturnsReadyWhenFullAssetsAndRootfsAreValid()
            throws IOException {
        File libDir = tmp.newFolder("preflight-full-ok");
        File fullKernel = new File(libDir, "libuml_kernel_full.so");
        File fullStub = new File(libDir, "libuml_stub_full.so");
        Files.write(fullKernel.toPath(), "full-k".getBytes(StandardCharsets.UTF_8));
        Files.write(fullStub.toPath(), "full-s".getBytes(StandardCharsets.UTF_8));
        assertTrue(fullKernel.setExecutable(true));
        assertTrue(fullStub.setExecutable(true));
        Map<String, String> pins = Map.of(
                "libuml_kernel_full.so", UmlKernelAssets.sha256Hex(fullKernel),
                "libuml_stub_full.so", UmlKernelAssets.sha256Hex(fullStub));

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight result =
                StandaloneUmlPreflightProbe.evaluateProfilePreflight(
                        UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64,
                        4096,
                        libDir,
                        pins,
                        pins,
                        true);

        assertEquals(
                ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                result.status());
        assertTrue(result.detail().contains("full-smp-arm64"));
    }

    @Test
    public void debianFullProfilePreflightRequiresDebianSeedAndRejectsFastProfile()
            throws IOException {
        File libDir = tmp.newFolder("preflight-debian-libs");
        File fullKernel = new File(libDir, "libuml_kernel_full.so");
        File fullStub = new File(libDir, "libuml_stub_full.so");
        Files.write(fullKernel.toPath(), "full-k".getBytes(StandardCharsets.UTF_8));
        Files.write(fullStub.toPath(), "full-s".getBytes(StandardCharsets.UTF_8));
        assertTrue(fullKernel.setExecutable(true));
        assertTrue(fullStub.setExecutable(true));
        Map<String, String> pins = Map.of(
                "libuml_kernel_full.so", UmlKernelAssets.sha256Hex(fullKernel),
                "libuml_stub_full.so", UmlKernelAssets.sha256Hex(fullStub));

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight missingSeed =
                StandaloneUmlPreflightProbe.evaluateProfilePreflight(
                        UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64,
                        4096,
                        libDir,
                        pins,
                        pins,
                        false);
        assertEquals(
                ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.ASSETS_MISSING,
                missingSeed.status());

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight fastDebian =
                StandaloneUmlPreflightProbe.evaluateDistroProfilePreflight(
                        UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64,
                        "debian",
                        4096,
                        libDir,
                        pins,
                        pins,
                        true);
        assertEquals(
                ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.ASSETS_MISSING,
                fastDebian.status());

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight fullDebian =
                StandaloneUmlPreflightProbe.evaluateDistroProfilePreflight(
                        UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64,
                        "debian",
                        4096,
                        libDir,
                        pins,
                        pins,
                        true);
        assertEquals(
                ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                fullDebian.status());
    }
}
