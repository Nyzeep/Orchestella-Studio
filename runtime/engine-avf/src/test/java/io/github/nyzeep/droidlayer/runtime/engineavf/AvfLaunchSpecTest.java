package io.github.nyzeep.droidlayer.runtime.engineavf;

import io.github.nyzeep.droidlayer.runtime.contract.NetworkContract;

/** Observable AvfLaunchSpec construction and validation behavior. */
public final class AvfLaunchSpecTest {
    public static void main(String[] arguments) {
        buildsTheDefaultSpecWithHvcConsoleAndDefaultPorts();
        carriesTheOutboundNetworkSpecAndTheSharedGuestDnsKey();
        appliesTheRequestedCpuCount();
        rejectsBlankPathsAndBadInstanceIds();
        rejectsInvalidMemoryPortsAndCpuCounts();
        System.out.println("AVF_LAUNCH_SPEC_TEST_OK defaults=1 outbound_net=1 cpu_count=1 validation=9");
    }

    private static void buildsTheDefaultSpecWithHvcConsoleAndDefaultPorts() {
        AvfLaunchSpec spec = AvfLaunchSpec.builder(
                        "instance-1", "/data/kernel", "/data/initramfs", "/data/root.raw")
                .build();

        assertEquals("instance-1", spec.instanceId(), "instance id");
        assertEquals(512L, spec.memoryMb(), "default memory");
        assertEquals(1, spec.cpuCount(), "default vCPU count");
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

    /**
     * Ticket 36 AC-1: the AVF profile consumes the SAME strategy-neutral
     * outbound network specification as the QEMU profile (ADR-0008) and
     * announces the same {@code droidlayer.dns=} guest contract key, so the
     * guest provisioning resolves DNS identically on both strategies.
     */
    private static void carriesTheOutboundNetworkSpecAndTheSharedGuestDnsKey() {
        AvfLaunchSpec spec = AvfLaunchSpec.builder(
                        "instance-1", "/data/kernel", "/data/initramfs", "/data/root.raw")
                .build();
        NetworkContract.OutboundNetworkSpec network = spec.networkSpec();

        assertEquals(
                NetworkContract.OutboundNetworkSpec.defaultSpec(),
                network,
                "default outbound network spec is exactly the ADR-0008 baseline");
        assertEquals("net0", network.netdevId(), "netdev id");
        assertEquals("10.0.2.0/24", network.guestNetworkCidr(), "guest network cidr");
        assertEquals("10.0.2.15", network.guestDhcpStart(), "guest dhcp start");
        assertEquals(NetworkContract.DEFAULT_FALLBACK_DNS, network.dnsServer(), "backend dns");
        assertEquals(NetworkContract.DEFAULT_FALLBACK_DNS, network.cmdlineDns(), "cmdline dns");
        assertTrue(!network.ipv6Enabled(), "IPv6 stays disabled per ADR-0008");
        assertTrue(
                spec.guestCmdline().contains("droidlayer.dns=" + NetworkContract.DEFAULT_FALLBACK_DNS),
                "the guest cmdline carries the same droidlayer.dns key as the QEMU profile: "
                        + spec.guestCmdline());

        AvfLaunchSpec customDns = AvfLaunchSpec.builder(
                        "instance-1", "/data/kernel", "/data/initramfs", "/data/root.raw")
                .networkSpec(NetworkContract.OutboundNetworkSpec.withDns(
                        NetworkContract.SECONDARY_FALLBACK_DNS))
                .build();
        assertEquals(
                NetworkContract.SECONDARY_FALLBACK_DNS,
                customDns.networkSpec().cmdlineDns(),
                "custom dns propagates through the spec");
        assertTrue(
                customDns.guestCmdline().contains("droidlayer.dns=" + NetworkContract.SECONDARY_FALLBACK_DNS),
                "custom dns reaches the guest cmdline");
    }

    private static void appliesTheRequestedCpuCount() {
        AvfLaunchSpec spec = AvfLaunchSpec.builder(
                        "instance-1", "/data/kernel", "/data/initramfs", "/data/root.raw")
                .cpuCount(4)
                .build();
        assertEquals(4, spec.cpuCount(), "requested vCPU count");
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

    private static void rejectsInvalidMemoryPortsAndCpuCounts() {
        AvfLaunchSpec.Builder valid =
                AvfLaunchSpec.builder("instance-1", "/kernel", "/initramfs", "/root.raw");
        rejects(IllegalArgumentException.class, () -> valid.memoryMb(0));
        rejects(IllegalArgumentException.class, () -> valid.memoryMb(16385));
        rejects(IllegalArgumentException.class, () -> valid.cpuCount(0));
        rejects(IllegalArgumentException.class, () -> valid.cpuCount(33));
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
