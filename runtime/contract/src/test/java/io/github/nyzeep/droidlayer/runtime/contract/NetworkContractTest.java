package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.Objects;

/**
 * Strategy-neutral contract tests for outbound network specification, SLIRP defaults,
 * DNS traversal, and zero inbound exposure (ADR-0008, ADR-0010).
 */
public final class NetworkContractTest {
    public static void main(String[] args) {
        defaultOutboundSpecHasExpectedBaselineParameters();
        supportsDnsConfigurationAndPresets();
        supportsAlternativeDeviceTypeVirtioNetDevice();
        rejectsIpv6PerAdr0008();
        validatesSubnetAndAddresses();
        enforcesZeroInboundExposureByDefault();
        configurationReplayAndImmutability();
        System.out.println("NETWORK_CONTRACT_TEST_OK");
    }

    private static void defaultOutboundSpecHasExpectedBaselineParameters() {
        NetworkContract.OutboundNetworkSpec spec = NetworkContract.OutboundNetworkSpec.defaultSpec();

        assertEquals("net0", spec.netdevId(), "default netdevId");
        assertEquals("10.0.2.0/24", spec.guestNetworkCidr(), "default guestNetworkCidr");
        assertEquals("10.0.2.15", spec.guestDhcpStart(), "default guestDhcpStart");
        assertFalse(spec.ipv6Enabled(), "ipv6 must be disabled by default (ADR-0008)");
        assertEquals(NetworkContract.DeviceType.VIRTIO_NET_PCI, spec.deviceType(), "default device type");
        assertEquals(null, spec.dnsServer(), "default dnsServer is null (standard QEMU gateway DNS)");
        assertEquals(null, spec.cmdlineDns(), "default cmdlineDns is null");
        assertFalse(spec.hasInboundExposure(), "inbound exposure must be zero by default");

        assertEquals(
                "user,id=net0,ipv6=off,net=10.0.2.0/24,dhcpstart=10.0.2.15",
                spec.toNetdevArg(),
                "default netdev argument string");
        assertEquals(
                "virtio-net-pci,netdev=net0",
                spec.toDeviceArg(),
                "default device argument string");
    }

    private static void supportsDnsConfigurationAndPresets() {
        // Google DNS preset
        NetworkContract.OutboundNetworkSpec googleDnsSpec =
                NetworkContract.OutboundNetworkSpec.withDns(NetworkContract.GOOGLE_DNS);
        assertEquals("8.8.8.8", googleDnsSpec.dnsServer(), "google dnsServer");
        assertEquals(
                "user,id=net0,ipv6=off,net=10.0.2.0/24,dhcpstart=10.0.2.15,dns=8.8.8.8",
                googleDnsSpec.toNetdevArg(),
                "netdev with google dns");

        // Cloudflare DNS preset
        NetworkContract.OutboundNetworkSpec cfDnsSpec =
                NetworkContract.OutboundNetworkSpec.withDns(NetworkContract.CLOUDFLARE_DNS);
        assertEquals("1.1.1.1", cfDnsSpec.dnsServer(), "cloudflare dnsServer");
        assertEquals(
                "user,id=net0,ipv6=off,net=10.0.2.0/24,dhcpstart=10.0.2.15,dns=1.1.1.1",
                cfDnsSpec.toNetdevArg(),
                "netdev with cloudflare dns");

        // DNS passed to kernel cmdline
        NetworkContract.OutboundNetworkSpec cmdlineSpec =
                NetworkContract.OutboundNetworkSpec.builder()
                        .dnsServer("8.8.8.8")
                        .cmdlineDns("8.8.8.8")
                        .build();
        assertEquals("8.8.8.8", cmdlineSpec.dnsServer(), "dnsServer");
        assertEquals("8.8.8.8", cmdlineSpec.cmdlineDns(), "cmdlineDns");
        assertEquals(
                "user,id=net0,ipv6=off,net=10.0.2.0/24,dhcpstart=10.0.2.15,dns=8.8.8.8",
                cmdlineSpec.toNetdevArg(),
                "netdev with dns");
    }

    private static void supportsAlternativeDeviceTypeVirtioNetDevice() {
        NetworkContract.OutboundNetworkSpec mmioSpec =
                NetworkContract.OutboundNetworkSpec.builder()
                        .deviceType(NetworkContract.DeviceType.VIRTIO_NET_DEVICE)
                        .build();
        assertEquals(NetworkContract.DeviceType.VIRTIO_NET_DEVICE, mmioSpec.deviceType(), "mmio device type");
        assertEquals(
                "virtio-net-device,netdev=net0",
                mmioSpec.toDeviceArg(),
                "mmio device argument string");
    }

    private static void rejectsIpv6PerAdr0008() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkContract.OutboundNetworkSpec.builder().ipv6Enabled(true).build(),
                "enabling IPv6 must be rejected per ADR-0008");
    }

    private static void validatesSubnetAndAddresses() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkContract.OutboundNetworkSpec.builder().netdevId("").build(),
                "blank netdevId must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkContract.OutboundNetworkSpec.builder().netdevId("net 0").build(),
                "netdevId with space must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkContract.OutboundNetworkSpec.builder().guestNetworkCidr("invalid-cidr").build(),
                "invalid CIDR must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkContract.OutboundNetworkSpec.builder().guestDhcpStart("999.999.999.999").build(),
                "invalid IP must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkContract.OutboundNetworkSpec.builder().dnsServer("not-an-ip").build(),
                "invalid DNS IP must be rejected");
    }

    private static void enforcesZeroInboundExposureByDefault() {
        NetworkContract.OutboundNetworkSpec spec = NetworkContract.OutboundNetworkSpec.defaultSpec();
        assertFalse(spec.hasInboundExposure(), "zero inbound exposure required");
        assertEquals(0, spec.inboundRulesCount(), "no inbound forwarding rules");
    }

    private static void configurationReplayAndImmutability() {
        NetworkContract.OutboundNetworkSpec original =
                NetworkContract.OutboundNetworkSpec.builder()
                        .netdevId("net0")
                        .guestNetworkCidr("10.0.2.0/24")
                        .guestDhcpStart("10.0.2.15")
                        .deviceType(NetworkContract.DeviceType.VIRTIO_NET_PCI)
                        .dnsServer("8.8.8.8")
                        .cmdlineDns("8.8.8.8")
                        .build();

        NetworkContract.OutboundNetworkSpec replayed =
                NetworkContract.OutboundNetworkSpec.builder(original).build();

        assertEquals(original, replayed, "builder replay equality");
        assertEquals(original.hashCode(), replayed.hashCode(), "hashCode equality");
        assertEquals(original.toNetdevArg(), replayed.toNetdevArg(), "netdev replay equality");
        assertEquals(original.toDeviceArg(), replayed.toDeviceArg(), "device replay equality");
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static <T extends Throwable> T assertThrows(
            Class<T> type, ThrowingRunnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return type.cast(throwable);
            }
            throw new AssertionError(description + ": unexpected exception", throwable);
        }
        throw new AssertionError(description + ": expected exception " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
