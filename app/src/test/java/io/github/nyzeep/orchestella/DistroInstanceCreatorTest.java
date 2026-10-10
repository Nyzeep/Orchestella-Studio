package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DistroInstanceCreatorTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rejectsAlteredDebianArchiveBeforeInstanceOrRootVolumeExists() throws Exception {
        Path privateDirectory = temporaryFolder.newFolder("private").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(privateDirectory.resolve("instances"));
        DistroInstanceCreator creator =
                new DistroInstanceCreator(
                        debianManifest(),
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
        DistroInstanceCreator creator =
                new DistroInstanceCreator(
                        debianManifest(),
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
    public void verifiedDebianArchiveCreatesPersistentStoppedInstanceAfterProvisioning() throws Exception {
        Path privateDirectory = temporaryFolder.newFolder("private").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(privateDirectory.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        DistroInstanceCreator creator = new DistroInstanceCreator(debianManifest(), controller, privateDirectory);

        InstanceLifecycle.Instance created =
                creator.create(new ByteArrayInputStream("valid-debian".getBytes(StandardCharsets.US_ASCII)), 12);

        assertEquals(InstanceLifecycle.State.CREATED, created.state());
        assertEquals("debian", created.distro().id());
        assertEquals("13", created.distro().version());
        assertTrue(Files.isRegularFile(created.rootVolume()));
        assertEquals(DistroInstanceCreator.DEFAULT_ROOT_VOLUME_BYTES, Files.size(created.rootVolume()));
        assertEquals(
                "valid-debian",
                new String(Files.readAllBytes(privateDirectory.resolve("distro-inputs/rootfs.tar.xz")), StandardCharsets.US_ASCII));
        assertEquals(created.id(), store.load(created.id()).id());
        controller.beginProvisioning(created.id());
        assertEquals(InstanceLifecycle.State.PROVISIONING, store.load(created.id()).state());
        controller.completeProvisioning(created.id());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(created.id()).state());
    }

    @Test
    public void supportsBothAlpineAndDebianInstancesSideBySideInStore() throws Exception {
        Path privateDirectory = temporaryFolder.newFolder("private").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(privateDirectory.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());

        DistroInstanceCreator alpineCreator = new DistroInstanceCreator(alpineManifest(), controller, privateDirectory);
        DistroInstanceCreator debianCreator = new DistroInstanceCreator(debianManifest(), controller, privateDirectory);

        InstanceLifecycle.Instance alpineInstance =
                alpineCreator.create(new ByteArrayInputStream("valid-alpine".getBytes(StandardCharsets.US_ASCII)), 12);
        InstanceLifecycle.Instance debianInstance =
                debianCreator.create(new ByteArrayInputStream("valid-debian".getBytes(StandardCharsets.US_ASCII)), 12);

        List<InstanceLifecycle.Instance> all = store.list();
        assertEquals(2, all.size());

        InstanceLifecycle.Instance loadedAlpine = store.load(alpineInstance.id());
        InstanceLifecycle.Instance loadedDebian = store.load(debianInstance.id());

        assertEquals("alpine", loadedAlpine.distro().id());
        assertEquals("3.24.1", loadedAlpine.distro().version());
        assertEquals("debian", loadedDebian.distro().id());
        assertEquals("13", loadedDebian.distro().version());
    }

    @Test
    public void verifiedArchiveWithCustomHardwareConfigAppliesConfig() throws Exception {
        Path privateDirectory = temporaryFolder.newFolder("private").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(privateDirectory.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());

        DistroInstanceCreator creator = new DistroInstanceCreator(alpineManifest(), controller, privateDirectory);
        io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig customConfig =
                io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.builder()
                        .cpuCores(4)
                        .ramMegabytes(4096)
                        .diskCapacityBytes(16L * 1024L * 1024L * 1024L)
                        .gpuAccelerationEnabled(true)
                        .build();

        InstanceLifecycle.Instance instance =
                creator.create(new ByteArrayInputStream("valid-alpine".getBytes(StandardCharsets.US_ASCII)), 12, customConfig);

        assertEquals(4, instance.hardwareConfig().cpuCores());
        assertEquals(4096, instance.hardwareConfig().ramMegabytes());
        assertEquals(16L * 1024L * 1024L * 1024L, instance.hardwareConfig().diskCapacityBytes());
        assertTrue(instance.hardwareConfig().gpuAccelerationEnabled());

        InstanceLifecycle.Instance loaded = store.load(instance.id());
        assertEquals(4, loaded.hardwareConfig().cpuCores());
        assertEquals(4096, loaded.hardwareConfig().ramMegabytes());
    }

    @Test
    public void creatorAcceptsAllManifestsFromRegistry() throws Exception {
        io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry registry =
                io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry.getInstance();
        Path privateDirectory = temporaryFolder.newFolder("registry-instances").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(privateDirectory.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());

        for (String distroId : registry.supportedDistroIds()) {
            DistroManifest manifest = registry.get(distroId);
            DistroInstanceCreator creator = new DistroInstanceCreator(manifest, controller, privateDirectory);
            assertEquals(distroId, creator.manifest().id());
            assertEquals(manifest.rootfsFileName(), creator.manifest().rootfsFileName());
        }
    }

    @Test
    public void createsInstanceWithCustomUserCredentialsAndHostname() throws Exception {
        Path privateDirectory = temporaryFolder.newFolder("private-user-creds").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(privateDirectory.resolve("instances"));
        DistroInstanceCreator creator =
                new DistroInstanceCreator(
                        alpineManifest(),
                        new InstanceLifecycle.Controller(store, Clock.systemUTC()),
                        privateDirectory);

        io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig userConfig =
                new io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig("developer", "DevPass2026", true, "workstation");

        InstanceLifecycle.Instance instance =
                creator.create(
                        new ByteArrayInputStream("valid-alpine".getBytes(StandardCharsets.US_ASCII)),
                        12,
                        VmHardwareConfig.defaultConfig(),
                        userConfig);

        assertEquals("developer", instance.defaultUser());
        assertEquals("DevPass2026", instance.guestPassword());
        assertEquals("workstation", instance.hostname());

        InstanceLifecycle.Instance loaded = store.load(instance.id());
        assertEquals("developer", loaded.defaultUser());
        assertEquals("DevPass2026", loaded.guestPassword());
        assertEquals("workstation", loaded.hostname());
    }

    private static DistroManifest debianManifest() {
        return new DistroManifest(
                "debian",
                "13",
                "aarch64",
                "https://example.invalid/rootfs.tar.xz",
                "rootfs.tar.xz",
                "3bf1e764eb3dc04530ca105b0d8f6c99606367b96a65124154ce7e43d6ba22d4",
                12);
    }

    private static DistroManifest alpineManifest() {
        return new DistroManifest(
                "alpine",
                "3.24.1",
                "aarch64",
                "https://example.invalid/alpine-minirootfs-3.24.1-aarch64.tar.gz",
                "alpine-minirootfs-3.24.1-aarch64.tar.gz",
                "2872e213efe4004c51574102e876b5a7f0c71017457a33ab0dcfcba27cbdc64c",
                12);
    }
}
