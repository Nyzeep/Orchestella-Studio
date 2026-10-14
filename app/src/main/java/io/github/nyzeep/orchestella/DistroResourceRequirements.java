package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;

/**
 * Pure, conservative guard for the peak RAM used while assembling the first boot initramfs.
 *
 * <p>Budgets two copies of the pinned rootfs input plus 512 MiB for the guest kernel and assembly
 * work, rounded up to 256 MiB. This is not a distribution's runtime requirement or a guarantee
 * that package installation, updates, or later workloads will fit in the allocated RAM.
 */
public final class DistroResourceRequirements {
    private static final long MIB = 1024L * 1024L;
    private static final long KERNEL_RESERVE_BYTES = 512L * MIB;
    private static final long RAM_STEP_BYTES = 256L * MIB;
    private static final int RAM_STEP_MB = 256;
    private static final int MINIMUM_RAM_MB = 512;
    private static final int RECOMMENDED_STEP_MB = 1024;

    private DistroResourceRequirements() {}

    /** Rejects unknown/non-positive sizes and arithmetic results that cannot be represented in MB. */
    public static int minimumBootRamMb(long manifestSizeBytes) {
        if (manifestSizeBytes <= 0
                || manifestSizeBytes > (Long.MAX_VALUE - KERNEL_RESERVE_BYTES) / 2L) {
            throw invalidSize();
        }
        long peakBytes = manifestSizeBytes * 2L + KERNEL_RESERVE_BYTES;
        long steps = peakBytes / RAM_STEP_BYTES + (peakBytes % RAM_STEP_BYTES == 0 ? 0 : 1);
        if (steps > Integer.MAX_VALUE / RAM_STEP_MB) {
            throw invalidSize();
        }
        return Math.max(MINIMUM_RAM_MB, (int) (steps * RAM_STEP_MB));
    }

    /** A convenient whole-GiB suggestion, separate from the enforced assembly minimum. */
    public static int recommendedBootRamMb(long manifestSizeBytes) {
        int minimum = minimumBootRamMb(manifestSizeBytes);
        long recommended = ((minimum + (long) RECOMMENDED_STEP_MB - 1L)
                / RECOMMENDED_STEP_MB) * RECOMMENDED_STEP_MB;
        if (recommended > Integer.MAX_VALUE) {
            throw invalidSize();
        }
        return (int) recommended;
    }

    /**
     * Preserves sufficient explicit RAM; otherwise suggests the recommendation within the host
     * budget. If even the minimum cannot fit, returns the minimum so the host safety gate blocks it.
     */
    public static int suggestedWizardRamMb(
            long manifestSizeBytes, int configuredRamMb, int safeMaximumRamMb) {
        int minimum = minimumBootRamMb(manifestSizeBytes);
        if (configuredRamMb >= minimum) {
            return configuredRamMb;
        }
        return Math.max(minimum,
                Math.min(recommendedBootRamMb(manifestSizeBytes), safeMaximumRamMb));
    }

    public static void requireBootRam(long manifestSizeBytes, int ramMb) {
        int minimum = minimumBootRamMb(manifestSizeBytes);
        if (ramMb < minimum) {
            throw new IllegalArgumentException("该镜像首次装配至少需要 " + minimum
                    + " MB，当前 " + ramMb + " MB；请先调整硬件后启动。");
        }
    }

    /** Resolves only the requested pinned manifest; unknown identities never use a fallback. */
    public static void requireBootRam(String distroId, int ramMb) {
        DistroManifest manifest = DistroCatalogRegistry.getInstance().get(distroId);
        if (manifest == null) {
            throw new IllegalArgumentException("无法确认该发行版的镜像清单及首次装配内存需求；请检查镜像后再启动。");
        }
        requireBootRam(manifest.sizeBytes(), ramMb);
    }

    private static IllegalArgumentException invalidSize() {
        return new IllegalArgumentException("镜像大小无效或超出可计算范围，无法确认首次装配内存需求；请检查镜像清单后重试。");
    }
}
