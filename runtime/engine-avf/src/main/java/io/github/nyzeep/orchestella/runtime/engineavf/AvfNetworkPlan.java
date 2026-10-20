package io.github.nyzeep.orchestella.runtime.engineavf;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One explicit outbound-network decision for an AVF launch. AVF must not call
 * {@code useNetwork(true)} unconditionally: Android 17 routes a network-enabled
 * VM without a GPU to {@code crosvm_minimal}, which has no {@code net} feature
 * and exits with status 35. The plan is therefore data — mode, addressing, DNS
 * and whether a failed headless-GPU workaround may degrade to no network.
 */
public record AvfNetworkPlan(
        Mode mode,
        Addressing addressing,
        String guestIp,
        String gateway,
        String netmask,
        String dnsServer,
        boolean allowDegradeToNone) {

    public enum Mode {
        NONE,
        NATIVE_TAP
    }

    public enum Addressing {
        NONE,
        STATIC,
        DHCP
    }

    private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])";
    private static final Pattern IPV4 =
            Pattern.compile("^(?:" + IPV4_OCTET + "\\.){3}" + IPV4_OCTET + "$");

    public AvfNetworkPlan {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(addressing, "addressing");
        if (dnsServer == null || dnsServer.isBlank()) {
            throw new IllegalArgumentException("dnsServer must not be blank");
        }
        dnsServer = requireIpv4(dnsServer, "dnsServer");
        if (mode == Mode.NONE) {
            if (addressing != Addressing.NONE || guestIp != null || gateway != null || netmask != null) {
                throw new IllegalArgumentException("NONE cannot carry an addressing plan");
            }
        } else if (addressing == Addressing.STATIC) {
            guestIp = requireIpv4(guestIp, "guestIp");
            gateway = requireIpv4(gateway, "gateway");
            netmask = requireIpv4(netmask, "netmask");
        } else if (addressing == Addressing.DHCP) {
            if (guestIp != null || gateway != null || netmask != null) {
                throw new IllegalArgumentException("DHCP must not carry static addressing");
            }
        } else {
            throw new IllegalArgumentException("NATIVE_TAP requires STATIC or DHCP addressing");
        }
    }

    public boolean enabled() {
        return mode == Mode.NATIVE_TAP;
    }

    public boolean staticAddressing() {
        return addressing == Addressing.STATIC;
    }

    /** No network; DNS is still carried so a later retry can use it. */
    public static AvfNetworkPlan none(String dnsServer) {
        return new AvfNetworkPlan(Mode.NONE, Addressing.NONE, null, null, null, dnsServer, false);
    }

    public static AvfNetworkPlan nativeTapDhcp(String dnsServer, boolean allowDegradeToNone) {
        return new AvfNetworkPlan(Mode.NATIVE_TAP, Addressing.DHCP, null, null, null,
                dnsServer, allowDegradeToNone);
    }

    public static AvfNetworkPlan nativeTapStatic(String guestIp, String gateway, String netmask,
            String dnsServer, boolean allowDegradeToNone) {
        return new AvfNetworkPlan(Mode.NATIVE_TAP, Addressing.STATIC, guestIp, gateway, netmask,
                dnsServer, allowDegradeToNone);
    }

    /** No network plan without a DNS is not useful; use the contract's fallback. */
    public static AvfNetworkPlan none() {
        return none(io.github.nyzeep.orchestella.runtime.contract.NetworkContract.DEFAULT_FALLBACK_DNS);
    }

    private static String requireIpv4(String value, String field) {
        if (value == null || !IPV4.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be an IPv4 literal");
        }
        return value;
    }
}
