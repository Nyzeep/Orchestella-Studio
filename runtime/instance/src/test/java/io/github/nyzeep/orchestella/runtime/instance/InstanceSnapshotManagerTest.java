package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TDD unit tests for {@link InstanceSnapshotManager} and {@link InstanceSnapshotNode}.
 * Verifies snapshot creation, chronological listing, consistency restore, deletion,
 * state safety guardrails, and metadata corruption resilience.
 */
public final class InstanceSnapshotManagerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    public static void main(String[] args) throws Exception {
        Path tempDir;
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            tempDir = Path.of(args[0]).toAbsolutePath().normalize();
            if (Files.exists(tempDir)) {
                deleteRecursively(tempDir);
            }
            Files.createDirectories(tempDir);
        } else {
            tempDir = Files.createTempDirectory("snapshot-manager-test-");
        }

        try {
            InstanceSnapshotManagerTest suite = new InstanceSnapshotManagerTest();
            suite.testCreateAndListSnapshots(tempDir);
            suite.testRejectSnapshotCreationWhenRunningOrSuspended(tempDir);
            suite.testRevertToSnapshotRestoresDiskAndHardwareConfig(tempDir);
            suite.testRejectRevertWhenRunningOrSuspended(tempDir);
            suite.testDeleteSnapshotRemovesCheckpointFileAndMetadata(tempDir);
            suite.testCorruptedMetadataFallbackAndResilience(tempDir);
            suite.testMultipleSnapshotsChronologicalOrderAndInstanceIsolation(tempDir);
            System.out.println("INSTANCE_SNAPSHOT_MANAGER_TEST_OK");
        } finally {
            if (args == null || args.length == 0) {
                deleteRecursively(tempDir);
            }
        }
    }

    @Test
    public void testAllCases() throws Exception {
        Path tempDir = Files.createTempDirectory("snapshot-manager-junit-");
        try {
            testCreateAndListSnapshots(tempDir);
            testRejectSnapshotCreationWhenRunningOrSuspended(tempDir);
            testRevertToSnapshotRestoresDiskAndHardwareConfig(tempDir);
            testRejectRevertWhenRunningOrSuspended(tempDir);
            testDeleteSnapshotRemovesCheckpointFileAndMetadata(tempDir);
            testCorruptedMetadataFallbackAndResilience(tempDir);
            testMultipleSnapshotsChronologicalOrderAndInstanceIsolation(tempDir);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public void testCreateAndListSnapshots(Path root) throws Exception {
        Path fixtureDir = root.resolve("create-list");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path snapshotsDir = fixtureDir.resolve("snapshots");

        Path disk = fixtureDir.resolve("inst.raw");
        Files.writeString(disk, "INITIAL_DISK_PAYLOAD_V1");

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                disk,
                new VmHardwareConfig(2, 2048, 10L * 1024 * 1024 * 1024, true, null));
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());

        InstanceSnapshotManager manager = new InstanceSnapshotManager(snapshotsDir, store, CLOCK);

        // Before any snapshot: list is empty
        List<InstanceSnapshotNode> emptyList = manager.listSnapshots(instance.id());
        assertNotNull(emptyList);
        assertTrue(emptyList.isEmpty());

        // Create snapshot 1
        InstanceSnapshotNode node1 = manager.createSnapshot(instance.id(), "Clean Install", "Baseline snapshot");
        assertNotNull(node1);
        assertNotNull(node1.snapshotId());
        assertEquals(instance.id(), node1.instanceId());
        assertEquals("Clean Install", node1.name());
        assertEquals("Baseline snapshot", node1.description());
        assertEquals(CLOCK.millis(), node1.createdAt());
        assertTrue("Checkpoint disk must exist", Files.exists(node1.diskCheckpointPath()));
        assertEquals("INITIAL_DISK_PAYLOAD_V1", Files.readString(node1.diskCheckpointPath()));
        assertEquals(2, node1.hardwareConfigSnapshot().cpuCores());
        assertEquals(2048, node1.hardwareConfigSnapshot().ramMegabytes());

        // List snapshots
        List<InstanceSnapshotNode> list = manager.listSnapshots(instance.id());
        assertEquals(1, list.size());
        assertEquals(node1.snapshotId(), list.get(0).snapshotId());
        assertEquals("Clean Install", list.get(0).name());
    }

    public void testRejectSnapshotCreationWhenRunningOrSuspended(Path root) throws Exception {
        Path fixtureDir = root.resolve("safety-guards");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path snapshotsDir = fixtureDir.resolve("snapshots");

        Path disk = fixtureDir.resolve("inst.raw");
        Files.writeString(disk, "DISK_DATA");

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), disk);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());

        InstanceSnapshotManager manager = new InstanceSnapshotManager(snapshotsDir, store, CLOCK);

        // 1. Rejects when RUNNING
        try {
            manager.createSnapshot(instance.id(), "Running Snapshot", null);
            fail("Should reject snapshot creation on RUNNING instance");
        } catch (IllegalStateException expected) {}

        // 2. Rejects when SUSPENDED
        controller.suspend(instance.id());
        assertEquals(InstanceLifecycle.State.SUSPENDED, store.load(instance.id()).state());
        try {
            manager.createSnapshot(instance.id(), "Suspended Snapshot", null);
            fail("Should reject snapshot creation on SUSPENDED instance");
        } catch (IllegalStateException expected) {}
    }

    public void testRevertToSnapshotRestoresDiskAndHardwareConfig(Path root) throws Exception {
        Path fixtureDir = root.resolve("revert-test");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path snapshotsDir = fixtureDir.resolve("snapshots");

        Path disk = fixtureDir.resolve("inst.raw");
        Files.writeString(disk, "STATE_BEFORE_EXPERIMENT_HELLO_WORLD", StandardCharsets.UTF_8);

        VmHardwareConfig originalHw = new VmHardwareConfig(2, 2048, 8L * 1024 * 1024 * 1024, true, "earlycon");
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), disk, originalHw);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        InstanceSnapshotManager manager = new InstanceSnapshotManager(snapshotsDir, store, CLOCK);

        // Take snapshot at baseline
        InstanceSnapshotNode snapshot = manager.createSnapshot(instance.id(), "Safe Point", "Before dangerous change");

        // Now simulate user making changes in the VM and modifying hardware
        Files.writeString(disk, "CORRUPTED_OR_BROKEN_MODIFIED_STATE", StandardCharsets.UTF_8);
        VmHardwareConfig modifiedHw = new VmHardwareConfig(4, 4096, 16L * 1024 * 1024 * 1024, false, "quiet");
        controller.updateHardwareConfig(instance.id(), modifiedHw);

        // Verify the changes took effect
        assertEquals("CORRUPTED_OR_BROKEN_MODIFIED_STATE", Files.readString(disk, StandardCharsets.UTF_8));
        assertEquals(4, store.load(instance.id()).hardwareConfig().cpuCores());

        // Perform revert
        boolean revertOk = manager.revertToSnapshot(instance.id(), snapshot.snapshotId());
        assertTrue("revertToSnapshot should return true", revertOk);

        // Verify disk is restored byte-for-byte
        assertEquals("STATE_BEFORE_EXPERIMENT_HELLO_WORLD", Files.readString(disk, StandardCharsets.UTF_8));

        // Verify hardware configuration is restored
        InstanceLifecycle.Instance restoredInstance = store.load(instance.id());
        assertEquals(2, restoredInstance.hardwareConfig().cpuCores());
        assertEquals(2048, restoredInstance.hardwareConfig().ramMegabytes());
        assertEquals("earlycon", restoredInstance.hardwareConfig().extraKernelArguments());
    }

    public void testRejectRevertWhenRunningOrSuspended(Path root) throws Exception {
        Path fixtureDir = root.resolve("revert-safety");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path snapshotsDir = fixtureDir.resolve("snapshots");

        Path disk = fixtureDir.resolve("inst.raw");
        Files.writeString(disk, "ORIGINAL");

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), disk);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        InstanceSnapshotManager manager = new InstanceSnapshotManager(snapshotsDir, store, CLOCK);
        InstanceSnapshotNode node = manager.createSnapshot(instance.id(), "Base", null);

        controller.start(instance.id());
        try {
            manager.revertToSnapshot(instance.id(), node.snapshotId());
            fail("Must not allow revert while RUNNING");
        } catch (IllegalStateException expected) {}

        controller.suspend(instance.id());
        try {
            manager.revertToSnapshot(instance.id(), node.snapshotId());
            fail("Must not allow revert while SUSPENDED");
        } catch (IllegalStateException expected) {}
    }

    public void testDeleteSnapshotRemovesCheckpointFileAndMetadata(Path root) throws Exception {
        Path fixtureDir = root.resolve("delete-test");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path snapshotsDir = fixtureDir.resolve("snapshots");

        Path disk = fixtureDir.resolve("inst.raw");
        Files.writeString(disk, "DATA");

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), disk);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        InstanceSnapshotManager manager = new InstanceSnapshotManager(snapshotsDir, store, CLOCK);
        InstanceSnapshotNode node = manager.createSnapshot(instance.id(), "Temporary Checkpoint", null);

        assertTrue(Files.exists(node.diskCheckpointPath()));
        assertEquals(1, manager.listSnapshots(instance.id()).size());

        // Delete snapshot
        boolean deleteOk = manager.deleteSnapshot(instance.id(), node.snapshotId());
        assertTrue("deleteSnapshot should succeed", deleteOk);

        // Checkpoint disk file removed
        assertFalse("Checkpoint file must be physically deleted", Files.exists(node.diskCheckpointPath()));

        // Snapshot list is now empty
        List<InstanceSnapshotNode> remaining = manager.listSnapshots(instance.id());
        assertTrue(remaining.isEmpty());

        // Deleting non-existent snapshot returns false
        assertFalse(manager.deleteSnapshot(instance.id(), "non-existent-id"));
    }

    public void testCorruptedMetadataFallbackAndResilience(Path root) throws Exception {
        Path fixtureDir = root.resolve("corrupt-resilience");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path snapshotsDir = fixtureDir.resolve("snapshots");

        Path disk = fixtureDir.resolve("inst.raw");
        Files.writeString(disk, "DATA");

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), disk);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        InstanceSnapshotManager manager = new InstanceSnapshotManager(snapshotsDir, store, CLOCK);
        InstanceSnapshotNode node = manager.createSnapshot(instance.id(), "Valid Node", "Before corruption");

        Path indexFile = snapshotsDir.resolve(instance.id()).resolve("snapshots.json");
        assertTrue(Files.exists(indexFile));

        // Intentionally corrupt index file with broken syntax
        Files.writeString(indexFile, "{\"corrupted_json\": [ invalid syntax !!!");

        // listSnapshots should not crash with uncaught exception
        List<InstanceSnapshotNode> recovered = manager.listSnapshots(instance.id());
        assertNotNull(recovered);

        // Creating a new snapshot over corrupted file recovers and resets cleanly
        InstanceSnapshotNode recoveredNode = manager.createSnapshot(instance.id(), "Recovered Node", "New state");
        assertNotNull(recoveredNode);
        List<InstanceSnapshotNode> listAfter = manager.listSnapshots(instance.id());
        assertEquals(1, listAfter.size());
        assertEquals("Recovered Node", listAfter.get(0).name());
    }

    public void testMultipleSnapshotsChronologicalOrderAndInstanceIsolation(Path root) throws Exception {
        Path fixtureDir = root.resolve("multiple-instances");
        Files.createDirectories(fixtureDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(fixtureDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path snapshotsDir = fixtureDir.resolve("snapshots");

        Path disk1 = fixtureDir.resolve("inst1.raw");
        Files.writeString(disk1, "DATA_1");
        InstanceLifecycle.Instance inst1 = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), disk1);
        controller.beginProvisioning(inst1.id());
        controller.completeProvisioning(inst1.id());

        Path disk2 = fixtureDir.resolve("inst2.raw");
        Files.writeString(disk2, "DATA_2");
        InstanceLifecycle.Instance inst2 = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), disk2);
        controller.beginProvisioning(inst2.id());
        controller.completeProvisioning(inst2.id());

        InstanceSnapshotManager manager = new InstanceSnapshotManager(snapshotsDir, store, CLOCK);

        InstanceSnapshotNode s1 = manager.createSnapshot(inst1.id(), "Inst1 - S1", null);
        InstanceSnapshotNode s2 = manager.createSnapshot(inst1.id(), "Inst1 - S2", null);
        InstanceSnapshotNode s3 = manager.createSnapshot(inst2.id(), "Inst2 - S1", null);

        List<InstanceSnapshotNode> inst1List = manager.listSnapshots(inst1.id());
        assertEquals(2, inst1List.size());
        assertEquals("Inst1 - S1", inst1List.get(0).name());
        assertEquals("Inst1 - S2", inst1List.get(1).name());

        List<InstanceSnapshotNode> inst2List = manager.listSnapshots(inst2.id());
        assertEquals(1, inst2List.size());
        assertEquals("Inst2 - S1", inst2List.get(0).name());
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        }
    }
}
