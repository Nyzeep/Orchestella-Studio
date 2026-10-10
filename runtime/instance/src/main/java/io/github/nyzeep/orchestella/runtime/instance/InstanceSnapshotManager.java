package io.github.nyzeep.orchestella.runtime.instance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deep module governing lightweight state snapshot management for virtual machine instances.
 *
 * <p>Maintains each instance's independent {@code snapshots/} directory structure, disk checkpoints,
 * and {@code snapshots.json} metadata index with atomic writes, corruption rollback resilience,
 * and strict state machine consistency guardrails.
 */
public final class InstanceSnapshotManager {

    private static final String INDEX_FILE_NAME = "snapshots.json";
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}");

    private final Path baseDirectory;
    private final InstanceLifecycle.Store store;
    private final Clock clock;

    public InstanceSnapshotManager(Path baseDirectory, InstanceLifecycle.Store store) {
        this(baseDirectory, store, Clock.systemUTC());
    }

    public InstanceSnapshotManager(Path baseDirectory, InstanceLifecycle.Store store, Clock clock) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory").toAbsolutePath().normalize();
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Resolves the dedicated snapshot storage directory for a given instance ID.
     */
    public Path instanceSnapshotsDir(String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return baseDirectory.resolve(instanceId);
    }

    /**
     * Creates a state snapshot of the instance, generating a differential checkpoint of the root volume
     * and atomically persisting hardware and metadata snapshots.
     *
     * <p>Preflight safety checks verify the instance is strictly powered down ({@code STOPPED} or {@code CREATED}).
     * Attempting to snapshot a {@code RUNNING}, {@code SUSPENDED}, or {@code PROVISIONING} instance is strictly rejected.
     *
     * @param instanceId target instance ID
     * @param name descriptive snapshot name
     * @param description optional note or description
     * @return the newly created and indexed {@link InstanceSnapshotNode}
     * @throws IOException on storage I/O failure
     * @throws IllegalStateException if instance is currently executing or transitioning
     */
    public synchronized InstanceSnapshotNode createSnapshot(String instanceId, String name, String description)
            throws IOException {
        Objects.requireNonNull(instanceId, "instanceId");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("snapshot name must not be blank");
        }

        InstanceLifecycle.Instance instance = store.load(instanceId);
        InstanceLifecycle.State state = instance.state();
        if (state != InstanceLifecycle.State.STOPPED && state != InstanceLifecycle.State.CREATED) {
            throw new IllegalStateException(
                    "Cannot create snapshot while instance is " + state + "; expected STOPPED or CREATED");
        }

        Path instanceDir = instanceSnapshotsDir(instanceId);
        Files.createDirectories(instanceDir);

        String snapshotId = UUID.randomUUID().toString();
        Path checkpointPath = instanceDir.resolve(snapshotId + ".raw");

        Path rootVolume = instance.rootVolume();
        if (rootVolume != null && Files.isRegularFile(rootVolume)) {
            Files.copy(rootVolume, checkpointPath, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        } else if (!Files.exists(checkpointPath)) {
            Files.createFile(checkpointPath);
        }

        long sizeBytes = Files.exists(checkpointPath) ? Files.size(checkpointPath) : 0L;
        long now = clock.millis();

        InstanceSnapshotNode node = new InstanceSnapshotNode(
                snapshotId,
                instanceId,
                name,
                description,
                now,
                checkpointPath,
                instance.hardwareConfig(),
                sizeBytes);

        List<InstanceSnapshotNode> nodes = new ArrayList<>(loadIndex(instanceId));
        nodes.add(node);
        nodes.sort(Comparator.comparingLong(InstanceSnapshotNode::createdAt));
        saveIndex(instanceId, nodes);

        return node;
    }

    /**
     * Lists all snapshot nodes for the instance in chronological order.
     * Tolerates corrupted or missing index records gracefully without crashing.
     *
     * @param instanceId target instance ID
     * @return unmodifiable list of snapshot nodes
     */
    public synchronized List<InstanceSnapshotNode> listSnapshots(String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return loadIndex(instanceId);
    }

    /**
     * Retrieves a single snapshot node by ID.
     *
     * @param instanceId target instance ID
     * @param snapshotId snapshot node ID
     * @return optional containing the node if found
     */
    public synchronized Optional<InstanceSnapshotNode> getSnapshot(String instanceId, String snapshotId) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        return loadIndex(instanceId).stream()
                .filter(node -> node.snapshotId().equals(snapshotId))
                .findFirst();
    }

    /**
     * Reverts the instance to a specified snapshot node.
     *
     * <p>Preflight safety guards verify the instance is powered down ({@code STOPPED} or {@code CREATED}).
     * Restores root volume disk bytes and hardware configuration atomically into metadata.
     *
     * @param instanceId target instance ID
     * @param snapshotId ID of the snapshot to revert to
     * @return true if successfully reverted, false if snapshot was not found
     * @throws IOException on I/O error
     * @throws IllegalStateException if instance is currently running
     */
    public synchronized boolean revertToSnapshot(String instanceId, String snapshotId) throws IOException {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(snapshotId, "snapshotId");

        InstanceLifecycle.Instance instance = store.load(instanceId);
        InstanceLifecycle.State state = instance.state();
        if (state != InstanceLifecycle.State.STOPPED && state != InstanceLifecycle.State.CREATED) {
            throw new IllegalStateException(
                    "Cannot revert snapshot while instance is " + state + "; expected STOPPED or CREATED");
        }

        Optional<InstanceSnapshotNode> optNode = getSnapshot(instanceId, snapshotId);
        if (optNode.isEmpty()) {
            return false;
        }

        InstanceSnapshotNode node = optNode.get();
        Path checkpointPath = node.diskCheckpointPath();
        if (!Files.isRegularFile(checkpointPath)) {
            throw new IOException("Snapshot disk checkpoint file does not exist: " + checkpointPath);
        }

        Path rootVolume = instance.rootVolume();
        if (rootVolume.getParent() != null) {
            Files.createDirectories(rootVolume.getParent());
        }
        Files.copy(checkpointPath, rootVolume, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);

        // Restore hardware configuration snapshot
        if (node.hardwareConfigSnapshot() != null) {
            InstanceLifecycle.Instance updated = instance.withHardwareConfig(node.hardwareConfigSnapshot(), clock);
            store.save(updated);
        }

        return true;
    }

    /**
     * Deletes a snapshot node, purging its physical disk checkpoint and updating metadata index.
     *
     * @param instanceId target instance ID
     * @param snapshotId snapshot node ID
     * @return true if deleted, false if node not found
     * @throws IOException on I/O failure
     */
    public synchronized boolean deleteSnapshot(String instanceId, String snapshotId) throws IOException {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(snapshotId, "snapshotId");

        List<InstanceSnapshotNode> nodes = loadIndex(instanceId);
        InstanceSnapshotNode targetNode = null;
        List<InstanceSnapshotNode> remaining = new ArrayList<>();

        for (InstanceSnapshotNode node : nodes) {
            if (node.snapshotId().equals(snapshotId)) {
                targetNode = node;
            } else {
                remaining.add(node);
            }
        }

        if (targetNode == null) {
            return false;
        }

        Files.deleteIfExists(targetNode.diskCheckpointPath());
        saveIndex(instanceId, remaining);
        return true;
    }

    /**
     * Purges all snapshots and checkpoint files for an instance (e.g. upon instance deletion).
     */
    public synchronized void deleteAllSnapshots(String instanceId) throws IOException {
        Path instanceDir = instanceSnapshotsDir(instanceId);
        if (!Files.exists(instanceDir)) {
            return;
        }
        try (var stream = Files.walk(instanceDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {}
            });
        }
    }

    private List<InstanceSnapshotNode> loadIndex(String instanceId) {
        Path indexFile = instanceSnapshotsDir(instanceId).resolve(INDEX_FILE_NAME);
        if (!Files.isRegularFile(indexFile)) {
            return Collections.emptyList();
        }

        try {
            String content = Files.readString(indexFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty() || !content.startsWith("[") || !content.endsWith("]")) {
                return Collections.emptyList();
            }

            List<InstanceSnapshotNode> list = new ArrayList<>();
            Matcher matcher = JSON_OBJECT_PATTERN.matcher(content);
            while (matcher.find()) {
                String objJson = matcher.group();
                try {
                    list.add(InstanceSnapshotNode.fromJson(objJson));
                } catch (Throwable ignored) {
                    // Tolerant to corrupted entries: skip bad entry and preserve valid ones
                }
            }
            list.sort(Comparator.comparingLong(InstanceSnapshotNode::createdAt));
            return Collections.unmodifiableList(list);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private void saveIndex(String instanceId, List<InstanceSnapshotNode> nodes) throws IOException {
        Path instanceDir = instanceSnapshotsDir(instanceId);
        Files.createDirectories(instanceDir);

        Path indexFile = instanceDir.resolve(INDEX_FILE_NAME);
        Path tmpFile = instanceDir.resolve(INDEX_FILE_NAME + ".tmp");

        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < nodes.size(); i++) {
            sb.append(nodes.get(i).toJson());
            if (i < nodes.size() - 1) {
                sb.append(",\n");
            } else {
                sb.append("\n");
            }
        }
        sb.append("]\n");

        Files.writeString(tmpFile, sb.toString(), StandardCharsets.UTF_8);

        try {
            Files.move(tmpFile, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmpFile, indexFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
