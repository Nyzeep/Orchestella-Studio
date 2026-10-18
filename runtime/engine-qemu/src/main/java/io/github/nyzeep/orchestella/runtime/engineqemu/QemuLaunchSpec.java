package io.github.nyzeep.orchestella.runtime.engineqemu;

import io.github.nyzeep.orchestella.runtime.contract.NetworkContract;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.SharedDirectoryContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Builds the fixed-argument QEMU command array for one instance launch.
 *
 * <p>The shape mirrors the proven provisioning smoke harness exactly:
 * provisioning inputs travel inside the initramfs as the embedded segment built by
 * {@link EmbeddedProvisionInput}. Outbound network (SLIRP NAT) and device
 * assembly conform to {@link NetworkContract.OutboundNetworkSpec} (ADR-0008,
 * ADR-0010). When configured, shared directories (such as read-only Downloads)
 * travel across an independent data plane via virtio-9p conforming to
 * {@link SharedDirectoryContract.SharedDirectorySpec} (ADR-0009, ADR-0010).
 * All hypervisor-specific CLI formatting is encapsulated in this engine class,
 * keeping the contract strategy-neutral.
 *
 * <p>The array is executed directly as one argument array by the approved engine seam;
 * no shell is involved and no element is dynamic beyond the caller-supplied paths,
 * socket names, memory size, network, and file sharing parameters.
 */
public final class QemuLaunchSpec {
    public static final String GUEST_CMDLINE = "console=ttyAMA0 rdinit=/init panic=-1"
            + " droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input"
            + " droidlayer.bridge_device=/dev/vport0p1 droidlayer.terminal_device=/dev/ttyAMA0";

    /**
     * Derives the corresponding proxy abstract socket name from a control socket name.
     */
    public static String defaultProxySocketName(String controlSocketName) {
        if (controlSocketName == null) {
            return null;
        }
        String name = controlSocketName;
        if (name.startsWith("droidlayer-")) {
            name = "orchestella-" + name.substring("droidlayer-".length());
        }
        if (name.contains("control")) {
            return name.replace("control", "proxy");
        }
        return name + "-proxy";
    }

    private QemuLaunchSpec() {
    }

    /**
     * Builds the standard QEMU launch argument array using the default outbound network specification.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            long memoryMb) {
        return build(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                memoryMb,
                NetworkContract.OutboundNetworkSpec.defaultSpec());
    }

    /**
     * Builds the QEMU launch argument array with a customizable outbound network specification.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            long memoryMb,
            NetworkContract.OutboundNetworkSpec networkSpec) {
        return build(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                null,
                memoryMb,
                networkSpec,
                Collections.emptyList(),
                null);
    }

    /**
     * Builds the QEMU launch argument array with default networking and a shared directory specification.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            long memoryMb,
            SharedDirectoryContract.SharedDirectorySpec sharedDirectorySpec) {
        return build(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                memoryMb,
                NetworkContract.OutboundNetworkSpec.defaultSpec(),
                sharedDirectorySpec);
    }

    /**
     * Builds the QEMU launch argument array with customizable network and shared directory specifications.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            long memoryMb,
            NetworkContract.OutboundNetworkSpec networkSpec,
            SharedDirectoryContract.SharedDirectorySpec sharedDirectorySpec) {
        return build(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                null,
                memoryMb,
                networkSpec,
                Collections.emptyList(),
                sharedDirectorySpec);
    }

    /**
     * Builds the QEMU launch argument array with port forwarding rules injected at launch time.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            long memoryMb,
            NetworkContract.OutboundNetworkSpec networkSpec,
            List<io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule> portForwardingRules) {
        return build(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                null,
                memoryMb,
                networkSpec,
                portForwardingRules,
                null);
    }

    /**
     * Builds the QEMU launch argument array with customizable network spec, port forwarding rules,
     * and optional QMP control socket.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            String qmpSocketName,
            long memoryMb,
            NetworkContract.OutboundNetworkSpec networkSpec,
            List<io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule> portForwardingRules) {
        return build(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                qmpSocketName,
                memoryMb,
                networkSpec,
                portForwardingRules,
                null);
    }

    /**
     * Builds the standard QEMU launch argument array using a strategy-neutral {@link VmHardwareConfig}
     * and default outbound network specification.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            VmHardwareConfig hardwareConfig) {
        return build(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                hardwareConfig,
                NetworkContract.OutboundNetworkSpec.defaultSpec());
    }

    /**
     * Builds the QEMU launch argument array with {@link VmHardwareConfig} and customizable network spec.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            VmHardwareConfig hardwareConfig,
            NetworkContract.OutboundNetworkSpec networkSpec) {
        return build(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                null,
                hardwareConfig,
                networkSpec,
                Collections.emptyList(),
                null);
    }

    /**
     * Builds the QEMU launch argument array with {@link VmHardwareConfig}, default network and shared directory spec.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            VmHardwareConfig hardwareConfig,
            SharedDirectoryContract.SharedDirectorySpec sharedDirectorySpec) {
        return build(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                hardwareConfig,
                NetworkContract.OutboundNetworkSpec.defaultSpec(),
                sharedDirectorySpec);
    }

    /**
     * Builds the QEMU launch argument array with {@link VmHardwareConfig}, customizable network and shared directory spec.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            VmHardwareConfig hardwareConfig,
            NetworkContract.OutboundNetworkSpec networkSpec,
            SharedDirectoryContract.SharedDirectorySpec sharedDirectorySpec) {
        return build(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                null,
                hardwareConfig,
                networkSpec,
                Collections.emptyList(),
                sharedDirectorySpec);
    }

    /**
     * Builds the QEMU launch argument array with {@link VmHardwareConfig}, customizable network spec,
     * and port forwarding rules.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            VmHardwareConfig hardwareConfig,
            NetworkContract.OutboundNetworkSpec networkSpec,
            List<PortForwardingRule> portForwardingRules) {
        return build(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                null,
                hardwareConfig,
                networkSpec,
                portForwardingRules,
                null);
    }

    /**
     * Builds the QEMU launch argument array with customizable network spec, port forwarding rules,
     * optional QMP control socket, and shared directory specification.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            String qmpSocketName,
            long memoryMb,
            NetworkContract.OutboundNetworkSpec networkSpec,
            List<PortForwardingRule> portForwardingRules,
            SharedDirectoryContract.SharedDirectorySpec sharedDirectorySpec) {
        return buildInternal(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                qmpSocketName,
                2,
                Long.toString(memoryMb),
                null,
                VmHardwareConfig.DEFAULT_GPU_ACCELERATION_ENABLED,
                networkSpec,
                portForwardingRules,
                sharedDirectorySpec,
                QemuTuning.DEFAULTS);
    }

    /**
     * Builds the QEMU launch argument array with explicit GPU acceleration toggle, customizable network spec,
     * port forwarding rules, optional QMP control socket, and shared directory specification.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            String qmpSocketName,
            long memoryMb,
            boolean gpuAccelerationEnabled,
            NetworkContract.OutboundNetworkSpec networkSpec,
            List<PortForwardingRule> portForwardingRules,
            SharedDirectoryContract.SharedDirectorySpec sharedDirectorySpec) {
        return buildInternal(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                qmpSocketName,
                2,
                Long.toString(memoryMb),
                null,
                gpuAccelerationEnabled,
                networkSpec,
                portForwardingRules,
                sharedDirectorySpec,
                QemuTuning.DEFAULTS);
    }

    /**
     * Builds the QEMU launch argument array with {@link VmHardwareConfig}, customizable network spec,
     * port forwarding rules, optional QMP control socket, and shared directory specification.
     */
    public static String[] build(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            String qmpSocketName,
            VmHardwareConfig hardwareConfig,
            NetworkContract.OutboundNetworkSpec networkSpec,
            List<PortForwardingRule> portForwardingRules,
            SharedDirectoryContract.SharedDirectorySpec sharedDirectorySpec) {
        Objects.requireNonNull(hardwareConfig, "hardwareConfig");
        return buildInternal(
                qemuExecutable,
                kernel,
                initramfs,
                rootVolume,
                controlSocketName,
                terminalSocketName,
                qmpSocketName,
                hardwareConfig.cpuCores(),
                hardwareConfig.ramMegabytes() + "M",
                hardwareConfig.extraKernelArguments(),
                hardwareConfig.gpuAccelerationEnabled(),
                networkSpec,
                portForwardingRules,
                sharedDirectorySpec,
                QemuTuning.DEFAULTS);
    }

    /**
     * Builds the QEMU launch argument array wired to a passt user-space network daemon
     * over a UNIX stream socket. The packaged QEMU binary has no slirp support, so the
     * passt backend (socket mode, spawned by the app) is the rootless network plane:
     * the daemon bridges guest Ethernet frames onto the host network stack, following
     * host connectivity including VPNs. Guest addressing rides the host parameters the
     * caller passes via the extra kernel arguments.
     */
    public static String[] buildWithPasst(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            VmHardwareConfig hardwareConfig,
            String passtTcpPort,
            String extraKernelArgs) {
        return buildWithPasst(qemuExecutable, kernel, initramfs, rootVolume,
                controlSocketName, terminalSocketName, hardwareConfig, passtTcpPort,
                extraKernelArgs, QemuTuning.DEFAULTS);
    }

    /**
     * Builds the passt-backed launch array with an explicit T7 tuning value object.
     * {@link QemuTuning#DEFAULTS} is byte-for-byte identical to the legacy overload.
     */
    public static String[] buildWithPasst(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            VmHardwareConfig hardwareConfig,
            String passtTcpPort,
            String extraKernelArgs,
            QemuTuning tuning) {
        Objects.requireNonNull(passtTcpPort, "passtTcpPort");
        Objects.requireNonNull(tuning, "tuning");
        String cmdline = GUEST_CMDLINE;
        if (extraKernelArgs != null && !extraKernelArgs.trim().isEmpty()) {
            cmdline = cmdline + " " + extraKernelArgs.trim();
        }
        List<String> args = new ArrayList<>(48);
        Collections.addAll(
                args,
                qemuExecutable,
                "-machine", "virt,gic-version=3",
                "-accel", accelArg(tuning),
                "-cpu", tuning.cpuModel(),
                "-smp", Integer.toString(tuning.vcpuCount() > 0 ? tuning.vcpuCount() : hardwareConfig.cpuCores()),
                "-m", hardwareConfig.ramMegabytes() + "M",
                "-nodefaults",
                "-display", "none",
                "-monitor", "none",
                "-no-reboot",
                "-chardev", "socket,id=terminal,path=" + terminalSocketName + ",abstract=on,server=on,wait=off",
                "-serial", "chardev:terminal",
                "-kernel", kernel,
                "-initrd", initramfs,
                "-append", cmdline,
                "-drive", rootDriveArg(rootVolume, tuning),
                "-device", "virtio-blk-device,drive=root",
                "-device", hardwareConfig.gpuAccelerationEnabled() ? "virtio-gpu-gl-device" : "virtio-gpu-device",
                "-netdev", passtNetdevArg(passtTcpPort, tuning),
                "-device", "virtio-net-device,netdev=orchestella-net",
                "-device", "virtio-serial-device",
                "-chardev", "socket,id=control,path=" + controlSocketName + ",abstract=on,server=on,wait=off",
                "-device", "virtserialport,chardev=control,name=droidlayer.bridge",
                "-chardev", "socket,id=netproxy,path=" + defaultProxySocketName(controlSocketName) + ",abstract=on,server=on,wait=off",
                "-device", "virtserialport,chardev=netproxy,name=orchestella.proxy");
        return args.toArray(new String[0]);
    }

    private static String accelArg(QemuTuning tuning) {
        return tuning.tbSizeKb() > 0
                ? "tcg,thread=multi,tb-size=" + tuning.tbSizeKb()
                : "tcg,thread=multi";
    }

    private static String rootDriveArg(String rootVolume, QemuTuning tuning) {
        StringBuilder drive = new StringBuilder("if=none,id=root,file=")
                .append(rootVolume)
                .append(",format=raw,cache=writeback");
        if (tuning.blockAio() != null) {
            drive.append(",aio=").append(tuning.blockAio());
        }
        if (tuning.discardUnmap()) {
            drive.append(",discard=unmap");
        }
        if (tuning.detectZeroesUnmap()) {
            drive.append(",detect-zeroes=unmap");
        }
        return drive.toString();
    }

    private static String passtNetdevArg(String passtTcpPort, QemuTuning tuning) {
        if (tuning.passtUnixSocketPath() != null) {
            return "stream,id=orchestella-net,addr.type=unix,addr.path="
                    + tuning.passtUnixSocketPath();
        }
        return "socket,id=orchestella-net,connect=127.0.0.1:" + passtTcpPort;
    }

    /** Adds an independent authenticated display data plane to an existing virtio-serial controller. */
    public static String[] withDisplayChannel(String[] launch, String privateSocketPath) {
        Objects.requireNonNull(launch, "launch");
        if (privateSocketPath == null || !privateSocketPath.startsWith("/")
                || privateSocketPath.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 100
                || privateSocketPath.indexOf(',') >= 0 || privateSocketPath.indexOf('\\') >= 0
                || privateSocketPath.chars().anyMatch(value -> value < 0x20)) {
            throw new IllegalArgumentException("invalid private display socket path");
        }
        List<String> arguments = new ArrayList<>(java.util.Arrays.asList(launch));
        if (!arguments.contains("virtio-serial-device")) throw new IllegalArgumentException("virtio serial is absent");
        Collections.addAll(arguments, "-chardev", "socket,id=display,path=" + privateSocketPath + ",server=on,wait=off",
                "-device", "virtserialport,chardev=display,name=orchestella.display");
        return arguments.toArray(new String[0]);
    }

    private static String[] buildInternal(
            String qemuExecutable,
            String kernel,
            String initramfs,
            String rootVolume,
            String controlSocketName,
            String terminalSocketName,
            String qmpSocketName,
            int cpuCores,
            String memoryArg,
            String extraKernelArgs,
            boolean gpuAccelerationEnabled,
            NetworkContract.OutboundNetworkSpec networkSpec,
            List<PortForwardingRule> portForwardingRules,
            SharedDirectoryContract.SharedDirectorySpec sharedDirectorySpec,
            QemuTuning tuning) {
        Objects.requireNonNull(qemuExecutable, "qemuExecutable");
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(initramfs, "initramfs");
        Objects.requireNonNull(rootVolume, "rootVolume");
        Objects.requireNonNull(controlSocketName, "controlSocketName");
        Objects.requireNonNull(terminalSocketName, "terminalSocketName");
        Objects.requireNonNull(networkSpec, "networkSpec");
        Objects.requireNonNull(tuning, "tuning");

        String cmdline = GUEST_CMDLINE;
        if (networkSpec.cmdlineDns() != null && !networkSpec.cmdlineDns().isEmpty()) {
            cmdline = cmdline + " droidlayer.dns=" + networkSpec.cmdlineDns();
        }
        if (extraKernelArgs != null && !extraKernelArgs.trim().isEmpty()) {
            cmdline = cmdline + " " + extraKernelArgs.trim();
        }

        List<String> args = new ArrayList<>(48);
        Collections.addAll(
                args,
                qemuExecutable,
                "-machine", "virt,gic-version=3",
                "-accel", accelArg(tuning),
                "-cpu", tuning.cpuModel(),
                "-smp", Integer.toString(tuning.vcpuCount() > 0 ? tuning.vcpuCount() : cpuCores),
                "-m", memoryArg,
                "-nodefaults",
                "-display", "none",
                "-monitor", "none",
                "-no-reboot",
                "-chardev", "socket,id=terminal,path=" + terminalSocketName + ",abstract=on,server=on,wait=off",
                "-serial", "chardev:terminal",
                "-kernel", kernel,
                "-initrd", initramfs,
                "-append", cmdline,
                "-drive", rootDriveArg(rootVolume, tuning),
                "-device", "virtio-blk-device,drive=root",
                "-device", gpuAccelerationEnabled ? "virtio-gpu-gl-device" : "virtio-gpu-device",
                "-netdev", formatNetdevArg(networkSpec, portForwardingRules),
                "-device", formatDeviceArg(networkSpec),
                "-device", "virtio-serial-device",
                "-chardev", "socket,id=control,path=" + controlSocketName + ",abstract=on,server=on,wait=off",
                "-device", "virtserialport,chardev=control,name=droidlayer.bridge",
                "-chardev", "socket,id=netproxy,path=" + defaultProxySocketName(controlSocketName) + ",abstract=on,server=on,wait=off",
                "-device", "virtserialport,chardev=netproxy,name=orchestella.proxy");

        if (qmpSocketName != null && !qmpSocketName.trim().isEmpty()) {
            args.add("-chardev");
            args.add("socket,id=qmp,path=" + qmpSocketName.trim() + ",abstract=on,server=on,wait=off");
            args.add("-mon");
            args.add("chardev=qmp,mode=control");
        }

        if (sharedDirectorySpec != null) {
            args.add("-fsdev");
            args.add(formatFsdevArg(sharedDirectorySpec));
            args.add("-device");
            args.add(formatFsDeviceArg(sharedDirectorySpec));
        }

        return args.toArray(new String[0]);
    }

    /**
     * Formats the QEMU {@code -netdev} option argument string for the user/SLIRP backend.
     */
    static String formatNetdevArg(NetworkContract.OutboundNetworkSpec spec) {
        return formatNetdevArg(spec, Collections.emptyList());
    }

    /**
     * Formats the QEMU {@code -netdev} option argument string with outbound network and hostfwd rules.
     */
    static String formatNetdevArg(
            NetworkContract.OutboundNetworkSpec spec,
            List<io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule> rules) {
        Objects.requireNonNull(spec, "spec");
        StringBuilder sb = new StringBuilder();
        sb.append("user,id=").append(spec.netdevId());
        sb.append(",ipv6=off");
        sb.append(",net=").append(spec.guestNetworkCidr());
        sb.append(",dhcpstart=").append(spec.guestDhcpStart());
        if (spec.dnsServer() != null && !spec.dnsServer().isEmpty()) {
            sb.append(",dns=").append(spec.dnsServer());
        }
        if (rules != null) {
            for (io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule rule : rules) {
                sb.append(",hostfwd=").append(QemuHostfwdFormatter.formatHostfwdPattern(rule));
            }
        }
        return sb.toString();
    }

    /**
     * Formats the QEMU {@code -device} option argument string for the network interface device.
     */
    static String formatDeviceArg(NetworkContract.OutboundNetworkSpec spec) {
        Objects.requireNonNull(spec, "spec");
        String deviceName;
        switch (spec.deviceType()) {
            case VIRTIO_NET_DEVICE:
                deviceName = "virtio-net-device";
                break;
            case VIRTIO_NET_PCI:
            default:
                deviceName = "virtio-net-pci";
                break;
        }
        return deviceName + ",netdev=" + spec.netdevId();
    }

    /**
     * Formats the QEMU {@code -fsdev} option argument string for the 9p shared directory backend.
     * e.g. {@code local,id=fsdev0,path=/storage/emulated/0/Download,security_model=none,readonly=on}
     */
    static String formatFsdevArg(SharedDirectoryContract.SharedDirectorySpec spec) {
        Objects.requireNonNull(spec, "spec");
        StringBuilder sb = new StringBuilder();
        sb.append("local,id=").append(spec.fsdevId());
        sb.append(",path=").append(spec.hostPath());
        sb.append(",security_model=").append(spec.mountOptions().securityModel().wireValue());
        if (spec.mountOptions().isReadOnly()) {
            sb.append(",readonly=on");
        }
        return sb.toString();
    }

    /**
     * Formats the QEMU {@code -device} option argument string for the 9p virtio filesystem device.
     * e.g. {@code virtio-9p-pci,fsdev=fsdev0,mount_tag=downloads}
     */
    static String formatFsDeviceArg(SharedDirectoryContract.SharedDirectorySpec spec) {
        Objects.requireNonNull(spec, "spec");
        String deviceName;
        switch (spec.deviceType()) {
            case VIRTIO_9P_DEVICE:
                deviceName = "virtio-9p-device";
                break;
            case VIRTIO_9P_PCI:
            default:
                deviceName = "virtio-9p-pci";
                break;
        }
        return deviceName + ",fsdev=" + spec.fsdevId() + ",mount_tag=" + spec.mountTag();
    }
}
