package io.github.nyzeep.orchestella.runtime.instance;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small app-private persistence adapter implementation; the public
 * {@link InstanceLifecycle.FileStore} type stays the API surface and extends this class.
 * The caller owns the directory choice and must pass an Android private-files path in
 * production.
 *
 * <p>Extracted verbatim from {@link InstanceLifecycle}; every method body is
 * behavior-preserving.
 */
class InstanceFileStore implements InstanceLifecycle.Store {
    private static final ConcurrentHashMap<Path, Object> STATE_MONITORS = new ConcurrentHashMap<>();

    private final Path directory;
    private final Object stateMonitor;

    InstanceFileStore(Path directory) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        this.stateMonitor = STATE_MONITORS.computeIfAbsent(this.directory.toRealPath(), ignored -> new Object());
    }

    @Override
    public Object stateMonitor() {
        return stateMonitor;
    }

    @Override
    public void save(InstanceLifecycle.Instance instance) throws IOException {
        synchronized (stateMonitor) {
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
    }

    @Override
    public InstanceLifecycle.Instance load(String id) throws IOException {
        synchronized (stateMonitor) {
            Path target = metadataPath(id);
            if (!Files.isRegularFile(target)) {
                throw new IOException("instance metadata not found");
            }
            return read(target);
        }
    }

    @Override
    public List<InstanceLifecycle.Instance> list() throws IOException {
        synchronized (stateMonitor) {
            List<InstanceLifecycle.Instance> instances = new ArrayList<>();
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
            instances.sort(Comparator.comparing(InstanceLifecycle.Instance::id));
            return Collections.unmodifiableList(instances);
        }
    }

    @Override
    public void delete(String id) throws IOException {
        synchronized (stateMonitor) {
            deleteLocked(id);
        }
    }

    private void deleteLocked(String id) throws IOException {
        InstanceLifecycle.requireInstanceId(id);
        Path target = metadataPath(id);
        if (!Files.isRegularFile(target)) {
            throw new NoSuchFileException("instance metadata not found: " + id);
        }
        InstanceLifecycle.Instance instance = read(target);
        InstanceLifecycle.State state = instance.state();
        if (state == InstanceLifecycle.State.RUNNING
                || state == InstanceLifecycle.State.PROVISIONING
                || state == InstanceLifecycle.State.SUSPENDED) {
            throw new IllegalStateException("Cannot delete instance while " + state);
        }
        if (state != InstanceLifecycle.State.STOPPED
                && state != InstanceLifecycle.State.CREATED
                && state != InstanceLifecycle.State.CRASHED) {
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
                } catch (IOException ignored) {
                    // Per-file best-effort sweep: all known artifacts above propagate
                    // their failures, so this catch-all only covers leftovers; an
                    // undeletable residual file does not change the delete outcome.
                }
            });
        }

        Files.deleteIfExists(target);
    }

    private InstanceLifecycle.Instance read(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        try {
            return InstanceLifecycle.Instance.fromProperties(properties);
        } catch (InstanceMetadataCodec.InvalidMetadataException malformed) {
            throw new IOException("invalid instance metadata", malformed.getCause());
        }
    }

    private Path metadataPath(String id) {
        InstanceLifecycle.requireInstanceId(id);
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
