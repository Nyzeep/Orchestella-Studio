package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure JVM unit tests for InstanceCardUiModel presentation mappings (Ticket 52 / AC-4).
 */
public final class InstanceCardUiModelTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);

    private Path tempDir;
    private InstanceLifecycle.FileStore store;
    private InstanceLifecycle.Controller controller;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("instance-card-test-");
        store = new InstanceLifecycle.FileStore(tempDir);
        controller = new InstanceLifecycle.Controller(store, FIXED_CLOCK);
    }

    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var s = Files.walk(tempDir)) {
                s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });
            }
        }
    }

    @Test
    public void mapsRunningInstanceWithBreathingAndDisabledEditAndDelete() throws Exception {
        Path rootVol = tempDir.resolve("alpine-run.raw");
        Files.createFile(rootVol);

        VmHardwareConfig customConfig = VmHardwareConfig.builder()
                .cpuCores(4)
                .ramMegabytes(3072)
                .diskCapacityBytes(16L * 1024L * 1024L * 1024L)
                .gpuAccelerationEnabled(true)
                .build();

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.20"),
                rootVol,
                customConfig);

        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        instance = controller.start(instance.id());

        assertEquals(InstanceLifecycle.State.RUNNING, instance.state());

        InstanceCardUiModel model = InstanceCardUiModel.Companion.from(instance, "pKVM 硬件虚拟化", true);

        assertEquals("Alpine", model.getDistroDisplayName());
        assertEquals("3.20", model.getDistroVersion());
        assertTrue("RUNNING state must have breathing animation enabled", model.isBreathing());
        assertTrue(model.getStateBadgeText().contains("运行中"));
        assertTrue(model.getStateBadgeText().contains("pKVM 硬件虚拟化"));
        assertEquals(R.color.status_running, model.getStateBadgeColorRes());

        // Safety assertions
        assertFalse("Cannot edit hardware config while running", model.getCanConfigureHardware());
        assertFalse("Cannot clone instance while running", model.getCanClone());
        assertFalse("Cannot delete instance while running", model.getCanDelete());
        assertFalse("Cannot launch an already running instance", model.getCanLaunch());
        assertTrue("Can open terminal while running", model.getCanOpenTerminal());
        assertTrue("Can launch desktop when supported and running", model.getCanLaunchDesktop());

        // Hardware formatting
        assertEquals(4, model.getCpuCores());
        assertEquals(3072, model.getRamMb());
        assertEquals(16, model.getDiskGb());
        assertTrue(model.getGpuEnabled());
        assertEquals("4 vCPU · 3072 MB · 16 GB · GPU加速", model.getHardwareSpecsText());

        assertEquals("运行中", model.getIpAddress());
        InstanceCardUiModel qemuModel = InstanceCardUiModel.Companion.from(
                instance, "QEMU 软件虚拟化", true, NetworkGatewayDaemon.GUEST_IP);
        assertEquals(NetworkGatewayDaemon.GUEST_IP, qemuModel.getIpAddress());
    }

    @Test
    public void mapsStoppedInstanceWithActiveEditAndDeleteAndNoBreathing() throws Exception {
        Path rootVol = tempDir.resolve("debian-stop.raw");
        Files.createFile(rootVol);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "12"),
                rootVol);
        controller.beginProvisioning(instance.id());
        instance = controller.completeProvisioning(instance.id());

        assertEquals(InstanceLifecycle.State.STOPPED, instance.state());

        InstanceCardUiModel model = InstanceCardUiModel.Companion.from(instance, "QEMU 软模拟", false);

        assertEquals("Debian", model.getDistroDisplayName());
        assertFalse("STOPPED state must not have breathing animation", model.isBreathing());
        assertEquals("■ 已停止", model.getStateBadgeText());
        assertEquals(R.color.status_stopped, model.getStateBadgeColorRes());

        assertTrue("Can edit hardware config when stopped", model.getCanConfigureHardware());
        assertTrue("Can clone instance when stopped", model.getCanClone());
        assertTrue("Can delete instance when stopped", model.getCanDelete());
        assertTrue("Can launch when stopped", model.getCanLaunch());
        assertFalse("Cannot open terminal when stopped", model.getCanOpenTerminal());
        assertFalse("Cannot launch desktop when stopped", model.getCanLaunchDesktop());
    }

    @Test
    public void mapsCrashedInstanceWithDiagnosticBadge() throws Exception {
        Path rootVol = tempDir.resolve("alpine-crashed.raw");
        Files.createFile(rootVol);
        Path crashLog = tempDir.resolve("crash.log");
        Files.write(crashLog, "Fatal segfault in guest kernel\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.20"),
                rootVol);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        InstanceLifecycle.CrashDiagnostic diagnostic = new InstanceLifecycle.CrashDiagnostic(
                FIXED_CLOCK.millis(),
                139,
                crashLog,
                "Segmentation fault"
        );
        instance = controller.recordAbnormalTermination(instance.id(), diagnostic);

        assertEquals(InstanceLifecycle.State.CRASHED, instance.state());

        InstanceCardUiModel model = InstanceCardUiModel.Companion.from(instance, "QEMU 软模拟", true);

        assertFalse("CRASHED must not be breathing", model.isBreathing());
        assertTrue(model.getStateBadgeText().contains("异常终止"));
        assertEquals(R.color.status_crashed, model.getStateBadgeColorRes());
        assertTrue("Must indicate diagnostic available", model.getHasCrashDiagnostic());
        assertNotNull(model.getCrashDiagnostic());
        assertEquals(139, model.getCrashDiagnostic().exitCode());
        assertEquals("Segmentation fault", model.getCrashDiagnostic().message());

        assertTrue("Can delete crashed instance", model.getCanDelete());
        assertFalse("Cannot clone crashed instance", model.getCanClone());
        assertFalse("Cannot edit hardware directly on crashed instance", model.getCanConfigureHardware());
    }

    @Test
    public void mapsGpuDisabledHardwareSpecsProperly() throws Exception {
        Path rootVol = tempDir.resolve("alpine-nogpu.raw");
        Files.createFile(rootVol);

        VmHardwareConfig config = VmHardwareConfig.builder()
                .cpuCores(2)
                .ramMegabytes(2048)
                .diskCapacityBytes(8L * 1024L * 1024L * 1024L)
                .gpuAccelerationEnabled(false)
                .build();

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.20"),
                rootVol,
                config);

        InstanceCardUiModel model = InstanceCardUiModel.Companion.from(instance, "QEMU 软模拟", false);
        assertFalse(model.getGpuEnabled());
        assertEquals("2 vCPU · 2048 MB · 8 GB", model.getHardwareSpecsText());
    }
}
