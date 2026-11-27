package io.github.nyzeep.orchestella.runtime.engineuml;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Single source of truth for UML kernel-profile distro creatability gates (W3-02 / R-02).
 *
 * <p>Every verdict binds a pinned distro tarball digest/size and explicit kernel capability
 * gaps observed against {@code Raymer8639/linux-um-arm64 um-arm64-v0.3.0}
 * ({@code defconfig-fast} and {@code defconfig-full}).
 * Only distros passing every profile gate are marked {@link DistroProfileStatus#CREATABLE}.
 */
public final class UmlDistroCapabilityCatalog {
    public enum KernelProfile {
        FAST_UP_ARM64,
        FULL_SMP_ARM64
    }

    public enum DistroProfileStatus {
        CREATABLE,
        REFUSED
    }

    public enum KernelRequirementGap {
        MISSING_CGROUP_MEMCG,
        MISSING_CGROUP_PIDS,
        MISSING_USER_NS,
        MISSING_PID_NS,
        MISSING_AUTOFS_FS,
        MISSING_BPF_SYSCALL,
        MISSING_PSI,
        MISSING_SECURITY_LANDLOCK,
        MISSING_BUSYBOX_INIT_CONTRACT,
        UNVERIFIED_ON_PROFILE
    }

    public record DistroGateVerdict(
            String distroId,
            String version,
            KernelProfile profile,
            DistroProfileStatus status,
            String expectedFileName,
            String expectedSha256,
            long expectedSizeBytes,
            List<KernelRequirementGap> missingRequirements,
            String summary) {
        public DistroGateVerdict {
            Objects.requireNonNull(distroId, "distroId");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(status, "status");
            missingRequirements = List.copyOf(Objects.requireNonNull(missingRequirements, "missingRequirements"));
            summary = summary == null ? "" : summary;
        }

        public boolean isCreatable() {
            return status == DistroProfileStatus.CREATABLE;
        }
    }

    public static final class DistroNotCreatableException extends IOException {
        private static final long serialVersionUID = 1L;
        private final DistroGateVerdict verdict;

        public DistroNotCreatableException(DistroGateVerdict verdict) {
            super("distro '" + verdict.distroId() + "' refused on " + verdict.profile() + ": " + verdict.summary());
            this.verdict = Objects.requireNonNull(verdict, "verdict");
        }

        public DistroGateVerdict verdict() {
            return verdict;
        }
    }

    private static final Map<String, DistroGateVerdict> FAST_UP_VERDICTS = Map.of(
            "alpine",
            new DistroGateVerdict(
                    "alpine",
                    "3.22.2",
                    KernelProfile.FAST_UP_ARM64,
                    DistroProfileStatus.CREATABLE,
                    "alpine-minirootfs-3.22.2-aarch64.tar.gz",
                    "6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489",
                    3_981_043L,
                    List.of(),
                    "Alpine 3.22.2 minirootfs passes BusyBox init + ext4 ubd0 + apk offline verification on fast-profile."),
            "debian",
            new DistroGateVerdict(
                    "debian",
                    "13",
                    KernelProfile.FAST_UP_ARM64,
                    DistroProfileStatus.REFUSED,
                    "debian-13-rootfs.tar.xz",
                    "e4d504e13e43a384227639b74391ef164a67d916aa8f8a9871ecd73dc577db91",
                    90_346_224L,
                    List.of(
                            KernelRequirementGap.MISSING_CGROUP_MEMCG,
                            KernelRequirementGap.MISSING_CGROUP_PIDS,
                            KernelRequirementGap.MISSING_USER_NS,
                            KernelRequirementGap.MISSING_PID_NS,
                            KernelRequirementGap.MISSING_AUTOFS_FS,
                            KernelRequirementGap.MISSING_BPF_SYSCALL,
                            KernelRequirementGap.MISSING_PSI,
                            KernelRequirementGap.MISSING_BUSYBOX_INIT_CONTRACT),
                    "Debian 13 requires systemd (MEMCG/CGROUP_PIDS/USER_NS/PID_NS/AUTOFS_FS/BPF_SYSCALL/PSI) and lacks /bin/busybox for embedded init."),
            "arch",
            new DistroGateVerdict(
                    "arch",
                    "rolling",
                    KernelProfile.FAST_UP_ARM64,
                    DistroProfileStatus.REFUSED,
                    "ArchLinuxARM-aarch64-latest.tar.gz",
                    "42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319",
                    829_367_415L,
                    List.of(
                            KernelRequirementGap.MISSING_SECURITY_LANDLOCK,
                            KernelRequirementGap.MISSING_CGROUP_MEMCG,
                            KernelRequirementGap.MISSING_CGROUP_PIDS,
                            KernelRequirementGap.MISSING_USER_NS,
                            KernelRequirementGap.MISSING_PID_NS,
                            KernelRequirementGap.MISSING_BUSYBOX_INIT_CONTRACT),
                    "Arch Linux ARM requires CONFIG_SECURITY_LANDLOCK (pacman sandbox fails with ENOSYS on fast-profile), systemd cgroup/ns stack, and lacks /bin/busybox."));

    private static final Map<String, DistroGateVerdict> FULL_SMP_VERDICTS = Map.of(
            "alpine",
            new DistroGateVerdict(
                    "alpine",
                    "3.22.2",
                    KernelProfile.FULL_SMP_ARM64,
                    DistroProfileStatus.CREATABLE,
                    "alpine-minirootfs-3.22.2-aarch64.tar.gz",
                    "6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489",
                    3_981_043L,
                    List.of(),
                    "Alpine 3.22.2 minirootfs passes BusyBox init + ext4 ubd0 verification on full-profile."),
            "debian",
            new DistroGateVerdict(
                    "debian",
                    "13",
                    KernelProfile.FULL_SMP_ARM64,
                    DistroProfileStatus.CREATABLE,
                    "debian-13-rootfs.tar.xz",
                    "e4d504e13e43a384227639b74391ef164a67d916aa8f8a9871ecd73dc577db91",
                    90_346_224L,
                    List.of(),
                    "Debian 13 (trixie) rootfs .tar.xz with native systemd /sbin/init is verified creatable on full-profile."),
            "arch",
            new DistroGateVerdict(
                    "arch",
                    "rolling",
                    KernelProfile.FULL_SMP_ARM64,
                    DistroProfileStatus.REFUSED,
                    "ArchLinuxARM-aarch64-latest.tar.gz",
                    "42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319",
                    829_367_415L,
                    List.of(KernelRequirementGap.UNVERIFIED_ON_PROFILE),
                    "Arch Linux ARM kernel requirements are compiled in full-profile; rootfs provisioning and runtime verification remain unverified."));

    private UmlDistroCapabilityCatalog() {}

    public static DistroGateVerdict evaluate(String distroId, KernelProfile profile) {
        Objects.requireNonNull(profile, "profile");
        String normalized = distroId == null ? "" : distroId.trim().toLowerCase(Locale.ROOT);
        Map<String, DistroGateVerdict> verdicts =
                profile == KernelProfile.FULL_SMP_ARM64 ? FULL_SMP_VERDICTS : FAST_UP_VERDICTS;
        DistroGateVerdict known = verdicts.get(normalized);
        if (known != null) {
            return known;
        }
        return new DistroGateVerdict(
                normalized.isEmpty() ? "unknown" : normalized,
                "unknown",
                profile,
                DistroProfileStatus.REFUSED,
                null,
                null,
                -1L,
                List.of(KernelRequirementGap.UNVERIFIED_ON_PROFILE),
                "Distribution '" + normalized + "' has not passed UML " + profile + " verification gates.");
    }

    public static DistroGateVerdict requireCreatable(String distroId, KernelProfile profile)
            throws DistroNotCreatableException {
        DistroGateVerdict verdict = evaluate(distroId, profile);
        if (!verdict.isCreatable()) {
            throw new DistroNotCreatableException(verdict);
        }
        return verdict;
    }

    public static List<DistroGateVerdict> listKnownForProfile(KernelProfile profile) {
        Objects.requireNonNull(profile, "profile");
        Map<String, DistroGateVerdict> verdicts =
                profile == KernelProfile.FULL_SMP_ARM64 ? FULL_SMP_VERDICTS : FAST_UP_VERDICTS;
        return List.of(
                verdicts.get("alpine"),
                verdicts.get("debian"),
                verdicts.get("arch"));
    }
}
