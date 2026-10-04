package io.github.nyzeep.droidlayer.runtime.engineavf;

import android.system.virtualmachine.VirtualMachineCustomImageConfig;

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
        retriesVsockWhileTheSimulatedGuestBoots();
        mapsMissingSdkSurfaceToReflectionUnavailable();
        mapsManagerAbsenceToVmManagerUnavailable();
        mapsHardwareRefusalToHardwareUnsupported();
        mapsPermissionRefusalToPermissionDenied();
        System.out.println(
                "AVF_VIRTUAL_MACHINE_ENGINE_TEST_OK assembly=1 retry=1 reflection_unavailable=1"
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
            assertEquals(384, config.memoryMbs(), "memory applied via the MiB setter");
            assertTrue(!config.isProtectedVm(), "custom VMs are non-protected");
            assertTrue(config.isNetworkSupported(), "outbound network declared for the AVF profile");

            assertTrue(
                    session.reflectionNotes().stream()
                            .anyMatch(note -> note.contains("addDisk(String,boolean): applied")),
                    "disk attachment variant recorded: " + session.reflectionNotes());
            assertTrue(
                    session.reflectionNotes().stream()
                            .anyMatch(note -> note.contains("setProtected: applied")),
                    "non-protected declaration recorded");

            session.controlChannel().outputStream().write("DROIDLAYER/PING\n".getBytes("US-ASCII"));
            session.controlChannel().outputStream().flush();
            assertEquals("DROIDLAYER/PONG 1\n", readLine(session.controlChannel().inputStream()),
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
            assertEquals("DROIDLAYER/PONG 1\n",
                    exchange(session.controlChannel(), "DROIDLAYER/PING\n"),
                    "control channel after the guest finished booting");
            session.closeChannels();
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
