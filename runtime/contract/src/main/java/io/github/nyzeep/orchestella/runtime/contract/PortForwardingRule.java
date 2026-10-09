package io.github.nyzeep.orchestella.runtime.contract;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable specification of a port forwarding rule ({协议, 宿主端口, guest 端口, 绑定范围}).
 *
 * <p>Conforms to ADR-0008 ("默认出站 NAT + 显式端口转发") and ADR-0010 ("安全边界：暴露面白名单制"):
 * <ul>
 *   <li>The default bind address is strictly loopback ({@value #LOOPBACK_ADDRESS}),
 *       providing access only to the local device.</li>
 *   <li>Binding to all interfaces / LAN exposure ({@value #LAN_EXPOSED_ADDRESS}) must be explicitly
 *       confirmed via {@link Builder#allowLanExposure(boolean)}. Creating a LAN-exposed rule
 *       without this flag triggers a {@link SecurityException} and surfaces {@value #SECURITY_LAN_WARNING}.</li>
 *   <li>Rules are immutable and strategy-neutral, consumed equally by QEMU SLIRP and AVF tunnels.</li>
 * </ul>
 */
public final class PortForwardingRule {
    public static final String LOOPBACK_ADDRESS = "127.0.0.1";
    public static final String LAN_EXPOSED_ADDRESS = "0.0.0.0";

    public static final String SECURITY_LAN_WARNING =
            "LAN exposure binds to 0.0.0.0, allowing any device on your local network to access this guest service. "
                    + "This expands your attack surface (ADR-0008, ADR-0010).";

    private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])";
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^(?:" + IPV4_OCTET + "\\.){3}" + IPV4_OCTET + "$");

    private final String id;
    private final Protocol protocol;
    private final int hostPort;
    private final int guestPort;
    private final String bindAddress;
    private final boolean lanExposed;
    private final long createdAtMillis;
    private final String description;

    private PortForwardingRule(
            String id,
            Protocol protocol,
            int hostPort,
            int guestPort,
            String bindAddress,
            boolean lanExposed,
            long createdAtMillis,
            String description) {
        this.id = validateId(id);
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.hostPort = validatePort(hostPort, "hostPort");
        this.guestPort = validatePort(guestPort, "guestPort");
        this.bindAddress = validateBindAddress(bindAddress, lanExposed);
        this.lanExposed = lanExposed;
        this.createdAtMillis = createdAtMillis;
        this.description = description != null ? description.trim() : "";
    }

    /**
     * Creates a default port forwarding rule bound strictly to {@value #LOOPBACK_ADDRESS}.
     */
    public static PortForwardingRule defaultRule(
            String id, Protocol protocol, int hostPort, int guestPort) {
        return builder()
                .id(id)
                .protocol(protocol)
                .hostPort(hostPort)
                .guestPort(guestPort)
                .bindAddress(LOOPBACK_ADDRESS)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(PortForwardingRule prototype) {
        return new Builder(prototype);
    }

    public String id() {
        return id;
    }

    public Protocol protocol() {
        return protocol;
    }

    public int hostPort() {
        return hostPort;
    }

    public int guestPort() {
        return guestPort;
    }

    public String bindAddress() {
        return bindAddress;
    }

    public boolean isLanExposed() {
        return lanExposed;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public String description() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PortForwardingRule)) {
            return false;
        }
        PortForwardingRule that = (PortForwardingRule) o;
        return hostPort == that.hostPort
                && guestPort == that.guestPort
                && lanExposed == that.lanExposed
                && Objects.equals(id, that.id)
                && protocol == that.protocol
                && Objects.equals(bindAddress, that.bindAddress)
                && Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, protocol, hostPort, guestPort, bindAddress, lanExposed, description);
    }

    @Override
    public String toString() {
        return "PortForwardingRule{"
                + "id='" + id + '\''
                + ", protocol=" + protocol
                + ", hostPort=" + hostPort
                + ", guestPort=" + guestPort
                + ", bindAddress='" + bindAddress + '\''
                + ", lanExposed=" + lanExposed
                + ", description='" + description + '\''
                + '}';
    }

    public static final class Builder {
        private String id;
        private Protocol protocol = Protocol.TCP;
        private int hostPort;
        private int guestPort;
        private String bindAddress = LOOPBACK_ADDRESS;
        private boolean allowLanExposure = false;
        private long createdAtMillis = System.currentTimeMillis();
        private String description = "";

        public Builder() {}

        public Builder(PortForwardingRule prototype) {
            Objects.requireNonNull(prototype, "prototype");
            this.id = prototype.id;
            this.protocol = prototype.protocol;
            this.hostPort = prototype.hostPort;
            this.guestPort = prototype.guestPort;
            this.bindAddress = prototype.bindAddress;
            this.allowLanExposure = prototype.lanExposed;
            this.createdAtMillis = prototype.createdAtMillis;
            this.description = prototype.description;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder protocol(Protocol protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder hostPort(int hostPort) {
            this.hostPort = hostPort;
            return this;
        }

        public Builder guestPort(int guestPort) {
            this.guestPort = guestPort;
            return this;
        }

        public Builder bindAddress(String bindAddress) {
            this.bindAddress = bindAddress;
            return this;
        }

        public Builder allowLanExposure(boolean allowLanExposure) {
            this.allowLanExposure = allowLanExposure;
            return this;
        }

        public Builder createdAtMillis(long createdAtMillis) {
            this.createdAtMillis = createdAtMillis;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public PortForwardingRule build() {
            String ruleId = (id != null && !id.trim().isEmpty()) ? id : UUID.randomUUID().toString();
            boolean isLan = !LOOPBACK_ADDRESS.equals(bindAddress) && allowLanExposure;
            return new PortForwardingRule(
                    ruleId,
                    protocol,
                    hostPort,
                    guestPort,
                    bindAddress,
                    isLan,
                    createdAtMillis,
                    description);
        }
    }

    private static String validateId(String id) {
        Objects.requireNonNull(id, "rule id");
        String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("rule id must not be blank");
        }
        return trimmed;
    }

    private static int validatePort(int port, String fieldName) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(fieldName + " must be between 1 and 65535: " + port);
        }
        return port;
    }

    private static String validateBindAddress(String ip, boolean lanExposed) {
        Objects.requireNonNull(ip, "bindAddress");
        if (!IPV4_PATTERN.matcher(ip).matches()) {
            throw new IllegalArgumentException("bindAddress must be a valid IPv4 address without leading zeros: " + ip);
        }
        if (!LOOPBACK_ADDRESS.equals(ip) && !lanExposed) {
            throw new SecurityException(
                    "Binding to non-loopback address " + ip + " is not permitted without explicit LAN exposure acknowledgment. "
                            + SECURITY_LAN_WARNING);
        }
        return ip;
    }
}
