package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroCapabilityCatalog.KernelProfile;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlKernelPayloadContract.FailureCode;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Dual-profile payload selection and verification tests (W01-02):
 * covers requesting FULL_SMP_ARM64 with fast-only payloads, digest mismatch,
 * missing stub, stub config mismatch, manifest mismatch, and ensuring
 * FAST_UP_ARM64 remains unaffected when full-profile artifacts are absent or present.
 */
public final class UmlKernelAssetsFullProfileTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File libDir;

    @Before
    public void setUp() {
        libDir = new File(tmp.getRoot(), "native-lib");
        assertTrue(libDir.mkdirs());
    }

    private File placeBinary(String name, String content) throws IOException {
        File file = new File(libDir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(file.setExecutable(true));
        return file;
    }

    @Test
    public void requestingFullProfileWithOnlyFastPayloadFailsWithProfileMismatch() throws IOException {
        File fastKernel = placeBinary("libuml_kernel.so", "fast-kernel");
        File fastStub = placeBinary("libuml_stub.so", "fast-stub");
        Map<String, String> fastPins = Map.of(
                "libuml_kernel.so", UmlKernelAssets.sha256Hex(fastKernel),
                "libuml_stub.so", UmlKernelAssets.sha256Hex(fastStub));
        Map<String, String> expectedFullPins = Map.of(
                "libuml_kernel_full.so", "a".repeat(64),
                "libuml_stub_full.so", "b".repeat(64));

        UmlKernelAssets.ProfileVerificationResult result = UmlKernelAssets.verifyProfilePayload(
                KernelProfile.FULL_SMP_ARM64, libDir, fastPins, expectedFullPins);

        assertFalse(result.ok());
        assertEquals(FailureCode.PROFILE_MISMATCH, result.failureCode());
        assertNull(result.kernelPath());
        assertNull(result.stubPath());
    }

    @Test
    public void requestingFullProfileWithNothingPlacedFailsWithMissingPayload() {
        Map<String, String> expectedFullPins = Map.of(
                "libuml_kernel_full.so", "a".repeat(64),
                "libuml_stub_full.so", "b".repeat(64));

        UmlKernelAssets.ProfileVerificationResult result = UmlKernelAssets.verifyProfilePayload(
                KernelProfile.FULL_SMP_ARM64, libDir, Map.of(), expectedFullPins);

        assertFalse(result.ok());
        assertEquals(FailureCode.MISSING_PAYLOAD, result.failureCode());
    }

    @Test
    public void missingFullStubFailsWithMissingStub() throws IOException {
        File fullKernel = placeBinary("libuml_kernel_full.so", "full-kernel");
        String kSha = UmlKernelAssets.sha256Hex(fullKernel);
        Map<String, String> pins = Map.of(
                "libuml_kernel_full.so", kSha,
                "libuml_stub_full.so", "b".repeat(64));

        UmlKernelAssets.ProfileVerificationResult result = UmlKernelAssets.verifyProfilePayload(
                KernelProfile.FULL_SMP_ARM64, libDir, pins, pins);

        assertFalse(result.ok());
        assertEquals(FailureCode.MISSING_STUB, result.failureCode());
    }

    @Test
    public void mismatchedFullStubFailsWithStubConfigMismatch() throws IOException {
        File fullKernel = placeBinary("libuml_kernel_full.so", "full-kernel");
        placeBinary("libuml_stub_full.so", "up-stub-instead-of-full");
        String kSha = UmlKernelAssets.sha256Hex(fullKernel);
        Map<String, String> pins = Map.of(
                "libuml_kernel_full.so", kSha,
                "libuml_stub_full.so", "b".repeat(64));

        UmlKernelAssets.ProfileVerificationResult result = UmlKernelAssets.verifyProfilePayload(
                KernelProfile.FULL_SMP_ARM64, libDir, pins, pins);

        assertFalse(result.ok());
        assertEquals(FailureCode.STUB_CONFIG_MISMATCH, result.failureCode());
    }

    @Test
    public void mismatchedFullKernelDigestFailsWithSha256Mismatch() throws IOException {
        placeBinary("libuml_kernel_full.so", "tampered-full-kernel");
        File fullStub = placeBinary("libuml_stub_full.so", "full-stub");
        String sSha = UmlKernelAssets.sha256Hex(fullStub);
        Map<String, String> pins = Map.of(
                "libuml_kernel_full.so", "a".repeat(64),
                "libuml_stub_full.so", sSha);

        UmlKernelAssets.ProfileVerificationResult result = UmlKernelAssets.verifyProfilePayload(
                KernelProfile.FULL_SMP_ARM64, libDir, pins, pins);

        assertFalse(result.ok());
        assertEquals(FailureCode.SHA256_MISMATCH, result.failureCode());
    }

    @Test
    public void manifestMismatchWithExpectedPinsFailsWithManifestApkMismatch() throws IOException {
        File fullKernel = placeBinary("libuml_kernel_full.so", "full-kernel");
        File fullStub = placeBinary("libuml_stub_full.so", "full-stub");
        String kSha = UmlKernelAssets.sha256Hex(fullKernel);
        String sSha = UmlKernelAssets.sha256Hex(fullStub);
        Map<String, String> manifest = Map.of(
                "libuml_kernel_full.so", "c".repeat(64),
                "libuml_stub_full.so", sSha);
        Map<String, String> expected = Map.of(
                "libuml_kernel_full.so", kSha,
                "libuml_stub_full.so", sSha);

        UmlKernelAssets.ProfileVerificationResult result = UmlKernelAssets.verifyProfilePayload(
                KernelProfile.FULL_SMP_ARM64, libDir, manifest, expected);

        assertFalse(result.ok());
        assertEquals(FailureCode.MANIFEST_APK_MISMATCH, result.failureCode());
    }

    @Test
    public void omittedExpectedPinFailsClosedWithManifestApkMismatch() throws IOException {
        File fullKernel = placeBinary("libuml_kernel_full.so", "full-kernel");
        File fullStub = placeBinary("libuml_stub_full.so", "full-stub");
        Map<String, String> manifest = Map.of(
                "libuml_kernel_full.so", UmlKernelAssets.sha256Hex(fullKernel),
                "libuml_stub_full.so", UmlKernelAssets.sha256Hex(fullStub));

        UmlKernelAssets.ProfileVerificationResult result = UmlKernelAssets.verifyProfilePayload(
                KernelProfile.FULL_SMP_ARM64, libDir, manifest, Map.of());

        assertFalse(result.ok());
        assertEquals(FailureCode.MANIFEST_APK_MISMATCH, result.failureCode());
    }

    @Test
    public void fastCandidateRemainsIsolatedAndIsNotOverwrittenByFullProfile() throws IOException {
        File fastKernel = placeBinary("libuml_kernel.so", "fast-kernel");
        File fastStub = placeBinary("libuml_stub.so", "fast-stub");
        File fullKernel = placeBinary("libuml_kernel_full.so", "full-kernel");
        File fullStub = placeBinary("libuml_stub_full.so", "full-stub");

        Map<String, String> fastPins = Map.of(
                "libuml_kernel.so", UmlKernelAssets.sha256Hex(fastKernel),
                "libuml_stub.so", UmlKernelAssets.sha256Hex(fastStub));
        Map<String, String> fullPins = Map.of(
                "libuml_kernel_full.so", UmlKernelAssets.sha256Hex(fullKernel),
                "libuml_stub_full.so", UmlKernelAssets.sha256Hex(fullStub));

        UmlKernelAssets.ProfileVerificationResult fastResult = UmlKernelAssets.verifyProfilePayload(
                KernelProfile.FAST_UP_ARM64, libDir, fastPins, fastPins);
        assertTrue(fastResult.ok());
        assertEquals(fastKernel.getAbsolutePath(), fastResult.kernelPath());
        assertEquals(fastStub.getAbsolutePath(), fastResult.stubPath());

        UmlKernelAssets.ProfileVerificationResult fullResult = UmlKernelAssets.verifyProfilePayload(
                KernelProfile.FULL_SMP_ARM64, libDir, fullPins, fullPins);
        assertTrue(fullResult.ok());
        assertEquals(fullKernel.getAbsolutePath(), fullResult.kernelPath());
        assertEquals(fullStub.getAbsolutePath(), fullResult.stubPath());
    }

    @Test
    public void fullProfilePinManifestUsesDistinctKeysFromFastProfile() {
        Map<String, String> fastPins = UmlKernelAssets.FastProfile.pins();
        Map<String, String> fullPins = UmlKernelAssets.FullProfile.pins();
        for (String key : fullPins.keySet()) {
            assertFalse("FullProfile pin key must not collide with FastProfile: " + key,
                    fastPins.containsKey(key));
        }
        assertTrue(fullPins.containsKey(UmlKernelAssets.FullProfile.KERNEL));
        assertTrue(fullPins.containsKey(UmlKernelAssets.FullProfile.STUB));
        assertTrue(fullPins.containsKey(UmlKernelAssets.FullProfile.INITRAMFS));
    }
}
