package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.droidlayer.runtime.distro.DistroManifest;
import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class AlpineInstanceCreatorTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rejectsAlteredArchiveBeforeInstanceOrRootVolumeExists() throws Exception {
        Path privateDirectory = temporaryFolder.newFolder("private").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(privateDirectory.resolve("instances"));
        AlpineInstanceCreator creator = new AlpineInstanceCreator(manifest(), new InstanceLifecycle.Controller(store, Clock.systemUTC()), privateDirectory);

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
        AlpineInstanceCreator creator = new AlpineInstanceCreator(manifest(), new InstanceLifecycle.Controller(store, Clock.systemUTC()), privateDirectory);
        InputStream unreadable = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("stream was consumed"); }
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
        AlpineInstanceCreator creator = new AlpineInstanceCreator(manifest(), controller, privateDirectory);

        InstanceLifecycle.Instance created = creator.create(new ByteArrayInputStream("valid-rootfs".getBytes(StandardCharsets.US_ASCII)), 12);

        assertEquals(InstanceLifecycle.State.CREATED, created.state());
        assertTrue(Files.isRegularFile(created.rootVolume()));
        assertEquals(AlpineInstanceCreator.ROOT_VOLUME_BYTES, Files.size(created.rootVolume()));
        assertEquals("valid-rootfs", new String(Files.readAllBytes(privateDirectory.resolve("distro-inputs/alpine-minirootfs-3.24.1-aarch64.tar.gz")), StandardCharsets.US_ASCII));
        assertEquals(created.id(), store.load(created.id()).id());
        controller.beginProvisioning(created.id());
        assertEquals(InstanceLifecycle.State.PROVISIONING, store.load(created.id()).state());
        controller.completeProvisioning(created.id());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(created.id()).state());
    }

    @Test
    public void rejectsCreationWhenStorageSpaceIsInsufficient() throws Exception {
        Path privateDirectory = temporaryFolder.newFolder("private-enospc").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(privateDirectory.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        io.github.nyzeep.droidlayer.runtime.contract.StorageContract.StorageSpaceChecker insufficientChecker =
                path -> 1024L * 1024L; // only 1 MiB available
        AlpineInstanceCreator creator = new AlpineInstanceCreator(
                manifest(), controller, privateDirectory, insufficientChecker);

        try {
            creator.create(new ByteArrayInputStream("valid-rootfs".getBytes(StandardCharsets.US_ASCII)), 12);
            fail("insufficient space must block creation");
        } catch (io.github.nyzeep.droidlayer.runtime.contract.StorageContract.InsufficientStorageException expected) {
            assertEquals(AlpineInstanceCreator.ROOT_VOLUME_BYTES, expected.requiredBytes());
            assertEquals(1024L * 1024L, expected.availableBytes());
        }

        assertTrue(store.list().isEmpty());
    }

    private static DistroManifest manifest() {
        return new DistroManifest(
                "alpine",
                "3.24.1",
                "aarch64",
                "https://example.invalid/alpine-minirootfs-3.24.1-aarch64.tar.gz",
                "alpine-minirootfs-3.24.1-aarch64.tar.gz",
                "d0fe6b16cc3d703834936a3a5d1b81bad0f40353821914274bb61526b9600c53",
                12);
    }
}
