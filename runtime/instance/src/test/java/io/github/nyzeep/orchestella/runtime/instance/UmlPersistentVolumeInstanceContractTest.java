package io.github.nyzeep.orchestella.runtime.instance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class UmlPersistentVolumeInstanceContractTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochMilli(1_790_100_000_000L), ZoneOffset.UTC);

    @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void coexistsWithQemuInstancesPreservesOwnershipAcrossExportImportAndRetry()
            throws Exception {
        Path root = tempFolder.newFolder("coexist").toPath();
        Path storeDir = root.resolve("instances");
        Path volumesDir = Files.createDirectories(root.resolve("root-volumes"));
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path qemuVol = volumesDir.resolve("qemu.raw");
        Files.write(qemuVol, createSyntheticExt4(8192, (byte) 0x11));
        InstanceLifecycle.Instance qemu = controller.create(
                new InstanceLifecycle.DistroReference("ubuntu", "24.04"),
                qemuVol,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot());
        controller.beginProvisioning(qemu.id());
        controller.completeProvisioning(qemu.id());

        Path umlVol = volumesDir.resolve("uml.raw");
        byte[] umlDiskBytes = createSyntheticExt4(8192, (byte) 0x22);
        Files.write(umlVol, umlDiskBytes);
        ExecutionPolicyContract.InstanceExecutionIdentity umlIdentity =
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE);
        InstanceLifecycle.Instance uml = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.22.2"),
                umlVol,
                VmHardwareConfig.defaultConfig(),
                new InstanceUserConfig("root", "testpass123", false, "qa-uml-alpine"),
                umlIdentity);
        controller.beginProvisioning(uml.id());
        controller.recordProvisioning(
                uml.id(),
                new InstanceLifecycle.ProvisioningEvidence(
                        1, "6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489", 1_790_100_000_000L));
        controller.completeProvisioning(uml.id());

        // Simulate failed launch transition (RUNNING -> CRASHED) and retry via coldRestore.
        controller.start(uml.id());
        assertThrows(IllegalStateException.class, () -> controller.start(qemu.id()));
        assertThrows(
                IllegalStateException.class,
                () -> controller.exportToArchive(uml.id(), new ByteArrayOutputStream()));

        controller.recordCrash(uml.id(), 1, null, "simulated transient boot failure");
        controller.coldRestore(uml.id());
        controller.recordNormalGuestStop(uml.id());

        // Export & import preserves strategy, volume format, distro provenance, and disk bytes,
        // while issuing an independent instance id and fresh bridge token.
        ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
        controller.exportToArchive(uml.id(), zipOut);

        Path importedVol = volumesDir.resolve("uml-imported.raw");
        InstanceLifecycle.Instance imported = controller.importFromArchive(
                new ByteArrayInputStream(zipOut.toByteArray()),
                importedVol,
                UUID.randomUUID().toString());
        assertEquals(umlIdentity, imported.executionIdentity());
        assertEquals("alpine", imported.distro().id());
        assertEquals("3.22.2", imported.distro().version());
        assertTrue(imported.isProvisionedFor(
                "6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489"));
        assertArrayEquals(umlDiskBytes, Files.readAllBytes(importedVol));
        assertFalse(uml.bridgeToken().equals(imported.bridgeToken()));

        // Existing QEMU instance remains legacy-absent and untouched.
        assertTrue(store.load(qemu.id()).executionIdentity().isLegacyAbsent());

        // Deleting UML instance also sweeps leftover .provision-*.part sidecars in volumesDir.
        Files.writeString(volumesDir.resolve("uml.raw.provision-stale.part"), "leftover");
        controller.delete(uml.id());
        assertFalse(Files.exists(umlVol));
        assertFalse(Files.exists(volumesDir.resolve("uml.raw.provision-stale.part")));
        assertTrue(Files.isRegularFile(qemuVol));
        assertTrue(Files.isRegularFile(importedVol));
    }

    @Test
    public void validatesImportContractAndSerializesConcurrentStartAndExport() throws Exception {
        Path root = tempFolder.newFolder("import-lock").toPath();
        Path qcow2 = root.resolve("bad.qcow2");
        Files.write(qcow2, new byte[] {0x51, 0x46, 0x49, (byte) 0xFB, 0x00});
        assertThrows(
                IOException.class,
                () -> InstanceStorageManager.validateImportContract(
                        qcow2,
                        ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                                FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                                StorageContract.RootVolumeFormat.RAW_SPARSE)));

        Path storeDir = root.resolve("instances");
        Path volumesDir = Files.createDirectories(root.resolve("volumes"));
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path vol = volumesDir.resolve("inst.raw");
        Files.write(vol, createSyntheticExt4(4096, (byte) 0x33));
        InstanceLifecycle.Instance inst = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.22.2"),
                vol,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE));
        controller.beginProvisioning(inst.id());
        controller.completeProvisioning(inst.id());

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicReference<Throwable> threadAError = new AtomicReference<>();
        AtomicReference<Throwable> threadBError = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                startGate.await();
                controller.start(inst.id());
            } catch (Throwable t) {
                threadAError.set(t);
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                startGate.await();
                controller.exportToArchive(inst.id(), new ByteArrayOutputStream());
            } catch (Throwable t) {
                threadBError.set(t);
            }
        });
        t1.start();
        t2.start();
        startGate.countDown();
        t1.join(5_000L);
        t2.join(5_000L);

        // Either export ran before start (both succeeded) or start ran first and export was cleanly
        // rejected with IllegalStateException; neither may crash or corrupt metadata.
        assertEquals(null, threadAError.get());
        if (threadBError.get() != null) {
            assertTrue(threadBError.get() instanceof IllegalStateException);
        }
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(inst.id()).state());
    }

    private static byte[] createSyntheticExt4(int size, byte fill) {
        byte[] bytes = new byte[size];
        bytes[0x438] = 0x53;
        bytes[0x439] = (byte) 0xEF;
        bytes[size - 1] = fill;
        return bytes;
    }
}
