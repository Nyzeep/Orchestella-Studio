package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.BridgeHandshake;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Normal Full Linux Instance lifecycle for one live host process.
 *
 * <p>This module deliberately models only Ticket 26's normal path. It has no process-death
 * reconciliation, cold restore, power command, QEMU control, or Android UI behavior.
 */
public final class InstanceLifecycle {
    private static final Pattern DISTRO_ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final int BRIDGE_TOKEN_BYTE_COUNT = BridgeHandshake.TOKEN_HEX_LENGTH / 2;
    private static final char[] LOWERCASE_HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom BRIDGE_TOKEN_RANDOM = new SecureRandom();
    private static final char[] PASSWORD_CHARS =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final int GUEST_PASSWORD_LENGTH = 16;

    public static String newGuestPassword() {
        char[] chars = new char[GUEST_PASSWORD_LENGTH];
        for (int i = 0; i < GUEST_PASSWORD_LENGTH; i++) {
            chars[i] = PASSWORD_CHARS[BRIDGE_TOKEN_RANDOM.nextInt(PASSWORD_CHARS.length)];
        }
        return new String(chars);
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

        private Instance(
                String id,
                DistroReference distro,
                Path rootVolume,
                String bridgeToken,
                FullLinuxContract.RuntimeClass runtimeClass,
                State state,
                long createdAtMillis,
                long updatedAtMillis) {
            this(id, distro, rootVolume, bridgeToken, InstanceUserConfig.DEFAULT_ROOT_PASSWORD, runtimeClass, state, createdAtMillis, updatedAtMillis, null, VmHardwareConfig.defaultConfig(), InstanceUserConfig.DEFAULT_HOSTNAME, InstanceUserConfig.DEFAULT_ROOT_USER);
        }

        private Instance(
                String id,
                DistroReference distro,
                Path rootVolume,
                String bridgeToken,
                FullLinuxContract.RuntimeClass runtimeClass,
                State state,
                long createdAtMillis,
                long updatedAtMillis,
                CrashDiagnostic crashDiagnostic) {
            this(id, distro, rootVolume, bridgeToken, InstanceUserConfig.DEFAULT_ROOT_PASSWORD, runtimeClass, state, createdAtMillis, updatedAtMillis, crashDiagnostic, VmHardwareConfig.defaultConfig(), InstanceUserConfig.DEFAULT_HOSTNAME, InstanceUserConfig.DEFAULT_ROOT_USER);
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
            this.hostname = (hostname != null && !hostname.trim().isEmpty())
                    ? hostname.trim().toLowerCase()
                    : InstanceUserConfig.DEFAULT_HOSTNAME;
            this.defaultUser = (defaultUser != null && !defaultUser.trim().isEmpty())
                    ? defaultUser.trim()
                    : InstanceUserConfig.DEFAULT_ROOT_USER;
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
         * User-visible and modifiable; never hardcoded or constant.
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

        private Instance transitioned(State next, Clock clock) {
            return transitioned(next, clock, null);
        }

        private Instance transitioned(State next, Clock clock, CrashDiagnostic diagnostic) {
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
                    defaultUser);
        }

        private Instance withUpdatedAt(Clock clock) {
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
                    defaultUser);
        }

        private Instance withGuestPassword(String newPassword, Clock clock) {
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
                    defaultUser);
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
                    defaultUser);
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
            long now = Objects.requireNonNull(clock, "clock").millis();
            return new Instance(
                    id,
                    distro,
                    rootVolume,
                    newBridgeToken(),
                    InstanceUserConfig.DEFAULT_ROOT_PASSWORD,
                    runtimeClass != null ? runtimeClass : FullLinuxContract.RuntimeClass.FULL_LINUX,
                    state != null ? state : State.STOPPED,
                    now,
                    now,
                    null,
                    hardwareConfig != null ? hardwareConfig : VmHardwareConfig.defaultConfig(),
                    hostname,
                    defaultUser);
        }

        private Properties toProperties() {
            Properties properties = new Properties();
            properties.setProperty("id", id);
            properties.setProperty("distroId", distro.id());
            properties.setProperty("distroVersion", distro.version());
            properties.setProperty("rootVolume", rootVolume.toString());
            properties.setProperty("bridgeToken", bridgeToken);
            properties.setProperty("guestPassword", guestPassword);
            properties.setProperty("runtimeClass", runtimeClass.name());
            properties.setProperty("state", state.name());
            properties.setProperty("createdAtMillis", Long.toString(createdAtMillis));
            properties.setProperty("updatedAtMillis", Long.toString(updatedAtMillis));
            properties.setProperty("hostname", hostname);
            properties.setProperty("defaultUser", defaultUser);
            if (crashDiagnostic != null) {
                properties.setProperty("crash.timestampMillis", Long.toString(crashDiagnostic.timestampMillis()));
                properties.setProperty("crash.exitCode", Integer.toString(crashDiagnostic.exitCode()));
                if (crashDiagnostic.logPath() != null) {
                    properties.setProperty("crash.logPath", crashDiagnostic.logPath().toString());
                }
                if (crashDiagnostic.message() != null) {
                    properties.setProperty("crash.message", crashDiagnostic.message());
                }
            }
            if (hardwareConfig != null) {
                properties.setProperty("hardware.cpuCores", Integer.toString(hardwareConfig.cpuCores()));
                properties.setProperty("hardware.ramMegabytes", Integer.toString(hardwareConfig.ramMegabytes()));
                properties.setProperty("hardware.diskCapacityBytes", Long.toString(hardwareConfig.diskCapacityBytes()));
                properties.setProperty("hardware.gpuAccelerationEnabled", Boolean.toString(hardwareConfig.gpuAccelerationEnabled()));
                if (hardwareConfig.extraKernelArguments() != null) {
                    properties.setProperty("hardware.extraKernelArguments", hardwareConfig.extraKernelArguments());
                }
            }
            return properties;
        }

        private static Instance fromProperties(Properties properties) {
            try {
                CrashDiagnostic diagnostic = null;
                String crashTimestamp = properties.getProperty("crash.timestampMillis");
                if (crashTimestamp != null) {
                    long ts = Long.parseLong(crashTimestamp);
                    int exitCode = Integer.parseInt(required(properties, "crash.exitCode"));
                    String logPathStr = properties.getProperty("crash.logPath");
                    Path logPath = logPathStr != null ? Path.of(logPathStr) : null;
                    String message = properties.getProperty("crash.message");
                    diagnostic = new CrashDiagnostic(ts, exitCode, logPath, message);
                }
                String guestPassword = properties.getProperty("guestPassword");
                if (guestPassword == null || guestPassword.trim().isEmpty()) {
                    guestPassword = InstanceUserConfig.DEFAULT_ROOT_PASSWORD;
                }
                String hostname = properties.getProperty("hostname");
                if (hostname == null || hostname.trim().isEmpty()) {
                    hostname = InstanceUserConfig.DEFAULT_HOSTNAME;
                }
                String defaultUser = properties.getProperty("defaultUser");
                if (defaultUser == null || defaultUser.trim().isEmpty()) {
                    defaultUser = InstanceUserConfig.DEFAULT_ROOT_USER;
                }
                VmHardwareConfig hardwareConfig = VmHardwareConfig.defaultConfig();
                String cpuCoresStr = properties.getProperty("hardware.cpuCores");
                if (cpuCoresStr != null) {
                    int cpuCores = Integer.parseInt(cpuCoresStr);
                    int ramMb = Integer.parseInt(required(properties, "hardware.ramMegabytes"));
                    long diskBytes = Long.parseLong(required(properties, "hardware.diskCapacityBytes"));
                    boolean gpuEnabled = Boolean.parseBoolean(properties.getProperty("hardware.gpuAccelerationEnabled", "true"));
                    String extraArgs = properties.getProperty("hardware.extraKernelArguments");
                    hardwareConfig = new VmHardwareConfig(cpuCores, ramMb, diskBytes, gpuEnabled, extraArgs);
                }
                return new Instance(
                        required(properties, "id"),
                        new DistroReference(
                                required(properties, "distroId"), required(properties, "distroVersion")),
                        Path.of(required(properties, "rootVolume")),
                        required(properties, "bridgeToken"),
                        guestPassword,
                        FullLinuxContract.RuntimeClass.valueOf(required(properties, "runtimeClass")),
                        State.valueOf(required(properties, "state")),
                        Long.parseLong(required(properties, "createdAtMillis")),
                        Long.parseLong(required(properties, "updatedAtMillis")),
                        diagnostic,
                        hardwareConfig,
                        hostname,
                        defaultUser);
            } catch (IllegalArgumentException malformed) {
                throw new InvalidMetadataException(malformed);
            }
        }
    }

    public interface Store {
        void save(Instance instance) throws IOException;

        Instance load(String id) throws IOException;

        List<Instance> list() throws IOException;

        void delete(String id) throws IOException;
    }

    /**
     * Small app-private persistence adapter. The caller owns the directory choice and must pass an
     * Android private-files path in production.
     */
    public static final class FileStore implements Store {
        private final Path directory;

        public FileStore(Path directory) throws IOException {
            this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
            Files.createDirectories(this.directory);
        }

        @Override
        public synchronized void save(Instance instance) throws IOException {
            Objects.requireNonNull(instance, "instance");
            Path target = metadataPath(instance.id());
            Path temporary = directory.resolve(instance.id() + ".properties.tmp");
            Properties properties = instance.toProperties();
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Orchestella Instance metadata");
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public synchronized Instance load(String id) throws IOException {
            Path target = metadataPath(id);
            if (!Files.isRegularFile(target)) {
                throw new IOException("instance metadata not found");
            }
            return read(target);
        }

        @Override
        public synchronized List<Instance> list() throws IOException {
            List<Instance> instances = new ArrayList<>();
            try (var entries = Files.list(directory)) {
                entries.filter(path -> path.getFileName().toString().endsWith(".properties"))
                        .sorted()
                        .forEach(
                                path -> {
                                    try {
                                        instances.add(read(path));
                                    } catch (IOException exception) {
                                        throw new ReadFailure(exception);
                                    }
                                });
            } catch (ReadFailure failure) {
                throw failure.cause;
            }
            instances.sort(Comparator.comparing(Instance::id));
            return Collections.unmodifiableList(instances);
        }

        @Override
        public synchronized void delete(String id) throws IOException {
            requireInstanceId(id);
            Path target = metadataPath(id);
            if (!Files.isRegularFile(target)) {
                throw new NoSuchFileException("instance metadata not found: " + id);
            }
            Instance instance = read(target);
            State state = instance.state();
            if (state == State.RUNNING || state == State.PROVISIONING || state == State.SUSPENDED) {
                throw new IllegalStateException("Cannot delete instance while " + state);
            }
            if (state != State.STOPPED && state != State.CREATED && state != State.CRASHED) {
                throw new IllegalStateException("Cannot delete instance in state " + state);
            }

            if (instance.rootVolume() != null) {
                Files.deleteIfExists(instance.rootVolume());
            }

            Files.deleteIfExists(directory.resolve(id + ".raw"));
            Files.deleteIfExists(directory.resolve(id + ".swap"));
            Files.deleteIfExists(directory.resolve(id + ".tmp"));
            Files.deleteIfExists(directory.resolve(id + ".properties.tmp"));
            if (directory.getParent() != null) {
                Path rootVolumesDir = directory.getParent().resolve("root-volumes");
                Files.deleteIfExists(rootVolumesDir.resolve(id + ".raw"));
            }

            if (instance.crashDiagnostic() != null && instance.crashDiagnostic().logPath() != null) {
                Files.deleteIfExists(instance.crashDiagnostic().logPath());
            }

            try (var entries = Files.list(directory)) {
                entries.filter(p -> {
                    String name = p.getFileName().toString();
                    return (name.startsWith(id + ".") || name.startsWith(id + "-")) && !name.equals(id + ".properties");
                }).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });
            }

            Files.deleteIfExists(target);
        }

        private Instance read(Path path) throws IOException {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
            try {
                return Instance.fromProperties(properties);
            } catch (InvalidMetadataException malformed) {
                throw new IOException("invalid instance metadata", malformed.getCause());
            }
        }

        private Path metadataPath(String id) {
            requireInstanceId(id);
            return directory.resolve(id + ".properties");
        }

        private static final class ReadFailure extends RuntimeException {
            private static final long serialVersionUID = 1L;

            private final IOException cause;

            private ReadFailure(IOException cause) {
                this.cause = cause;
            }
        }
    }

    /** Normal-path coordinator; caller must keep it in one live host process. */
    public static final class Controller {
        private final Store store;
        private final Clock clock;

        public Controller(Store store, Clock clock) {
            this.store = Objects.requireNonNull(store, "store");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        public synchronized void delete(String id) throws IOException {
            store.delete(id);
        }

        public synchronized Instance create(DistroReference distro, Path rootVolume) throws IOException {
            return create(distro, rootVolume, VmHardwareConfig.defaultConfig(), InstanceUserConfig.defaultRoot());
        }

        public synchronized Instance create(
                DistroReference distro, Path rootVolume, VmHardwareConfig hardwareConfig) throws IOException {
            return create(distro, rootVolume, hardwareConfig, InstanceUserConfig.defaultRoot());
        }

        public synchronized Instance create(
                DistroReference distro,
                Path rootVolume,
                VmHardwareConfig hardwareConfig,
                InstanceUserConfig userConfig) throws IOException {
            Objects.requireNonNull(distro, "distro");
            Objects.requireNonNull(rootVolume, "rootVolume");
            Objects.requireNonNull(hardwareConfig, "hardwareConfig");
            InstanceUserConfig effectiveConfig = userConfig != null ? userConfig : InstanceUserConfig.defaultRoot();
            long now = clock.millis();
            Instance instance =
                    new Instance(
                            UUID.randomUUID().toString(),
                            distro,
                            rootVolume,
                            newBridgeToken(),
                            effectiveConfig.password(),
                            FullLinuxContract.RuntimeClass.FULL_LINUX,
                            State.CREATED,
                            now,
                            now,
                            null,
                            hardwareConfig,
                            effectiveConfig.hostname(),
                            effectiveConfig.username());
            store.save(instance);
            return instance;
        }

        /**
         * Clones an existing instance into a new independent instance, duplicating its configuration
         * and physical root-volume disk image.
         *
         * @param sourceId the id of the source instance to clone
         * @param targetRootVolume the path for the new instance's root volume
         * @return the new cloned Instance in CREATED state
         * @throws IOException on I/O error copying disk
         * @throws IllegalStateException if source instance is RUNNING or PROVISIONING
         */
        public synchronized Instance cloneInstance(String sourceId, Path targetRootVolume) throws IOException {
            Objects.requireNonNull(targetRootVolume, "targetRootVolume");
            Instance source = store.load(sourceId);
            if (source.state() != State.STOPPED && source.state() != State.CREATED) {
                throw new IllegalStateException(
                        "cannot clone instance in state: " + source.state() + "; instance must be STOPPED or CREATED");
            }
            if (targetRootVolume.getParent() != null) {
                Files.createDirectories(targetRootVolume.getParent());
            }
            if (source.rootVolume() != null && Files.isRegularFile(source.rootVolume())) {
                Files.copy(source.rootVolume(), targetRootVolume, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            } else if (!Files.exists(targetRootVolume)) {
                Files.createFile(targetRootVolume);
            }
            long now = clock.millis();
            Instance cloned =
                    new Instance(
                            UUID.randomUUID().toString(),
                            source.distro(),
                            targetRootVolume,
                            newBridgeToken(),
                            source.guestPassword(),
                            source.runtimeClass(),
                            State.CREATED,
                            now,
                            now,
                            null,
                            source.hardwareConfig(),
                            source.hostname(),
                            source.defaultUser());
            store.save(cloned);
            return cloned;
        }

        /**
         * Convenience method to clone an instance, automatically placing the new root volume
         * beside the original with the new instance ID.
         */
        public synchronized Instance cloneInstance(String sourceId) throws IOException {
            Instance source = store.load(sourceId);
            String newId = UUID.randomUUID().toString();
            Path sourceVolume = source.rootVolume();
            Path targetVolume;
            if (sourceVolume != null && sourceVolume.getParent() != null) {
                String fileName = sourceVolume.getFileName().toString();
                String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : ".raw";
                targetVolume = sourceVolume.getParent().resolve(newId + ext);
            } else {
                targetVolume = Path.of(newId + ".raw");
            }
            if (source.state() != State.STOPPED && source.state() != State.CREATED) {
                throw new IllegalStateException(
                        "cannot clone instance in state: " + source.state() + "; instance must be STOPPED or CREATED");
            }
            if (targetVolume.getParent() != null) {
                Files.createDirectories(targetVolume.getParent());
            }
            if (sourceVolume != null && Files.isRegularFile(sourceVolume)) {
                Files.copy(sourceVolume, targetVolume, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            } else if (!Files.exists(targetVolume)) {
                Files.createFile(targetVolume);
            }
            long now = clock.millis();
            Instance cloned =
                    new Instance(
                            newId,
                            source.distro(),
                            targetVolume,
                            newBridgeToken(),
                            source.guestPassword(),
                            source.runtimeClass(),
                            State.CREATED,
                            now,
                            now,
                            null,
                            source.hardwareConfig(),
                            source.hostname(),
                            source.defaultUser());
            store.save(cloned);
            return cloned;
        }

        public synchronized Instance importFromArchive(
                InputStream archiveInputStream,
                Path targetRootVolume) throws IOException {
            return InstanceStorageManager.importFromArchive(archiveInputStream, targetRootVolume, store, clock);
        }

        public synchronized Instance importFromArchive(
                InputStream archiveInputStream,
                Path targetRootVolume,
                String targetInstanceId) throws IOException {
            return InstanceStorageManager.importFromArchive(archiveInputStream, targetRootVolume, targetInstanceId, store, clock);
        }

        public synchronized Instance updateHardwareConfig(String id, VmHardwareConfig newConfig) throws IOException {
            Objects.requireNonNull(newConfig, "newConfig");
            Instance current = store.load(id);
            if (current.state() != State.STOPPED && current.state() != State.CREATED) {
                throw new IllegalStateException("cannot update hardware config while in state: " + current.state());
            }
            Instance updated = current.withHardwareConfig(newConfig, clock);
            store.save(updated);
            return updated;
        }

        public synchronized Instance updateGuestPassword(String id, String newPassword) throws IOException {
            Objects.requireNonNull(newPassword, "newPassword");
            String trimmed = newPassword.trim();
            if (trimmed.length() < 8) {
                throw new IllegalArgumentException("guest password must be at least 8 characters");
            }
            if (trimmed.contains(" ") || trimmed.contains("\t") || trimmed.contains("\n") || trimmed.contains("\r")) {
                throw new IllegalArgumentException("guest password must not contain whitespace");
            }
            Instance current = store.load(id);
            Instance updated = current.withGuestPassword(trimmed, clock);
            store.save(updated);
            return updated;
        }

        public synchronized Instance beginProvisioning(String id) throws IOException {
            return transition(id, State.PROVISIONING);
        }

        public synchronized Instance completeProvisioning(String id) throws IOException {
            return transition(id, State.STOPPED);
        }

        public synchronized Instance start(String id) throws IOException {
            Instance current = store.load(id);
            if (current.state() != State.STOPPED) {
                throw new InvalidTransitionException(
                        "cannot start instance in state " + current.state() + "; expected STOPPED");
            }
            for (Instance instance : store.list()) {
                if (!instance.id().equals(id) && instance.state() == State.RUNNING) {
                    throw new SingleRunningInstanceException("another Instance is already RUNNING");
                }
            }
            return transition(id, State.RUNNING);
        }

        /**
         * Suspends a currently RUNNING instance, freezing CPU scheduling.
         */
        public synchronized Instance suspend(String id) throws IOException {
            Instance current = store.load(id);
            if (current.state() != State.RUNNING) {
                throw new InvalidTransitionException(
                        "cannot suspend instance in state " + current.state() + "; expected RUNNING");
            }
            return transition(id, State.SUSPENDED);
        }

        /**
         * Resumes a SUSPENDED instance, unfreezing CPU scheduling and returning to RUNNING.
         */
        public synchronized Instance resume(String id) throws IOException {
            Instance current = store.load(id);
            if (current.state() != State.SUSPENDED) {
                throw new InvalidTransitionException(
                        "cannot resume instance in state " + current.state() + "; expected SUSPENDED");
            }
            for (Instance instance : store.list()) {
                if (!instance.id().equals(id) && instance.state() == State.RUNNING) {
                    throw new SingleRunningInstanceException("another Instance is already RUNNING");
                }
            }
            return transition(id, State.RUNNING);
        }

        /**
         * Force stops an instance, transitioning RUNNING or SUSPENDED states directly to STOPPED.
         */
        public synchronized Instance forceStop(String id) throws IOException {
            Instance current = store.load(id);
            if (current.state() == State.STOPPED) {
                return current;
            }
            return transition(id, State.STOPPED);
        }

        /** Records an observed normal guest-initiated stop; it is not a host power command. */
        public synchronized Instance recordNormalGuestStop(String id) throws IOException {
            return transition(id, State.STOPPED);
        }

        /** Records an intentional graceful stop initiated by the host or guest. */
        public synchronized Instance recordGracefulStop(String id) throws IOException {
            return transition(id, State.STOPPED);
        }

        public synchronized Instance recordCrash(String id, int exitCode, Path logPath) throws IOException {
            return recordCrash(id, exitCode, logPath, null);
        }

        public synchronized Instance recordCrash(String id, int exitCode, Path logPath, String message)
                throws IOException {
            return recordCrash(id, new CrashDiagnostic(clock.millis(), exitCode, logPath, message));
        }

        public synchronized Instance recordCrash(String id, CrashDiagnostic diagnostic) throws IOException {
            Objects.requireNonNull(diagnostic, "diagnostic");
            Instance current = store.load(id);
            Instance transitioned = current.transitioned(State.CRASHED, clock, diagnostic);
            store.save(transitioned);
            return transitioned;
        }

        public synchronized Instance recordAbnormalTermination(String id, int exitCode, Path logPath)
                throws IOException {
            return recordCrash(id, exitCode, logPath);
        }

        public synchronized Instance recordAbnormalTermination(String id, CrashDiagnostic diagnostic)
                throws IOException {
            return recordCrash(id, diagnostic);
        }

        /**
         * Reconciles all stale RUNNING or SUSPENDED instances to INTERRUPTED (the default cold-restorable state).
         * Returns the list of reconciled instances.
         */
        public synchronized List<Instance> reconcileAfterHostDeath() throws IOException {
            return reconcileAfterHostDeath(State.INTERRUPTED);
        }

        /**
         * Reconciles all stale RUNNING or SUSPENDED instances to the specified target state (INTERRUPTED or STOPPED).
         */
        public synchronized List<Instance> reconcileAfterHostDeath(State targetState) throws IOException {
            if (targetState != State.INTERRUPTED && targetState != State.STOPPED) {
                throw new IllegalArgumentException(
                        "targetState must be INTERRUPTED or STOPPED, got: " + targetState);
            }
            List<Instance> reconciled = new ArrayList<>();
            for (Instance instance : store.list()) {
                if (instance.state() == State.RUNNING || instance.state() == State.SUSPENDED) {
                    Instance updated = instance.transitioned(targetState, clock);
                    store.save(updated);
                    reconciled.add(updated);
                }
            }
            return Collections.unmodifiableList(reconciled);
        }

        /**
         * Reconciles a single instance by id if it is in stale RUNNING or SUSPENDED state, setting it to INTERRUPTED.
         */
        public synchronized Instance reconcileAfterHostDeath(String id) throws IOException {
            return reconcileAfterHostDeath(id, State.INTERRUPTED);
        }

        /**
         * Reconciles a single instance by id to the specified target state (INTERRUPTED or STOPPED).
         */
        public synchronized Instance reconcileAfterHostDeath(String id, State targetState) throws IOException {
            if (targetState != State.INTERRUPTED && targetState != State.STOPPED) {
                throw new IllegalArgumentException(
                        "targetState must be INTERRUPTED or STOPPED, got: " + targetState);
            }
            Instance instance = store.load(id);
            if (instance.state() == State.RUNNING || instance.state() == State.SUSPENDED) {
                Instance updated = instance.transitioned(targetState, clock);
                store.save(updated);
                return updated;
            }
            return instance;
        }

        /**
         * Restores an instance from an interrupted, crashed, or stopped state and prepares it for execution.
         * Enforces the single-running-instance constraint and clears prior crash diagnostics.
         */
        public synchronized Instance coldRestore(String id) throws IOException {
            for (Instance instance : store.list()) {
                if (!instance.id().equals(id) && instance.state() == State.RUNNING) {
                    throw new SingleRunningInstanceException("another Instance is already RUNNING");
                }
            }
            Instance current = store.load(id);
            if (current.state() != State.INTERRUPTED
                    && current.state() != State.CRASHED
                    && current.state() != State.STOPPED) {
                throw new InvalidTransitionException(
                        "cannot cold-restore instance in state "
                                + current.state()
                                + "; expected INTERRUPTED, CRASHED, or STOPPED");
            }
            Instance transitioned = current.transitioned(State.RUNNING, clock, null);
            store.save(transitioned);
            return transitioned;
        }

        /**
         * Resets an interrupted or crashed instance back to STOPPED without starting it.
         */
        public synchronized Instance resetToStopped(String id) throws IOException {
            Instance current = store.load(id);
            if (current.state() != State.INTERRUPTED && current.state() != State.CRASHED) {
                throw new InvalidTransitionException(
                        "cannot reset instance in state " + current.state() + " to STOPPED");
            }
            Instance transitioned = current.transitioned(State.STOPPED, clock, null);
            store.save(transitioned);
            return transitioned;
        }

        /**
         * Exports structured diagnostic details and copies referenced log data (if available)
         * to the given destination path.
         */
        public synchronized void exportDiagnostic(String id, Path destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            Instance instance = store.load(id);
            CrashDiagnostic diagnostic = instance.crashDiagnostic();
            if (diagnostic == null) {
                throw new IllegalStateException("instance " + id + " has no crash diagnostic to export");
            }

            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }

            StringBuilder report = new StringBuilder();
            report.append("Orchestella Crash Diagnostic Report\n");
            report.append("===================================\n");
            report.append("Instance ID:       ").append(instance.id()).append("\n");
            report.append("Distro:            ").append(instance.distro().id())
                    .append(" ").append(instance.distro().version()).append("\n");
            report.append("Crash Timestamp:   ").append(diagnostic.timestampMillis()).append("\n");
            report.append("Exit Code:         ").append(diagnostic.exitCode()).append("\n");
            if (diagnostic.message() != null) {
                report.append("Message:           ").append(diagnostic.message()).append("\n");
            }
            if (diagnostic.logPath() != null) {
                report.append("Log Reference:     ").append(diagnostic.logPath()).append("\n");
                if (Files.isRegularFile(diagnostic.logPath())) {
                    report.append("\n--- Log Contents ---\n");
                    report.append(Files.readString(diagnostic.logPath()));
                }
            }

            Files.writeString(destination, report.toString());
        }

        /**
         * Resizes the root volume of an instance to {@code targetSizeBytes}.
         * Preflights available host storage space using {@code spaceChecker}.
         */
        public synchronized void resizeRootVolume(
                String id,
                long targetSizeBytes,
                io.github.nyzeep.orchestella.runtime.contract.StorageContract.StorageSpaceChecker spaceChecker)
                throws IOException {
            Instance instance = store.load(id);
            InstanceStorageManager.resizeRootVolume(instance, targetSizeBytes, spaceChecker);
            Instance updated = instance.withUpdatedAt(clock);
            store.save(updated);
        }

        /**
         * Exports an instance (root volume, metadata JSON, and checksums) to a directory.
         */
        public synchronized io.github.nyzeep.orchestella.runtime.contract.StorageContract.InstanceExportMetadata exportToDirectory(
                String id,
                Path destinationDirectory,
                io.github.nyzeep.orchestella.runtime.contract.StorageContract.StorageSpaceChecker spaceChecker)
                throws IOException {
            Instance instance = store.load(id);
            return InstanceStorageManager.exportToDirectory(instance, destinationDirectory, spaceChecker, clock);
        }

        /**
         * Exports an instance as an archive package (ZIP) to an output stream.
         */
        public synchronized io.github.nyzeep.orchestella.runtime.contract.StorageContract.InstanceExportMetadata exportToArchive(
                String id, OutputStream destinationStream) throws IOException {
            Instance instance = store.load(id);
            return InstanceStorageManager.exportToArchive(instance, destinationStream, clock);
        }

        private Instance transition(String id, State next) throws IOException {
            Instance transitioned = store.load(id).transitioned(next, clock);
            store.save(transitioned);
            return transitioned;
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

    private static final class InvalidMetadataException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private InvalidMetadataException(Throwable cause) {
            super(cause);
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

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("missing metadata field");
        }
        return value;
    }

    private static String newBridgeToken() {
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

    private static void requireInstanceId(String id) {
        try {
            UUID.fromString(Objects.requireNonNull(id, "id"));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("invalid instance id", malformed);
        }
    }
}
