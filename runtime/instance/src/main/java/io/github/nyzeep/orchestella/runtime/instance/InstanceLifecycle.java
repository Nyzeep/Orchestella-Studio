package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.BridgeHandshake;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Normal Full Linux Instance lifecycle for one live host process.
 *
 * <p>This module deliberately models only Ticket 26's normal path. It has no process-death
 * reconciliation, cold restore, power command, QEMU control, or Android UI behavior.
 *
 * <p>File layout: the Instance state model (states, diagnostics, metadata transitions) lives
 * here; the coordination boundary lives in {@link InstanceLifecycleController}, the properties
 * persistence adapter in {@link InstanceFileStore}, and the metadata (de)serialization in
 * {@link InstanceMetadataCodec}. The nested public types below stay the API surface and
 * extend/delegate to those extracted collaborators, so callers are unaffected.
 */
public final class InstanceLifecycle {
    private static final Pattern DISTRO_ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** Guest marker version whose observation the host is allowed to persist. */
    public static final int PROVISIONING_MARKER_VERSION = 1;
    private static final int BRIDGE_TOKEN_BYTE_COUNT = BridgeHandshake.TOKEN_HEX_LENGTH / 2;
    private static final char[] LOWERCASE_HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom BRIDGE_TOKEN_RANDOM = new SecureRandom();
    public static String newGuestPassword() {
        return InstanceUserConfig.newPassword();
    }

    private InstanceLifecycle() {}

    public enum State {
        CREATED,
        PROVISIONING,
        STOPPED,
        RUNNING,
        INTERRUPTED,
        CRASHED,
        SUSPENDED
    }

    /** Structured diagnostic information for an abnormal termination or VMM crash. */
    public static final class CrashDiagnostic {
        private final long timestampMillis;
        private final int exitCode;
        private final Path logPath;
        private final String message;

        public CrashDiagnostic(long timestampMillis, int exitCode, Path logPath, String message) {
            this.timestampMillis = timestampMillis;
            this.exitCode = exitCode;
            this.logPath = logPath != null ? logPath.toAbsolutePath().normalize() : null;
            this.message = message;
        }

        public long timestampMillis() {
            return timestampMillis;
        }

        public int exitCode() {
            return exitCode;
        }

        public Path logPath() {
            return logPath;
        }

        public String message() {
            return message;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            CrashDiagnostic that = (CrashDiagnostic) o;
            return timestampMillis == that.timestampMillis
                    && exitCode == that.exitCode
                    && Objects.equals(logPath, that.logPath)
                    && Objects.equals(message, that.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(timestampMillis, exitCode, logPath, message);
        }
    }

    /** Product catalog identity, distinct from a root-volume image and from an Instance. */
    public static final class DistroReference {
        private final String id;
        private final String version;

        public DistroReference(String id, String version) {
            if (id == null || !DISTRO_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("invalid distro id");
            }
            if (version == null || version.trim().isEmpty()) {
                throw new IllegalArgumentException("distro version must not be blank");
            }
            this.id = id;
            this.version = version;
        }

        public String id() {
            return id;
        }

        public String version() {
            return version;
        }
    }

    /**
     * Evidence that a guest observed the one-time Provisioning marker.
     *
     * <p>It deliberately carries no token, password, key, or terminal content: only the marker
     * contract version, the distro rootfs SHA-256 the instance was created from (nullable when
     * the host could not resolve it), and when the host recorded the fact.
     */
    public static final class ProvisioningEvidence {
        private final int markerVersion;
        private final String rootfsSha256;
        private final long recordedAtMillis;

        public ProvisioningEvidence(int markerVersion, String rootfsSha256, long recordedAtMillis) {
            if (markerVersion <= 0) {
                throw new IllegalArgumentException("markerVersion must be positive");
            }
            if (rootfsSha256 != null && !SHA256.matcher(rootfsSha256).matches()) {
                throw new IllegalArgumentException("invalid rootfs sha256");
            }
            if (recordedAtMillis < 0) {
                throw new IllegalArgumentException("recordedAtMillis must not be negative");
            }
            this.markerVersion = markerVersion;
            this.rootfsSha256 = rootfsSha256;
            this.recordedAtMillis = recordedAtMillis;
        }

        public int markerVersion() {
            return markerVersion;
        }

        /** Expected distro rootfs digest, or {@code null} when it could not be resolved. */
        public String rootfsSha256() {
            return rootfsSha256;
        }

        public long recordedAtMillis() {
            return recordedAtMillis;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ProvisioningEvidence that = (ProvisioningEvidence) o;
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
            return "ProvisioningEvidence{markerVersion=" + markerVersion
                    + ", rootfsSha256=" + rootfsSha256
                    + ", recordedAtMillis=" + recordedAtMillis + '}';
        }
    }

    /** Persistent Instance metadata; a Session is intentionally not represented here. */
    public static final class Instance {
        private final String id;
        private final DistroReference distro;
        private final Path rootVolume;
        private final String bridgeToken;
        private final String guestPassword;
        private final FullLinuxContract.RuntimeClass runtimeClass;
        private final State state;
        private final long createdAtMillis;
        private final long updatedAtMillis;
        private final CrashDiagnostic crashDiagnostic;
        private final VmHardwareConfig hardwareConfig;
        private final String hostname;
        private final String defaultUser;
        private final boolean hasSudo;
        private final ProvisioningEvidence provisioningEvidence;
        private final Map<String, String> unknownMetadata;

        private Instance(
                String id,
                DistroReference distro,
                Path rootVolume,
                String bridgeToken,
                String guestPassword,
                FullLinuxContract.RuntimeClass runtimeClass,
                State state,
                long createdAtMillis,
                long updatedAtMillis,
                CrashDiagnostic crashDiagnostic) {
            this(id, distro, rootVolume, bridgeToken, guestPassword, runtimeClass, state, createdAtMillis, updatedAtMillis, crashDiagnostic, VmHardwareConfig.defaultConfig(), InstanceUserConfig.DEFAULT_HOSTNAME, InstanceUserConfig.DEFAULT_ROOT_USER);
        }

        private Instance(
                String id,
                DistroReference distro,
                Path rootVolume,
                String bridgeToken,
                String guestPassword,
                FullLinuxContract.RuntimeClass runtimeClass,
                State state,
                long createdAtMillis,
                long updatedAtMillis,
                CrashDiagnostic crashDiagnostic,
                VmHardwareConfig hardwareConfig) {
            this(id, distro, rootVolume, bridgeToken, guestPassword, runtimeClass, state, createdAtMillis, updatedAtMillis, crashDiagnostic, hardwareConfig, InstanceUserConfig.DEFAULT_HOSTNAME, InstanceUserConfig.DEFAULT_ROOT_USER);
        }

        private Instance(
                String id,
                DistroReference distro,
                Path rootVolume,
                String bridgeToken,
                String guestPassword,
                FullLinuxContract.RuntimeClass runtimeClass,
                State state,
                long createdAtMillis,
                long updatedAtMillis,
                CrashDiagnostic crashDiagnostic,
                VmHardwareConfig hardwareConfig,
                String hostname,
                String defaultUser) {
            this(id, distro, rootVolume, bridgeToken, guestPassword, runtimeClass, state,
                    createdAtMillis, updatedAtMillis, crashDiagnostic, hardwareConfig,
                    hostname, defaultUser, false, null);
        }

        Instance(
                String id,
                DistroReference distro,
                Path rootVolume,
                String bridgeToken,
                String guestPassword,
                FullLinuxContract.RuntimeClass runtimeClass,
                State state,
                long createdAtMillis,
                long updatedAtMillis,
                CrashDiagnostic crashDiagnostic,
                VmHardwareConfig hardwareConfig,
                String hostname,
                String defaultUser,
                boolean hasSudo,
                ProvisioningEvidence provisioningEvidence) {
            this(id, distro, rootVolume, bridgeToken, guestPassword, runtimeClass, state,
                    createdAtMillis, updatedAtMillis, crashDiagnostic, hardwareConfig,
                    hostname, defaultUser, hasSudo, provisioningEvidence, Collections.emptyMap());
        }

        Instance(
                String id,
                DistroReference distro,
                Path rootVolume,
                String bridgeToken,
                String guestPassword,
                FullLinuxContract.RuntimeClass runtimeClass,
                State state,
                long createdAtMillis,
                long updatedAtMillis,
                CrashDiagnostic crashDiagnostic,
                VmHardwareConfig hardwareConfig,
                String hostname,
                String defaultUser,
                boolean hasSudo,
                ProvisioningEvidence provisioningEvidence,
                Map<String, String> unknownMetadata) {
            requireInstanceId(id);
            this.id = id;
            this.distro = Objects.requireNonNull(distro, "distro");
            this.rootVolume = Objects.requireNonNull(rootVolume, "rootVolume").normalize();
            this.bridgeToken = requireCanonicalBridgeToken(bridgeToken);
            this.guestPassword = Objects.requireNonNull(guestPassword, "guestPassword");
            this.runtimeClass = Objects.requireNonNull(runtimeClass, "runtimeClass");
            this.state = Objects.requireNonNull(state, "state");
            this.createdAtMillis = createdAtMillis;
            this.updatedAtMillis = updatedAtMillis;
            this.crashDiagnostic = crashDiagnostic;
            this.hardwareConfig = hardwareConfig != null ? hardwareConfig : VmHardwareConfig.defaultConfig();
            this.hostname = InstanceUserConfig.normalizeHostname(hostname);
            this.defaultUser = defaultUser == null || defaultUser.trim().isEmpty()
                    ? InstanceUserConfig.DEFAULT_ROOT_USER : defaultUser;
            if (!InstanceUserConfig.isValidUsername(this.defaultUser)) {
                throw new IllegalArgumentException("invalid guest username");
            }
            this.hasSudo = hasSudo;
            this.provisioningEvidence = provisioningEvidence;
            this.unknownMetadata = Collections.unmodifiableMap(
                    new TreeMap<>(Objects.requireNonNull(unknownMetadata, "unknownMetadata")));
        }

        public String id() {
            return id;
        }

        public DistroReference distro() {
            return distro;
        }

        public Path rootVolume() {
            return rootVolume;
        }

        /**
         * Internal bridge credential. Visible to the host launcher so the token
         * can reach the guest's embedded provisioning input and the control
         * handshake; callers must not log it or surface it in the UI.
         */
        public String bridgeToken() {
            return bridgeToken;
        }

        /**
         * Per-instance random guest user/root credential (ADR-0010).
         * User-visible and modifiable; must never be logged. An empty value marks an
         * unknown legacy/imported credential and must not trigger a guest password reset.
         */
        public String guestPassword() {
            return guestPassword;
        }

        public String hostname() {
            return hostname;
        }

        public String defaultUser() {
            return defaultUser;
        }

        /** Persisted explicit sudo choice; absent legacy metadata never grants sudo. */
        public boolean hasSudo() {
            return hasSudo;
        }

        /**
         * Whether guest Provisioning completion was observed and recorded for the supported marker
         * version. Legacy or imported metadata without evidence reads as not provisioned.
         */
        public boolean isProvisioned() {
            return provisioningEvidence != null
                    && provisioningEvidence.markerVersion() == PROVISIONING_MARKER_VERSION;
        }

        /** Recorded evidence, or {@code null} when no supported marker was ever observed. */
        public ProvisioningEvidence provisioningEvidence() {
            return provisioningEvidence;
        }

        /**
         * Whether this instance is provisioned from exactly the supplied rootfs digest.
         *
         * <p>False when evidence is missing, the marker version is unsupported, or the recorded
         * digest is absent or differs. Callers must treat false as "use the full provisioning
         * path".
         */
        public boolean isProvisionedFor(String currentRootfsSha256) {
            return isProvisioned()
                    && currentRootfsSha256 != null
                    && currentRootfsSha256.equals(provisioningEvidence.rootfsSha256());
        }

        public FullLinuxContract.RuntimeClass runtimeClass() {
            return runtimeClass;
        }

        public State state() {
            return state;
        }

        public long createdAtMillis() {
            return createdAtMillis;
        }

        public long updatedAtMillis() {
            return updatedAtMillis;
        }

        public CrashDiagnostic crashDiagnostic() {
            return crashDiagnostic;
        }

        public VmHardwareConfig hardwareConfig() {
            return hardwareConfig;
        }

        public Path diagnosticLogPath() {
            return crashDiagnostic != null ? crashDiagnostic.logPath() : null;
        }

        /** Package-private read access for {@link InstanceMetadataCodec#encode(Instance)}. */
        Map<String, String> unknownMetadata() {
            return unknownMetadata;
        }

        Instance transitioned(State next, Clock clock) {
            return transitioned(next, clock, null);
        }

        Instance transitioned(State next, Clock clock, CrashDiagnostic diagnostic) {
            if (!isValidTransition(state, next)) {
                throw new InvalidTransitionException(state + " -> " + next + " is not a valid transition");
            }
            return new Instance(
                    id,
                    distro,
                    rootVolume,
                    bridgeToken,
                    guestPassword,
                    runtimeClass,
                    next,
                    createdAtMillis,
                    Objects.requireNonNull(clock, "clock").millis(),
                    diagnostic,
                    hardwareConfig,
                    hostname,
                    defaultUser,
                    hasSudo,
                    provisioningEvidence,
                    unknownMetadata);
        }

        Instance withUpdatedAt(Clock clock) {
            return new Instance(
                    id,
                    distro,
                    rootVolume,
                    bridgeToken,
                    guestPassword,
                    runtimeClass,
                    state,
                    createdAtMillis,
                    Objects.requireNonNull(clock, "clock").millis(),
                    crashDiagnostic,
                    hardwareConfig,
                    hostname,
                    defaultUser,
                    hasSudo,
                    provisioningEvidence,
                    unknownMetadata);
        }

        Instance withGuestPassword(String newPassword, Clock clock) {
            return new Instance(
                    id,
                    distro,
                    rootVolume,
                    bridgeToken,
                    newPassword,
                    runtimeClass,
                    state,
                    createdAtMillis,
                    Objects.requireNonNull(clock, "clock").millis(),
                    crashDiagnostic,
                    hardwareConfig,
                    hostname,
                    defaultUser,
                    hasSudo,
                    provisioningEvidence,
                    unknownMetadata);
        }

        Instance withHardwareConfig(VmHardwareConfig newConfig, Clock clock) {
            return new Instance(
                    id,
                    distro,
                    rootVolume,
                    bridgeToken,
                    guestPassword,
                    runtimeClass,
                    state,
                    createdAtMillis,
                    Objects.requireNonNull(clock, "clock").millis(),
                    crashDiagnostic,
                    Objects.requireNonNull(newConfig, "newConfig"),
                    hostname,
                    defaultUser,
                    hasSudo,
                    provisioningEvidence,
                    unknownMetadata);
        }

        Instance withProvisioningEvidence(ProvisioningEvidence evidence, Clock clock) {
            return new Instance(
                    id,
                    distro,
                    rootVolume,
                    bridgeToken,
                    guestPassword,
                    runtimeClass,
                    state,
                    createdAtMillis,
                    Objects.requireNonNull(clock, "clock").millis(),
                    crashDiagnostic,
                    hardwareConfig,
                    hostname,
                    defaultUser,
                    hasSudo,
                    Objects.requireNonNull(evidence, "evidence"),
                    unknownMetadata);
        }

        static Instance restore(
                String id,
                DistroReference distro,
                Path rootVolume,
                FullLinuxContract.RuntimeClass runtimeClass,
                State state,
                VmHardwareConfig hardwareConfig,
                Clock clock) {
            return restore(id, distro, rootVolume, runtimeClass, state, hardwareConfig,
                    InstanceUserConfig.DEFAULT_HOSTNAME, InstanceUserConfig.DEFAULT_ROOT_USER, clock);
        }

        static Instance restore(
                String id,
                DistroReference distro,
                Path rootVolume,
                FullLinuxContract.RuntimeClass runtimeClass,
                State state,
                VmHardwareConfig hardwareConfig,
                String hostname,
                String defaultUser,
                Clock clock) {
            return restore(id, distro, rootVolume, runtimeClass, state, hardwareConfig,
                    hostname, defaultUser, false, null, clock);
        }

        /**
         * Restores an imported instance while preserving the user-facing configuration and
         * observed provisioning evidence carried by the export.
         *
         * <p>The guest password deliberately stays empty: the imported root volume keeps whatever
         * credential the guest already has, and metadata cannot prove it. A fresh bridge token is
         * always generated. Preserving provisioning evidence is required so an imported,
         * already-provisioned root volume is not provisioned again (which could overwrite it).
         */
        static Instance restore(
                String id,
                DistroReference distro,
                Path rootVolume,
                FullLinuxContract.RuntimeClass runtimeClass,
                State state,
                VmHardwareConfig hardwareConfig,
                String hostname,
                String defaultUser,
                boolean hasSudo,
                ProvisioningEvidence provisioningEvidence,
                Clock clock) {
            long now = Objects.requireNonNull(clock, "clock").millis();
            return new Instance(
                    id,
                    distro,
                    rootVolume,
                    newBridgeToken(),
                    "", // Imported root volumes retain their existing, unknown guest credential.
                    runtimeClass != null ? runtimeClass : FullLinuxContract.RuntimeClass.FULL_LINUX,
                    state != null ? state : State.STOPPED,
                    now,
                    now,
                    null,
                    hardwareConfig != null ? hardwareConfig : VmHardwareConfig.defaultConfig(),
                    hostname,
                    defaultUser,
                    hasSudo,
                    provisioningEvidence);
        }

        Properties toProperties() {
            return InstanceMetadataCodec.encode(this);
        }

        static Instance fromProperties(Properties properties) {
            return InstanceMetadataCodec.decode(properties);
        }
    }

    public interface Store {
        void save(Instance instance) throws IOException;

        Instance load(String id) throws IOException;

        List<Instance> list() throws IOException;

        void delete(String id) throws IOException;

        /**
         * Monitor shared by every Store instance backed by the same state data. Callers must hold
         * it across read-modify-write sequences; custom stores default to instance-local locking.
         */
        default Object stateMonitor() {
            return this;
        }
    }

    /**
     * Small app-private persistence adapter. The caller owns the directory choice and must pass an
     * Android private-files path in production.
     *
     * <p>Implementation lives in {@link InstanceFileStore}; this nested type keeps the public
     * {@code InstanceLifecycle.FileStore} API surface unchanged.
     */
    public static final class FileStore extends InstanceFileStore {
        public FileStore(Path directory) throws IOException {
            super(directory);
        }
    }

    /** Normal-path coordinator; caller must keep it in one live host process. */
    public static final class Controller extends InstanceLifecycleController {
        public Controller(Store store, Clock clock) {
            super(store, clock);
        }
    }

    public static class InvalidTransitionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public InvalidTransitionException(String message) {
            super(message);
        }
    }

    public static final class SingleRunningInstanceException extends InvalidTransitionException {
        private static final long serialVersionUID = 1L;

        public SingleRunningInstanceException(String message) {
            super(message);
        }
    }

    public static boolean isNormalTransition(State from, State to) {
        return (from == State.CREATED && to == State.PROVISIONING)
                || (from == State.PROVISIONING && to == State.STOPPED)
                || (from == State.STOPPED && to == State.RUNNING)
                || (from == State.RUNNING && to == State.STOPPED);
    }

    private static boolean isValidTransition(State from, State to) {
        if (isNormalTransition(from, to)) {
            return true;
        }
        if (from == State.RUNNING && to == State.INTERRUPTED) {
            return true;
        }
        if (from == State.RUNNING && to == State.CRASHED) {
            return true;
        }
        if (from == State.INTERRUPTED && to == State.STOPPED) {
            return true;
        }
        if (from == State.INTERRUPTED && to == State.RUNNING) {
            return true;
        }
        if (from == State.CRASHED && to == State.STOPPED) {
            return true;
        }
        if (from == State.CRASHED && to == State.RUNNING) {
            return true;
        }
        if (from == State.RUNNING && to == State.SUSPENDED) {
            return true;
        }
        if (from == State.SUSPENDED && to == State.RUNNING) {
            return true;
        }
        if (from == State.SUSPENDED && to == State.STOPPED) {
            return true;
        }
        if (from == State.SUSPENDED && to == State.INTERRUPTED) {
            return true;
        }
        if (from == State.SUSPENDED && to == State.CRASHED) {
            return true;
        }
        return false;
    }

    static String newBridgeToken() {
        byte[] bytes = new byte[BRIDGE_TOKEN_BYTE_COUNT];
        BRIDGE_TOKEN_RANDOM.nextBytes(bytes);
        char[] hexadecimal = new char[BridgeHandshake.TOKEN_HEX_LENGTH];
        for (int index = 0; index < bytes.length; index++) {
            int value = Byte.toUnsignedInt(bytes[index]);
            hexadecimal[index * 2] = LOWERCASE_HEX[value >>> 4];
            hexadecimal[index * 2 + 1] = LOWERCASE_HEX[value & 0x0f];
        }
        return new String(hexadecimal);
    }

    private static String requireCanonicalBridgeToken(String token) {
        if (!BridgeHandshake.isCanonicalToken(token)) {
            throw new IllegalArgumentException("invalid bridge token");
        }
        return token;
    }

    static void requireInstanceId(String id) {
        try {
            UUID.fromString(Objects.requireNonNull(id, "id"));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("invalid instance id", malformed);
        }
    }
}
