package io.github.nyzeep.orchestella.runtime.engineuml;

import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Atomic per-instance ext4 {@code ubd0} root-volume provisioner for standalone UML (W3-02 / R-02).
 *
 * <p>Centralizes input validation (distro gate, path hygiene, tar size/digest, optional hostfs
 * seed attestation), host space preflight, same-directory {@code .part} sparse image allocation,
 * isolated guest {@code mkfs.ext4} + {@code tar -xzpf} assembly under {@link UmlRuntimeSupervisor},
 * post-exit superblock verification, and write-locked atomic publication. Never modifies an
 * existing volume until the replacement volume passes every verification check.
 */
public final class UmlInstanceRootfsProvisioner {
    public static final String REASON_DISTRO_REFUSED = "uml-provision-distro-refused";
    public static final String REASON_INVALID_PATH = "uml-provision-invalid-path";
    public static final String REASON_PARTIAL_INPUT = "uml-provision-partial-input";
    public static final String REASON_CHECKSUM_MISMATCH = "uml-provision-checksum-mismatch";
    public static final String REASON_SEED_UNVERIFIED = "uml-provision-seed-unverified";
    public static final String REASON_INSUFFICIENT_SPACE = "uml-provision-insufficient-space";
    public static final String REASON_VOLUME_FORMAT_CONFLICT = "uml-provision-volume-format-conflict";
    public static final String REASON_EXISTING_VOLUME_REQUIRES_EXPLICIT_MIGRATION =
            "uml-provision-existing-volume-requires-explicit-migration";
    public static final String REASON_STALE_GENERATION = "uml-provision-stale-generation";
    public static final String REASON_GUEST_FAILED = "uml-provision-guest-failed";
    public static final String REASON_GUEST_TIMEOUT = "uml-provision-guest-timeout";
    public static final String REASON_GUEST_STOP_UNCONFIRMED =
            "uml-provision-guest-stop-unconfirmed";
    public static final String REASON_EXT4_VERIFICATION_FAILED =
            "uml-provision-ext4-verification-failed";
    public static final String REASON_UNSUPPORTED_ARCHIVE_FORMAT =
            "uml-provision-unsupported-archive-format";

    public static final long DEFAULT_PROVISION_TIMEOUT_MS = 120_000L;
    public static final long DEFAULT_DEBIAN_PROVISION_TIMEOUT_MS = 600_000L;
    public static final long DEFAULT_STAGING_HEADROOM_BYTES = 16L * 1024L * 1024L;
    private static final int PROVISION_MARKER_VERSION = 1;
    private static final int MAX_GUEST_OUTPUT_BYTES = 256 * 1024;
    private static final Pattern SAFE_HOST_SEGMENT = Pattern.compile("[A-Za-z0-9._-]{1,80}");

    public enum ProvisionMode {
        CREATE_NEW,
        REPLACE_EXPLICIT
    }

    @FunctionalInterface
    public interface ToolchainStager {
        void stageInto(Path stagingRoot) throws IOException;
    }

    @FunctionalInterface
    public interface GenerationGuard {
        boolean isCurrent(String instanceId, String generation);
    }

    @FunctionalInterface
    public interface PublishedCallback {
        void onPublished(ProvisionReceipt receipt) throws IOException;
    }

    public record SeedInput(
            String distroId,
            String distroVersion,
            Path verifiedTarPath,
            Path verifiedHostfsSeedDir,
            Path verifiedSidecarPath,
            String expectedSha256,
            long expectedSizeBytes) {
        public SeedInput {
            Objects.requireNonNull(distroId, "distroId");
            Objects.requireNonNull(distroVersion, "distroVersion");
            Objects.requireNonNull(verifiedTarPath, "verifiedTarPath");
            Objects.requireNonNull(expectedSha256, "expectedSha256");
            if (expectedSizeBytes <= 0L) {
                throw new IllegalArgumentException("expectedSizeBytes must be positive");
            }
        }
    }

    public record ProvisionRequest(
            String instanceId,
            String generation,
            Path targetRootVolume,
            long capacityBytes,
            String hostname,
            SeedInput seedInput,
            ProvisionMode mode,
            String migrationReason,
            String kernelPath,
            String stubExePath,
            Path runtimeDir,
            Object instanceWriteLock,
            GenerationGuard generationGuard,
            PublishedCallback publishedCallback,
            long guestTimeoutMs,
            long stopGraceMs,
            long forceWaitMs,
            long channelsCloseTimeoutMs,
            UmlDistroCapabilityCatalog.KernelProfile kernelProfile) {
        public ProvisionRequest(
                String instanceId,
                String generation,
                Path targetRootVolume,
                long capacityBytes,
                String hostname,
                SeedInput seedInput,
                ProvisionMode mode,
                String migrationReason,
                String kernelPath,
                String stubExePath,
                Path runtimeDir,
                Object instanceWriteLock,
                GenerationGuard generationGuard,
                PublishedCallback publishedCallback,
                long guestTimeoutMs,
                long stopGraceMs,
                long forceWaitMs,
                long channelsCloseTimeoutMs) {
            this(
                    instanceId,
                    generation,
                    targetRootVolume,
                    capacityBytes,
                    hostname,
                    seedInput,
                    mode,
                    migrationReason,
                    kernelPath,
                    stubExePath,
                    runtimeDir,
                    instanceWriteLock,
                    generationGuard,
                    publishedCallback,
                    guestTimeoutMs,
                    stopGraceMs,
                    forceWaitMs,
                    channelsCloseTimeoutMs,
                    UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64);
        }

        public ProvisionRequest {
            if (instanceId == null || instanceId.isBlank()) {
                throw new IllegalArgumentException("instanceId must not be blank");
            }
            if (generation == null || generation.isBlank()) {
                throw new IllegalArgumentException("generation must not be blank");
            }
            Objects.requireNonNull(targetRootVolume, "targetRootVolume");
            Objects.requireNonNull(seedInput, "seedInput");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(kernelPath, "kernelPath");
            Objects.requireNonNull(stubExePath, "stubExePath");
            Objects.requireNonNull(runtimeDir, "runtimeDir");
            Objects.requireNonNull(instanceWriteLock, "instanceWriteLock");
            Objects.requireNonNull(generationGuard, "generationGuard");
            Objects.requireNonNull(kernelProfile, "kernelProfile");
            if (capacityBytes < StorageContract.MIN_ROOT_VOLUME_BYTES
                    || capacityBytes > StorageContract.MAX_ROOT_VOLUME_BYTES) {
                throw new IllegalArgumentException("capacityBytes out of bounds: " + capacityBytes);
            }
        }
    }

    public record ProvisionReceipt(
            Path rootVolume,
            String volumeId,
            String distroId,
            String distroVersion,
            String rootfsSha256,
            long capacityBytes,
            boolean replacedExisting,
            long provisionedAtMillis) {}

    public static final class ProvisionException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String reason;

        public ProvisionException(String reason, String detail) {
            super(detail == null || detail.isEmpty() ? reason : reason + ": " + detail);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public ProvisionException(String reason, String detail, Throwable cause) {
            super(detail == null || detail.isEmpty() ? reason : reason + ": " + detail, cause);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public String reason() {
            return reason;
        }
    }

    private final UmlProcessFactory processFactory;
    private final UmlRootReservationRegistry reservationRegistry;
    private final ToolchainStager toolchainStager;
    private final StorageContract.StorageSpaceChecker spaceChecker;
    private final Clock clock;

    public UmlInstanceRootfsProvisioner(
            UmlProcessFactory processFactory,
            UmlRootReservationRegistry reservationRegistry,
            ToolchainStager toolchainStager,
            StorageContract.StorageSpaceChecker spaceChecker,
            Clock clock) {
        this.processFactory = Objects.requireNonNull(processFactory, "processFactory");
        this.reservationRegistry =
                Objects.requireNonNull(reservationRegistry, "reservationRegistry");
        this.toolchainStager = Objects.requireNonNull(toolchainStager, "toolchainStager");
        this.spaceChecker = Objects.requireNonNull(spaceChecker, "spaceChecker");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Provisions a target root volume while acquiring and releasing an exclusive reservation. */
    public ProvisionReceipt provision(ProvisionRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        Path normalizedTarget = validateTargetPath(request.targetRootVolume());
        UmlRootReservationRegistry.Reservation reservation =
                reservationRegistry.acquire(
                        normalizedTarget, request.instanceId(), request.generation());
        boolean retainReservationOnUnconfirmedStop = false;
        try {
            return executeProvision(request, normalizedTarget, reservation);
        } catch (ProvisionException failure) {
            if (REASON_GUEST_STOP_UNCONFIRMED.equals(failure.reason())) {
                retainReservationOnUnconfirmedStop = true;
            }
            throw failure;
        } finally {
            if (!retainReservationOnUnconfirmedStop) {
                reservationRegistry.release(reservation);
            }
        }
    }

    /**
     * Provisions a target root volume under an already-held reservation owned by the caller
     * (e.g., {@code UmlInstanceLauncher} holding one reservation across provisioning and initial boot).
     */
    public ProvisionReceipt provisionWithHeldReservation(
            ProvisionRequest request, UmlRootReservationRegistry.Reservation heldReservation)
            throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(heldReservation, "heldReservation");
        Path normalizedTarget = validateTargetPath(request.targetRootVolume());
        if (!normalizedTarget.equals(heldReservation.normalizedPath())
                || heldReservation.isReleased()) {
            throw new IllegalArgumentException(
                    "heldReservation does not match active target volume: " + normalizedTarget);
        }
        return executeProvision(request, normalizedTarget, heldReservation);
    }

    private ProvisionReceipt executeProvision(
            ProvisionRequest request,
            Path normalizedTarget,
            UmlRootReservationRegistry.Reservation reservation)
            throws IOException {
        validateDistroAndSeed(request);
        validateCurrentGeneration(request);

        UmlVolumeInspector.VolumeInspection existing =
                validateTargetEligibility(normalizedTarget, request.mode(), request.migrationReason());

        Path targetDir = normalizedTarget.getParent();
        if (targetDir == null) {
            throw new ProvisionException(REASON_INVALID_PATH, "target volume must have a parent dir");
        }
        Files.createDirectories(targetDir);
        StorageContract.StoragePreflight.checkCreationSpace(
                spaceChecker,
                targetDir,
                Math.addExact(request.capacityBytes(), DEFAULT_STAGING_HEADROOM_BYTES));

        Path runtimeDir = request.runtimeDir().toAbsolutePath().normalize();
        Files.createDirectories(runtimeDir);
        Files.createDirectories(runtimeDir.resolve("tmp"));

        String safeGen = sanitizeSegment(request.generation());
        Path tempVolume =
                targetDir.resolve(normalizedTarget.getFileName() + ".provision-" + safeGen + ".part");
        Path stageDir = runtimeDir.resolve("uml-provision-stage-" + safeGen);
        String volumeId = UUID.randomUUID().toString();
        boolean keepTempForUnconfirmedStop = false;

        try {
            deleteRecursively(tempVolume);
            deleteRecursively(stageDir);

            createSparseFile(tempVolume, request.capacityBytes());
            prepareProvisionStageDir(request, stageDir, volumeId);

            runProvisionGuest(request, stageDir, tempVolume, volumeId, reservation);

            UmlVolumeInspector.VolumeInspection built = UmlVolumeInspector.inspect(tempVolume);
            if (!built.isExt4Ready()) {
                throw new ProvisionException(
                        REASON_EXT4_VERIFICATION_FAILED,
                        "assembled volume failed ext4 verification (" + built.state() + "): " + built.detail());
            }

            synchronized (request.instanceWriteLock()) {
                validateCurrentGeneration(request);
                UmlVolumeInspector.VolumeInspection currentTarget =
                        validateTargetEligibility(
                                normalizedTarget, request.mode(), request.migrationReason());
                boolean replacedExisting = currentTarget.isExt4Ready();

                Path backupVolume = null;
                boolean swapped = false;
                try {
                    if (Files.isRegularFile(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                        backupVolume = targetDir.resolve(
                                normalizedTarget.getFileName() + ".backup-" + safeGen + ".part");
                        deleteRecursively(backupVolume);
                        moveFileIntoPlace(normalizedTarget, backupVolume);
                    }
                    moveFileIntoPlace(tempVolume, normalizedTarget);
                    swapped = true;

                    ProvisionReceipt receipt = new ProvisionReceipt(
                            normalizedTarget,
                            volumeId,
                            request.seedInput().distroId().trim().toLowerCase(Locale.ROOT),
                            request.seedInput().distroVersion(),
                            request.seedInput().expectedSha256().toLowerCase(Locale.ROOT),
                            request.capacityBytes(),
                            replacedExisting || existing.isExt4Ready(),
                            clock.millis());
                    if (request.publishedCallback() != null) {
                        request.publishedCallback().onPublished(receipt);
                    }
                    deleteQuietly(backupVolume);
                    backupVolume = null;
                    return receipt;
                } catch (IOException | RuntimeException | Error publishFailure) {
                    if (swapped) {
                        try {
                            if (backupVolume != null
                                    && Files.isRegularFile(backupVolume, LinkOption.NOFOLLOW_LINKS)) {
                                moveFileIntoPlace(backupVolume, normalizedTarget);
                            } else {
                                Files.deleteIfExists(normalizedTarget);
                            }
                        } catch (IOException rollbackFailure) {
                            publishFailure.addSuppressed(rollbackFailure);
                        }
                    } else if (backupVolume != null
                            && Files.isRegularFile(backupVolume, LinkOption.NOFOLLOW_LINKS)
                            && !Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            moveFileIntoPlace(backupVolume, normalizedTarget);
                        } catch (IOException rollbackFailure) {
                            publishFailure.addSuppressed(rollbackFailure);
                        }
                    }
                    throw publishFailure;
                } finally {
                    if (backupVolume != null
                            && Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                        deleteQuietly(backupVolume);
                    }
                }
            }
        } catch (ProvisionException failure) {
            if (REASON_GUEST_STOP_UNCONFIRMED.equals(failure.reason())) {
                keepTempForUnconfirmedStop = true;
            }
            throw failure;
        } finally {
            if (!keepTempForUnconfirmedStop) {
                deleteQuietly(stageDir);
                deleteQuietly(tempVolume);
            }
        }
    }

    private static Path validateTargetPath(Path rawTarget) throws ProvisionException {
        if (rawTarget == null) {
            throw new ProvisionException(REASON_INVALID_PATH, "targetRootVolume is null");
        }
        String rawText = rawTarget.toString();
        if (rawText.isBlank() || rawText.indexOf('\0') >= 0) {
            throw new ProvisionException(REASON_INVALID_PATH, "targetRootVolume contains blank or NUL");
        }
        for (Path segment : rawTarget) {
            if ("..".equals(segment.toString())) {
                throw new ProvisionException(REASON_INVALID_PATH, "targetRootVolume contains '..'");
            }
        }
        Path normalized = rawTarget.toAbsolutePath().normalize();
        Path fileName = normalized.getFileName();
        if (fileName == null || fileName.toString().endsWith(".part")) {
            throw new ProvisionException(
                    REASON_INVALID_PATH, "targetRootVolume must not target a .part staging file");
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new ProvisionException(
                    REASON_INVALID_PATH, "targetRootVolume must not be a symbolic link");
        }
        if (normalized.getParent() != null && Files.isSymbolicLink(normalized.getParent())) {
            throw new ProvisionException(
                    REASON_INVALID_PATH, "targetRootVolume parent must not be a symbolic link");
        }
        return normalized;
    }

    private static UmlVolumeInspector.VolumeInspection validateTargetEligibility(
            Path target, ProvisionMode mode, String migrationReason) throws ProvisionException {
        UmlVolumeInspector.VolumeInspection inspection = UmlVolumeInspector.inspect(target);
        if (inspection.state() == UmlVolumeInspector.VolumeState.FORMAT_CONFLICT) {
            throw new ProvisionException(REASON_VOLUME_FORMAT_CONFLICT, inspection.detail());
        }
        if (mode == ProvisionMode.CREATE_NEW) {
            if (!inspection.isReadyToProvision()) {
                throw new ProvisionException(
                        REASON_EXISTING_VOLUME_REQUIRES_EXPLICIT_MIGRATION,
                        "existing volume (" + inspection.state() + ") requires explicit migration/replacement");
            }
        } else if (mode == ProvisionMode.REPLACE_EXPLICIT) {
            if (migrationReason == null || migrationReason.isBlank()) {
                throw new ProvisionException(
                        REASON_EXISTING_VOLUME_REQUIRES_EXPLICIT_MIGRATION,
                        "REPLACE_EXPLICIT requires a non-blank migrationReason");
            }
        }
        return inspection;
    }

    private static void validateDistroAndSeed(ProvisionRequest request) throws IOException {
        SeedInput seed = request.seedInput();
        try {
            UmlDistroCapabilityCatalog.requireCreatable(
                    seed.distroId(), request.kernelProfile());
        } catch (UmlDistroCapabilityCatalog.DistroNotCreatableException refused) {
            throw new ProvisionException(REASON_DISTRO_REFUSED, refused.getMessage(), refused);
        }

        Path tarPath = seed.verifiedTarPath();
        if (tarPath.toString().indexOf('\0') >= 0 || containsParentDotSegment(tarPath)) {
            throw new ProvisionException(REASON_INVALID_PATH, "invalid seed tarball path");
        }
        Path normalizedTar = tarPath.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalizedTar)) {
            throw new ProvisionException(REASON_INVALID_PATH, "seed tarball must not be a symlink");
        }
        String tarName = normalizedTar.getFileName() != null ? normalizedTar.getFileName().toString() : "";
        if (tarName.endsWith(".part")) {
            throw new ProvisionException(
                    REASON_PARTIAL_INPUT, "refusing unverified .part download file: " + tarName);
        }
        if (!isSupportedArchiveName(seed.distroId(), tarName)) {
            throw new ProvisionException(
                    REASON_UNSUPPORTED_ARCHIVE_FORMAT,
                    "unsupported rootfs archive format: " + tarName);
        }
        if (!Files.isRegularFile(normalizedTar, LinkOption.NOFOLLOW_LINKS)) {
            throw new ProvisionException(
                    REASON_PARTIAL_INPUT, "verified rootfs tarball missing: " + normalizedTar);
        }
        long actualSize = Files.size(normalizedTar);
        if (actualSize != seed.expectedSizeBytes()) {
            throw new ProvisionException(
                    REASON_PARTIAL_INPUT,
                    "rootfs size mismatch: expected=" + seed.expectedSizeBytes() + ", actual=" + actualSize);
        }
        String actualSha = sha256Hex(normalizedTar);
        if (!MessageDigest.isEqual(
                seed.expectedSha256().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                actualSha.getBytes(StandardCharsets.US_ASCII))) {
            throw new ProvisionException(
                    REASON_CHECKSUM_MISMATCH,
                    "rootfs digest mismatch: expected=" + seed.expectedSha256() + ", actual=" + actualSha);
        }

        if (seed.verifiedHostfsSeedDir() != null || seed.verifiedSidecarPath() != null) {
            if (seed.verifiedHostfsSeedDir() == null || seed.verifiedSidecarPath() == null) {
                throw new ProvisionException(
                        REASON_SEED_UNVERIFIED, "hostfs seed dir and sidecar must be paired");
            }
            Path seedDir = seed.verifiedHostfsSeedDir().toAbsolutePath().normalize();
            Path sidecar = seed.verifiedSidecarPath().toAbsolutePath().normalize();
            if (!Files.isDirectory(seedDir, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                throw new ProvisionException(
                        REASON_SEED_UNVERIFIED, "hostfs seed dir or sidecar missing");
            }
            if (!attestsPin(sidecar, seed.expectedSha256().toLowerCase(Locale.ROOT))) {
                throw new ProvisionException(
                        REASON_SEED_UNVERIFIED, "hostfs seed sidecar pin mismatch");
            }
        }
    }

    private static void validateCurrentGeneration(ProvisionRequest request)
            throws ProvisionException {
        if (!request.generationGuard().isCurrent(request.instanceId(), request.generation())) {
            throw new ProvisionException(
                    REASON_STALE_GENERATION,
                    "stale provisioning generation: " + request.generation());
        }
    }

    private void prepareProvisionStageDir(
            ProvisionRequest request, Path stageDir, String volumeId) throws IOException {
        Files.createDirectories(stageDir);
        applyModeBestEffort(stageDir, 0755);
        toolchainStager.stageInto(stageDir);
        String distroId = request.seedInput().distroId().trim().toLowerCase(Locale.ROOT);
        boolean isDebian = "debian".equals(distroId);
        List<String> requiredTools = isDebian
                ? List.of("sbin/mkfs.ext4", "etc/mke2fs.conf", "bin/busybox", "opt/droidlayer/guest-agent")
                : List.of("sbin/mkfs.ext4", "etc/mke2fs.conf", "bin/busybox");
        for (String required : requiredTools) {
            Path p = stageDir.resolve(required);
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                throw new ProvisionException(
                        REASON_SEED_UNVERIFIED, "missing staged provisioning tool: " + required);
            }
        }

        String hostname = sanitizeHostname(request.hostname());
        Path guestAssets = stageDir.resolve("guest-assets");
        Path etcOrch = guestAssets.resolve("etc/orchestella");
        Files.createDirectories(etcOrch);
        applyModeBestEffort(guestAssets, 0755);
        applyModeBestEffort(guestAssets.resolve("etc"), 0755);
        applyModeBestEffort(etcOrch, 0755);

        if (isDebian) {
            Path usrLibOrch = guestAssets.resolve("usr/lib/orchestella");
            Path profileD = guestAssets.resolve("etc/profile.d");
            Path systemdSystem = guestAssets.resolve("etc/systemd/system");
            Path systemdNetwork = guestAssets.resolve("etc/systemd/network");
            Files.createDirectories(usrLibOrch);
            Files.createDirectories(profileD);
            Files.createDirectories(systemdSystem);
            Files.createDirectories(systemdNetwork);
            for (Path dir : List.of(
                    guestAssets.resolve("usr"),
                    guestAssets.resolve("usr/lib"),
                    usrLibOrch,
                    profileD,
                    guestAssets.resolve("etc/systemd"),
                    systemdSystem,
                    systemdNetwork)) {
                applyModeBestEffort(dir, 0755);
            }
            writeUtf8(
                    etcOrch.resolve("forward-connection.sh"),
                    UmlPersistentGuestScripts.debianForwardConnectionScript(),
                    0755,
                    true);
            writeUtf8(
                    etcOrch.resolve("login-shell.sh"),
                    UmlPersistentGuestScripts.debianLoginShellScript(hostname),
                    0755,
                    true);
            writeUtf8(
                    usrLibOrch.resolve("setup-creds.sh"),
                    UmlPersistentGuestScripts.debianCredsSetupScript(),
                    0755,
                    true);
            writeUtf8(
                    usrLibOrch.resolve("guest-agent-launcher.sh"),
                    UmlPersistentGuestScripts.debianGuestAgentLauncherScript(),
                    0755,
                    true);
            writeUtf8(
                    usrLibOrch.resolve("uml-network.sh"),
                    UmlPersistentGuestScripts.debianUmlNetworkScript(),
                    0755,
                    true);
            writeUtf8(
                    profileD.resolve("20-orchestella-terminal-integration.sh"),
                    UmlPersistentGuestScripts.debianTerminalIntegrationScript(),
                    0644,
                    false);
            writeUtf8(
                    systemdNetwork.resolve("10-orchestella-vec0.network"),
                    UmlPersistentGuestScripts.debianVec0NetworkdConfig(),
                    0644,
                    false);
            writeUtf8(
                    systemdSystem.resolve("orchestella-uml-creds.service"),
                    UmlPersistentGuestScripts.debianCredsUnit(),
                    0644,
                    false);
            writeUtf8(
                    systemdSystem.resolve("orchestella-guest-agent.service"),
                    UmlPersistentGuestScripts.debianGuestAgentUnit(),
                    0644,
                    false);
            writeUtf8(
                    systemdSystem.resolve("orchestella-uml-network.service"),
                    UmlPersistentGuestScripts.debianUmlNetworkUnit(),
                    0644,
                    false);
            writeUtf8(
                    systemdSystem.resolve("orchestella-console.service"),
                    UmlPersistentGuestScripts.debianConsoleUnit(),
                    0644,
                    false);
            writeUtf8(
                    systemdSystem.resolve("orchestella-systemd-ready.service"),
                    UmlPersistentGuestScripts.debianSystemdReadyUnit(),
                    0644,
                    false);
        } else {
            writeUtf8(
                    guestAssets.resolve("um-command.sh"),
                    UmlPersistentGuestScripts.persistentUmCommandScript(),
                    0755,
                    true);
            writeUtf8(
                    etcOrch.resolve("bridge-agent.sh"),
                    UmlPersistentGuestScripts.persistentBridgeAgentScript(),
                    0755,
                    true);
            writeUtf8(
                    etcOrch.resolve("forward-connection.sh"),
                    UmlPersistentGuestScripts.forwardConnectionScript(),
                    0755,
                    true);
            writeUtf8(
                    etcOrch.resolve("login-shell.sh"),
                    UmlPersistentGuestScripts.persistentLoginShellScript(hostname),
                    0755,
                    true);
            writeUtf8(
                    etcOrch.resolve("ashrc"),
                    UmlPersistentGuestScripts.ashRcScript(),
                    0644,
                    false);
        }
        writeUtf8(
                etcOrch.resolve("provision-marker"),
                formatProvisionMarker(request, volumeId),
                0644,
                false);
        writeUtf8(
                guestAssets.resolve("etc/hostname"),
                hostname + "\n",
                0644,
                false);

        Path seedTar = request.seedInput().verifiedTarPath().toAbsolutePath().normalize();
        String script = buildProvisionGuestScript(
                request.instanceId(),
                distroId,
                request.seedInput().expectedSha256().toLowerCase(Locale.ROOT),
                volumeId,
                seedTar.getParent(),
                seedTar.getFileName().toString());
        writeUtf8(stageDir.resolve("um-command.sh"), script, 0755, true);
    }

    private void runProvisionGuest(
            ProvisionRequest request,
            Path stageDir,
            Path tempVolume,
            String volumeId,
            UmlRootReservationRegistry.Reservation reservation)
            throws IOException {
        int provisionMemMb = "debian".equalsIgnoreCase(request.seedInput().distroId().trim())
                ? 768
                : UmlLaunchSpec.DEFAULT_MEMORY_MB;
        UmlLaunchSpec spec = UmlLaunchSpec.builder()
                .kernelPath(request.kernelPath())
                .stubExePath(request.stubExePath())
                .memMb(provisionMemMb)
                .rootMode(UmlLaunchSpec.RootMode.HOSTFS)
                .ephemeralRootDir(stageDir)
                .ubd0Path(tempVolume)
                .runtimeDir(request.runtimeDir())
                .extraArgs(List.of("console=tty0"))
                .build();

        UmlProcessHandle handle = processFactory.spawn(spec, 80, 24);
        // Never release caller/provision reservation inside the supervisor: we still need it
        // across ext4 inspection and atomic move; cleanup of reservation is owned by caller/finally.
        UmlRuntimeSupervisor supervisor = new UmlRuntimeSupervisor(
                handle,
                () -> closeQuietly(handle),
                () -> {},
                null,
                null,
                request.forceWaitMs(),
                request.channelsCloseTimeoutMs(),
                null);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> drainStream(handle, captured), "uml-provision-console");
        drain.setDaemon(true);
        drain.start();

        boolean timedOut = false;
        try {
            long defaultTimeoutMs = "debian".equalsIgnoreCase(
                    request.seedInput().distroId().trim())
                    ? DEFAULT_DEBIAN_PROVISION_TIMEOUT_MS
                    : DEFAULT_PROVISION_TIMEOUT_MS;
            long timeoutMs = request.guestTimeoutMs() > 0L
                    ? request.guestTimeoutMs()
                    : defaultTimeoutMs;
            if (!handle.waitFor(timeoutMs, TimeUnit.MILLISECONDS) && handle.isAlive()) {
                timedOut = true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            UmlRuntimeSupervisor.StopEvidence evidence = supervisor.stop(request.stopGraceMs());
            if (evidence.result() == UmlRuntimeSupervisor.StopResult.UNCONFIRMED) {
                throw new ProvisionException(
                        REASON_GUEST_STOP_UNCONFIRMED,
                        "interrupted while stopping provision guest (pid=" + evidence.pid() + ")",
                        interrupted);
            }
            throw new ProvisionException(REASON_GUEST_FAILED, "provisioning interrupted", interrupted);
        }

        UmlRuntimeSupervisor.StopEvidence stopEvidence = supervisor.stop(request.stopGraceMs());
        try {
            drain.join(1_000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (stopEvidence.result() == UmlRuntimeSupervisor.StopResult.UNCONFIRMED) {
            throw new ProvisionException(
                    REASON_GUEST_STOP_UNCONFIRMED,
                    "provision guest failed to stop cleanly at stage=" + stopEvidence.stage()
                            + " pid=" + stopEvidence.pid());
        }
        String consoleLog = captured.toString(StandardCharsets.UTF_8);
        if (timedOut) {
            throw new ProvisionException(
                    REASON_GUEST_TIMEOUT,
                    "provision guest timed out before finishing ext4 assembly (tail="
                            + lastNonEmptyLine(consoleLog) + ")");
        }
        String expectedToken = expectedSuccessLine(
                request.instanceId(),
                request.seedInput().distroId().trim().toLowerCase(Locale.ROOT),
                request.seedInput().expectedSha256().toLowerCase(Locale.ROOT),
                volumeId);
        if (stopEvidence.exitCode() != 0 || !consoleLog.contains(expectedToken)) {
            throw new ProvisionException(
                    REASON_GUEST_FAILED,
                    "provision guest did not emit verified OK marker (exitCode="
                            + stopEvidence.exitCode() + "): " + lastNonEmptyLine(consoleLog));
        }
    }

    private static boolean isDebianDistro(String distroId) {
        return distroId != null && "debian".equals(distroId.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isSupportedArchiveName(String distroId, String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (isDebianDistro(distroId)) {
            return lower.endsWith(".tar.xz")
                    || lower.endsWith(".txz")
                    || lower.endsWith(".tar.gz")
                    || lower.endsWith(".tgz")
                    || lower.endsWith(".tar");
        }
        return lower.endsWith(".tar.gz")
                || lower.endsWith(".tgz")
                || lower.endsWith(".tar");
    }

    private static String buildProvisionGuestScript(
            String instanceId,
            String distroId,
            String rootfsSha256,
            String volumeId,
            Path tarParentDir,
            String tarFileName) {
        String okLine = expectedSuccessLine(instanceId, distroId, rootfsSha256, volumeId);
        String safeParent = shellQuote(tarParentDir.toAbsolutePath().toString());
        String safeSeedTar = shellQuote("/mnt/seed/" + tarFileName);
        if ("debian".equals(distroId)) {
            return buildDebianProvisionGuestScript(okLine, safeParent, safeSeedTar, tarFileName);
        }
        return buildAlpineProvisionGuestScript(okLine, safeParent, safeSeedTar);
    }

    private static String buildAlpineProvisionGuestScript(
            String okLine, String safeParent, String safeSeedTar) {
        return buildProvisionPreamble(safeParent, "umask=0000", false)
                + "\"$BB\" tar -xzpf " + safeSeedTar + " -C /mnt/target || fail_prov untar $?\n"
                + "\"$BB\" umount /mnt/seed || :\n"
                + "\"$BB\" mkdir -p /mnt/target/etc/orchestella /mnt/target/tmp /mnt/target/run /mnt/target/root /mnt/target/proc /mnt/target/sys /mnt/target/dev /mnt/target/dev/pts /mnt/target/sys/fs/cgroup\n"
                + "\"$BB\" chmod 1777 /mnt/target/tmp 2>/dev/null || :\n"
                + "\"$BB\" cp /guest-assets/um-command.sh /mnt/target/um-command.sh || fail_prov cp_cmd $?\n"
                + "\"$BB\" chmod 0755 /mnt/target/um-command.sh || fail_prov chmod_cmd $?\n"
                + "\"$BB\" cp /guest-assets/etc/orchestella/bridge-agent.sh /mnt/target/etc/orchestella/bridge-agent.sh || fail_prov cp_agent $?\n"
                + "\"$BB\" chmod 0755 /mnt/target/etc/orchestella/bridge-agent.sh || fail_prov chmod_agent $?\n"
                + "\"$BB\" cp /guest-assets/etc/orchestella/forward-connection.sh /mnt/target/etc/orchestella/forward-connection.sh || fail_prov cp_forward $?\n"
                + "\"$BB\" chmod 0755 /mnt/target/etc/orchestella/forward-connection.sh || fail_prov chmod_forward $?\n"
                + "\"$BB\" cp /guest-assets/etc/orchestella/login-shell.sh /mnt/target/etc/orchestella/login-shell.sh || fail_prov cp_login $?\n"
                + "\"$BB\" chmod 0755 /mnt/target/etc/orchestella/login-shell.sh || fail_prov chmod_login $?\n"
                + "\"$BB\" cp /guest-assets/etc/orchestella/ashrc /mnt/target/etc/orchestella/ashrc || fail_prov cp_ashrc $?\n"
                + "\"$BB\" chmod 0644 /mnt/target/etc/orchestella/ashrc\n"
                + "\"$BB\" cp /guest-assets/etc/orchestella/provision-marker /mnt/target/etc/orchestella/provision-marker || fail_prov cp_marker $?\n"
                + "\"$BB\" chmod 0644 /mnt/target/etc/orchestella/provision-marker\n"
                + "\"$BB\" cp /guest-assets/etc/hostname /mnt/target/etc/hostname || fail_prov cp_host $?\n"
                + "\"$BB\" chmod 0644 /mnt/target/etc/hostname\n"
                + "if [ -e /mnt/target/usr/bin/vi ] && [ ! -e /mnt/target/usr/bin/vim ]; then\n"
                + "    \"$BB\" ln -s vi /mnt/target/usr/bin/vim 2>/dev/null || :\n"
                + "fi\n"
                + "[ -x /mnt/target/bin/busybox ] || fail_prov verify_busybox 1\n"
                + "[ -x /mnt/target/um-command.sh ] || fail_prov verify_cmd 1\n"
                + buildProvisionFinish(okLine);
    }

    private static String buildDebianProvisionGuestScript(
            String okLine, String safeParent, String safeSeedTar, String tarFileName) {
        String lower = tarFileName.toLowerCase(Locale.ROOT);
        String decompCmd;
        if (lower.endsWith(".tar.xz") || lower.endsWith(".txz")) {
            decompCmd = "\"$BB\" xzcat " + safeSeedTar;
        } else if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            decompCmd = "\"$BB\" zcat " + safeSeedTar;
        } else {
            decompCmd = "\"$BB\" cat " + safeSeedTar;
        }
        return buildProvisionPreamble(safeParent, "acl,user_xattr", true)
                + "stream_tar() {\n"
                + "    rm -f /tmp/decomp.rc\n"
                + "    " + decompCmd + "\n"
                + "    printf '%s\\n' \"$?\" > /tmp/decomp.rc\n"
                + "}\n"
                + "check_decomp() {\n"
                + "    decomp_rc=1\n"
                + "    if [ -r /tmp/decomp.rc ]; then\n"
                + "        read -r decomp_rc </tmp/decomp.rc || decomp_rc=1\n"
                + "    fi\n"
                + "    [ \"${decomp_rc:-1}\" -eq 0 ] || fail_prov decomp \"${decomp_rc:-1}\"\n"
                + "}\n"
                + "\"$BB\" echo 'ORCHESTELLA_PROVISION_STEP=stage1_gnutar'\n"
                + "\"$BB\" mkdir -p /tmp/gnutar /tmp/gnutar/mnt/target\n"
                + "stream_tar | \"$BB\" tar -xpf - -C /tmp/gnutar "
                + "./bin ./lib ./sbin ./usr/bin/tar ./usr/lib/ld-linux-aarch64.so.1 ./usr/lib/aarch64-linux-gnu 2>/dev/null || :\n"
                + "if [ ! -x /tmp/gnutar/usr/bin/tar ]; then\n"
                + "    rm -rf /tmp/gnutar/*\n"
                + "    \"$BB\" mkdir -p /tmp/gnutar/mnt/target\n"
                + "    stream_tar | \"$BB\" tar -xpf - -C /tmp/gnutar "
                + "bin lib sbin usr/bin/tar usr/lib/ld-linux-aarch64.so.1 usr/lib/aarch64-linux-gnu 2>/dev/null || :\n"
                + "fi\n"
                + "check_decomp\n"
                + "[ -x /tmp/gnutar/usr/bin/tar ] || fail_prov gnutar_bootstrap 1\n"
                + "\"$BB\" echo 'ORCHESTELLA_PROVISION_STEP=bind_and_stage2'\n"
                + "\"$BB\" mount --bind /mnt/target /tmp/gnutar/mnt/target || fail_prov bind_target $?\n"
                + "stream_tar | \"$BB\" chroot /tmp/gnutar /usr/bin/tar --numeric-owner -xpf - "
                + "--acls --xattrs --xattrs-include='*' --keep-directory-symlink -C /mnt/target\n"
                + "tar_rc=$?\n"
                + "\"$BB\" umount /tmp/gnutar/mnt/target || fail_prov unbind_target $?\n"
                + "check_decomp\n"
                + "[ \"$tar_rc\" -eq 0 ] || fail_prov untar \"$tar_rc\"\n"
                + "\"$BB\" echo 'ORCHESTELLA_PROVISION_STEP=stage2_done'\n"
                + "\"$BB\" rm -rf /tmp/gnutar /tmp/decomp.rc\n"
                + "\"$BB\" umount /mnt/seed || :\n"
                + "\"$BB\" mkdir -p /mnt/target/etc/orchestella /mnt/target/usr/lib/orchestella "
                + "/mnt/target/etc/profile.d /mnt/target/etc/systemd/system/multi-user.target.wants "
                + "/mnt/target/etc/systemd/network /mnt/target/tmp /mnt/target/run /mnt/target/root "
                + "/mnt/target/proc /mnt/target/sys /mnt/target/dev /mnt/target/dev/pts /mnt/target/sys/fs/cgroup\n"
                + "\"$BB\" chmod 1777 /mnt/target/tmp 2>/dev/null || :\n"
                + "\"$BB\" cp /bin/busybox /mnt/target/usr/lib/orchestella/busybox || fail_prov cp_busybox $?\n"
                + "\"$BB\" chmod 0755 /mnt/target/usr/lib/orchestella/busybox || fail_prov chmod_busybox $?\n"
                + "\"$BB\" cp /opt/droidlayer/guest-agent /mnt/target/usr/bin/orchestella-guest-agent || fail_prov cp_agent $?\n"
                + "\"$BB\" chmod 0755 /mnt/target/usr/bin/orchestella-guest-agent || fail_prov chmod_agent $?\n"
                + "\"$BB\" cp /guest-assets/etc/orchestella/forward-connection.sh /mnt/target/etc/orchestella/forward-connection.sh || fail_prov cp_forward $?\n"
                + "\"$BB\" chmod 0755 /mnt/target/etc/orchestella/forward-connection.sh || fail_prov chmod_forward $?\n"
                + "\"$BB\" cp /guest-assets/etc/orchestella/login-shell.sh /mnt/target/etc/orchestella/login-shell.sh || fail_prov cp_login $?\n"
                + "\"$BB\" chmod 0755 /mnt/target/etc/orchestella/login-shell.sh || fail_prov chmod_login $?\n"
                + "\"$BB\" cp /guest-assets/usr/lib/orchestella/setup-creds.sh /mnt/target/usr/lib/orchestella/setup-creds.sh || fail_prov cp_creds $?\n"
                + "\"$BB\" chmod 0755 /mnt/target/usr/lib/orchestella/setup-creds.sh || fail_prov chmod_creds $?\n"
                + "\"$BB\" cp /guest-assets/usr/lib/orchestella/guest-agent-launcher.sh /mnt/target/usr/lib/orchestella/guest-agent-launcher.sh || fail_prov cp_agent_launch $?\n"
                + "\"$BB\" chmod 0755 /mnt/target/usr/lib/orchestella/guest-agent-launcher.sh || fail_prov chmod_agent_launch $?\n"
                + "\"$BB\" cp /guest-assets/usr/lib/orchestella/uml-network.sh /mnt/target/usr/lib/orchestella/uml-network.sh || fail_prov cp_net $?\n"
                + "\"$BB\" chmod 0755 /mnt/target/usr/lib/orchestella/uml-network.sh || fail_prov chmod_net $?\n"
                + "\"$BB\" cp /guest-assets/etc/profile.d/20-orchestella-terminal-integration.sh /mnt/target/etc/profile.d/20-orchestella-terminal-integration.sh || fail_prov cp_prof $?\n"
                + "\"$BB\" chmod 0644 /mnt/target/etc/profile.d/20-orchestella-terminal-integration.sh\n"
                + "\"$BB\" cp /guest-assets/etc/systemd/network/10-orchestella-vec0.network /mnt/target/etc/systemd/network/10-orchestella-vec0.network || fail_prov cp_netd $?\n"
                + "\"$BB\" chmod 0644 /mnt/target/etc/systemd/network/10-orchestella-vec0.network\n"
                + "for unit in orchestella-uml-creds.service orchestella-guest-agent.service "
                + "orchestella-uml-network.service orchestella-console.service orchestella-systemd-ready.service; do\n"
                + "    \"$BB\" cp \"/guest-assets/etc/systemd/system/$unit\" \"/mnt/target/etc/systemd/system/$unit\" || fail_prov cp_unit $?\n"
                + "    \"$BB\" chmod 0644 \"/mnt/target/etc/systemd/system/$unit\"\n"
                + "    \"$BB\" ln -sf \"../$unit\" \"/mnt/target/etc/systemd/system/multi-user.target.wants/$unit\" || fail_prov link_unit $?\n"
                + "done\n"
                + "for masked in console-getty.service serial-getty@ttyS0.service getty@tty1.service systemd-networkd-wait-online.service; do\n"
                + "    \"$BB\" ln -sf /dev/null \"/mnt/target/etc/systemd/system/$masked\" || :\n"
                + "done\n"
                + "\"$BB\" cp /guest-assets/etc/orchestella/provision-marker /mnt/target/etc/orchestella/provision-marker || fail_prov cp_marker $?\n"
                + "\"$BB\" chmod 0644 /mnt/target/etc/orchestella/provision-marker\n"
                + "\"$BB\" cp /guest-assets/etc/hostname /mnt/target/etc/hostname || fail_prov cp_host $?\n"
                + "\"$BB\" chmod 0644 /mnt/target/etc/hostname\n"
                + "[ -L /mnt/target/bin ] && [ -L /mnt/target/lib ] && [ -L /mnt/target/sbin ] || fail_prov verify_usrmerge 1\n"
                + "[ -x /mnt/target/sbin/init ] || \"$BB\" chroot /mnt/target /usr/bin/test -x /sbin/init || fail_prov verify_init 1\n"
                + "[ -x /mnt/target/usr/bin/orchestella-guest-agent ] || fail_prov verify_agent 1\n"
                + "[ -x /mnt/target/usr/lib/orchestella/busybox ] || fail_prov verify_busybox 1\n"
                + buildProvisionFinish(okLine);
    }

    private static String buildProvisionPreamble(
            String safeParent, String ext4MountOpts, boolean mountTmpfs) {
        return "#!/bin/busybox sh\n"
                + "set -u\n"
                + "BB=/bin/busybox\n"
                + "PATH=/sbin:/bin:/usr/sbin:/usr/bin\n"
                + "export PATH\n"
                + "fail_prov() {\n"
                + "    \"$BB\" printf 'ORCHESTELLA_UML_PROVISION_FAIL step=%s rc=%s\\n' \"$1\" \"$2\"\n"
                + "    \"$BB\" umount /tmp/gnutar/mnt/target 2>/dev/null || :\n"
                + "    \"$BB\" umount /mnt/seed 2>/dev/null || :\n"
                + "    \"$BB\" umount /mnt/target 2>/dev/null || :\n"
                + "    \"$BB\" sync 2>/dev/null || :\n"
                + "    \"$BB\" poweroff -f 2>/dev/null || :\n"
                + "    exit 1\n"
                + "}\n"
                + "\"$BB\" mkdir -p /tmp /mnt/target /mnt/seed /dev\n"
                + (mountTmpfs
                        ? "\"$BB\" mount -t tmpfs -o size=192m,mode=1777 tmpfs /tmp || fail_prov mount_tmpfs $?\n"
                        : "")
                + "[ -b /dev/ubda ] || \"$BB\" mknod -m 0660 /dev/ubda b 98 0 || fail_prov ubda_node $?\n"
                + "MKE2FS_CONFIG=/etc/mke2fs.conf /sbin/mkfs.ext4 -F -q -m 1 -L orchestella-root /dev/ubda || fail_prov mkfs $?\n"
                + ("umask=0000".equals(ext4MountOpts)
                        ? "\"$BB\" mount -t ext4 /dev/ubda /mnt/target || fail_prov mount_target $?\n"
                        : "\"$BB\" mount -t ext4 -o " + ext4MountOpts + " /dev/ubda /mnt/target || fail_prov mount_target $?\n")
                + "\"$BB\" mount -t hostfs -o " + safeParent + " none /mnt/seed || fail_prov mount_seed $?\n";
    }

    private static String buildProvisionFinish(String okLine) {
        return "\"$BB\" printf 'probe' >/mnt/target/tmp/.w302-hl-a && \"$BB\" chown 1000:1000 /mnt/target/tmp/.w302-hl-a && \"$BB\" ln /mnt/target/tmp/.w302-hl-a /mnt/target/tmp/.w302-hl-b || fail_prov verify_posix 1\n"
                + "\"$BB\" rm -f /mnt/target/tmp/.w302-hl-a /mnt/target/tmp/.w302-hl-b\n"
                + "\"$BB\" sync\n"
                + "\"$BB\" echo 'ORCHESTELLA_PROVISION_STEP=sync_done'\n"
                + "\"$BB\" umount /mnt/target || fail_prov umount_target $?\n"
                + "\"$BB\" sync\n"
                + "\"$BB\" printf '" + okLine + "\\n'\n"
                + "\"$BB\" poweroff -f 2>/dev/null || :\n"
                + "exit 0\n";
    }

    private static String expectedSuccessLine(
            String instanceId, String distroId, String sha256, String volumeId) {
        return "ORCHESTELLA_UML_PROVISION_OK version=" + PROVISION_MARKER_VERSION
                + " instance=" + sanitizeSegment(instanceId)
                + " distro=" + sanitizeSegment(distroId)
                + " sha256=" + sha256
                + " volume_id=" + sanitizeSegment(volumeId)
                + " fstype=ext4";
    }

    private static String formatProvisionMarker(ProvisionRequest request, String volumeId) {
        boolean isDebian = "debian".equalsIgnoreCase(request.seedInput().distroId().trim());
        return "marker_version=" + PROVISION_MARKER_VERSION + "\n"
                + "instance_id=" + request.instanceId() + "\n"
                + "distro_id=" + request.seedInput().distroId().trim().toLowerCase(Locale.ROOT) + "\n"
                + "distro_version=" + request.seedInput().distroVersion() + "\n"
                + "rootfs_sha256=" + request.seedInput().expectedSha256().toLowerCase(Locale.ROOT) + "\n"
                + "kernel_profile=" + UmlKernelPayloadContract.descriptorFor(request.kernelProfile()).profileId() + "\n"
                + "init_path=" + (isDebian ? "/sbin/init" : "/um-command.sh") + "\n"
                + "volume_id=" + volumeId + "\n"
                + "format=RAW_SPARSE_EXT4\n";
    }

    private static void createSparseFile(Path path, long sizeBytes) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.position(sizeBytes - 1L);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
    }

    private static void moveFileIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean attestsPin(Path sidecar, String expectedPin) throws IOException {
        for (String line : Files.readAllLines(sidecar, StandardCharsets.UTF_8)) {
            if (line.startsWith("pin=")) {
                String actual = line.substring(4).trim().toLowerCase(Locale.ROOT);
                return MessageDigest.isEqual(
                        expectedPin.getBytes(StandardCharsets.US_ASCII),
                        actual.getBytes(StandardCharsets.US_ASCII));
            }
        }
        return false;
    }

    private static String sha256Hex(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static void drainStream(UmlProcessHandle handle, ByteArrayOutputStream out) {
        try (InputStream in = handle.terminalOutput()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                int remaining = MAX_GUEST_OUTPUT_BYTES - out.size();
                if (remaining > 0) {
                    out.write(buf, 0, Math.min(n, remaining));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(UmlProcessHandle handle) {
        try {
            handle.controlInput().close();
        } catch (IOException | RuntimeException ignored) {
        }
        try {
            handle.controlOutput().close();
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void writeUtf8(Path target, String content, int mode, boolean executable)
            throws IOException {
        Files.write(
                target,
                content.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        applyModeBestEffort(target, mode);
        if (executable) {
            target.toFile().setReadable(true, false);
            target.toFile().setExecutable(true, false);
        } else {
            target.toFile().setReadable(true, (mode & 0077) == 0);
        }
    }

    private static void applyModeBestEffort(Path target, int mode) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
            if ((mode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
            if ((mode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
            if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
            if ((mode & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
            if ((mode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
            if ((mode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
            if ((mode & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
            if ((mode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(target, perms);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(root);
            return;
        }
        List<Path> entries;
        try (var walk = Files.walk(root)) {
            entries = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path entry : entries) {
            Files.deleteIfExists(entry);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static boolean containsParentDotSegment(Path path) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeSegment(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            return "seg";
        }
        return SAFE_HOST_SEGMENT.matcher(cleaned).matches() ? cleaned : cleaned.substring(0, Math.min(80, cleaned.length()));
    }

    private static String sanitizeHostname(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return "orchestella-uml";
        }
        String cleaned = hostname.replaceAll("[^A-Za-z0-9-]", "");
        return cleaned.isEmpty() ? "orchestella-uml" : cleaned;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String lastNonEmptyLine(String log) {
        String last = "";
        for (String line : log.split("\\r?\\n")) {
            if (!line.isBlank()) {
                last = line.trim();
            }
        }
        return last;
    }
}
