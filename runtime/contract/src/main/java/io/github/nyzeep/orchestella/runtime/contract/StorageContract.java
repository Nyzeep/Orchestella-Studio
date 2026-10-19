package io.github.nyzeep.orchestella.runtime.contract;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strategy-neutral storage contract, space preflight, disk resize, and export models (ADR-0007).
 *
 * <p>Implements core requirements:
 * <ul>
 *   <li>Raw sparse single root-volume disk format ({@value #ROOT_VOLUME_FILE_NAME}).</li>
 *   <li>Host storage space preflight preventing ENOSPC overcommit.</li>
 *   <li>Disk expansion specification (forbidding shrinking).</li>
 *   <li>One-way export bundle manifest (root volume, metadata JSON, SHA-256 checksums).</li>
 * </ul>
 */
public final class StorageContract {
    public static final String ROOT_VOLUME_FILE_NAME = "root-volume.raw";
    public static final String METADATA_FILE_NAME = "instance-metadata.json";
    public static final String CHECKSUMS_FILE_NAME = "SHA256SUMS";

    public static final long DEFAULT_ROOT_VOLUME_BYTES = 2L * 1024L * 1024L * 1024L; // 2 GiB
    public static final long MIN_ROOT_VOLUME_BYTES = 512L * 1024L * 1024L;          // 512 MiB
    public static final long MAX_ROOT_VOLUME_BYTES = 2L * 1024L * 1024L * 1024L * 1024L; // 2 TiB

    private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private StorageContract() {}

    /**
     * Seam for inspecting available host filesystem storage space.
     */
    public interface StorageSpaceChecker {
        long getUsableSpace(Path path) throws IOException;
    }

    /**
     * Default host storage space checker using Java NIO FileStore.
     */
    public static final class DefaultStorageSpaceChecker implements StorageSpaceChecker {
        @Override
        public long getUsableSpace(Path path) throws IOException {
            Objects.requireNonNull(path, "path");
            Path checkPath = Files.isDirectory(path) ? path : (path.getParent() != null ? path.getParent() : path);
            try {
                return Files.getFileStore(checkPath).getUsableSpace();
            } catch (Exception fallback) {
                return checkPath.toFile().getUsableSpace();
            }
        }
    }

    /**
     * Explicit exception indicating host storage space insufficiency (ADR-0007 ENOSPC prevention).
     */
    public static final class InsufficientStorageException extends IOException {
        private static final long serialVersionUID = 1L;
        private final Path path;
        private final long requiredBytes;
        private final long availableBytes;

        public InsufficientStorageException(Path path, long requiredBytes, long availableBytes) {
            super(String.format(
                    "Insufficient host storage space at '%s': required %d bytes, but only %d bytes available (ENOSPC prevention)",
                    path, requiredBytes, availableBytes));
            this.path = path;
            this.requiredBytes = requiredBytes;
            this.availableBytes = availableBytes;
        }

        public Path path() {
            return path;
        }

        public long requiredBytes() {
            return requiredBytes;
        }

        public long availableBytes() {
            return availableBytes;
        }
    }

    /**
     * Preflight checks for storage operations before file creation or size changes.
     */
    public static final class StoragePreflight {
        private StoragePreflight() {}

        public static void checkCreationSpace(
                StorageSpaceChecker checker, Path targetDirectory, long requiredBytes) throws IOException {
            Objects.requireNonNull(checker, "checker");
            Objects.requireNonNull(targetDirectory, "targetDirectory");
            if (requiredBytes <= 0) {
                throw new IllegalArgumentException("requiredBytes must be positive: " + requiredBytes);
            }
            long usable = checker.getUsableSpace(targetDirectory);
            if (usable < requiredBytes) {
                throw new InsufficientStorageException(targetDirectory, requiredBytes, usable);
            }
        }

        public static void checkResizeSpace(
                StorageSpaceChecker checker, Path volumePath, long currentBytes, long targetBytes) throws IOException {
            Objects.requireNonNull(checker, "checker");
            Objects.requireNonNull(volumePath, "volumePath");
            DiskResizeSpec.validate(currentBytes, targetBytes);
            long delta = targetBytes - currentBytes;
            Path checkTarget = volumePath.getParent() != null ? volumePath.getParent() : volumePath;
            long usable = checker.getUsableSpace(checkTarget);
            if (usable < delta) {
                throw new InsufficientStorageException(volumePath, delta, usable);
            }
        }
    }

    /**
     * Validates and models a disk expansion specification. Shrinking is strictly prohibited.
     */
    public static final class DiskResizeSpec {
        private final long currentSizeBytes;
        private final long targetSizeBytes;

        public DiskResizeSpec(long currentSizeBytes, long targetSizeBytes) {
            validate(currentSizeBytes, targetSizeBytes);
            this.currentSizeBytes = currentSizeBytes;
            this.targetSizeBytes = targetSizeBytes;
        }

        public static void validate(long currentSizeBytes, long targetSizeBytes) {
            if (currentSizeBytes <= 0) {
                throw new IllegalArgumentException("current size must be positive: " + currentSizeBytes);
            }
            if (targetSizeBytes <= 0) {
                throw new IllegalArgumentException("target size must be positive: " + targetSizeBytes);
            }
            if (targetSizeBytes <= currentSizeBytes) {
                throw new IllegalArgumentException(
                        "target size (" + targetSizeBytes + " B) must be strictly greater than current size ("
                                + currentSizeBytes + " B); disk shrinking is prohibited");
            }
            if (targetSizeBytes > MAX_ROOT_VOLUME_BYTES) {
                throw new IllegalArgumentException(
                        "target size (" + targetSizeBytes + " B) exceeds maximum supported limit ("
                                + MAX_ROOT_VOLUME_BYTES + " B)");
            }
        }

        public long currentSizeBytes() {
            return currentSizeBytes;
        }

        public long targetSizeBytes() {
            return targetSizeBytes;
        }

        public long additionalBytesNeeded() {
            return targetSizeBytes - currentSizeBytes;
        }
    }

    /**
     * Optional export-time snapshot of observed guest provisioning evidence.
     *
     * <p>Mirrors the lifecycle evidence without coupling this strategy-neutral contract to the
     * instance implementation. Missing evidence (marker version 0, no rootfs digest, recorded 0)
     * is represented by {@code null} at the DTO level and means "never observed as provisioned".
     */
    public static final class ProvisioningEvidenceMetadata {
        private final int markerVersion;
        private final String rootfsSha256;
        private final long recordedAtMillis;

        public ProvisioningEvidenceMetadata(int markerVersion, String rootfsSha256, long recordedAtMillis) {
            if (markerVersion <= 0) {
                throw new IllegalArgumentException("markerVersion must be positive: " + markerVersion);
            }
            if (rootfsSha256 != null && !HEX_64_PATTERN.matcher(rootfsSha256).matches()) {
                throw new IllegalArgumentException("invalid provisioning rootfs sha256: " + rootfsSha256);
            }
            if (recordedAtMillis < 0) {
                throw new IllegalArgumentException("recordedAtMillis must not be negative: " + recordedAtMillis);
            }
            this.markerVersion = markerVersion;
            this.rootfsSha256 = rootfsSha256;
            this.recordedAtMillis = recordedAtMillis;
        }

        public int markerVersion() {
            return markerVersion;
        }

        public String rootfsSha256() {
            return rootfsSha256;
        }

        public long recordedAtMillis() {
            return recordedAtMillis;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ProvisioningEvidenceMetadata that = (ProvisioningEvidenceMetadata) o;
            return markerVersion == that.markerVersion
                    && recordedAtMillis == that.recordedAtMillis
                    && Objects.equals(rootfsSha256, that.rootfsSha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(markerVersion, rootfsSha256, recordedAtMillis);
        }

        @Override
        public String toString() {
            return "ProvisioningEvidenceMetadata{markerVersion=" + markerVersion
                    + ", rootfsSha256=" + rootfsSha256
                    + ", recordedAtMillis=" + recordedAtMillis
                    + '}';
        }
    }

    /**
     * Immutable metadata DTO included in instance export bundles.
     *
     * <p>Fields added after the original ten-field format are optional on read. Legacy JSON
     * without hostname/user/sudo/hardware/provisioning fields parses with safe defaults
     * ({@value #DEFAULT_HOSTNAME}, {@value #DEFAULT_USER}, no sudo, default hardware, no
     * provisioning evidence).
     */
    public static final class InstanceExportMetadata {
        public static final String DEFAULT_HOSTNAME = "orchestella";
        public static final String DEFAULT_USER = "root";

        private final String instanceId;
        private final String distroId;
        private final String distroVersion;
        private final String runtimeClass;
        private final String state;
        private final long diskSizeBytes;
        private final String sha256RootVolume;
        private final long createdAtMillis;
        private final long updatedAtMillis;
        private final long exportedAtMillis;
        private final String hostname;
        private final String defaultUser;
        private final boolean hasSudo;
        private final VmHardwareConfig hardwareConfig;
        private final ProvisioningEvidenceMetadata provisioningEvidence;

        /** Legacy ten-field constructor; extension fields receive safe defaults. */
        public InstanceExportMetadata(
                String instanceId,
                String distroId,
                String distroVersion,
                String runtimeClass,
                String state,
                long diskSizeBytes,
                String sha256RootVolume,
                long createdAtMillis,
                long updatedAtMillis,
                long exportedAtMillis) {
            this(instanceId, distroId, distroVersion, runtimeClass, state, diskSizeBytes,
                    sha256RootVolume, createdAtMillis, updatedAtMillis, exportedAtMillis,
                    DEFAULT_HOSTNAME, DEFAULT_USER, false, VmHardwareConfig.defaultConfig(), null);
        }

        public InstanceExportMetadata(
                String instanceId,
                String distroId,
                String distroVersion,
                String runtimeClass,
                String state,
                long diskSizeBytes,
                String sha256RootVolume,
                long createdAtMillis,
                long updatedAtMillis,
                long exportedAtMillis,
                String hostname,
                String defaultUser,
                boolean hasSudo,
                VmHardwareConfig hardwareConfig,
                ProvisioningEvidenceMetadata provisioningEvidence) {
            this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
            this.distroId = Objects.requireNonNull(distroId, "distroId");
            this.distroVersion = Objects.requireNonNull(distroVersion, "distroVersion");
            this.runtimeClass = Objects.requireNonNull(runtimeClass, "runtimeClass");
            this.state = Objects.requireNonNull(state, "state");
            if (diskSizeBytes <= 0) {
                throw new IllegalArgumentException("diskSizeBytes must be positive: " + diskSizeBytes);
            }
            this.diskSizeBytes = diskSizeBytes;
            if (sha256RootVolume == null || !HEX_64_PATTERN.matcher(sha256RootVolume).matches()) {
                throw new IllegalArgumentException("invalid sha256RootVolume digest: " + sha256RootVolume);
            }
            this.sha256RootVolume = sha256RootVolume;
            this.createdAtMillis = createdAtMillis;
            this.updatedAtMillis = updatedAtMillis;
            this.exportedAtMillis = exportedAtMillis;
            this.hostname = hostname == null || hostname.trim().isEmpty()
                    ? DEFAULT_HOSTNAME : hostname.trim();
            this.defaultUser = defaultUser == null || defaultUser.trim().isEmpty()
                    ? DEFAULT_USER : defaultUser.trim();
            this.hasSudo = hasSudo;
            this.hardwareConfig = hardwareConfig != null ? hardwareConfig : VmHardwareConfig.defaultConfig();
            this.provisioningEvidence = provisioningEvidence;
        }

        public String instanceId() {
            return instanceId;
        }

        public String distroId() {
            return distroId;
        }

        public String distroVersion() {
            return distroVersion;
        }

        public String runtimeClass() {
            return runtimeClass;
        }

        public String state() {
            return state;
        }

        public long diskSizeBytes() {
            return diskSizeBytes;
        }

        public String sha256RootVolume() {
            return sha256RootVolume;
        }

        public long createdAtMillis() {
            return createdAtMillis;
        }

        public long updatedAtMillis() {
            return updatedAtMillis;
        }

        public long exportedAtMillis() {
            return exportedAtMillis;
        }

        public String hostname() {
            return hostname;
        }

        public String defaultUser() {
            return defaultUser;
        }

        public boolean hasSudo() {
            return hasSudo;
        }

        public VmHardwareConfig hardwareConfig() {
            return hardwareConfig;
        }

        /** Recorded provisioning evidence, or {@code null} when the export had none. */
        public ProvisioningEvidenceMetadata provisioningEvidence() {
            return provisioningEvidence;
        }

        public String toJson() {
            String extraArgs = hardwareConfig.extraKernelArguments();
            String evidenceRootfs = provisioningEvidence != null ? provisioningEvidence.rootfsSha256() : null;
            return "{\n"
                    + "  \"instanceId\": \"" + escapeJson(instanceId) + "\",\n"
                    + "  \"distroId\": \"" + escapeJson(distroId) + "\",\n"
                    + "  \"distroVersion\": \"" + escapeJson(distroVersion) + "\",\n"
                    + "  \"runtimeClass\": \"" + escapeJson(runtimeClass) + "\",\n"
                    + "  \"state\": \"" + escapeJson(state) + "\",\n"
                    + "  \"diskSizeBytes\": " + diskSizeBytes + ",\n"
                    + "  \"sha256RootVolume\": \"" + sha256RootVolume + "\",\n"
                    + "  \"createdAtMillis\": " + createdAtMillis + ",\n"
                    + "  \"updatedAtMillis\": " + updatedAtMillis + ",\n"
                    + "  \"exportedAtMillis\": " + exportedAtMillis + ",\n"
                    + "  \"hostname\": \"" + escapeJson(hostname) + "\",\n"
                    + "  \"defaultUser\": \"" + escapeJson(defaultUser) + "\",\n"
                    + "  \"hasSudo\": " + hasSudo + ",\n"
                    + "  \"hardwareCpuCores\": " + hardwareConfig.cpuCores() + ",\n"
                    + "  \"hardwareRamMegabytes\": " + hardwareConfig.ramMegabytes() + ",\n"
                    + "  \"hardwareDiskCapacityBytes\": " + hardwareConfig.diskCapacityBytes() + ",\n"
                    + "  \"hardwareGpuAccelerationEnabled\": " + hardwareConfig.gpuAccelerationEnabled() + ",\n"
                    + "  \"hardwareExtraKernelArguments\": "
                    + (extraArgs == null ? "null" : "\"" + escapeJson(extraArgs) + "\"") + ",\n"
                    + "  \"provisioningMarkerVersion\": "
                    + (provisioningEvidence != null ? provisioningEvidence.markerVersion() : 0) + ",\n"
                    + "  \"provisioningRootfsSha256\": "
                    + (evidenceRootfs == null ? "null" : "\"" + evidenceRootfs + "\"") + ",\n"
                    + "  \"provisioningRecordedAtMillis\": "
                    + (provisioningEvidence != null ? provisioningEvidence.recordedAtMillis() : 0) + "\n"
                    + "}\n";
        }

        public static InstanceExportMetadata fromJson(String json) {
            Objects.requireNonNull(json, "json");
            String instanceId = extractStringField(json, "instanceId");
            String distroId = extractStringField(json, "distroId");
            String distroVersion = extractStringField(json, "distroVersion");
            String runtimeClass = extractStringField(json, "runtimeClass");
            String state = extractStringField(json, "state");
            long diskSizeBytes = extractLongField(json, "diskSizeBytes");
            String sha256RootVolume = extractStringField(json, "sha256RootVolume");
            long createdAtMillis = extractLongField(json, "createdAtMillis");
            long updatedAtMillis = extractLongField(json, "updatedAtMillis");
            long exportedAtMillis = extractLongField(json, "exportedAtMillis");

            String hostname = extractOptionalStringField(json, "hostname");
            if (hostname == null) {
                hostname = DEFAULT_HOSTNAME;
            }
            String defaultUser = extractOptionalStringField(json, "defaultUser");
            if (defaultUser == null) {
                defaultUser = DEFAULT_USER;
            }
            boolean hasSudo = extractOptionalBooleanField(json, "hasSudo", false);
            VmHardwareConfig hardwareConfig = extractHardwareConfig(json);
            ProvisioningEvidenceMetadata provisioningEvidence = extractProvisioningEvidence(json);

            return new InstanceExportMetadata(
                    instanceId,
                    distroId,
                    distroVersion,
                    runtimeClass,
                    state,
                    diskSizeBytes,
                    sha256RootVolume,
                    createdAtMillis,
                    updatedAtMillis,
                    exportedAtMillis,
                    hostname,
                    defaultUser,
                    hasSudo,
                    hardwareConfig,
                    provisioningEvidence);
        }

        private static VmHardwareConfig extractHardwareConfig(String json) {
            Long cpuCores = extractOptionalLongField(json, "hardwareCpuCores");
            Long ramMegabytes = extractOptionalLongField(json, "hardwareRamMegabytes");
            Long diskCapacityBytes = extractOptionalLongField(json, "hardwareDiskCapacityBytes");
            if (cpuCores == null || ramMegabytes == null || diskCapacityBytes == null) {
                return VmHardwareConfig.defaultConfig();
            }
            boolean gpuEnabled = extractOptionalBooleanField(
                    json, "hardwareGpuAccelerationEnabled", VmHardwareConfig.DEFAULT_GPU_ACCELERATION_ENABLED);
            String extraKernelArguments = extractOptionalStringField(json, "hardwareExtraKernelArguments");
            try {
                return new VmHardwareConfig(
                        Math.toIntExact(cpuCores),
                        Math.toIntExact(ramMegabytes),
                        diskCapacityBytes,
                        gpuEnabled,
                        extraKernelArguments);
            } catch (ArithmeticException | IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "invalid hardware config in export metadata: " + invalid.getMessage(), invalid);
            }
        }

        private static ProvisioningEvidenceMetadata extractProvisioningEvidence(String json) {
            Long markerVersion = extractOptionalLongField(json, "provisioningMarkerVersion");
            if (markerVersion == null || markerVersion <= 0) {
                return null;
            }
            if (markerVersion > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid provisioning marker version: " + markerVersion);
            }
            Long recordedAtMillis = extractOptionalLongField(json, "provisioningRecordedAtMillis");
            return new ProvisioningEvidenceMetadata(
                    markerVersion.intValue(),
                    extractOptionalStringField(json, "provisioningRootfsSha256"),
                    recordedAtMillis != null ? recordedAtMillis : 0L);
        }

        private static String escapeJson(String input) {
            return input.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String extractStringField(String json, String key) {
            Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
            Matcher matcher = pattern.matcher(json);
            if (!matcher.find()) {
                throw new IllegalArgumentException("Missing or invalid string field: " + key);
            }
            return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        }

        private static long extractLongField(String json, String key) {
            Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)");
            Matcher matcher = pattern.matcher(json);
            if (!matcher.find()) {
                throw new IllegalArgumentException("Missing or invalid numeric field: " + key);
            }
            return Long.parseLong(matcher.group(1));
        }

        private static String extractOptionalStringField(String json, String key) {
            Pattern nullPattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*null\\b");
            if (nullPattern.matcher(json).find()) {
                return null;
            }
            Pattern pattern = Pattern.compile(
                    "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
            Matcher matcher = pattern.matcher(json);
            if (!matcher.find()) {
                return null;
            }
            return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        }

        private static Long extractOptionalLongField(String json, String key) {
            Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
            Matcher matcher = pattern.matcher(json);
            return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
        }

        private static boolean extractOptionalBooleanField(String json, String key, boolean defaultValue) {
            Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)");
            Matcher matcher = pattern.matcher(json);
            return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : defaultValue;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            InstanceExportMetadata that = (InstanceExportMetadata) o;
            return diskSizeBytes == that.diskSizeBytes
                    && createdAtMillis == that.createdAtMillis
                    && updatedAtMillis == that.updatedAtMillis
                    && exportedAtMillis == that.exportedAtMillis
                    && hasSudo == that.hasSudo
                    && Objects.equals(instanceId, that.instanceId)
                    && Objects.equals(distroId, that.distroId)
                    && Objects.equals(distroVersion, that.distroVersion)
                    && Objects.equals(runtimeClass, that.runtimeClass)
                    && Objects.equals(state, that.state)
                    && Objects.equals(sha256RootVolume, that.sha256RootVolume)
                    && Objects.equals(hostname, that.hostname)
                    && Objects.equals(defaultUser, that.defaultUser)
                    && Objects.equals(hardwareConfig, that.hardwareConfig)
                    && Objects.equals(provisioningEvidence, that.provisioningEvidence);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    instanceId,
                    distroId,
                    distroVersion,
                    runtimeClass,
                    state,
                    diskSizeBytes,
                    sha256RootVolume,
                    createdAtMillis,
                    updatedAtMillis,
                    exportedAtMillis,
                    hostname,
                    defaultUser,
                    hasSudo,
                    hardwareConfig,
                    provisioningEvidence);
        }
    }

    /**
     * Formats and verifies SHA256SUMS manifests.
     */
    public static final class ChecksumManifest {
        private final Map<String, String> fileToHash;

        public ChecksumManifest(Map<String, String> entries) {
            Objects.requireNonNull(entries, "entries");
            Map<String, String> map = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String file = Objects.requireNonNull(entry.getKey(), "file");
                String hash = Objects.requireNonNull(entry.getValue(), "hash").toLowerCase();
                if (!HEX_64_PATTERN.matcher(hash).matches()) {
                    throw new IllegalArgumentException("invalid SHA-256 hash: " + hash);
                }
                map.put(file, hash);
            }
            this.fileToHash = Collections.unmodifiableMap(map);
        }

        public Map<String, String> entries() {
            return fileToHash;
        }

        public String getHash(String fileName) {
            return fileToHash.get(fileName);
        }

        public String format() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : fileToHash.entrySet()) {
                sb.append(entry.getValue()).append("  ").append(entry.getKey()).append("\n");
            }
            return sb.toString();
        }

        public static ChecksumManifest parse(String content) {
            Objects.requireNonNull(content, "content");
            Map<String, String> map = new LinkedHashMap<>();
            for (String line : content.split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+", 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Malformed SHA256SUMS line: " + line);
                }
                String hash = parts[0].toLowerCase();
                String file = parts[1];
                if (!HEX_64_PATTERN.matcher(hash).matches()) {
                    throw new IllegalArgumentException("Invalid hash in line: " + line);
                }
                map.put(file, hash);
            }
            return new ChecksumManifest(map);
        }
    }

    /**
     * Computes the SHA-256 hexadecimal digest of an input stream.
     */
    public static String sha256Hex(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            digest.update(buffer, 0, count);
        }
        return bytesToHex(digest.digest());
    }

    /**
     * Computes the SHA-256 hexadecimal digest of byte data.
     */
    public static String sha256Hex(byte[] data) {
        Objects.requireNonNull(data, "data");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        return bytesToHex(digest.digest(data));
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
