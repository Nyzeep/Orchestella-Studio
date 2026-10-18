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

/** Contract for the production Provisioning evidence recorder used by QEMU launches. */
public final class ProvisioningEvidenceRecorderTest {
    private static final String RESOLVED_SHA256 = "c".repeat(64);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void resolvesTheCatalogShaForAKnownRootfsFileName() {
        DistroCatalogRegistry catalog = DistroCatalogRegistry.createDefault();
        for (DistroManifest manifest : catalog.listAll()) {
            assertEquals(manifest.sha256(), QemuInstanceLauncher.resolveRootfsSha256(manifest.rootfsFileName()));
        }
    }

    @Test
    public void unresolvedRootfsNamesResolveToNullInsteadOfGuessing() {
        assertNull(QemuInstanceLauncher.resolveRootfsSha256("not-a-catalog-rootfs.tar.gz"));
        assertNull(QemuInstanceLauncher.resolveRootfsSha256(""));
        assertNull(QemuInstanceLauncher.resolveRootfsSha256(null));
    }

    @Test
    public void noRecorderIsProducedWithoutAResolvedSha() throws Exception {
        InstanceLifecycle.Controller controller = newController();
        assertNull(QemuInstanceLauncher.provisioningRecorder(controller, "instance", null));
        assertNull(QemuInstanceLauncher.provisioningRecorder(null, "instance", RESOLVED_SHA256));
        assertNull(QemuInstanceLauncher.provisioningRecorder(controller, null, RESOLVED_SHA256));
    }

    @Test
    public void recorderWritesEvidenceOnlyForItsOwnInstanceAndNeverThrows() throws Exception {
        InstanceLifecycle.FileStore store = newStore();
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance target = controller.create(
                new InstanceLifecycle.DistroReference("ubuntu", "24.04"),
                temporaryFolder.newFile("target.raw").toPath());
        InstanceLifecycle.Instance other = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"),
                temporaryFolder.newFile("other.raw").toPath());

        PersistentTerminalSessionPool.ProvisioningListener recorder =
                QemuInstanceLauncher.provisioningRecorder(controller, target.id(), RESOLVED_SHA256);
        assertNotNull(recorder);

        recorder.onProvisioningComplete(other.id(), 1);
        assertFalse(store.load(other.id()).isProvisioned());

        recorder.onProvisioningComplete(target.id(), 1);
        InstanceLifecycle.Instance recorded = store.load(target.id());
        assertTrue(recorded.isProvisioned());
        assertTrue(recorded.isProvisionedFor(RESOLVED_SHA256));
        assertFalse(recorded.isProvisionedFor("d".repeat(64)));
        assertEquals(1, recorded.provisioningEvidence().markerVersion());

        // A missing metadata file must be a swallowed, best-effort failure.
        recorder.onProvisioningComplete("11111111-1111-1111-1111-111111111111", 1);
    }

    private InstanceLifecycle.FileStore newStore() throws IOException {
        return new InstanceLifecycle.FileStore(temporaryFolder.newFolder("instances").toPath());
    }

    private InstanceLifecycle.Controller newController() throws IOException {
        return new InstanceLifecycle.Controller(newStore(), Clock.systemUTC());
    }
}
