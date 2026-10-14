package io.github.nyzeep.orchestella.runtime.engineavf;

import android.system.virtualmachine.VirtualMachineCustomImageConfig;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes the production reflective adapter against the fake AVF SDK: VM
 * assembly, vsock channels, and the precise failure-reason mapping that the
 * transparent fallback flow depends on.
 */
public final class AvfVirtualMachineEngineTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        assemblesTheCustomVmReflectivelyAndOpensBothVsockChannels();
        rejectsUnsupportedExactCpuCount();
        retriesVsockWhileTheSimulatedGuestBoots();
        rejectsNonPositiveConnectAttempts();
        observesGuestExitThroughTheRealNumericStatusSurface();
        mapsMissingSdkSurfaceToReflectionUnavailable();
        mapsManagerAbsenceToVmManagerUnavailable();
        mapsHardwareRefusalToHardwareUnsupported();
        mapsPermissionRefusalToPermissionDenied();
        System.out.println(
                "AVF_VIRTUAL_MACHINE_ENGINE_TEST_OK assembly=1 cpu_count=1 retry=1"
                        + " connect_guard=1 numeric_status=1 reflection_unavailable=1"
                        + " manager_unavailable=1 hardware_unsupported=1 permission_denied=1");
    }

    private static AvfLaunchSpec spec() {
        return AvfLaunchSpec.builder(
                        "engine-test-1", "/data/kernel", "/data/initramfs.cpio.gz", "/data/root.raw")
                .memoryMb(384)
                .build();
    }

    private static void assemblesTheCustomVmReflectivelyAndOpensBothVsockChannels()
            throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine engine =
                    new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.recordingApiOver(guest));
            AvfVirtualMachineEngine.AvfVmSession session = engine.launch(spec());

            assertTrue(!guest.observedConfigs().isEmpty(), "one config must reach the manager");
            VirtualMachineCustomImageConfig config = guest.observedConfigs().get(0);
            assertEquals("/data/kernel", config.kernelPath(), "kernel Image path");
            assertEquals("/data/initramfs.cpio.gz", config.initrdPath(), "initramfs path");
            assertEquals(1, config.disks().size(), "exactly the root disk");
            assertEquals("/data/root.raw", config.disks().get(0).path(), "raw root volume path");
            assertTrue(config.disks().get(0).writable(), "root volume must be writable");
            android.system.virtualmachine.VirtualMachineConfig vmConfig = guest.observedVmConfigs().get(0);
            assertEquals(402653184L, vmConfig.getMemoryBytes(), "memory on the VM builder in bytes");
            assertEquals(0, vmConfig.getCpuTopology(), "ONE_CPU topology, not an invented count setter");
            assertTrue(!vmConfig.isProtectedVm(), "custom VMs are non-protected");
            assertTrue(config.isNetworkSupported(), "outbound network declared for the AVF profile");
            assertTrue(
                    config.cmdline() != null
                            && config.cmdline().contains("droidlayer.vsock_control_port=9100")
                            && config.cmdline().contains("droidlayer.vsock_terminal_port=9101"),
                    "the guest contract travels through the kernel cmdline: "
                            + config.cmdline());
            assertTrue(
                    !config.cmdline().contains("droidlayer.vsock_9p_port"),
                    "no 9p data-plane token without a share: " + config.cmdline());

            assertEquals("engine-test-1", config.getName(), "custom image name");
            assertTrue(session.reflectionNotes().contains("run: applied"), "VM run was invoked");

            session.controlChannel().outputStream().write("ORCHESTELLA/PING\n".getBytes("US-ASCII"));
            session.controlChannel().outputStream().flush();
            assertEquals("ORCHESTELLA/PONG 1\n", readLine(session.controlChannel().inputStream()),
                    "control channel speaks the bridge wire");
            session.terminalChannel().outputStream().write("echo hi\n".getBytes("UTF-8"));
            session.terminalChannel().outputStream().flush();
            assertEquals("echo hi\n", readLine(session.terminalChannel().inputStream()),
                    "terminal channel echoes the shell");

            session.closeChannels();
        }
    }

    private static void retriesVsockWhileTheSimulatedGuestBoots() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101, 700)) {
            AvfVirtualMachineEngine engine =
                    new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.apiOver(guest));
            AvfVirtualMachineEngine.AvfVmSession session = engine.launch(spec());
            // connectWithRetry must bridge the simulated boot window, not fail.
            assertEquals("ORCHESTELLA/PONG 1\n",
                    exchange(session.controlChannel(), "ORCHESTELLA/PING\n"),
                    "control channel after the guest finished booting");
            session.closeChannels();
        }
    }

    private static void rejectsUnsupportedExactCpuCount() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfLaunchSpec spec = AvfLaunchSpec.builder(
                            "engine-cpu-1", "/data/kernel", "/data/initramfs.cpio.gz",
                            "/data/root.raw")
                    .memoryMb(384)
                    .cpuCount(4)
                    .build();
            AvfVirtualMachineEngine engine =
                    new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.recordingApiOver(guest));
            rejects(AvfEngineException.class, AvfEngineException.HARDWARE_UNSUPPORTED,
                    () -> engine.launch(spec));
            assertTrue(guest.observedConfigs().isEmpty(), "unsupported CPU count must not create VM");
        }
    }

    private static void rejectsNonPositiveConnectAttempts() {
        rejects(IllegalArgumentException.class, () ->
                new AvfVirtualMachineEngine(unavailableApi(), 0, 1));
        rejects(IllegalArgumentException.class, () ->
                new AvfVirtualMachineEngine(unavailableApi(), -3, 1));
        rejects(IllegalArgumentException.class, () ->
                new AvfVirtualMachineEngine(unavailableApi(), 2, -1));
    }

    private static ReflectiveAvfVirtualMachineApi unavailableApi() {
        return new ReflectiveAvfVirtualMachineApi(
                new Object(), AvfVirtualMachineApiFixture.sdkAbsentClassLoader());
    }

    private static void observesGuestExitThroughTheRealNumericStatusSurface() throws Exception {
        // Real AOSP VirtualMachine.getStatus() returns a @Status int boxed to
        // an Integer (0=STOPPED, 1=RUNNING, 2=DELETED). The fixture uses that
        // same numeric signature; no enum-shaped compatibility shortcut is accepted.
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AtomicInteger status = new AtomicInteger(1); // VirtualMachine.Status.STATUS_RUNNING
            AvfVirtualMachineEngine.AvfVmSession session = engineOverNumericStatus(guest,
                    status::get).launch(spec());

            ExitWatcher watcher = new ExitWatcher(session);
            watcher.join(500);
            assertTrue(watcher.isAlive(), "RUNNING(1) must keep the exit watcher waiting");
            status.set(0); // VirtualMachine.Status.STATUS_STOPPED
            watcher.join(2000);
            assertTrue(!watcher.isAlive(), "numeric STOPPED(0) must end the wait");
            assertEquals(0, watcher.exit(), "guest exit observed on the numeric surface");
        }

        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = engineOverNumericStatus(guest,
                    () -> 2 /* VirtualMachine.Status.STATUS_DELETED */).launch(spec());
            ExitWatcher watcher = new ExitWatcher(session);
            watcher.join(2000);
            assertTrue(!watcher.isAlive(), "numeric DELETED(2) must end the wait");
            assertEquals(0, watcher.exit(), "deleted guest reported as exited");
        }
    }

    private static AvfVirtualMachineEngine engineOverNumericStatus(
            SimulatedAvfGuest guest, java.util.function.IntSupplier statusSource) {
        return new AvfVirtualMachineEngine(new ReflectiveAvfVirtualMachineApi(
                guest.newContext(new android.system.virtualmachine.VirtualMachineManager(
                        (name, config) ->
                                android.system.virtualmachine.VirtualMachine.withNumericStatus(
                                        statusSource))),
                AvfVirtualMachineApiFixture.class.getClassLoader()));
    }

    private static final class ExitWatcher extends Thread {
        private final AvfVirtualMachineEngine.AvfVmSession session;
        private int exit = -1;

        ExitWatcher(AvfVirtualMachineEngine.AvfVmSession session) {
            this.session = session;
            setDaemon(true);
            start();
        }

        int exit() {
            return exit;
        }

        @Override
        public void run() {
            try {
                exit = session.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void mapsMissingSdkSurfaceToReflectionUnavailable() {
        AvfVirtualMachineApi unavailableApi = new ReflectiveAvfVirtualMachineApi(
                new Object(), AvfVirtualMachineApiFixture.sdkAbsentClassLoader());
        assertTrue(!unavailableApi.isReflectionAvailable(), "absent SDK must report unavailable");
        AvfVirtualMachineEngine engine = new AvfVirtualMachineEngine(unavailableApi, 2, 1);
        rejects(
                AvfEngineException.class,
                AvfEngineException.REFLECTION_UNAVAILABLE,
                () -> engine.launch(spec()));
    }

    private static void mapsManagerAbsenceToVmManagerUnavailable() {
        AvfVirtualMachineEngine engine =
                new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.apiWithoutService(), 2, 1);
        rejects(
                AvfEngineException.class,
                AvfEngineException.VM_MANAGER_UNAVAILABLE,
                () -> engine.launch(spec()));
    }

    private static void mapsHardwareRefusalToHardwareUnsupported() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            ReflectiveAvfVirtualMachineApi api = new ReflectiveAvfVirtualMachineApi(
                    guest.newContext(
                            new android.system.virtualmachine.VirtualMachineManager(
                                    (name, config) -> {
                                        throw new UnsupportedOperationException(
                                                "non-protected VMs not supported by hardware");
                                    })),
                    AvfVirtualMachineApiFixture.class.getClassLoader());
            AvfVirtualMachineEngine engine = new AvfVirtualMachineEngine(api, 2, 1);
            rejects(
                    AvfEngineException.class,
                    AvfEngineException.HARDWARE_UNSUPPORTED,
                    () -> engine.launch(spec()));
        }
    }

    private static void mapsPermissionRefusalToPermissionDenied() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            ReflectiveAvfVirtualMachineApi api = new ReflectiveAvfVirtualMachineApi(
                    guest.newContext(
                            new android.system.virtualmachine.VirtualMachineManager(
                                    (name, config) -> {
                                        throw new SecurityException("MANAGE_VIRTUAL_MACHINE");
                                    })),
                    AvfVirtualMachineApiFixture.class.getClassLoader());
            AvfVirtualMachineEngine engine = new AvfVirtualMachineEngine(api, 2, 1);
            rejects(
                    AvfEngineException.class,
                    AvfEngineException.PERMISSION_DENIED,
                    () -> engine.launch(spec()));
        }
    }

    private static String exchange(
            AvfVirtualMachineApi.AvfVsockChannel channel, String line) throws Exception {
        channel.outputStream().write(line.getBytes("US-ASCII"));
        channel.outputStream().flush();
        return readLine(channel.inputStream());
    }

    private static String readLine(java.io.InputStream in) throws Exception {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                return line + "\n";
            }
            line.append((char) b);
        }
        return line.toString();
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void rejects(
            Class<? extends Exception> expected, String expectedReason, ThrowingRunnable action) {
        try {
            action.run();
            fail("expected " + expected.getSimpleName());
        } catch (Exception thrown) {
            if (!expected.isInstance(thrown)) {
                fail("expected " + expected.getSimpleName() + " but got " + thrown);
            }
            String reason = ((AvfEngineException) thrown).reason();
            if (!expectedReason.equals(reason)) {
                fail("expected reason " + expectedReason + " but got " + reason);
            }
        }
    }

    /** Type-only variant for plain runtime guards (no AvfEngineException reason). */
    private static void rejects(Class<? extends Exception> expected, ThrowingRunnable action) {
        try {
            action.run();
            fail("expected " + expected.getSimpleName());
        } catch (Exception thrown) {
            if (!expected.isInstance(thrown)) {
                fail("expected " + expected.getSimpleName() + " but got " + thrown);
            }
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            fail(description);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void fail(String reason) {
        System.out.println("AVF_VIRTUAL_MACHINE_ENGINE_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
