package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;

public final class UmlKernelPayloadContractTest {

    @Test
    public void descriptorsSeparateFastAndFullProfilesWithSingleVcpuLaunchDefault() {
        UmlKernelPayloadContract.ProfileDescriptor fast =
                UmlKernelPayloadContract.descriptorFor(
                        UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64);
        assertEquals("fast-up-arm64", fast.profileId());
        assertEquals("libuml_kernel.so", fast.kernelLibName());
        assertEquals("libuml_stub.so", fast.stubLibName());
        assertEquals("uml-fast-profile-v1.sha256", fast.manifestAssetName());
        assertFalse(fast.smpCompiled());
        assertEquals(1, fast.defaultLaunchVcpus());

        UmlKernelPayloadContract.ProfileDescriptor full =
                UmlKernelPayloadContract.descriptorFor(
                        UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64);
        assertEquals("full-smp-arm64", full.profileId());
        assertEquals("libuml_kernel_full.so", full.kernelLibName());
        assertEquals("libuml_stub_full.so", full.stubLibName());
        assertEquals("uml-full-profile-v1.sha256", full.manifestAssetName());
        assertTrue(full.smpCompiled());
        assertEquals(1, full.defaultLaunchVcpus());
    }

    @Test
    public void profileLookupByIdAcceptsKnownProfilesAndRefusesUnknown() {
        assertEquals(
                UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64,
                UmlKernelPayloadContract.fromProfileId("fast-up-arm64"));
        assertEquals(
                UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64,
                UmlKernelPayloadContract.fromProfileId("FULL-SMP-ARM64"));
        assertThrows(
                IllegalArgumentException.class,
                () -> UmlKernelPayloadContract.fromProfileId("unknown-profile"));
    }

    @Test
    public void failureCodesCoverAllStableCategories() {
        assertEquals(
                Set.of(
                        UmlKernelPayloadContract.FailureCode.MISSING_PAYLOAD,
                        UmlKernelPayloadContract.FailureCode.PROFILE_MISMATCH,
                        UmlKernelPayloadContract.FailureCode.SHA256_MISMATCH,
                        UmlKernelPayloadContract.FailureCode.MISSING_STUB,
                        UmlKernelPayloadContract.FailureCode.STUB_CONFIG_MISMATCH,
                        UmlKernelPayloadContract.FailureCode.MANIFEST_APK_MISMATCH,
                        UmlKernelPayloadContract.FailureCode.ELF_16KB_UNALIGNED,
                        UmlKernelPayloadContract.FailureCode.NON_REPRODUCIBLE),
                Set.of(UmlKernelPayloadContract.FailureCode.values()));
    }
}
