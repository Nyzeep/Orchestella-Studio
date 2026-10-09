package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pure JVM unit tests for {@link InstanceHardwareConfigEditor} (Phase 5.0 Task 3).
 *
 * Verifies:
 * <ul>
 *   <li>Lifecycle state machine guard (only STOPPED / CREATED instances allow modification);</li>
 *   <li>CPU core boundaries (1..8, bounded by host core count);</li>
 *   <li>RAM safety red-line boundaries (rejects Red Zone allocations threatening host OS stability);</li>
 *   <li>Disk expansion preflight validation (rejects shrinking and insufficient storage space);</li>
 *   <li>Atomic metadata update and sparse disk resize with next-boot notification.</li>
 * </ul>
 */
public class InstanceHardwareConfigEditorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private Path workDir;
    private InstanceLifecycle.FileStore store;
    private InstanceLifecycle.Controller controller;
    private HostHardwareProfile hostProfile;
    private Path rootVolume;

    @Before
    public void setUp() throws Exception {
        workDir = tempFolder.newFolder("hw_editor").toPath();
        store = new InstanceLifecycle.FileStore(workDir.resolve("metadata"));
        controller = new InstanceLifecycle.Controller(store, CLOCK);

        // Host profile: 8 cores, 8GB total RAM, 4GB available, 512MB threshold, not low memory
        hostProfile = new HostHardwareProfile(
                8,
                8L * 1024L * 1024L * 1024L,
                4L * 1024L * 1024L * 1024L,
                512L * 1024L * 1024L,
                false
        );

        rootVolume = workDir.resolve("rootfs.raw");
        try (RandomAccessFile raf = new RandomAccessFile(rootVolume.toFile(), "rw")) {
            raf.setLength(VmHardwareConfig.DEFAULT_DISK_CAPACITY_BYTES); // 8 GB
        }
    }

    private InstanceLifecycle.Instance createStoppedInstance() throws IOException {
        InstanceLifecycle.Instance inst = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                rootVolume
        );
        controller.beginProvisioning(inst.id());
        controller.completeProvisioning(inst.id());
        return store.load(inst.id());
    }

    @Test
    public void rejectsEditingWhenInstanceIsRunning() throws IOException {
        InstanceLifecycle.Instance instance = createStoppedInstance();
        controller.start(instance.id());
        InstanceLifecycle.Instance running = store.load(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING, running.state());

        VmHardwareConfig newConfig = VmHardwareConfig.builder()
                .cpuCores(4)
                .ramMegabytes(2048)
                .build();

        try {
            InstanceHardwareConfigEditor.apply(
                    running,
                    newConfig,
                    null,
                    controller,
                    hostProfile,
                    path -> 100L * 1024L * 1024L * 1024L
            );
            fail("Expected IllegalStateException when editing RUNNING instance");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("RUNNING"));
        }
    }

    @Test
    public void rejectsEditingWhenInstanceIsProvisioning() throws IOException {
        InstanceLifecycle.Instance inst = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                rootVolume
        );
        controller.beginProvisioning(inst.id());
        InstanceLifecycle.Instance provisioning = store.load(inst.id());
        assertEquals(InstanceLifecycle.State.PROVISIONING, provisioning.state());

        VmHardwareConfig newConfig = VmHardwareConfig.builder()
                .cpuCores(4)
                .ramMegabytes(2048)
                .build();

        try {
            InstanceHardwareConfigEditor.apply(
                    provisioning,
                    newConfig,
                    null,
                    controller,
                    hostProfile,
                    path -> 100L * 1024L * 1024L * 1024L
            );
            fail("Expected IllegalStateException when editing PROVISIONING instance");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("PROVISIONING"));
        }
    }

    @Test
    public void rejectsCpuCoresExceedingHostOrHardwareBounds() throws IOException {
        InstanceLifecycle.Instance instance = createStoppedInstance();

        // 1. Cores > host total cores (e.g. host has 4 cores, requesting 6)
        HostHardwareProfile quadCoreHost = new HostHardwareProfile(
                4,
                8L * 1024L * 1024L * 1024L,
                4L * 1024L * 1024L * 1024L,
                512L * 1024L * 1024L,
                false
        );

        VmHardwareConfig invalidCoresConfig = VmHardwareConfig.builder()
                .cpuCores(6)
                .ramMegabytes(2048)
                .build();

        try {
            InstanceHardwareConfigEditor.apply(
                    instance,
                    invalidCoresConfig,
                    null,
                    controller,
                    quadCoreHost,
                    path -> 100L * 1024L * 1024L * 1024L
            );
            fail("Expected IllegalArgumentException when cores exceed host total");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cores"));
        }
    }

    @Test
    public void rejectsRamExceedingHostRedZoneSafetyBoundary() throws IOException {
        InstanceLifecycle.Instance instance = createStoppedInstance();

        // Host has 4GB total RAM; requesting 3584 MB leaves less than mandatory 2GB reserve, triggering RED zone
        HostHardwareProfile constrainedHost = new HostHardwareProfile(
                4,
                4L * 1024L * 1024L * 1024L, // 4096 MB total
                2048L * 1024L * 1024L,
                512L * 1024L * 1024L,
                false
        );

        VmHardwareConfig dangerousRamConfig = VmHardwareConfig.builder()
                .cpuCores(2)
                .ramMegabytes(3584) // 3.5 GB out of 4 GB
                .build();

        try {
            InstanceHardwareConfigEditor.apply(
                    instance,
                    dangerousRamConfig,
                    null,
                    controller,
                    constrainedHost,
                    path -> 100L * 1024L * 1024L * 1024L
            );
            fail("Expected IllegalStateException when RAM enters RED safety zone");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("safety boundary") || expected.getMessage().contains("Red Zone"));
        }
    }

    @Test
    public void rejectsDiskShrinking() throws IOException {
        InstanceLifecycle.Instance instance = createStoppedInstance();
        long currentSize = Files.size(rootVolume); // 8 GB

        VmHardwareConfig config = VmHardwareConfig.builder()
                .cpuCores(2)
                .ramMegabytes(2048)
                .build();

        // Attempting to shrink disk to 4 GB
        long smallerDisk = 4L * 1024L * 1024L * 1024L;

        try {
            InstanceHardwareConfigEditor.apply(
                    instance,
                    config,
                    smallerDisk,
                    controller,
                    hostProfile,
                    path -> 100L * 1024L * 1024L * 1024L
            );
            fail("Expected IllegalArgumentException on disk shrinking");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("shrinking"));
        }
    }

    @Test
    public void rejectsDiskExpansionWhenHostSpaceIsInsufficient() throws IOException {
        InstanceLifecycle.Instance instance = createStoppedInstance();
        long currentSize = Files.size(rootVolume); // 8 GB
        long expandedSize = 16L * 1024L * 1024L * 1024L; // 16 GB (+8GB needed)

        VmHardwareConfig config = VmHardwareConfig.builder()
                .cpuCores(2)
                .ramMegabytes(2048)
                .build();

        // Space checker reports only 2 GB available (less than 8 GB needed)
        StorageContract.StorageSpaceChecker constrainedSpaceChecker = path -> 2L * 1024L * 1024L * 1024L;

        try {
            InstanceHardwareConfigEditor.apply(
                    instance,
                    config,
                    expandedSize,
                    controller,
                    hostProfile,
                    constrainedSpaceChecker
            );
            fail("Expected InsufficientStorageException when free space is inadequate");
        } catch (StorageContract.InsufficientStorageException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void successfullyAppliesConfigurationAndDiskExpansion() throws Exception {
        InstanceLifecycle.Instance instance = createStoppedInstance();
        long currentSize = Files.size(rootVolume);
        long expandedSize = 12L * 1024L * 1024L * 1024L; // 12 GB

        VmHardwareConfig newConfig = VmHardwareConfig.builder()
                .cpuCores(4)
                .ramMegabytes(3072) // Safe in 8GB host
                .diskCapacityBytes(expandedSize)
                .gpuAccelerationEnabled(true)
                .extraKernelArguments("console=ttyAMA0")
                .build();

        InstanceHardwareConfigEditor.EditResult result = InstanceHardwareConfigEditor.apply(
                instance,
                newConfig,
                expandedSize,
                controller,
                hostProfile,
                path -> 50L * 1024L * 1024L * 1024L // 50 GB free
        );

        assertNotNull(result);
        assertTrue(result.isDiskExpanded());
        assertEquals(expandedSize, result.getDiskCapacityBytes());
        assertTrue(result.getNoticeMessage().contains("生效") || result.getNoticeMessage().contains("next launch"));

        // Verify root volume size on disk was expanded
        assertEquals(expandedSize, Files.size(rootVolume));

        // Verify reloaded instance from store has new hardware configuration
        InstanceLifecycle.Instance reloaded = store.load(instance.id());
        assertEquals(4, reloaded.hardwareConfig().cpuCores());
        assertEquals(3072, reloaded.hardwareConfig().ramMegabytes());
        assertEquals(expandedSize, reloaded.hardwareConfig().diskCapacityBytes());
        assertTrue(reloaded.hardwareConfig().gpuAccelerationEnabled());
        assertEquals("console=ttyAMA0", reloaded.hardwareConfig().extraKernelArguments());
    }

    @Test
    public void successfullyAppliesConfigWithoutDiskExpansion() throws Exception {
        InstanceLifecycle.Instance instance = createStoppedInstance();
        long initialDiskSize = Files.size(rootVolume);

        VmHardwareConfig newConfig = VmHardwareConfig.builder()
                .cpuCores(3)
                .ramMegabytes(2048)
                .diskCapacityBytes(initialDiskSize)
                .gpuAccelerationEnabled(false)
                .build();

        InstanceHardwareConfigEditor.EditResult result = InstanceHardwareConfigEditor.apply(
                instance,
                newConfig,
                null,
                controller,
                hostProfile,
                path -> 50L * 1024L * 1024L * 1024L
        );

        assertNotNull(result);
        assertFalse(result.isDiskExpanded());
        assertEquals(initialDiskSize, Files.size(rootVolume));

        InstanceLifecycle.Instance reloaded = store.load(instance.id());
        assertEquals(3, reloaded.hardwareConfig().cpuCores());
        assertEquals(2048, reloaded.hardwareConfig().ramMegabytes());
        assertFalse(reloaded.hardwareConfig().gpuAccelerationEnabled());
    }
}
