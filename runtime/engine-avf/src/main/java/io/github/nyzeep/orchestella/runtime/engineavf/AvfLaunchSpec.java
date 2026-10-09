package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.NetworkContract;
import io.github.nyzeep.orchestella.runtime.contract.SharedDirectoryContract;
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
 *
 * <p>Shared data plane (ADR-0009/0010): when a {@code SharedDirectorySpec} is
 * configured, the guest cmdline announces the 9p-over-vsock rendezvous port
 * ({@code droidlayer.vsock_9p_port}) on the independent data channel; the
 * host serves the read-only 9p2000.L server over it and the guest mounts it
 * via trans=fd. When null, no 9p port is announced and no channel is opened.
 *
 * <p>Every setting is explicit and immutable once built.
 */
public final class AvfLaunchSpec {
    /**
     * Minimal custom-VM guest command-line. Android 13/14 AVF maps the guest
     * console to the first virtio-console device (hvc0). The init script uses
     * the root device, input tag and the vsock port announcements to set up
     * the console shell, Bridge v1 protocol and 9p mount.
     */
    public static final String GUEST_CMDLINE_TEMPLATE =
            "console=hvc0 rdinit=/init panic=-1"
                    + " droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input"
                    + " droidlayer.vsock_control_port=%d droidlayer.vsock_terminal_port=%d";

    public static final int DEFAULT_CONTROL_VSOCK_PORT = 9100;
    public static final int DEFAULT_TERMINAL_VSOCK_PORT = 9101;
    public static final int DEFAULT_9P_VSOCK_PORT = 9102;
    public static final int DEFAULT_GRAPHICS_VSOCK_PORT = 9103;
    public static final int AVF_GRAPHICS_VSOCK_PORT = 9103;
    public static final int DEFAULT_AUDIO_VSOCK_PORT = 9104;
    public static final int AVF_AUDIO_VSOCK_PORT = 9104;
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
    private final int sharedDirectoryVsockPort;
    private final SharedDirectoryContract.SharedDirectorySpec sharedDirectory;
    private final int graphicsVsockPort;
    private final int audioVsockPort;
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
        this.sharedDirectoryVsockPort = builder.sharedDirectoryVsockPort;
        this.sharedDirectory = builder.sharedDirectory;
        this.graphicsVsockPort = builder.graphicsVsockPort;
        this.audioVsockPort = builder.audioVsockPort;

        StringBuilder cmdline = new StringBuilder(String.format(
                GUEST_CMDLINE_TEMPLATE, builder.controlVsockPort, builder.terminalVsockPort));
        if (builder.sharedDirectory != null) {
            cmdline.append(" droidlayer.vsock_9p_port=")
                    .append(builder.sharedDirectoryVsockPort);
        }
        this.guestCmdline = appendDnsKey(cmdline.toString(), builder.networkSpec);
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

    /** The 9p data-plane vsock port; meaningful only with a share configured. */
    public int sharedDirectoryVsockPort() {
        return sharedDirectoryVsockPort;
    }

    /** The read-only shared directory, or null when this boot shares nothing. */
    public SharedDirectoryContract.SharedDirectorySpec sharedDirectory() {
        return sharedDirectory;
    }

    /** Dedicated vsock graphics port (defaults to 9103). */
    public int graphicsVsockPort() {
        return graphicsVsockPort;
    }

    /** Dedicated vsock audio port (defaults to 9104). */
    public int audioVsockPort() {
        return audioVsockPort;
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
        private int sharedDirectoryVsockPort = DEFAULT_9P_VSOCK_PORT;
        private SharedDirectoryContract.SharedDirectorySpec sharedDirectory;
        private int graphicsVsockPort = DEFAULT_GRAPHICS_VSOCK_PORT;
        private int audioVsockPort = DEFAULT_AUDIO_VSOCK_PORT;

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

        public Builder sharedDirectoryVsockPort(int port) {
            this.sharedDirectoryVsockPort = requirePort(port);
            return this;
        }

        /** Configures the read-only shared directory; null clears it. */
        public Builder sharedDirectory(SharedDirectoryContract.SharedDirectorySpec sharedDirectory) {
            this.sharedDirectory = sharedDirectory;
            return this;
        }

        public Builder graphicsVsockPort(int port) {
            this.graphicsVsockPort = requirePort(port);
            return this;
        }

        public Builder audioVsockPort(int port) {
            this.audioVsockPort = requirePort(port);
            return this;
        }

        public AvfLaunchSpec build() {
            if (controlVsockPort == terminalVsockPort) {
                throw new IllegalArgumentException("control and terminal vsock ports must differ");
            }
            if (sharedDirectoryVsockPort == controlVsockPort
                    || sharedDirectoryVsockPort == terminalVsockPort) {
                throw new IllegalArgumentException(
                        "the 9p data-plane vsock port must differ from the control"
                                + " and terminal ports");
            }
            if (graphicsVsockPort == controlVsockPort
                    || graphicsVsockPort == terminalVsockPort
                    || graphicsVsockPort == sharedDirectoryVsockPort) {
                throw new IllegalArgumentException(
                        "the graphics vsock port must differ from the control, terminal,"
                                + " and 9p ports");
            }
            if (audioVsockPort == controlVsockPort
                    || audioVsockPort == terminalVsockPort
                    || audioVsockPort == sharedDirectoryVsockPort
                    || audioVsockPort == graphicsVsockPort) {
                throw new IllegalArgumentException(
                        "the audio vsock port must differ from the control, terminal, 9p,"
                                + " and graphics ports");
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
