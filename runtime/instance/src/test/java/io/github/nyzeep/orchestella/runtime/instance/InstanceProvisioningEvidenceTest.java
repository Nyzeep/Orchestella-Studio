package io.github.nyzeep.orchestella.runtime.instance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Controller;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.DistroReference;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.FileStore;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Instance;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.ProvisioningEvidence;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.State;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Contract for persisted Provisioning evidence on Instance metadata. */
public final class InstanceProvisioningEvidenceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);
    private static final String ROOTFS_SHA256 = "a".repeat(64);
    private static final String OTHER_SHA256 = "b".repeat(64);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recordedEvidenceRoundTripsAndMatchesTheCurrentRootfs() throws Exception {
        FileStore store = newStore("roundtrip");
        Controller controller = new Controller(store, CLOCK);
        Instance created = controller.create(new DistroReference("ubuntu", "24.04"), rootVolume("roundtrip"));
        assertFalse(created.isProvisioned());
        assertNull(created.provisioningEvidence());

        Instance recorded = controller.recordProvisioning(
                created.id(), new ProvisioningEvidence(1, ROOTFS_SHA256, 1_700_000_000_123L));
        assertTrue(recorded.isProvisioned());
        assertEquals(1, recorded.provisioningEvidence().markerVersion());
        assertEquals(ROOTFS_SHA256, recorded.provisioningEvidence().rootfsSha256());
        assertEquals(1_700_000_000_123L, recorded.provisioningEvidence().recordedAtMillis());
        assertEquals(State.CREATED, recorded.state());
        assertEquals(CLOCK.millis(), recorded.updatedAtMillis());

        Instance reloaded = store.load(created.id());
        assertTrue(reloaded.isProvisioned());
        assertTrue(reloaded.isProvisionedFor(ROOTFS_SHA256));
        assertFalse(reloaded.isProvisionedFor(OTHER_SHA256));
        assertFalse(reloaded.isProvisionedFor(null));
    }

    @Test
    public void oldMetadataWithoutProvisioningKeysReadsAsNotProvisioned() throws Exception {
        FileStore store = newStore("old-metadata");
        Controller controller = new Controller(store, CLOCK);
        Instance created = controller.create(new DistroReference("debian", "13"), rootVolume("old-metadata"));

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metadataFile("old-metadata", created.id()))) {
            properties.load(input);
        }
        assertFalse(properties.containsKey("provisioning.markerVersion"));
        assertFalse(properties.containsKey("provisioning.rootfsSha256"));
        assertFalse(properties.containsKey("provisioning.recordedAtMillis"));

        Instance reloaded = store.load(created.id());
        assertFalse(reloaded.isProvisioned());
        assertNull(reloaded.provisioningEvidence());
        assertFalse(reloaded.isProvisionedFor(ROOTFS_SHA256));
    }

    @Test
    public void evidenceForAnotherDistroDoesNotCountAsProvisionedForThisOne() throws Exception {
        FileStore store = newStore("mismatch");
        Controller controller = new Controller(store, CLOCK);
        Instance created = controller.create(new DistroReference("fedora", "43"), rootVolume("mismatch"));
        controller.recordProvisioning(created.id(), new ProvisioningEvidence(1, ROOTFS_SHA256, CLOCK.millis()));

        Instance reloaded = store.load(created.id());
        assertTrue(reloaded.isProvisioned());
        assertFalse(reloaded.isProvisionedFor(OTHER_SHA256));
        assertFalse(reloaded.isProvisionedFor("A".repeat(64)));
    }

    @Test
    public void unknownMarkerVersionsAreNotProvisioned() throws Exception {
        FileStore store = newStore("version");
        Controller controller = new Controller(store, CLOCK);
        Instance created = controller.create(new DistroReference("arch", "latest"), rootVolume("version"));
        controller.recordProvisioning(created.id(), new ProvisioningEvidence(2, ROOTFS_SHA256, CLOCK.millis()));

        Instance reloaded = store.load(created.id());
        assertFalse(reloaded.isProvisioned());
        assertFalse(reloaded.isProvisionedFor(ROOTFS_SHA256));
        assertEquals(2, reloaded.provisioningEvidence().markerVersion());
    }

    @Test
    public void evidenceWithoutAResolvedRootfsShaIsNeverAProvisionedMatch() throws Exception {
        FileStore store = newStore("unknown-sha");
        Controller controller = new Controller(store, CLOCK);
        Instance created = controller.create(new DistroReference("opensuse", "15.6"), rootVolume("unknown-sha"));
        Instance recorded = controller.recordProvisioning(
                created.id(), new ProvisioningEvidence(1, null, CLOCK.millis()));

        assertTrue(recorded.isProvisioned());
        assertNull(recorded.provisioningEvidence().rootfsSha256());
        Instance reloaded = store.load(created.id());
        assertTrue(reloaded.isProvisioned());
        assertFalse(reloaded.isProvisionedFor(ROOTFS_SHA256));
    }

    @Test
    public void recordingPreservesTheCurrentTransitionAndDiagnostics() throws Exception {
        FileStore store = newStore("preserve");
        Controller controller = new Controller(store, CLOCK);
        Instance created = controller.create(new DistroReference("alma", "9"), rootVolume("preserve"));
        controller.beginProvisioning(created.id());
        controller.completeProvisioning(created.id());
        Instance running = controller.start(created.id());
        assertEquals(State.RUNNING, running.state());

        Instance recorded = controller.recordProvisioning(
                running.id(), new ProvisioningEvidence(1, ROOTFS_SHA256, CLOCK.millis()));
        assertEquals("recording evidence must not change lifecycle state", State.RUNNING, recorded.state());
        assertEquals(recorded.id(), store.load(created.id()).id());
        assertTrue(store.load(created.id()).isProvisioned());

        controller.recordCrash(created.id(), 137, null, "guest power loss");
        Instance crashed = controller.recordProvisioning(
                created.id(), new ProvisioningEvidence(1, ROOTFS_SHA256, CLOCK.millis()));
        assertEquals(State.CRASHED, crashed.state());
        assertEquals("guest power loss", crashed.crashDiagnostic().message());
        assertTrue(crashed.isProvisioned());
    }

    @Test
    public void deletingAnInstanceRemovesItsEvidenceFile() throws Exception {
        FileStore store = newStore("delete");
        Controller controller = new Controller(store, CLOCK);
        Instance created = controller.create(new DistroReference("rocky", "9"), rootVolume("delete"));
        controller.recordProvisioning(created.id(), new ProvisioningEvidence(1, ROOTFS_SHA256, CLOCK.millis()));
        controller.delete(created.id());

        assertFalse(Files.exists(metadataFile("delete", created.id())));
        try {
            store.load(created.id());
            fail("deleted instance metadata must not be readable");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void markerVersionWithoutRecordingTimeReadsAsNotProvisioned() throws Exception {
        FileStore store = newStore("partial");
        Controller controller = new Controller(store, CLOCK);
        Instance created = controller.create(new DistroReference("ubuntu", "24.04"), rootVolume("partial"));
        Path metadata = metadataFile("partial", created.id());
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metadata)) {
            properties.load(input);
        }
        properties.setProperty("provisioning.markerVersion", "1");
        properties.setProperty("provisioning.rootfsSha256", ROOTFS_SHA256);
        properties.remove("provisioning.recordedAtMillis");
        try (java.io.OutputStream output = Files.newOutputStream(metadata)) {
            properties.store(output, "partial evidence");
        }

        Instance reloaded = store.load(created.id());
        assertFalse("incomplete evidence must keep the conservative full path", reloaded.isProvisioned());
        assertNull(reloaded.provisioningEvidence());
        assertFalse(reloaded.isProvisionedFor(ROOTFS_SHA256));
    }

    @Test
    public void malformedProvisioningVersionIsRejectedAsInvalidMetadata() throws Exception {
        FileStore store = newStore("malformed");
        Controller controller = new Controller(store, CLOCK);
        Instance created = controller.create(new DistroReference("ubuntu", "24.04"), rootVolume("malformed"));
        Path metadata = metadataFile("malformed", created.id());
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metadata)) {
            properties.load(input);
        }
        properties.setProperty("provisioning.markerVersion", "not-a-number");
        try (java.io.OutputStream output = Files.newOutputStream(metadata)) {
            properties.store(output, "malformed evidence");
        }
        try {
            store.load(created.id());
            fail("malformed provisioning metadata must not be silently accepted");
        } catch (IOException expected) {
            // expected
        }
    }

    private FileStore newStore(String name) throws IOException {
        Path directory = temporaryFolder.newFolder(name).toPath().resolve("instances");
        return new FileStore(directory);
    }

    private Path rootVolume(String name) throws IOException {
        return temporaryFolder.newFile(name + ".raw").toPath();
    }

    private Path metadataFile(String storeName, String instanceId) {
        return temporaryFolder.getRoot().toPath()
                .resolve(storeName)
                .resolve("instances")
                .resolve(instanceId + ".properties");
    }
}
