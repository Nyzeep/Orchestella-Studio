package io.github.nyzeep.orchestella.runtime.engineuml;

/** Stable IPv4 guest-side addresses across replacement passt processes. */
public final class UmlNetworkProfile {
    public static final String GUEST_ADDRESS = "10.0.2.15";
    public static final int PREFIX_LENGTH = 24;
    public static final String GUEST_GATEWAY = "10.0.2.2";
    public static final String GUEST_DNS = "10.0.2.3";
    public static final int GUEST_RELAY_PORT = 65353;

    private UmlNetworkProfile() {}
}
