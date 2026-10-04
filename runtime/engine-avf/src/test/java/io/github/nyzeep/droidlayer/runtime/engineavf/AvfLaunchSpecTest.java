package io.github.nyzeep.droidlayer.runtime.engineavf;

/** Observable AvfLaunchSpec construction and validation behavior. */
public final class AvfLaunchSpecTest {
    public static void main(String[] arguments) {
        buildsTheDefaultSpecWithHvcConsoleAndDefaultPorts();
        rejectsBlankPathsAndBadInstanceIds();
        rejectsInvalidMemoryAndPorts();
        System.out.println("AVF_LAUNCH_SPEC_TEST_OK defaults=1 validation=6");
    }

    private static void buildsTheDefaultSpecWithHvcConsoleAndDefaultPorts() {
        AvfLaunchSpec spec = AvfLaunchSpec.builder(
                        "instance-1", "/data/kernel", "/data/initramfs", "/data/root.raw")
                .build();

        assertEquals("instance-1", spec.instanceId(), "instance id");
        assertEquals(512L, spec.memoryMb(), "default memory");
        assertEquals(9100, spec.controlVsockPort(), "default control vsock port");
        assertEquals(9101, spec.terminalVsockPort(), "default terminal vsock port");
        assertTrue(
                spec.guestCmdline().contains("console=hvc0"),
                "crosvm/pKVM boots through virtio-console hvc0");
        assertTrue(
                spec.guestCmdline().contains("droidlayer.root_device=/dev/vda"),
                "the raw root disk is the first virtio-blk device");
        assertTrue(
                spec.guestCmdline().contains("droidlayer.input_tag=droidlayer-input"),
                "the embedded-provision input tag matches the QEMU guest contract");
        assertTrue(
                spec.guestCmdline().contains("droidlayer.vsock_control_port=9100")
                        && spec.guestCmdline().contains("droidlayer.vsock_terminal_port=9101"),
                "guest agent learns both vsock service ports from the cmdline");
    }

    private static void rejectsBlankPathsAndBadInstanceIds() {
        rejects(IllegalArgumentException.class, () ->
                AvfLaunchSpec.builder("instance-1", " ", "/initramfs", "/root.raw"));
        rejects(IllegalArgumentException.class, () ->
                AvfLaunchSpec.builder("instance-1", "/kernel", "", "/root.raw"));
        rejects(IllegalArgumentException.class, () ->
                AvfLaunchSpec.builder("instance-1", "/kernel", "/initramfs", null));
        rejects(IllegalArgumentException.class, () ->
                AvfLaunchSpec.builder("../escape", "/kernel", "/initramfs", "/root.raw"));
        rejects(IllegalArgumentException.class, () ->
                AvfLaunchSpec.builder("bad id with spaces", "/kernel", "/initramfs", "/root.raw"));
    }

    private static void rejectsInvalidMemoryAndPorts() {
        AvfLaunchSpec.Builder valid =
                AvfLaunchSpec.builder("instance-1", "/kernel", "/initramfs", "/root.raw");
        rejects(IllegalArgumentException.class, () -> valid.memoryMb(0));
        rejects(IllegalArgumentException.class, () -> valid.memoryMb(16385));
        rejects(IllegalArgumentException.class, () -> valid.controlVsockPort(80));
        rejects(IllegalArgumentException.class, () -> valid.terminalVsockPort(65536));
        rejects(IllegalArgumentException.class, () ->
                valid.controlVsockPort(9101).terminalVsockPort(9101).build());
    }

    private static void rejects(Class<? extends Exception> expected, Runnable action) {
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
        System.out.println("AVF_LAUNCH_SPEC_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
