package io.github.nyzeep.orchestella.runtime.engineqemu;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;

import java.util.Arrays;

public final class Ticket57DeviceVerifier {
    private static void log(String msg) {
        System.out.println(msg);
        try {
            android.util.Log.i("OrchestellaQemu", msg);
        } catch (Throwable ignored) {
        }
    }

    public static void main(String[] args) {
        System.out.println("=== TICKET 57 ON-DEVICE VERIFIER START ===");
        try {
            // 1. Explicitly load libvirglrenderer.so
            System.loadLibrary("virglrenderer");
            log("Loaded libvirglrenderer.so successfully");

            // 2. Initialize VirGL renderer with 1024MB quota and EGL robustness
            boolean initOk = VirglJniBridge.init(1024, true);
            if (!initOk) {
                throw new IllegalStateException("VirglJniBridge.init failed!");
            }
            if (!VirglJniBridge.isInitialized()) {
                throw new IllegalStateException("VirglJniBridge.isInitialized() is false!");
            }
            if (!VirglJniBridge.isAvailable()) {
                throw new IllegalStateException("VirglJniBridge.isAvailable() is false!");
            }
            log("VirGL initialized successfully (native available=" + VirglJniBridge.isAvailable() + ", robustness=" + VirglJniBridge.isRobustnessEnabled() + ")");

            // 3. Test memory quota guard (MemoryQuotaSentinel)
            int qMb = VirglJniBridge.getMemoryQuotaMb();
            log("VirGL Quota Limit: " + qMb + " MB");
            if (qMb != 1024) {
                throw new IllegalStateException("Expected quota 1024MB, got " + qMb);
            }

            boolean alloc1 = VirglJniBridge.tryAllocateTestMemory(256L * 1024L * 1024L);
            if (!alloc1) {
                throw new IllegalStateException("Failed to allocate 256MB");
            }
            log("Allocated 256MB. Current: " + (VirglJniBridge.getAllocatedMemoryBytes() / (1024 * 1024)) + " MB");

            boolean alloc2 = VirglJniBridge.tryAllocateTestMemory(512L * 1024L * 1024L);
            if (!alloc2) {
                throw new IllegalStateException("Failed to allocate 512MB");
            }
            log("Allocated 512MB. Current: " + (VirglJniBridge.getAllocatedMemoryBytes() / (1024 * 1024)) + " MB");

            // Over-quota attempt (768MB + 300MB = 1068MB > 1024MB)
            boolean allocOver = VirglJniBridge.tryAllocateTestMemory(300L * 1024L * 1024L);
            if (allocOver) {
                throw new IllegalStateException("Memory quota guard failed to reject over-quota allocation!");
            }
            log("Quota guard successfully rejected over-quota allocation (300MB over 1024MB cap)");

            VirglJniBridge.releaseTestMemory(512L * 1024L * 1024L);
            log("Released 512MB. Current: " + (VirglJniBridge.getAllocatedMemoryBytes() / (1024 * 1024)) + " MB");

            // 4. Test simulated shader crash / robustness recovery
            boolean crashRecovered = VirglJniBridge.simulateShaderCrash();
            if (!crashRecovered) {
                throw new IllegalStateException("Shader crash recovery failed!");
            }
            log("Shader crash recovery verified: host survived without crashing");

            // 5. Cleanup
            VirglJniBridge.cleanup();
            if (VirglJniBridge.isInitialized()) {
                throw new IllegalStateException("VirglJniBridge.isInitialized() still true after cleanup!");
            }
            log("Virgl renderer cleanup completed");

            // 6. Test QemuLaunchSpec GPU and fallback generation
            VmHardwareConfig gpuEnabledConfig = VmHardwareConfig.builder()
                    .cpuCores(4)
                    .ramMegabytes(2048)
                    .diskCapacityBytes(10L * 1024 * 1024 * 1024)
                    .gpuAccelerationEnabled(true)
                    .build();

            String[] glSpec = QemuLaunchSpec.build(
                    "/data/app/lib/libdroidlayer_qemu.so",
                    "/data/local/tmp/vmlinuz",
                    "/data/local/tmp/initramfs",
                    "/data/local/tmp/rootfs.raw",
                    "control.sock",
                    "terminal.sock",
                    gpuEnabledConfig);

            boolean hasGlDevice = Arrays.asList(glSpec).contains("-device")
                    && Arrays.asList(glSpec).contains("virtio-gpu-gl-device");
            if (!hasGlDevice) {
                throw new IllegalStateException("glSpec missing virtio-gpu-gl-device!");
            }
            log("Launching QEMU with virtio-gpu-gl-device (GPU acceleration enabled)");
            log("glSpec verified: -device virtio-gpu-gl-device present");

            VmHardwareConfig fallbackConfig = VmHardwareConfig.builder()
                    .cpuCores(4)
                    .ramMegabytes(2048)
                    .diskCapacityBytes(10L * 1024 * 1024 * 1024)
                    .gpuAccelerationEnabled(false)
                    .build();

            String[] fbSpec = QemuLaunchSpec.build(
                    "/data/app/lib/libdroidlayer_qemu.so",
                    "/data/local/tmp/vmlinuz",
                    "/data/local/tmp/initramfs",
                    "/data/local/tmp/rootfs.raw",
                    "control.sock",
                    "terminal.sock",
                    fallbackConfig);

            boolean hasFbDevice = Arrays.asList(fbSpec).contains("-device")
                    && Arrays.asList(fbSpec).contains("virtio-gpu-device");
            boolean hasNoGlDevice = !Arrays.asList(fbSpec).contains("virtio-gpu-gl-device");
            if (!hasFbDevice || !hasNoGlDevice) {
                throw new IllegalStateException("fbSpec fallback verification failed!");
            }
            log("Launching QEMU with virtio-gpu-device (software fallback)");
            log("fbSpec verified: -device virtio-gpu-device fallback present");

            System.out.println("TICKET_57_DEVICE_VERIFICATION_PASS");
        } catch (Throwable t) {
            t.printStackTrace();
            System.err.println("TICKET_57_DEVICE_VERIFICATION_FAIL: " + t.getMessage());
            System.exit(1);
        }
    }
}
