package io.github.nyzeep.orchestella.runtime.engineqemu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * G-06 JVM state-machine tests for the production GPU lifecycle gate.
 *
 * <p>Verifies the three decision branches the launcher consumes
 * (native available / native unavailable / init failed) plus the
 * flag-off pass-through, the quota sizing rule and the grep-able
 * structured log line. The bridge seam keeps the tests hermetic: the
 * real {@link VirglJniBridge} is never initialized from here.
 */
public final class VirglGpuLifecycleTest {

    @After
    public void restoreDefaultBridge() {
        VirglGpuLifecycle.useBridgeForTest(null);
        QemuDeviceModelProbe.useRunnerForTest(null);
        QemuDeviceModelProbe.resetForTest();
    }

    /** Records seam interactions so tests can prove when init is (not) called. */
    private static final class RecordingBridge implements VirglGpuLifecycle.Bridge {
        final boolean available;
        final boolean initResult;
        final boolean throwOnInit;
        int availabilityCalls;
        int initCalls;
        int lastQuotaMb = -1;
        boolean lastRobustness = false;

        RecordingBridge(boolean available, boolean initResult, boolean throwOnInit) {
            this.available = available;
            this.initResult = initResult;
            this.throwOnInit = throwOnInit;
        }

        @Override public boolean isAvailable() {
            availabilityCalls++;
            return available;
        }

        @Override public boolean init(int quotaMb, boolean robustness) {
            initCalls++;
            lastQuotaMb = quotaMb;
            lastRobustness = robustness;
            if (throwOnInit) {
                throw new IllegalStateException("synthetic nativeInit crash");
            }
            return initResult;
        }
    }

    @Test
    public void flagOffReportsDisabledAndNeverTouchesTheBridge() {
        RecordingBridge bridge = new RecordingBridge(true, true, false);
        VirglGpuLifecycle.useBridgeForTest(bridge);

        GpuAccelerationStatus status = VirglGpuLifecycle.activate(false, 2048);

        assertEquals(GpuAccelerationStatus.State.DISABLED, status.state());
        assertEquals(GpuFallbackReason.NONE, status.reason());
        assertEquals(0, status.failureCode());
        assertEquals("virtio-gpu-device", status.deviceArg());
        assertFalse(status.gpuDeviceActive());
        assertEquals(0, bridge.availabilityCalls);
        assertEquals(0, bridge.initCalls);
    }

    @Test
    public void nativeUnavailableFallsBackTo2dWithoutCallingInit() {
        RecordingBridge bridge = new RecordingBridge(false, true, false);
        VirglGpuLifecycle.useBridgeForTest(bridge);

        GpuAccelerationStatus status = VirglGpuLifecycle.activate(true, 2048);

        assertEquals(GpuAccelerationStatus.State.FALLBACK, status.state());
        assertEquals(GpuFallbackReason.NATIVE_UNAVAILABLE, status.reason());
        assertEquals(0, status.failureCode());
        assertEquals("virtio-gpu-device", status.deviceArg());
        assertFalse(status.gpuDeviceActive());
        assertEquals(1, bridge.availabilityCalls);
        assertEquals("init must not be called when native is missing", 0, bridge.initCalls);
    }

    @Test
    public void nativeAvailableAndInitSucceedsActivatesGlDevice() {
        RecordingBridge bridge = new RecordingBridge(true, true, false);
        VirglGpuLifecycle.useBridgeForTest(bridge);

        GpuAccelerationStatus status = VirglGpuLifecycle.activate(true, 2048);

        assertEquals(GpuAccelerationStatus.State.ACTIVE, status.state());
        assertEquals(GpuFallbackReason.NONE, status.reason());
        assertEquals("virtio-gpu-gl-device", status.deviceArg());
        assertTrue(status.gpuDeviceActive());
        assertEquals(1024, status.quotaMb());
        assertEquals("init receives the quota derived from the instance RAM", 1024, bridge.lastQuotaMb);
        assertTrue("robustness stays on for the production path", bridge.lastRobustness);
        assertEquals(1, bridge.initCalls);
    }

    @Test
    public void initReturningFalseFallsBackWithInitFailed() {
        RecordingBridge bridge = new RecordingBridge(true, false, false);
        VirglGpuLifecycle.useBridgeForTest(bridge);

        GpuAccelerationStatus status = VirglGpuLifecycle.activate(true, 2048);

        assertEquals(GpuAccelerationStatus.State.FALLBACK, status.state());
        assertEquals(GpuFallbackReason.INIT_FAILED, status.reason());
        assertEquals("init() has no numeric error code; -1 records the unknown code",
                -1, status.failureCode());
        assertEquals("virtio-gpu-device", status.deviceArg());
        assertFalse(status.gpuDeviceActive());
    }

    @Test
    public void initThrowingIsIsolatedAndFallsBackWithInitFailed() {
        RecordingBridge bridge = new RecordingBridge(true, false, true);
        VirglGpuLifecycle.useBridgeForTest(bridge);

        GpuAccelerationStatus status = VirglGpuLifecycle.activate(true, 2048);

        assertEquals(GpuAccelerationStatus.State.FALLBACK, status.state());
        assertEquals(GpuFallbackReason.INIT_FAILED, status.reason());
        assertEquals("virtio-gpu-device", status.deviceArg());
    }

    @Test
    public void quotaMbFollowsInstanceRamCappedBySentinelDefault() {
        assertEquals("small instances keep their RAM as the GPU quota",
                512, VirglGpuLifecycle.quotaMbForRam(512));
        assertEquals("the 2048 MB default RAM caps at the 1024 MB sentinel default",
                1024, VirglGpuLifecycle.quotaMbForRam(2048));
        assertEquals(1024, VirglGpuLifecycle.quotaMbForRam(8192));
        assertEquals(1024, VirglGpuLifecycle.quotaMbForRam(1024));
        assertEquals("non-positive input degrades to the smallest usable quota",
                1, VirglGpuLifecycle.quotaMbForRam(0));
        assertEquals(1, VirglGpuLifecycle.quotaMbForRam(-5));
    }

    @Test
    public void reasonCodeLiteralsAreTheSingleSourceForLogs() {
        assertEquals("NATIVE_UNAVAILABLE", GpuFallbackReason.NATIVE_UNAVAILABLE.name());
        assertEquals("INIT_FAILED", GpuFallbackReason.INIT_FAILED.name());
        assertEquals("NONE", GpuFallbackReason.NONE.name());
    }

    @Test
    public void structuredLogLinesCarryGrepableStateAndReasonTokens() {
        RecordingBridge bridge = new RecordingBridge(true, true, false);
        VirglGpuLifecycle.useBridgeForTest(bridge);

        String active = VirglGpuLifecycle.activate(true, 2048).structuredLogLine();
        assertTrue(active.contains("gpu-acceleration"));
        assertTrue(active.contains("state=ACTIVE"));
        assertTrue(active.contains("reason=NONE"));
        assertTrue(active.contains("device=virtio-gpu-gl-device"));
        assertTrue(active.contains("quotaMb=1024"));

        VirglGpuLifecycle.useBridgeForTest(new RecordingBridge(false, true, false));
        String unavailable = VirglGpuLifecycle.activate(true, 2048).structuredLogLine();
        assertTrue(unavailable.contains("state=FALLBACK"));
        assertTrue(unavailable.contains("reason=NATIVE_UNAVAILABLE"));
        assertTrue(unavailable.contains("device=virtio-gpu-device"));

        VirglGpuLifecycle.useBridgeForTest(new RecordingBridge(true, false, false));
        String failed = VirglGpuLifecycle.activate(true, 2048).structuredLogLine();
        assertTrue(failed.contains("state=FALLBACK"));
        assertTrue(failed.contains("reason=INIT_FAILED"));
        assertTrue(failed.contains("code=-1"));

        String disabled = VirglGpuLifecycle.activate(false, 2048).structuredLogLine();
        assertTrue(disabled.contains("state=DISABLED"));
        assertTrue(disabled.contains("reason=NONE"));
        assertTrue(disabled.contains("device=virtio-gpu-device"));
    }

    @Test
    public void defaultBridgeDelegatesToVirglJniBridge() {
        VirglGpuLifecycle.useBridgeForTest(null);
        assertEquals("the default seam mirrors the real bridge availability",
                VirglJniBridge.isAvailable(), VirglGpuLifecycle.defaultBridge().isAvailable());
    }

    @Test
    public void activeStatusCarriesQuotaAndRobustnessForUiConsumers() {
        GpuAccelerationStatus status = GpuAccelerationStatus.active(512);
        assertEquals(GpuAccelerationStatus.State.ACTIVE, status.state());
        assertEquals(512, status.quotaMb());
        assertTrue(status.robustness());

        GpuAccelerationStatus disabled = GpuAccelerationStatus.disabled();
        assertEquals(GpuAccelerationStatus.State.DISABLED, disabled.state());
        assertEquals(0, disabled.quotaMb());
        assertFalse(disabled.robustness());

        GpuAccelerationStatus fallback =
                GpuAccelerationStatus.fallback(GpuFallbackReason.NATIVE_UNAVAILABLE, 0, 512);
        assertEquals(GpuAccelerationStatus.State.FALLBACK, fallback.state());
        assertEquals(GpuFallbackReason.NATIVE_UNAVAILABLE, fallback.reason());
        assertEquals(512, fallback.quotaMb());
    }

    // ---- DW-01: QEMU 设备模型探测门（打包 QEMU 缺 virtio-gpu-gl-device 时
    // 不得声称 ACTIVE，也不得把 QEMU exit 1 的死链路当加速链路）----

    private static final String PACKAGED_QEMU = "/native/lib/arm64-v8a/libdroidlayer_qemu.so";

    private static QemuDeviceModelProbe.CommandRunner stdoutOf(String stdout) {
        return (command, timeoutMs) -> stdout;
    }

    @Test
    public void deviceModelUnsupportedFallsBackWithoutCallingInit() {
        RecordingBridge bridge = new RecordingBridge(true, true, false);
        VirglGpuLifecycle.useBridgeForTest(bridge);
        QemuDeviceModelProbe.useRunnerForTest(
                stdoutOf("Supported devices:\nvirtio-gpu-device\nvirtio-net-device\n"));

        GpuAccelerationStatus status = VirglGpuLifecycle.activate(true, 2048, PACKAGED_QEMU);

        assertEquals(GpuAccelerationStatus.State.FALLBACK, status.state());
        assertEquals(GpuFallbackReason.QEMU_DEVICE_MODEL_UNSUPPORTED, status.reason());
        assertEquals(0, status.failureCode());
        assertEquals("the spec must downgrade to the 2D device", "virtio-gpu-device",
                status.deviceArg());
        assertFalse(status.gpuDeviceActive());
        assertEquals("init must not run against a binary that cannot boot the gl device",
                0, bridge.initCalls);
    }

    @Test
    public void deviceModelUnsupportedStructuredLogStatesTheRealReason() {
        RecordingBridge bridge = new RecordingBridge(true, true, false);
        VirglGpuLifecycle.useBridgeForTest(bridge);
        QemuDeviceModelProbe.useRunnerForTest(stdoutOf("virtio-gpu-device only"));

        String log = VirglGpuLifecycle.activate(true, 2048, PACKAGED_QEMU).structuredLogLine();

        assertTrue(log.contains("gpu-acceleration"));
        assertTrue("the log must never claim ACTIVE for a binary the gl device cannot boot on",
                log.contains("state=FALLBACK"));
        assertTrue(log.contains("reason=QEMU_DEVICE_MODEL_UNSUPPORTED"));
        assertTrue(log.contains("device=virtio-gpu-device"));
        assertTrue(log.contains("quotaMb=1024"));
    }

    @Test
    public void deviceModelSupportedKeepsTheOriginalActiveContract() {
        RecordingBridge bridge = new RecordingBridge(true, true, false);
        VirglGpuLifecycle.useBridgeForTest(bridge);
        QemuDeviceModelProbe.useRunnerForTest(
                stdoutOf("Supported devices:\nvirtio-gpu-gl-device\n"));

        GpuAccelerationStatus status = VirglGpuLifecycle.activate(true, 2048, PACKAGED_QEMU);

        assertEquals(GpuAccelerationStatus.State.ACTIVE, status.state());
        assertEquals("virtio-gpu-gl-device", status.deviceArg());
        assertTrue(status.gpuDeviceActive());
        assertEquals(1, bridge.initCalls);
        assertEquals(1024, bridge.lastQuotaMb);
    }

    @Test
    public void deviceModelProbeFailureFailsClosedWithoutInit() {
        RecordingBridge bridge = new RecordingBridge(true, true, false);
        VirglGpuLifecycle.useBridgeForTest(bridge);
        QemuDeviceModelProbe.CommandRunner failing = (command, timeoutMs) -> {
            throw new java.io.IOException("synthetic probe failure");
        };
        QemuDeviceModelProbe.useRunnerForTest(failing);

        GpuAccelerationStatus status = VirglGpuLifecycle.activate(true, 2048, PACKAGED_QEMU);

        assertEquals(GpuAccelerationStatus.State.FALLBACK, status.state());
        assertEquals(GpuFallbackReason.QEMU_DEVICE_MODEL_UNSUPPORTED, status.reason());
        assertEquals("a failed probe must not risk the QEMU exit-1 dead path", 0, bridge.initCalls);
    }

    @Test
    public void legacyTwoArgumentActivateSkipsTheDeviceModelGate() {
        // Compat contract for callers/tests that cannot supply the QEMU path:
        // the 2-arg overload keeps the pre-DW-01 behavior (probe never runs).
        RecordingBridge bridge = new RecordingBridge(true, true, false);
        VirglGpuLifecycle.useBridgeForTest(bridge);
        QemuDeviceModelProbe.useRunnerForTest(stdoutOf("virtio-gpu-device only"));

        GpuAccelerationStatus status = VirglGpuLifecycle.activate(true, 2048);

        assertEquals(GpuAccelerationStatus.State.ACTIVE, status.state());
        assertEquals("virtio-gpu-gl-device", status.deviceArg());
    }

    @Test
    public void nativeUnavailableStillWinsTheReasonSlotOverTheDeviceModel() {
        RecordingBridge bridge = new RecordingBridge(false, true, false);
        VirglGpuLifecycle.useBridgeForTest(bridge);
        QemuDeviceModelProbe.useRunnerForTest(stdoutOf("virtio-gpu-device only"));

        GpuAccelerationStatus status = VirglGpuLifecycle.activate(true, 2048, PACKAGED_QEMU);

        assertEquals(GpuFallbackReason.NATIVE_UNAVAILABLE, status.reason());
        assertEquals("the bridge availability check must stay the first gate",
                1, bridge.availabilityCalls);
        assertEquals(0, bridge.initCalls);
    }

    @Test
    public void qemuDeviceModelUnsupportedReasonLiteralIsPinnedForLogs() {
        assertEquals("QEMU_DEVICE_MODEL_UNSUPPORTED",
                GpuFallbackReason.QEMU_DEVICE_MODEL_UNSUPPORTED.name());
    }
}
