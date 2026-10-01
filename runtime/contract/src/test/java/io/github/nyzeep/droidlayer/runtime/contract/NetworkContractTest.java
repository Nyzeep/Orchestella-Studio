package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.Objects;

/**
 * Strategy-neutral contract tests for outbound network specification, SLIRP defaults,
 * DNS traversal defaults, and validation constraints (ADR-0008, ADR-0010).
 */
public final class NetworkContractTest {
    public static void main(String[] args) {
        defaultOutboundSpecHasExpectedBaselineParameters();
        supportsDnsConfigurationAndPresets();
        supportsAlternativeDeviceTypeVirtioNetDevice();
        rejectsIpv6PerAdr0008();
        validatesSubnetAndAddresses();
        rejectsLeadingZeroIpAddresses();
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

        // Spec AC-2 fix: Android hosts lack /etc/resolv.conf, so default spec must provide
        // reliable upstream public DNS fallback out of the box.
        assertEquals(NetworkContract.DEFAULT_FALLBACK_DNS, spec.dnsServer(), "default dnsServer fallback (8.8.8.8)");
        assertEquals(NetworkContract.DEFAULT_FALLBACK_DNS, spec.cmdlineDns(), "default cmdlineDns fallback (8.8.8.8)");
    }

    private static void supportsDnsConfigurationAndPresets() {
        // Cloudflare DNS preset with withDns helper (synchronously configures both channels)
        NetworkContract.OutboundNetworkSpec cfDnsSpec =
                NetworkContract.OutboundNetworkSpec.withDns(NetworkContract.CLOUDFLARE_DNS);
        assertEquals("1.1.1.1", cfDnsSpec.dnsServer(), "cloudflare dnsServer");
        assertEquals("1.1.1.1", cfDnsSpec.cmdlineDns(), "cloudflare cmdlineDns");

        // Custom DNS through builder
        NetworkContract.OutboundNetworkSpec customSpec =
                NetworkContract.OutboundNetworkSpec.builder()
                        .dnsServer("9.9.9.9")
                        .cmdlineDns("9.9.9.9")
                        .build();
        assertEquals("9.9.9.9", customSpec.dnsServer(), "custom dnsServer");
        assertEquals("9.9.9.9", customSpec.cmdlineDns(), "custom cmdlineDns");
    }

    private static void supportsAlternativeDeviceTypeVirtioNetDevice() {
        NetworkContract.OutboundNetworkSpec mmioSpec =
                NetworkContract.OutboundNetworkSpec.builder()
                        .deviceType(NetworkContract.DeviceType.VIRTIO_NET_DEVICE)
                        .build();
        assertEquals(NetworkContract.DeviceType.VIRTIO_NET_DEVICE, mmioSpec.deviceType(), "mmio device type");
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
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkContract.OutboundNetworkSpec.builder().cmdlineDns("not-an-ip").build(),
                "invalid cmdline DNS IP must be rejected");
    }

    private static void rejectsLeadingZeroIpAddresses() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkContract.OutboundNetworkSpec.builder().guestDhcpStart("010.0.2.15").build(),
                "IP with leading zero octet must be rejected to avoid octal misinterpretation");
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkContract.OutboundNetworkSpec.builder().dnsServer("08.8.8.8").build(),
                "DNS IP with leading zero octet must be rejected");
    }

    private static void configurationReplayAndImmutability() {
        NetworkContract.OutboundNetworkSpec original =
                NetworkContract.OutboundNetworkSpec.builder()
                        .netdevId("net0")
                        .guestNetworkCidr("10.0.2.0/24")
                        .guestDhcpStart("10.0.2.15")
                        .deviceType(NetworkContract.DeviceType.VIRTIO_NET_PCI)
                        .dnsServer(NetworkContract.DEFAULT_FALLBACK_DNS)
                        .cmdlineDns(NetworkContract.DEFAULT_FALLBACK_DNS)
                        .build();

        NetworkContract.OutboundNetworkSpec replayed =
                NetworkContract.OutboundNetworkSpec.builder(original).build();

        assertEquals(original, replayed, "builder replay equality");
        assertEquals(original.hashCode(), replayed.hashCode(), "hashCode equality");
    }

    private static void assertFalse(boolean condition, String description) {
        if (condition) {
            throw new AssertionError(description);
        }
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
