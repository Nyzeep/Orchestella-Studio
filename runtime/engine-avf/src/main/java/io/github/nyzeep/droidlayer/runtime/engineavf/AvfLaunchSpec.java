package io.github.nyzeep.droidlayer.runtime.engineavf;

import io.github.nyzeep.droidlayer.runtime.contract.NetworkContract;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Engine-local launch specification for one AVF custom-VM boot. This is a
 * strategy-specific type by design (ADR-0002 layering): it never appears in
 * {@code runtime:contract}, exactly like {@code QemuLaunchSpec} on the other
 * engine.
 *
 * <p>Asset interop (ADR-0001): the kernel Image, the embedded-provision
 * initramfs and the raw root volume consumed here are the very same files the
 * QEMU strategy consumes — no conversion, no copy, one binary identity.
 *
 * <p>Network interop (ADR-0008, Ticket 36): the outbound network assembly
 * consumes the SAME strategy-neutral {@link NetworkContract.OutboundNetworkSpec}
 * the QEMU engine consumes; the crosvm network backend is enabled through the
 * AVF config's network-supported switch and the guest learns the resolver via
 * the same {@code droidlayer.dns=} kernel cmdline key.
 */
public final class AvfLaunchSpec {
    /**
     * crosvm/pKVM boots the custom kernel with virtio-console as the boot
     * console (hvc0); the droidlayer.* keys mirror the QEMU guest contract so
     * the same guest provisioning logic can consume them. The vsock ports
     * tell a vsock-speaking guest agent where to serve the bridge control
     * channel and the terminal channel. {@code droidlayer.dns=} is appended
     * from the outbound network spec so guest DNS resolution matches the
     * QEMU profile (Ticket 27/36).
     */
    public static final String GUEST_CMDLINE_TEMPLATE =
            "console=hvc0 rdinit=/init panic=-1"
                    + " droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input"
                    + " droidlayer.vsock_control_port=%d droidlayer.vsock_terminal_port=%d";

    public static final int DEFAULT_CONTROL_VSOCK_PORT = 9100;
    public static final int DEFAULT_TERMINAL_VSOCK_PORT = 9101;
    public static final int DEFAULT_CPU_COUNT = 1;

    private static final Pattern INSTANCE_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");
    private static final int MIN_PORT = 1024;
    private static final int MAX_PORT = 65535;
    /** Sanity cap well above any phone core count; the platform still rejects
     * counts above the host's physical CPUs at VM creation time. */
    private static final int MAX_CPU_COUNT = 32;

    private final String instanceId;
    private final String kernelPath;
    private final String initramfsPath;
    private final String rootVolumePath;
    private final long memoryMb;
    private final int cpuCount;
    private final int controlVsockPort;
    private final int terminalVsockPort;
    private final NetworkContract.OutboundNetworkSpec networkSpec;
    private final String guestCmdline;

    private AvfLaunchSpec(Builder builder) {
        this.instanceId = builder.instanceId;
        this.kernelPath = builder.kernelPath;
        this.initramfsPath = builder.initramfsPath;
        this.rootVolumePath = builder.rootVolumePath;
        this.memoryMb = builder.memoryMb;
        this.cpuCount = builder.cpuCount;
        this.controlVsockPort = builder.controlVsockPort;
        this.terminalVsockPort = builder.terminalVsockPort;
        this.networkSpec = builder.networkSpec;
        this.guestCmdline = appendDnsKey(
                String.format(GUEST_CMDLINE_TEMPLATE, builder.controlVsockPort, builder.terminalVsockPort),
                builder.networkSpec);
    }

    private static String appendDnsKey(String cmdline, NetworkContract.OutboundNetworkSpec spec) {
        String dns = spec.cmdlineDns();
        if (dns == null || dns.isEmpty()) {
            return cmdline;
        }
        return cmdline + " droidlayer.dns=" + dns;
    }

    public String instanceId() {
        return instanceId;
    }

    public String kernelPath() {
        return kernelPath;
    }

    public String initramfsPath() {
        return initramfsPath;
    }

    public String rootVolumePath() {
        return rootVolumePath;
    }

    public long memoryMb() {
        return memoryMb;
    }

    public int cpuCount() {
        return cpuCount;
    }

    public int controlVsockPort() {
        return controlVsockPort;
    }

    public int terminalVsockPort() {
        return terminalVsockPort;
    }

    /** The strategy-neutral outbound network assembly shared with the QEMU engine. */
    public NetworkContract.OutboundNetworkSpec networkSpec() {
        return networkSpec;
    }

    public String guestCmdline() {
        return guestCmdline;
    }

    public static Builder builder(
            String instanceId, String kernelPath, String initramfsPath, String rootVolumePath) {
        return new Builder(instanceId, kernelPath, initramfsPath, rootVolumePath);
    }

    public static final class Builder {
        private final String instanceId;
        private final String kernelPath;
        private final String initramfsPath;
        private final String rootVolumePath;
        private long memoryMb = 512;
        private int cpuCount = DEFAULT_CPU_COUNT;
        private int controlVsockPort = DEFAULT_CONTROL_VSOCK_PORT;
        private int terminalVsockPort = DEFAULT_TERMINAL_VSOCK_PORT;
        private NetworkContract.OutboundNetworkSpec networkSpec =
                NetworkContract.OutboundNetworkSpec.defaultSpec();

        private Builder(
                String instanceId, String kernelPath, String initramfsPath, String rootVolumePath) {
            this.instanceId = requireInstanceId(instanceId);
            this.kernelPath = requireNonBlank(kernelPath, "kernelPath");
            this.initramfsPath = requireNonBlank(initramfsPath, "initramfsPath");
            this.rootVolumePath = requireNonBlank(rootVolumePath, "rootVolumePath");
        }

        public Builder memoryMb(long memoryMb) {
            if (memoryMb <= 0 || memoryMb > 16384) {
                throw new IllegalArgumentException("memoryMb must be within (0, 16384]");
            }
            this.memoryMb = memoryMb;
            return this;
        }

        public Builder cpuCount(int cpuCount) {
            if (cpuCount < 1 || cpuCount > MAX_CPU_COUNT) {
                throw new IllegalArgumentException(
                        "cpuCount must be within [1, " + MAX_CPU_COUNT + "]");
            }
            this.cpuCount = cpuCount;
            return this;
        }

        public Builder controlVsockPort(int port) {
            this.controlVsockPort = requirePort(port);
            return this;
        }

        public Builder terminalVsockPort(int port) {
            this.terminalVsockPort = requirePort(port);
            return this;
        }

        /**
         * Replaces the outbound network assembly. IPv6-enabled specifications are
         * rejected here because the AVF profile must not claim an IPv6-capable
         * guest network (ADR-0008; the contract type enforces the same rule).
         */
        public Builder networkSpec(NetworkContract.OutboundNetworkSpec networkSpec) {
            Objects.requireNonNull(networkSpec, "networkSpec");
            if (networkSpec.ipv6Enabled()) {
                throw new IllegalArgumentException("IPv6 is not supported per ADR-0008");
            }
            this.networkSpec = networkSpec;
            return this;
        }

        public AvfLaunchSpec build() {
            if (controlVsockPort == terminalVsockPort) {
                throw new IllegalArgumentException("control and terminal vsock ports must differ");
            }
            return new AvfLaunchSpec(this);
        }
    }

    private static String requireInstanceId(String value) {
        if (value == null || !INSTANCE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("instanceId must match " + INSTANCE_ID.pattern());
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int requirePort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(
                    "vsock port must be within [" + MIN_PORT + ", " + MAX_PORT + "]");
        }
        return port;
    }
}
