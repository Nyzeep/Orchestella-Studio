package io.github.nyzeep.orchestella.runtime.engineqemu;

import io.github.nyzeep.orchestella.runtime.contract.NetworkContract;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.contract.SharedDirectoryContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.util.Collections;
import java.util.List;

/**
 * JVM unit tests for QEMU GPU acceleration launch specification (M4 Ticket 57 / ADR-0019).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>When {@code gpuAccelerationEnabled == true}, {@code -device virtio-gpu-gl-device} is appended;</li>
 *   <li>When {@code gpuAccelerationEnabled == false}, {@code -device virtio-gpu-device} is appended (2D fallback);</li>
 *   <li>Default configuration activates GPU 3D acceleration;</li>
 *   <li>Overloaded launch builders preserve full compatibility with network, shared directory, and QMP features.</li>
 * </ul>
 */
public final class QemuGpuLaunchSpecTest {

    public static void main(String[] args) {
        testGpuAccelerationEnabledUsesVirtioGpuGlDevice();
        testGpuAccelerationDisabledUsesVirtioGpuDevice();
        testDefaultConfigUsesVirtioGpuGlDevice();
        testLegacyMemoryMbSpecHasVirtioGpuGlDeviceByDefault();
        testLegacyMemoryMbExplicitGpuToggle();
        testFullOptionsSpecWithGpuToggle();
        System.out.println("ALL_QEMU_GPU_LAUNCH_SPEC_TESTS_PASSED");
    }

    private static void testGpuAccelerationEnabledUsesVirtioGpuGlDevice() {
        VmHardwareConfig config = VmHardwareConfig.builder()
                .cpuCores(4)
                .ramMegabytes(4096)
                .gpuAccelerationEnabled(true)
                .build();

        String[] spec = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                config);

        requireDevice(spec, "virtio-gpu-gl-device");
        forbidDevice(spec, "virtio-gpu-device");
        System.out.println("TEST_GPU_ENABLED_OK");
    }

    private static void testGpuAccelerationDisabledUsesVirtioGpuDevice() {
        VmHardwareConfig config = VmHardwareConfig.builder()
                .cpuCores(2)
                .ramMegabytes(2048)
                .gpuAccelerationEnabled(false)
                .build();

        String[] spec = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                config);

        requireDevice(spec, "virtio-gpu-device");
        forbidDevice(spec, "virtio-gpu-gl-device");
        System.out.println("TEST_GPU_DISABLED_OK");
    }

    private static void testDefaultConfigUsesVirtioGpuGlDevice() {
        VmHardwareConfig config = VmHardwareConfig.defaultConfig();

        String[] spec = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                config);

        requireDevice(spec, "virtio-gpu-gl-device");
        forbidDevice(spec, "virtio-gpu-device");
        System.out.println("TEST_DEFAULT_CONFIG_GPU_OK");
    }

    private static void testLegacyMemoryMbSpecHasVirtioGpuGlDeviceByDefault() {
        String[] spec = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                1024L);

        requireDevice(spec, "virtio-gpu-gl-device");
        forbidDevice(spec, "virtio-gpu-device");
        System.out.println("TEST_LEGACY_MEMORY_GPU_DEFAULT_OK");
    }

    private static void testLegacyMemoryMbExplicitGpuToggle() {
        String[] specDisabled = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                null,
                1024L,
                false,
                NetworkContract.OutboundNetworkSpec.defaultSpec(),
                Collections.emptyList(),
                null);

        requireDevice(specDisabled, "virtio-gpu-device");
        forbidDevice(specDisabled, "virtio-gpu-gl-device");

        String[] specEnabled = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                null,
                1024L,
                true,
                NetworkContract.OutboundNetworkSpec.defaultSpec(),
                Collections.emptyList(),
                null);

        requireDevice(specEnabled, "virtio-gpu-gl-device");
        forbidDevice(specEnabled, "virtio-gpu-device");
        System.out.println("TEST_LEGACY_EXPLICIT_GPU_TOGGLE_OK");
    }

    private static void testFullOptionsSpecWithGpuToggle() {
        VmHardwareConfig config = VmHardwareConfig.builder()
                .cpuCores(4)
                .ramMegabytes(4096)
                .gpuAccelerationEnabled(true)
                .extraKernelArguments("cgroup_enable=memory")
                .build();

        SharedDirectoryContract.SharedDirectorySpec shareSpec =
                SharedDirectoryContract.SharedDirectorySpec.defaultDownloads("/sdcard/Download");

        List<PortForwardingRule> rules = Collections.singletonList(
                PortForwardingRule.defaultRule("rule1", Protocol.TCP, 2222, 22));

        String[] spec = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                "qmp-sock-i1",
                config,
                NetworkContract.OutboundNetworkSpec.defaultSpec(),
                rules,
                shareSpec);

        requireDevice(spec, "virtio-gpu-gl-device");
        requireDevice(spec, "virtio-blk-device,drive=root");
        requireDevice(spec, "virtio-net-pci,netdev=net0");
        requireDevice(spec, "virtio-serial-device");
        requireDevice(spec, "virtio-9p-pci,fsdev=fsdev0,mount_tag=downloads");
        System.out.println("TEST_FULL_OPTIONS_GPU_OK");
    }

    private static void requireDevice(String[] spec, String deviceName) {
        for (int i = 0; i < spec.length - 1; i++) {
            if (spec[i].equals("-device") && spec[i + 1].equals(deviceName)) {
                return;
            }
        }
        fail("Missing expected -device " + deviceName);
    }

    private static void forbidDevice(String[] spec, String deviceName) {
        for (int i = 0; i < spec.length - 1; i++) {
            if (spec[i].equals("-device") && spec[i + 1].equals(deviceName)) {
                fail("Forbidden -device present: " + deviceName);
            }
        }
    }

    private static void fail(String message) {
        System.err.println("QEMU_GPU_LAUNCH_SPEC_TEST_FAILED: " + message);
        System.exit(1);
    }
}
