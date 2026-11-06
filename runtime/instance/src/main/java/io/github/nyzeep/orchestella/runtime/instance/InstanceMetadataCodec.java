package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * Metadata (de)serialization for {@link InstanceLifecycle.Instance}, extracted verbatim from
 * {@link InstanceLifecycle} so the Instance state model and its Properties encoding evolve in
 * one focused place.
 *
 * <p>Package-private on purpose: the persisted contract is exercised only through
 * {@link InstanceLifecycle.Instance}, {@link InstanceLifecycle.FileStore} and
 * {@link InstanceLifecycle.Controller}; nothing here changes the public API.
 */
final class InstanceMetadataCodec {
    private static final String KEY_PROVISIONING_MARKER_VERSION = "provisioning.markerVersion";
    private static final String KEY_PROVISIONING_ROOTFS_SHA256 = "provisioning.rootfsSha256";
    private static final String KEY_PROVISIONING_RECORDED_AT = "provisioning.recordedAtMillis";
    /** Metadata schema. Absent means legacy metadata written before schemaVersion existed. */
    private static final String KEY_METADATA_SCHEMA_VERSION = "metadata.schemaVersion";
    private static final String KEY_HARDWARE_LEGACY_DEFAULTS = "hardware.legacyDefaults";
    private static final int CURRENT_METADATA_SCHEMA_VERSION = 1;
    /**
     * Keys this codec understands. Everything else is preserved verbatim on
     * rewrite so a newer writer's metadata is not silently dropped by an
     * older reader; unknown keys never participate in hardware parsing.
     */
    private static final Set<String> KNOWN_METADATA_KEYS = Set.of(
            "id",
            "distroId",
            "distroVersion",
            "rootVolume",
            "bridgeToken",
            "guestPassword",
            "runtimeClass",
            "state",
            "createdAtMillis",
            "updatedAtMillis",
            "hostname",
            "defaultUser",
            "hasSudo",
            "crash.timestampMillis",
            "crash.exitCode",
            "crash.logPath",
            "crash.message",
            KEY_METADATA_SCHEMA_VERSION,
            "hardware.cpuCores",
            "hardware.ramMegabytes",
            "hardware.diskCapacityBytes",
            "hardware.gpuAccelerationEnabled",
            "hardware.extraKernelArguments",
            KEY_HARDWARE_LEGACY_DEFAULTS,
            KEY_PROVISIONING_MARKER_VERSION,
            KEY_PROVISIONING_ROOTFS_SHA256,
            KEY_PROVISIONING_RECORDED_AT);

    private InstanceMetadataCodec() {}

    /** Encodes an instance as persistable Properties; mirrors the historical wire format. */
    static Properties encode(InstanceLifecycle.Instance instance) {
        Properties properties = new Properties();
        properties.setProperty(KEY_METADATA_SCHEMA_VERSION,
                Integer.toString(CURRENT_METADATA_SCHEMA_VERSION));
        properties.setProperty("id", instance.id());
        properties.setProperty("distroId", instance.distro().id());
        properties.setProperty("distroVersion", instance.distro().version());
        properties.setProperty("rootVolume", instance.rootVolume().toString());
        properties.setProperty("bridgeToken", instance.bridgeToken());
        if (!instance.guestPassword().isEmpty()) {
            properties.setProperty("guestPassword", instance.guestPassword());
        }
        properties.setProperty("runtimeClass", instance.runtimeClass().name());
        properties.setProperty("state", instance.state().name());
        properties.setProperty("createdAtMillis", Long.toString(instance.createdAtMillis()));
        properties.setProperty("updatedAtMillis", Long.toString(instance.updatedAtMillis()));
        properties.setProperty("hostname", instance.hostname());
        properties.setProperty("defaultUser", instance.defaultUser());
        properties.setProperty("hasSudo", Boolean.toString(instance.hasSudo()));
        InstanceLifecycle.CrashDiagnostic crashDiagnostic = instance.crashDiagnostic();
        if (crashDiagnostic != null) {
            properties.setProperty("crash.timestampMillis",
                    Long.toString(crashDiagnostic.timestampMillis()));
            properties.setProperty("crash.exitCode", Integer.toString(crashDiagnostic.exitCode()));
            if (crashDiagnostic.logPath() != null) {
                properties.setProperty("crash.logPath", crashDiagnostic.logPath().toString());
            }
            if (crashDiagnostic.message() != null) {
                properties.setProperty("crash.message", crashDiagnostic.message());
            }
        }
        VmHardwareConfig hardwareConfig = instance.hardwareConfig();
        if (hardwareConfig != null) {
            properties.setProperty("hardware.cpuCores", Integer.toString(hardwareConfig.cpuCores()));
            properties.setProperty("hardware.ramMegabytes", Integer.toString(hardwareConfig.ramMegabytes()));
            properties.setProperty("hardware.diskCapacityBytes", Long.toString(hardwareConfig.diskCapacityBytes()));
            properties.setProperty("hardware.gpuAccelerationEnabled",
                    Boolean.toString(hardwareConfig.gpuAccelerationEnabled()));
            properties.setProperty(KEY_HARDWARE_LEGACY_DEFAULTS,
                    Boolean.toString(hardwareConfig.isLegacyDefaults()));
            if (hardwareConfig.extraKernelArguments() != null) {
                properties.setProperty("hardware.extraKernelArguments",
                        hardwareConfig.extraKernelArguments());
            }
        }
        InstanceLifecycle.ProvisioningEvidence provisioningEvidence = instance.provisioningEvidence();
        if (provisioningEvidence != null) {
            properties.setProperty(
                    KEY_PROVISIONING_MARKER_VERSION,
                    Integer.toString(provisioningEvidence.markerVersion()));
            if (provisioningEvidence.rootfsSha256() != null) {
                properties.setProperty(
                        KEY_PROVISIONING_ROOTFS_SHA256, provisioningEvidence.rootfsSha256());
            }
            properties.setProperty(
                    KEY_PROVISIONING_RECORDED_AT,
                    Long.toString(provisioningEvidence.recordedAtMillis()));
        }
        for (Map.Entry<String, String> entry : instance.unknownMetadata().entrySet()) {
            if (!KNOWN_METADATA_KEYS.contains(entry.getKey())) {
                properties.setProperty(entry.getKey(), entry.getValue());
            }
        }
        return properties;
    }

    /**
     * Parses stored metadata back into an Instance.
     *
     * @throws InstanceMetadataCodec.InvalidMetadataException when any field is malformed,
     *         incomplete, or written by a newer schema
     */
    static InstanceLifecycle.Instance decode(Properties properties) {
        try {
            InstanceLifecycle.CrashDiagnostic diagnostic = null;
            String crashTimestamp = properties.getProperty("crash.timestampMillis");
            if (crashTimestamp != null) {
                long ts = Long.parseLong(crashTimestamp);
                int exitCode = Integer.parseInt(required(properties, "crash.exitCode"));
                String logPathStr = properties.getProperty("crash.logPath");
                Path logPath = logPathStr != null ? Path.of(logPathStr) : null;
                String message = properties.getProperty("crash.message");
                diagnostic = new InstanceLifecycle.CrashDiagnostic(ts, exitCode, logPath, message);
            }
            String guestPassword = properties.getProperty("guestPassword");
            if (guestPassword == null || guestPassword.trim().isEmpty()) {
                // Metadata cannot prove the password installed on an existing root volume.
                // Keep it unknown so upgrades do not silently reset the guest account.
                guestPassword = "";
            }
            String hostname = properties.getProperty("hostname");
            if (hostname == null || hostname.trim().isEmpty()) {
                hostname = InstanceUserConfig.DEFAULT_HOSTNAME;
            }
            String defaultUser = properties.getProperty("defaultUser");
            if (defaultUser == null || defaultUser.trim().isEmpty()) {
                defaultUser = InstanceUserConfig.DEFAULT_ROOT_USER;
            }
            String storedSudo = properties.getProperty("hasSudo", "false");
            if (!"true".equals(storedSudo) && !"false".equals(storedSudo)) {
                throw new IllegalArgumentException("invalid stored sudo choice");
            }
            boolean hasSudo = Boolean.parseBoolean(storedSudo);
            String schemaText = properties.getProperty(KEY_METADATA_SCHEMA_VERSION);
            int schemaVersion = schemaText == null || schemaText.isEmpty()
                    ? 0 : Integer.parseInt(schemaText);
            if (schemaVersion < 0 || schemaVersion > CURRENT_METADATA_SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported metadata schema version");
            }
            String cpuCoresStr = properties.getProperty("hardware.cpuCores");
            boolean hasAnyHardwareKey = properties.stringPropertyNames().stream()
                    .anyMatch(key -> key.startsWith("hardware."));
            VmHardwareConfig hardwareConfig;
            if (cpuCoresStr != null) {
                String ramText = properties.getProperty("hardware.ramMegabytes");
                String diskText = properties.getProperty("hardware.diskCapacityBytes");
                if (ramText == null || ramText.isEmpty() || diskText == null || diskText.isEmpty()) {
                    // A partial block is corrupt: never fill the gaps with silent defaults.
                    throw new IllegalArgumentException("incomplete hardware metadata");
                }
                int cpuCores = Integer.parseInt(cpuCoresStr);
                int ramMb = Integer.parseInt(ramText);
                long diskBytes = Long.parseLong(diskText);
                boolean gpuEnabled = Boolean.parseBoolean(
                        properties.getProperty("hardware.gpuAccelerationEnabled", "true"));
                boolean legacyDefaults = Boolean.parseBoolean(
                        properties.getProperty(KEY_HARDWARE_LEGACY_DEFAULTS, "false"));
                String extraArgs = properties.getProperty("hardware.extraKernelArguments");
                hardwareConfig = new VmHardwareConfig(
                        cpuCores, ramMb, diskBytes, gpuEnabled, extraArgs, legacyDefaults);
            } else if (hasAnyHardwareKey) {
                throw new IllegalArgumentException("incomplete hardware metadata");
            } else if (schemaVersion == 0) {
                // Explicit legacy migration: keep the historical effective hardware
                // and carry provenance instead of presenting it as a user choice.
                hardwareConfig = new VmHardwareConfig(
                        VmHardwareConfig.DEFAULT_CPU_CORES,
                        VmHardwareConfig.DEFAULT_RAM_MEGABYTES,
                        VmHardwareConfig.DEFAULT_DISK_CAPACITY_BYTES,
                        VmHardwareConfig.DEFAULT_GPU_ACCELERATION_ENABLED,
                        null,
                        true);
            } else {
                throw new IllegalArgumentException("hardware metadata missing");
            }
            InstanceLifecycle.ProvisioningEvidence provisioningEvidence = null;
            String markerVersionText = properties.getProperty(KEY_PROVISIONING_MARKER_VERSION);
            String recordedAtText = properties.getProperty(KEY_PROVISIONING_RECORDED_AT);
            if (markerVersionText != null) {
                // Parse the version first so malformed metadata still fails closed;
                // a valid version without its recording time is incomplete evidence
                // and keeps the conservative full-provisioning path.
                int markerVersion = Integer.parseInt(markerVersionText);
                if (recordedAtText != null) {
                    provisioningEvidence = new InstanceLifecycle.ProvisioningEvidence(
                            markerVersion,
                            properties.getProperty(KEY_PROVISIONING_ROOTFS_SHA256),
                            Long.parseLong(recordedAtText));
                }
            }
            Map<String, String> unknownMetadata = new TreeMap<>();
            for (String name : properties.stringPropertyNames()) {
                if (!KNOWN_METADATA_KEYS.contains(name)) {
                    unknownMetadata.put(name, properties.getProperty(name));
                }
            }
            return new InstanceLifecycle.Instance(
                    required(properties, "id"),
                    new InstanceLifecycle.DistroReference(
                            required(properties, "distroId"), required(properties, "distroVersion")),
                    Path.of(required(properties, "rootVolume")),
                    required(properties, "bridgeToken"),
                    guestPassword,
                    FullLinuxContract.RuntimeClass.valueOf(required(properties, "runtimeClass")),
                    InstanceLifecycle.State.valueOf(required(properties, "state")),
                    Long.parseLong(required(properties, "createdAtMillis")),
                    Long.parseLong(required(properties, "updatedAtMillis")),
                    diagnostic,
                    hardwareConfig,
                    hostname,
                    defaultUser,
                    hasSudo,
                    provisioningEvidence,
                    unknownMetadata);
        } catch (IllegalArgumentException malformed) {
            throw new InvalidMetadataException(malformed);
        }
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("missing metadata field");
        }
        return value;
    }

    /** Wraps any malformed stored metadata; never escapes the runtime.instance package. */
    static final class InvalidMetadataException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private InvalidMetadataException(Throwable cause) {
            super(cause);
        }
    }
}
