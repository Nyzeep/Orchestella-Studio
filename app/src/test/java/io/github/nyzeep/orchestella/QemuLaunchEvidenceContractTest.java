package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.time.Clock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Contract for the launch-evidence collaborator behind {@link QemuInstanceLauncher}'s
 * provisioning seam (2026-11-09 round A, seam 1): the shell keeps its original static
 * API, this test locks the extracted catalog/digest and persisted-evidence semantics.
 */
public final class QemuLaunchEvidenceContractTest {
    private static final String OTHER_SHA256 = "d".repeat(64);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void resolvesTheCatalogShaForEveryKnownRootfsFileName() {
        DistroCatalogRegistry catalog = DistroCatalogRegistry.createDefault();
        assertTrue("the default catalog must have entries", catalog.listAll().size() > 0);
        for (DistroManifest manifest : catalog.listAll()) {
            assertEquals(manifest.sha256(),
                    QemuLaunchEvidence.resolveRootfsSha256(manifest.rootfsFileName()));
        }
    }

    @Test
    public void unresolvedRootfsNamesResolveToNullInsteadOfGuessing() {
        assertNull(QemuLaunchEvidence.resolveRootfsSha256("not-a-catalog-rootfs.tar.gz"));
        assertNull(QemuLaunchEvidence.resolveRootfsSha256(""));
        assertNull(QemuLaunchEvidence.resolveRootfsSha256(null));
    }

    @Test
    public void distroIdStaysHonestForUnknownOrMissingFileNames() {
        DistroManifest known = DistroCatalogRegistry.createDefault().listAll().get(0);
        assertEquals(known.id(), QemuLaunchEvidence.resolveDistroId(known.rootfsFileName()));
        assertEquals("unknown", QemuLaunchEvidence.resolveDistroId("not-a-catalog-rootfs.tar.gz"));
        assertEquals("unknown", QemuLaunchEvidence.resolveDistroId(""));
        assertEquals("unknown", QemuLaunchEvidence.resolveDistroId(null));
    }

    @Test
    public void provisionedInstanceWithCurrentDigestUnlocksCredentialsOnlyLaunch() throws Exception {
        DistroManifest known = DistroCatalogRegistry.createDefault().listAll().get(0);
        java.nio.file.Path instances = temporaryFolder.newFolder("instances").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(instances);
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference(known.id(), known.version()),
                temporaryFolder.newFile("volume.raw").toPath());
        controller.recordProvisioning(instance.id(), new InstanceLifecycle.ProvisioningEvidence(
                1, known.sha256(), System.currentTimeMillis()));

        assertTrue(QemuLaunchEvidence.credentialsOnlyLaunch(
                instances, instance.id(), known.rootfsFileName()));
        assertTrue(QemuLaunchEvidence.isProvisionedForInstance(
                instances, instance.id(), known.sha256()));
    }

    @Test
    public void staleDigestOrMissingRecordStaysOnTheFullLaunchPath() throws Exception {
        DistroManifest known = DistroCatalogRegistry.createDefault().listAll().get(0);
        java.nio.file.Path instances = temporaryFolder.newFolder("instances").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(instances);
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference(known.id(), known.version()),
                temporaryFolder.newFile("volume.raw").toPath());
        controller.recordProvisioning(instance.id(), new InstanceLifecycle.ProvisioningEvidence(
                1, OTHER_SHA256, System.currentTimeMillis()));

        assertFalse("a digest drift must re-verify inputs, not trust stale evidence",
                QemuLaunchEvidence.credentialsOnlyLaunch(
                        instances, instance.id(), known.rootfsFileName()));
        assertFalse(QemuLaunchEvidence.isProvisionedForInstance(
                instances, instance.id(), known.sha256()));
        assertFalse("a missing record must not unlock credentials-only",
                QemuLaunchEvidence.credentialsOnlyLaunch(
                        instances, "11111111-1111-1111-1111-111111111111",
                        known.rootfsFileName()));
    }

    @Test
    public void nullArgumentsStayConservative() throws Exception {
        assertFalse(QemuLaunchEvidence.isProvisionedForInstance(null, "instance", "a".repeat(64)));
        assertFalse(QemuLaunchEvidence.isProvisionedForInstance(
                java.nio.file.Paths.get("nowhere"), null, "a".repeat(64)));
        assertFalse(QemuLaunchEvidence.isProvisionedForInstance(
                java.nio.file.Paths.get("nowhere"), "", "a".repeat(64)));
        assertFalse(QemuLaunchEvidence.isProvisionedForInstance(
                java.nio.file.Paths.get("nowhere"), "instance", null));
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(newStore(), Clock.systemUTC());
        assertNull(QemuLaunchEvidence.provisioningRecorder(null, "instance", "a".repeat(64)));
        assertNull(QemuLaunchEvidence.provisioningRecorder(controller, null, "a".repeat(64)));
        assertNull(QemuLaunchEvidence.provisioningRecorder(controller, "instance", null));
    }

    @Test
    public void recorderWritesEvidenceOnlyForItsOwnInstanceAndNeverThrows() throws Exception {
        InstanceLifecycle.FileStore store = newStore();
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance target = controller.create(
                new InstanceLifecycle.DistroReference("ubuntu", "24.04"),
                temporaryFolder.newFile("target.raw").toPath());
        InstanceLifecycle.Instance other = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"),
                temporaryFolder.newFile("other.raw").toPath());

        PersistentTerminalSessionPool.ProvisioningListener recorder =
                QemuLaunchEvidence.provisioningRecorder(controller, target.id(), OTHER_SHA256);
        assertNotNull(recorder);

        recorder.onProvisioningComplete(other.id(), 1);
        assertFalse(store.load(other.id()).isProvisioned());

        recorder.onProvisioningComplete(target.id(), 1);
        InstanceLifecycle.Instance recorded = store.load(target.id());
        assertTrue(recorded.isProvisioned());
        assertTrue(recorded.isProvisionedFor(OTHER_SHA256));

        // A missing metadata file must be a swallowed, best-effort failure.
        recorder.onProvisioningComplete("11111111-1111-1111-1111-111111111111", 1);
    }

    private InstanceLifecycle.FileStore newStore() throws IOException {
        return new InstanceLifecycle.FileStore(temporaryFolder.newFolder("instances").toPath());
    }
}
