package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable metadata node representing one state snapshot checkpoint of an instance.
 */
public final class InstanceSnapshotNode {

    private final String snapshotId;
    private final String instanceId;
    private final String name;
    private final String description;
    private final long createdAt;
    private final Path diskCheckpointPath;
    private final VmHardwareConfig hardwareConfigSnapshot;
    private final long sizeBytes;

    public InstanceSnapshotNode(
            String snapshotId,
            String instanceId,
            String name,
            String description,
            long createdAt,
            Path diskCheckpointPath,
            VmHardwareConfig hardwareConfigSnapshot,
            long sizeBytes) {
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("snapshot name must not be blank");
        }
        this.name = name.trim();
        this.description = description != null ? description.trim() : null;
        this.createdAt = createdAt;
        this.diskCheckpointPath = Objects.requireNonNull(diskCheckpointPath, "diskCheckpointPath").toAbsolutePath().normalize();
        this.hardwareConfigSnapshot = hardwareConfigSnapshot != null ? hardwareConfigSnapshot : VmHardwareConfig.defaultConfig();
        this.sizeBytes = Math.max(0L, sizeBytes);
    }

    public String snapshotId() {
        return snapshotId;
    }

    public String instanceId() {
        return instanceId;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public long createdAt() {
        return createdAt;
    }

    public Path diskCheckpointPath() {
        return diskCheckpointPath;
    }

    public VmHardwareConfig hardwareConfigSnapshot() {
        return hardwareConfigSnapshot;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"snapshotId\": \"").append(escapeJson(snapshotId)).append("\",\n");
        sb.append("  \"instanceId\": \"").append(escapeJson(instanceId)).append("\",\n");
        sb.append("  \"name\": \"").append(escapeJson(name)).append("\",\n");
        if (description != null) {
            sb.append("  \"description\": \"").append(escapeJson(description)).append("\",\n");
        } else {
            sb.append("  \"description\": null,\n");
        }
        sb.append("  \"createdAt\": ").append(createdAt).append(",\n");
        sb.append("  \"diskCheckpointPath\": \"").append(escapeJson(diskCheckpointPath.toString())).append("\",\n");
        sb.append("  \"sizeBytes\": ").append(sizeBytes).append(",\n");
        sb.append("  \"cpuCores\": ").append(hardwareConfigSnapshot.cpuCores()).append(",\n");
        sb.append("  \"ramMegabytes\": ").append(hardwareConfigSnapshot.ramMegabytes()).append(",\n");
        sb.append("  \"diskCapacityBytes\": ").append(hardwareConfigSnapshot.diskCapacityBytes()).append(",\n");
        sb.append("  \"gpuAccelerationEnabled\": ").append(hardwareConfigSnapshot.gpuAccelerationEnabled());
        if (hardwareConfigSnapshot.extraKernelArguments() != null) {
            sb.append(",\n  \"extraKernelArguments\": \"")
                    .append(escapeJson(hardwareConfigSnapshot.extraKernelArguments())).append("\"\n");
        } else {
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    public static InstanceSnapshotNode fromJson(String json) {
        Objects.requireNonNull(json, "json");
        String snapshotId = extractStringField(json, "snapshotId");
        String instanceId = extractStringField(json, "instanceId");
        String name = extractStringField(json, "name");
        String description = extractNullableStringField(json, "description");
        long createdAt = extractLongField(json, "createdAt");
        String checkpointPathStr = extractStringField(json, "diskCheckpointPath");
        long sizeBytes = extractLongFieldOrDefault(json, "sizeBytes", 0L);

        int cpuCores = (int) extractLongFieldOrDefault(json, "cpuCores", 2);
        int ramMb = (int) extractLongFieldOrDefault(json, "ramMegabytes", 2048);
        long diskBytes = extractLongFieldOrDefault(json, "diskCapacityBytes", 10L * 1024 * 1024 * 1024);
        boolean gpu = extractBooleanFieldOrDefault(json, "gpuAccelerationEnabled", true);
        String extraArgs = extractNullableStringField(json, "extraKernelArguments");
        VmHardwareConfig hw = new VmHardwareConfig(cpuCores, ramMb, diskBytes, gpu, extraArgs);

        return new InstanceSnapshotNode(
                snapshotId,
                instanceId,
                name,
                description,
                createdAt,
                Path.of(checkpointPathStr),
                hw,
                sizeBytes);
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
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

    private static String extractNullableStringField(String json, String key) {
        Pattern nullPattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*null");
        if (nullPattern.matcher(json).find()) {
            return null;
        }
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static long extractLongField(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing or invalid long field: " + key);
        }
        return Long.parseLong(matcher.group(1));
    }

    private static long extractLongFieldOrDefault(String json, String key, long defaultValue) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return defaultValue;
        }
        return Long.parseLong(matcher.group(1));
    }

    private static boolean extractBooleanFieldOrDefault(String json, String key, boolean defaultValue) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(matcher.group(1));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InstanceSnapshotNode that = (InstanceSnapshotNode) o;
        return createdAt == that.createdAt
                && sizeBytes == that.sizeBytes
                && Objects.equals(snapshotId, that.snapshotId)
                && Objects.equals(instanceId, that.instanceId)
                && Objects.equals(name, that.name)
                && Objects.equals(description, that.description)
                && Objects.equals(diskCheckpointPath, that.diskCheckpointPath)
                && Objects.equals(hardwareConfigSnapshot, that.hardwareConfigSnapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(snapshotId, instanceId, name, description, createdAt, diskCheckpointPath, hardwareConfigSnapshot, sizeBytes);
    }

    @Override
    public String toString() {
        return "InstanceSnapshotNode{" +
                "snapshotId='" + snapshotId + '\'' +
                ", instanceId='" + instanceId + '\'' +
                ", name='" + name + '\'' +
                ", createdAt=" + createdAt +
                ", sizeBytes=" + sizeBytes +
                '}';
    }
}
