package io.github.nyzeep.orchestella.runtime.engineuml;

import java.util.Locale;
import java.util.Objects;

/**
 * Pure-JVM profile and failure-code contract separating {@code FAST_UP_ARM64} from
 * {@code FULL_SMP_ARM64} UML kernel payloads (W01-02).
 */
public final class UmlKernelPayloadContract {

    public enum FailureCode {
        MISSING_PAYLOAD,
        PROFILE_MISMATCH,
        SHA256_MISMATCH,
        MISSING_STUB,
        STUB_CONFIG_MISMATCH,
        MANIFEST_APK_MISMATCH,
        ELF_16KB_UNALIGNED,
        NON_REPRODUCIBLE
    }

    public record ProfileDescriptor(
            UmlDistroCapabilityCatalog.KernelProfile profile,
            String profileId,
            String kernelLibName,
            String stubLibName,
            String manifestAssetName,
            boolean smpCompiled,
            int defaultLaunchVcpus) {
        public ProfileDescriptor {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(kernelLibName, "kernelLibName");
            Objects.requireNonNull(stubLibName, "stubLibName");
            Objects.requireNonNull(manifestAssetName, "manifestAssetName");
            if (defaultLaunchVcpus < 1) {
                throw new IllegalArgumentException("defaultLaunchVcpus must be >= 1");
            }
        }
    }

    private static final ProfileDescriptor FAST_UP_DESCRIPTOR = new ProfileDescriptor(
            UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64,
            "fast-up-arm64",
            "libuml_kernel.so",
            "libuml_stub.so",
            "uml-fast-profile-v1.sha256",
            false,
            1);

    private static final ProfileDescriptor FULL_SMP_DESCRIPTOR = new ProfileDescriptor(
            UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64,
            "full-smp-arm64",
            "libuml_kernel_full.so",
            "libuml_stub_full.so",
            "uml-full-profile-v1.sha256",
            true,
            1);

    private UmlKernelPayloadContract() {}

    public static ProfileDescriptor descriptorFor(
            UmlDistroCapabilityCatalog.KernelProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return switch (profile) {
            case FAST_UP_ARM64 -> FAST_UP_DESCRIPTOR;
            case FULL_SMP_ARM64 -> FULL_SMP_DESCRIPTOR;
        };
    }

    public static UmlDistroCapabilityCatalog.KernelProfile fromProfileId(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        String normalized = profileId.trim().toLowerCase(Locale.ROOT);
        if (FAST_UP_DESCRIPTOR.profileId().equals(normalized)) {
            return UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64;
        }
        if (FULL_SMP_DESCRIPTOR.profileId().equals(normalized)) {
            return UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64;
        }
        throw new IllegalArgumentException("unknown UML kernel profileId: " + profileId);
    }
}
