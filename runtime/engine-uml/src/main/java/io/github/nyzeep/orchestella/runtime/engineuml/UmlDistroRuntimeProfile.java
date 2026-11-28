package io.github.nyzeep.orchestella.runtime.engineuml;

import java.util.Locale;
import java.util.Objects;

/**
 * Converged immutable runtime profile for one (distroId, KernelProfile) pair.
 *
 * <p>Centralizes archive format, native init path, provisioning resources, and QA channel
 * admission so launcher, preflight, provisioner, and capability projections share one source
 * of truth when onboarding additional distributions.
 */
public record UmlDistroRuntimeProfile(
        String distroId,
        String version,
        String architecture,
        UmlKernelPayloadContract.ProfileDescriptor profileDescriptor,
        UmlDistroCapabilityCatalog.DistroGateVerdict verdict,
        ArchiveFormat archiveFormat,
        String nativeInitPath,
        boolean usesNativeSystemdInit,
        boolean requiresGuestAgent,
        String defaultQaHostname,
        int provisionMemMb,
        long provisionTimeoutMs,
        boolean requiresIsolatedQaChannel) {

    /** Supported rootfs seed archive families per distribution. */
    public enum ArchiveFormat {
        TAR_GZ,
        TAR_XZ,
        TAR;

        public boolean matchesFileName(String fileName) {
            if (fileName == null || fileName.isBlank()) {
                return false;
            }
            String lower = fileName.trim().toLowerCase(Locale.ROOT);
            return switch (this) {
                case TAR_XZ -> lower.endsWith(".tar.xz")
                        || lower.endsWith(".txz")
                        || lower.endsWith(".tar.gz")
                        || lower.endsWith(".tgz")
                        || lower.endsWith(".tar");
                case TAR_GZ -> lower.endsWith(".tar.gz")
                        || lower.endsWith(".tgz")
                        || lower.endsWith(".tar");
                case TAR -> lower.endsWith(".tar");
            };
        }
    }

    public UmlDistroRuntimeProfile {
        Objects.requireNonNull(distroId, "distroId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(architecture, "architecture");
        Objects.requireNonNull(profileDescriptor, "profileDescriptor");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(archiveFormat, "archiveFormat");
        Objects.requireNonNull(defaultQaHostname, "defaultQaHostname");
        if (provisionMemMb < UmlLaunchSpec.MIN_MEMORY_MB) {
            throw new IllegalArgumentException("provisionMemMb must be >= " + UmlLaunchSpec.MIN_MEMORY_MB);
        }
        if (provisionTimeoutMs <= 0L) {
            throw new IllegalArgumentException("provisionTimeoutMs must be positive");
        }
    }

    public String markerInitPath() {
        return nativeInitPath != null ? nativeInitPath : "/um-command.sh";
    }
}
