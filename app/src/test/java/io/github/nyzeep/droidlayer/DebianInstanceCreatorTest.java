package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.droidlayer.runtime.distro.DistroManifest;
import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DebianInstanceCreatorTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rejectsAlteredArchiveBeforeInstanceOrRootVolumeExists() throws Exception {
        Path privateDirectory = temporaryFolder.newFolder("private").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(privateDirectory.resolve("instances"));
        DebianInstanceCreator creator =
                new DebianInstanceCreator(
                        manifest(),
                        new InstanceLifecycle.Controller(store, Clock.systemUTC()),
                        privateDirectory);

        try {
            creator.create(new ByteArrayInputStream("wrong-rootfs".getBytes(StandardCharsets.US_ASCII)), 12);
            fail("tampered archive must not create an Instance");
        } catch (DistroManifest.InputRejectedException expected) {
            assertEquals("rootfs-checksum-mismatch", expected.reason());
        }

        assertTrue(store.list().isEmpty());
        assertFalse(Files.exists(privateDirectory.resolve("root-volumes")));
    }

    @Test
    public void rejectsWrongDeclaredSizeWithoutReadingOrStagingAnInput() throws Exception {
        Path privateDirectory = temporaryFolder.newFolder("private").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(privateDirectory.resolve("instances"));
        DebianInstanceCreator creator =
                new DebianInstanceCreator(
                        manifest(),
                        new InstanceLifecycle.Controller(store, Clock.systemUTC()),
                        privateDirectory);
        InputStream unreadable =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("stream was consumed");
                    }
                };

        try {
            creator.create(unreadable, 11);
            fail("incorrect declared size must be rejected before staging");
        } catch (DistroManifest.InputRejectedException expected) {
            assertEquals("rootfs-size-mismatch", expected.reason());
        }

        assertFalse(Files.exists(privateDirectory.resolve("distro-inputs")));
        assertTrue(store.list().isEmpty());
    }

    @Test
    public void verifiedArchiveCreatesPersistentStoppedInstanceAfterProvisioning() throws Exception {
        Path privateDirectory = temporaryFolder.newFolder("private").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(privateDirectory.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        DebianInstanceCreator creator = new DebianInstanceCreator(manifest(), controller, privateDirectory);

        InstanceLifecycle.Instance created =
                creator.create(new ByteArrayInputStream("valid-debian".getBytes(StandardCharsets.US_ASCII)), 12);

        assertEquals(InstanceLifecycle.State.CREATED, created.state());
        assertEquals("debian", created.distro().id());
        assertEquals("13", created.distro().version());
        assertTrue(Files.isRegularFile(created.rootVolume()));
        assertEquals(DebianInstanceCreator.ROOT_VOLUME_BYTES, Files.size(created.rootVolume()));
        assertEquals(
                "valid-debian",
                new String(Files.readAllBytes(privateDirectory.resolve("distro-inputs/rootfs.tar.xz")), StandardCharsets.US_ASCII));
        assertEquals(created.id(), store.load(created.id()).id());
        controller.beginProvisioning(created.id());
        assertEquals(InstanceLifecycle.State.PROVISIONING, store.load(created.id()).state());
        controller.completeProvisioning(created.id());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(created.id()).state());
    }

    private static DistroManifest manifest() {
        return new DistroManifest(
                "debian",
                "13",
                "aarch64",
                "https://example.invalid/rootfs.tar.xz",
                "rootfs.tar.xz",
                "3bf1e764eb3dc04530ca105b0d8f6c99606367b96a65124154ce7e43d6ba22d4",
                12);
    }
}
