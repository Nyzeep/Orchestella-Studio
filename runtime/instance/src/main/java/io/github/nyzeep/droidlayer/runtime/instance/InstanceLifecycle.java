package io.github.nyzeep.droidlayer.runtime.instance;

import io.github.nyzeep.droidlayer.runtime.contract.BridgeHandshake;
import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
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

    private InstanceLifecycle() {}

    public enum State {
        CREATED,
        PROVISIONING,
        STOPPED,
        RUNNING
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
        private final FullLinuxContract.RuntimeClass runtimeClass;
        private final State state;
        private final long createdAtMillis;
        private final long updatedAtMillis;

        private Instance(
                String id,
                DistroReference distro,
                Path rootVolume,
                String bridgeToken,
                FullLinuxContract.RuntimeClass runtimeClass,
                State state,
                long createdAtMillis,
                long updatedAtMillis) {
            requireInstanceId(id);
            this.id = id;
            this.distro = Objects.requireNonNull(distro, "distro");
            this.rootVolume = Objects.requireNonNull(rootVolume, "rootVolume").normalize();
            this.bridgeToken = requireCanonicalBridgeToken(bridgeToken);
            this.runtimeClass = Objects.requireNonNull(runtimeClass, "runtimeClass");
            this.state = Objects.requireNonNull(state, "state");
            this.createdAtMillis = createdAtMillis;
            this.updatedAtMillis = updatedAtMillis;
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

        private Instance transitioned(State next, Clock clock) {
            if (!isNormalTransition(state, next)) {
                throw new InvalidTransitionException(state + " -> " + next + " is not a normal transition");
            }
            return new Instance(
                    id,
                    distro,
                    rootVolume,
                    bridgeToken,
                    runtimeClass,
                    next,
                    createdAtMillis,
                    Objects.requireNonNull(clock, "clock").millis());
        }

        private Properties toProperties() {
            Properties properties = new Properties();
            properties.setProperty("id", id);
            properties.setProperty("distroId", distro.id());
            properties.setProperty("distroVersion", distro.version());
            properties.setProperty("rootVolume", rootVolume.toString());
            properties.setProperty("bridgeToken", bridgeToken);
            properties.setProperty("runtimeClass", runtimeClass.name());
            properties.setProperty("state", state.name());
            properties.setProperty("createdAtMillis", Long.toString(createdAtMillis));
            properties.setProperty("updatedAtMillis", Long.toString(updatedAtMillis));
            return properties;
        }

        private static Instance fromProperties(Properties properties) {
            try {
                return new Instance(
                        required(properties, "id"),
                        new DistroReference(
                                required(properties, "distroId"), required(properties, "distroVersion")),
                        Path.of(required(properties, "rootVolume")),
                        required(properties, "bridgeToken"),
                        FullLinuxContract.RuntimeClass.valueOf(required(properties, "runtimeClass")),
                        State.valueOf(required(properties, "state")),
                        Long.parseLong(required(properties, "createdAtMillis")),
                        Long.parseLong(required(properties, "updatedAtMillis")));
            } catch (IllegalArgumentException malformed) {
                throw new InvalidMetadataException(malformed);
            }
        }
    }

    public interface Store {
        void save(Instance instance) throws IOException;

        Instance load(String id) throws IOException;

        List<Instance> list() throws IOException;
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
                properties.store(output, "DroidLayer Instance metadata");
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

        public synchronized Instance create(DistroReference distro, Path rootVolume) throws IOException {
            Objects.requireNonNull(distro, "distro");
            Objects.requireNonNull(rootVolume, "rootVolume");
            long now = clock.millis();
            Instance instance =
                    new Instance(
                            UUID.randomUUID().toString(),
                            distro,
                            rootVolume,
                            newBridgeToken(),
                            FullLinuxContract.RuntimeClass.FULL_LINUX,
                            State.CREATED,
                            now,
                            now);
            store.save(instance);
            return instance;
        }

        public synchronized Instance beginProvisioning(String id) throws IOException {
            return transition(id, State.PROVISIONING);
        }

        public synchronized Instance completeProvisioning(String id) throws IOException {
            return transition(id, State.STOPPED);
        }

        public synchronized Instance start(String id) throws IOException {
            for (Instance instance : store.list()) {
                if (!instance.id().equals(id) && instance.state() == State.RUNNING) {
                    throw new SingleRunningInstanceException("another Instance is already RUNNING");
                }
            }
            return transition(id, State.RUNNING);
        }

        /** Records an observed normal guest-initiated stop; it is not a host power command. */
        public synchronized Instance recordNormalGuestStop(String id) throws IOException {
            return transition(id, State.STOPPED);
        }

        private Instance transition(String id, State next) throws IOException {
            Instance transitioned = store.load(id).transitioned(next, clock);
            store.save(transitioned);
            return transitioned;
        }
    }

    public static class InvalidTransitionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private InvalidTransitionException(String message) {
            super(message);
        }
    }

    public static final class SingleRunningInstanceException extends InvalidTransitionException {
        private static final long serialVersionUID = 1L;

        private SingleRunningInstanceException(String message) {
            super(message);
        }
    }

    private static final class InvalidMetadataException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private InvalidMetadataException(Throwable cause) {
            super(cause);
        }
    }

    private static boolean isNormalTransition(State from, State to) {
        return (from == State.CREATED && to == State.PROVISIONING)
                || (from == State.PROVISIONING && to == State.STOPPED)
                || (from == State.STOPPED && to == State.RUNNING)
                || (from == State.RUNNING && to == State.STOPPED);
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
