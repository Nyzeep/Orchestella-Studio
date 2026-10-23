package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import io.github.nyzeep.orchestella.runtime.contract.NetworkContract;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Error;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AX-04: plans the guest-to-host accelerator uplink for the two QEMU network
 * backends.
 *
 * <p>Primary path is gvproxy: the guest connects to {@code 192.168.127.254},
 * the gvproxy {@code hostIP}, which gvproxy NATs to {@code 127.0.0.1} on the
 * host (gvproxy v0.9.0 defaults {@code NAT={HostIP:127.0.0.1}} and
 * {@code GatewayVirtualIPs=[HostIP]}). {@code 192.168.127.1} is the gvproxy
 * gateway and runs DNS/API services, not the host loopback, so it is not used.
 *
 * <p>Fallback is QEMU slirp: the guest connects to the second address of the
 * slirp subnet (default {@code 10.0.2.2}), which QEMU maps to the host.
 *
 * <p>The plan carries no network I/O and no free-form text. The token is
 * generated per run, appears in the guest environment and the AXP1 handshake,
 * and is never written to a public directory or printed by {@link Plan}.
 */
public final class AcceleratorUplink {
    public static final int DEFAULT_DISTCC_PORT = 3632;
    public static final int DEFAULT_JOB_LIMIT = 4;

    public static final String DISTCC_HOSTS_ENV = "DISTCC_HOSTS";
    public static final String TOKEN_ENV = "ORCHESTELLA_ACCEL_TOKEN";

    public static final String LOOPBACK_BIND = "127.0.0.1";
    public static final String GVPROXY_SUBNET = "192.168.127.0/24";
    public static final String GVPROXY_HOST_ALIAS = "192.168.127.254";

    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern CIDR = Pattern.compile(
            "(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})");
    private static final SecureRandom RANDOM = new SecureRandom();

    private AcceleratorUplink() {
    }

    /** QEMU network backend used by this plan. */
    public enum Backend {
        GVPROXY,
        SLIRP
    }

    /** Fixed uplink failure codes. */
    public enum FailureCode {
        UPLINK_UNAVAILABLE,
        UPLINK_BIND_FAILED,
        UPLINK_AUTH_FAILED
    }

    /** Typed uplink failure; the message is the fixed code. */
    public static final class UplinkException extends Exception {
        private static final long serialVersionUID = 1L;
        private final FailureCode code;

        public UplinkException(FailureCode code) {
            super(code.name());
            this.code = code;
        }

        public FailureCode code() {
            return code;
        }
    }

    /** Immutable host/guest configuration for one run. */
    public record Plan(
            Backend backend,
            String guestHost,
            int guestPort,
            String hostBindAddress,
            String distccHosts,
            String token,
            boolean ipv6Enabled) {
        public Plan {
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(guestHost, "guestHost");
            Objects.requireNonNull(hostBindAddress, "hostBindAddress");
            Objects.requireNonNull(distccHosts, "distccHosts");
            Objects.requireNonNull(token, "token");
            if (guestPort < 1 || guestPort > 65535) {
                throw new IllegalArgumentException("guestPort");
            }
        }

        /** Guest-side environment; immutable and never persisted here. */
        public Map<String, String> guestEnvironment() {
            return Map.of(
                    DISTCC_HOSTS_ENV, distccHosts,
                    TOKEN_ENV, token);
        }

        @Override
        public String toString() {
            return "Plan{backend=" + backend
                    + ", guestHost='" + guestHost + '\''
                    + ", guestPort=" + guestPort
                    + ", hostBindAddress='" + hostBindAddress + '\''
                    + ", distccHosts='" + distccHosts + '\''
                    + ", token=<redacted>"
                    + ", ipv6Enabled=" + ipv6Enabled
                    + '}';
        }
    }

    public static Plan plan(
            Backend backend,
            NetworkContract.OutboundNetworkSpec spec,
            int port,
            int jobLimit,
            String token) throws UplinkException {
        return plan(backend, spec, port, jobLimit, token, Set.of());
    }

    /**
     * Plans one uplink. {@code occupiedPorts} is a pure local check; no socket
     * is opened and no host address is resolved.
     */
    public static Plan plan(
            Backend backend,
            NetworkContract.OutboundNetworkSpec spec,
            int port,
            int jobLimit,
            String token,
            Collection<Integer> occupiedPorts) throws UplinkException {
        if (backend == null || spec == null) {
            throw new UplinkException(FailureCode.UPLINK_UNAVAILABLE);
        }
        if (spec.ipv6Enabled()) {
            throw new UplinkException(FailureCode.UPLINK_UNAVAILABLE);
        }
        if (port < 1 || port > 65535 || jobLimit < 1 || jobLimit > 64) {
            throw new UplinkException(FailureCode.UPLINK_UNAVAILABLE);
        }
        if (occupiedPorts != null && occupiedPorts.contains(port)) {
            throw new UplinkException(FailureCode.UPLINK_UNAVAILABLE);
        }
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new UplinkException(FailureCode.UPLINK_AUTH_FAILED);
        }

        String guestHost = backend == Backend.GVPROXY
                ? gvproxyHost(spec)
                : slirpHost(spec);
        String distccHosts = guestHost + ":" + port + "/" + jobLimit;
        return new Plan(backend, guestHost, port, LOOPBACK_BIND, distccHosts, token, false);
    }

    /** 32 lowercase hex characters from a per-process CSPRNG. */
    public static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Only loopback may be bound. The gvproxy hostIP and the slirp host alias
     * are guest-visible addresses, not host bind addresses.
     */
    public static void requirePrivateBind(String address) throws UplinkException {
        if (!LOOPBACK_BIND.equals(address)) {
            throw new UplinkException(FailureCode.UPLINK_BIND_FAILED);
        }
    }

    /** Maps an AXP1 error to the AX-04 failure vocabulary. */
    public static FailureCode classify(Axp1Error error) {
        if (error == Axp1Error.AUTH_FAILED) {
            return FailureCode.UPLINK_AUTH_FAILED;
        }
        return FailureCode.UPLINK_UNAVAILABLE;
    }

    private static String gvproxyHost(NetworkContract.OutboundNetworkSpec spec)
            throws UplinkException {
        if (!GVPROXY_SUBNET.equals(spec.guestNetworkCidr())) {
            throw new UplinkException(FailureCode.UPLINK_UNAVAILABLE);
        }
        return GVPROXY_HOST_ALIAS;
    }

    private static String slirpHost(NetworkContract.OutboundNetworkSpec spec)
            throws UplinkException {
        Matcher matcher = CIDR.matcher(spec.guestNetworkCidr());
        if (!matcher.matches()) {
            throw new UplinkException(FailureCode.UPLINK_UNAVAILABLE);
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = Integer.parseInt(matcher.group(i + 1));
            if (octets[i] > 255) {
                throw new UplinkException(FailureCode.UPLINK_UNAVAILABLE);
            }
        }
        int prefix = Integer.parseInt(matcher.group(5));
        if (prefix < 8 || prefix > 30) {
            throw new UplinkException(FailureCode.UPLINK_UNAVAILABLE);
        }
        long address = ((long) octets[0] << 24)
                | ((long) octets[1] << 16)
                | ((long) octets[2] << 8)
                | octets[3];
        long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        long network = address & mask;
        long host = (network + 2L) & 0xffffffffL;
        return ((host >> 24) & 0xff) + "."
                + ((host >> 16) & 0xff) + "."
                + ((host >> 8) & 0xff) + "."
                + (host & 0xff);
    }
}
