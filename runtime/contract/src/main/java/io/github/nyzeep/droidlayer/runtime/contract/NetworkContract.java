package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strategy-neutral network contract and outbound specification models.
 *
 * <p>Implements ADR-0008 ("默认出站 NAT + 显式端口转发") and ADR-0010 ("安全边界：暴露面白名单制"):
 * <ul>
 *   <li>Outbound traffic is allowed by default using NAT without requiring guest configuration.</li>
 *   <li>IPv6 is explicitly disabled across all virtual machine tiers.</li>
 *   <li>Strategy-neutral specification: contains only semantic network configuration data;
 *       all hypervisor-specific command-line serialization (e.g. QEMU CLI arguments)
 *       is isolated in the corresponding engine layer.</li>
 *   <li>DNS traversal defaults to a public upstream DNS server ({@value #DEFAULT_FALLBACK_DNS})
 *       to ensure guest resolution is immediately functional on Android hosts without
 *       {@code /etc/resolv.conf}.</li>
 * </ul>
 */
public final class NetworkContract {
    public static final String DEFAULT_NETDEV_ID = "net0";
    public static final String DEFAULT_GUEST_NETWORK_CIDR = "10.0.2.0/24";
    public static final String DEFAULT_GUEST_DHCP_START = "10.0.2.15";

    public static final String DEFAULT_FALLBACK_DNS = "8.8.8.8";
    public static final String SECONDARY_FALLBACK_DNS = "1.1.1.1";

    public static final String GOOGLE_DNS = DEFAULT_FALLBACK_DNS;
    public static final String CLOUDFLARE_DNS = SECONDARY_FALLBACK_DNS;

    private static final Pattern NETDEV_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    // Strict IPv4 octet (0-255) without leading zeros to avoid octal parsing hazards
    private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])";
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^(?:" + IPV4_OCTET + "\\.){3}" + IPV4_OCTET + "$");
    private static final Pattern CIDR_PATTERN =
            Pattern.compile("^(?:" + IPV4_OCTET + "\\.){3}" + IPV4_OCTET + "/(?:[0-9]|[12][0-9]|3[0-2])$");

    private NetworkContract() {}

    /**
     * Supported virtual network interface device models (strategy-neutral).
     */
    public enum DeviceType {
        VIRTIO_NET_PCI,
        VIRTIO_NET_DEVICE
    }

    /**
     * Immutable specification for guest outbound network configuration.
     *
     * <p>Holds strategy-neutral network topology and DNS parameters. Engine implementations
     * (such as QEMU or AVF) consume this specification to configure their respective
     * device backends and hypervisor parameters.
     */
    public static final class OutboundNetworkSpec {
        private final String netdevId;
        private final String guestNetworkCidr;
        private final String guestDhcpStart;
        private final boolean ipv6Enabled;
        private final DeviceType deviceType;
        private final String dnsServer;
        private final String cmdlineDns;

        private OutboundNetworkSpec(
                String netdevId,
                String guestNetworkCidr,
                String guestDhcpStart,
                boolean ipv6Enabled,
                DeviceType deviceType,
                String dnsServer,
                String cmdlineDns) {
            this.netdevId = validateNetdevId(netdevId);
            this.guestNetworkCidr = validateCidr(guestNetworkCidr);
            this.guestDhcpStart = validateIpv4(guestDhcpStart, "guestDhcpStart");
            if (ipv6Enabled) {
                throw new IllegalArgumentException(
                        "IPv6 is not supported in the MVP networking contract and must be disabled per ADR-0008");
            }
            this.ipv6Enabled = false;
            this.deviceType = Objects.requireNonNull(deviceType, "deviceType");
            this.dnsServer = dnsServer != null ? validateIpv4(dnsServer, "dnsServer") : null;
            this.cmdlineDns = cmdlineDns != null ? validateIpv4(cmdlineDns, "cmdlineDns") : null;
        }

        /**
         * Creates a baseline outbound specification conforming strictly to ADR-0008.
         * Default configuration includes public DNS fallback ({@value #DEFAULT_FALLBACK_DNS})
         * for both the network backend and kernel cmdline.
         */
        public static OutboundNetworkSpec defaultSpec() {
            return new Builder().build();
        }

        /**
         * Creates an outbound specification with a specific primary DNS server.
         * Synchronously configures both the network backend DNS and kernel cmdline DNS.
         */
        public static OutboundNetworkSpec withDns(String dnsServer) {
            return new Builder()
                    .dnsServer(dnsServer)
                    .cmdlineDns(dnsServer)
                    .build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public static Builder builder(OutboundNetworkSpec prototype) {
            return new Builder(prototype);
        }

        public String netdevId() {
            return netdevId;
        }

        public String guestNetworkCidr() {
            return guestNetworkCidr;
        }

        public String guestDhcpStart() {
            return guestDhcpStart;
        }

        public boolean ipv6Enabled() {
            return ipv6Enabled;
        }

        public DeviceType deviceType() {
            return deviceType;
        }

        public String dnsServer() {
            return dnsServer;
        }

        public String cmdlineDns() {
            return cmdlineDns;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof OutboundNetworkSpec)) {
                return false;
            }
            OutboundNetworkSpec that = (OutboundNetworkSpec) o;
            return ipv6Enabled == that.ipv6Enabled
                    && Objects.equals(netdevId, that.netdevId)
                    && Objects.equals(guestNetworkCidr, that.guestNetworkCidr)
                    && Objects.equals(guestDhcpStart, that.guestDhcpStart)
                    && deviceType == that.deviceType
                    && Objects.equals(dnsServer, that.dnsServer)
                    && Objects.equals(cmdlineDns, that.cmdlineDns);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    netdevId, guestNetworkCidr, guestDhcpStart, ipv6Enabled, deviceType, dnsServer, cmdlineDns);
        }

        @Override
        public String toString() {
            return "OutboundNetworkSpec{"
                    + "netdevId='" + netdevId + '\''
                    + ", guestNetworkCidr='" + guestNetworkCidr + '\''
                    + ", guestDhcpStart='" + guestDhcpStart + '\''
                    + ", ipv6Enabled=" + ipv6Enabled
                    + ", deviceType=" + deviceType
                    + ", dnsServer='" + dnsServer + '\''
                    + ", cmdlineDns='" + cmdlineDns + '\''
                    + '}';
        }

        public static final class Builder {
            private String netdevId = DEFAULT_NETDEV_ID;
            private String guestNetworkCidr = DEFAULT_GUEST_NETWORK_CIDR;
            private String guestDhcpStart = DEFAULT_GUEST_DHCP_START;
            private boolean ipv6Enabled = false;
            private DeviceType deviceType = DeviceType.VIRTIO_NET_PCI;
            private String dnsServer = DEFAULT_FALLBACK_DNS;
            private String cmdlineDns = DEFAULT_FALLBACK_DNS;

            public Builder() {}

            public Builder(OutboundNetworkSpec prototype) {
                Objects.requireNonNull(prototype, "prototype");
                this.netdevId = prototype.netdevId;
                this.guestNetworkCidr = prototype.guestNetworkCidr;
                this.guestDhcpStart = prototype.guestDhcpStart;
                this.ipv6Enabled = prototype.ipv6Enabled;
                this.deviceType = prototype.deviceType;
                this.dnsServer = prototype.dnsServer;
                this.cmdlineDns = prototype.cmdlineDns;
            }

            public Builder netdevId(String netdevId) {
                this.netdevId = netdevId;
                return this;
            }

            public Builder guestNetworkCidr(String guestNetworkCidr) {
                this.guestNetworkCidr = guestNetworkCidr;
                return this;
            }

            public Builder guestDhcpStart(String guestDhcpStart) {
                this.guestDhcpStart = guestDhcpStart;
                return this;
            }

            public Builder ipv6Enabled(boolean ipv6Enabled) {
                this.ipv6Enabled = ipv6Enabled;
                return this;
            }

            public Builder deviceType(DeviceType deviceType) {
                this.deviceType = deviceType;
                return this;
            }

            public Builder dnsServer(String dnsServer) {
                this.dnsServer = dnsServer;
                return this;
            }

            public Builder cmdlineDns(String cmdlineDns) {
                this.cmdlineDns = cmdlineDns;
                return this;
            }

            public OutboundNetworkSpec build() {
                return new OutboundNetworkSpec(
                        netdevId,
                        guestNetworkCidr,
                        guestDhcpStart,
                        ipv6Enabled,
                        deviceType,
                        dnsServer,
                        cmdlineDns);
            }
        }

        private static String validateNetdevId(String id) {
            Objects.requireNonNull(id, "netdevId");
            if (id.trim().isEmpty() || !NETDEV_ID_PATTERN.matcher(id).matches()) {
                throw new IllegalArgumentException("netdevId must be a non-empty alphanumeric identifier: " + id);
            }
            return id;
        }

        private static String validateCidr(String cidr) {
            Objects.requireNonNull(cidr, "guestNetworkCidr");
            if (!CIDR_PATTERN.matcher(cidr).matches()) {
                throw new IllegalArgumentException("guestNetworkCidr must be a valid IPv4 CIDR: " + cidr);
            }
            return cidr;
        }

        private static String validateIpv4(String ip, String fieldName) {
            Objects.requireNonNull(ip, fieldName);
            if (!IPV4_PATTERN.matcher(ip).matches()) {
                throw new IllegalArgumentException(fieldName + " must be a valid IPv4 address: " + ip);
            }
            return ip;
        }
    }
}
