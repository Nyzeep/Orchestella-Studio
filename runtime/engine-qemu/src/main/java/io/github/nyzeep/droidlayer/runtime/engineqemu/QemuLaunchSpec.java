package io.github.nyzeep.droidlayer.runtime.engineqemu;

import io.github.nyzeep.droidlayer.runtime.contract.NetworkContract;
import java.util.Objects;

/**
 * Builds the fixed-argument QEMU command array for one instance launch.
 *
 * <p>The shape mirrors the proven provisioning smoke harness exactly, minus
 * the virtio-9p transport (the shipped QEMU disables virtfs): provisioning
 * inputs travel inside the initramfs as the embedded segment built by
 * {@link EmbeddedProvisionInput}. Outbound network (SLIRP NAT) and device
 * assembly conform to {@link NetworkContract.OutboundNetworkSpec} (ADR-0008,
 * ADR-0010). All hypervisor-specific CLI formatting is encapsulated in this
 * engine class, keeping the contract strategy-neutral.
 *
 * <p>The array is executed directly as one argument array by the approved engine seam;
 * no shell is involved and no element is dynamic beyond the caller-supplied paths,
 * socket names, memory size, and network parameters.
 */
public final class QemuLaunchSpec {
    public static final String GUEST_CMDLINE = "console=ttyAMA0 rdinit=/init panic=-1"
            + " droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input"
            + " droidlayer.bridge_device=/dev/vport0p1 droidlayer.terminal_device=/dev/ttyAMA0";

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
                java.util.Collections.emptyList());
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
            java.util.List<io.github.nyzeep.droidlayer.runtime.contract.PortForwardingRule> portForwardingRules) {
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
                portForwardingRules);
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
            java.util.List<io.github.nyzeep.droidlayer.runtime.contract.PortForwardingRule> portForwardingRules) {
        Objects.requireNonNull(qemuExecutable, "qemuExecutable");
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(initramfs, "initramfs");
        Objects.requireNonNull(rootVolume, "rootVolume");
        Objects.requireNonNull(controlSocketName, "controlSocketName");
        Objects.requireNonNull(terminalSocketName, "terminalSocketName");
        Objects.requireNonNull(networkSpec, "networkSpec");

        String cmdline = GUEST_CMDLINE;
        if (networkSpec.cmdlineDns() != null && !networkSpec.cmdlineDns().isEmpty()) {
            cmdline = cmdline + " droidlayer.dns=" + networkSpec.cmdlineDns();
        }

        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(qemuExecutable);
        args.add("-machine");
        args.add("virt,gic-version=3");
        args.add("-accel");
        args.add("tcg,thread=multi");
        args.add("-cpu");
        args.add("cortex-a57");
        args.add("-smp");
        args.add("2");
        args.add("-m");
        args.add(Long.toString(memoryMb));
        args.add("-nodefaults");
        args.add("-display");
        args.add("none");
        args.add("-monitor");
        args.add("none");
        args.add("-no-reboot");
        args.add("-chardev");
        args.add("socket,id=terminal,path=" + terminalSocketName + ",abstract=on,server=on,wait=off");
        args.add("-serial");
        args.add("chardev:terminal");
        args.add("-kernel");
        args.add(kernel);
        args.add("-initrd");
        args.add(initramfs);
        args.add("-append");
        args.add(cmdline);
        args.add("-drive");
        args.add("if=none,id=root,file=" + rootVolume + ",format=raw,cache=unsafe");
        args.add("-device");
        args.add("virtio-blk-device,drive=root");
        args.add("-netdev");
        args.add(formatNetdevArg(networkSpec, portForwardingRules));
        args.add("-device");
        args.add(formatDeviceArg(networkSpec));
        args.add("-device");
        args.add("virtio-serial-device");
        args.add("-chardev");
        args.add("socket,id=control,path=" + controlSocketName + ",abstract=on,server=on,wait=off");
        args.add("-device");
        args.add("virtserialport,chardev=control,name=droidlayer.bridge");

        if (qmpSocketName != null && !qmpSocketName.trim().isEmpty()) {
            args.add("-chardev");
            args.add("socket,id=qmp,path=" + qmpSocketName.trim() + ",abstract=on,server=on,wait=off");
            args.add("-mon");
            args.add("chardev=qmp,mode=control");
        }

        return args.toArray(new String[0]);
    }

    /**
     * Formats the QEMU {@code -netdev} option argument string for the user/SLIRP backend.
     */
    static String formatNetdevArg(NetworkContract.OutboundNetworkSpec spec) {
        return formatNetdevArg(spec, java.util.Collections.emptyList());
    }

    /**
     * Formats the QEMU {@code -netdev} option argument string with outbound network and hostfwd rules.
     */
    static String formatNetdevArg(
            NetworkContract.OutboundNetworkSpec spec,
            java.util.List<io.github.nyzeep.droidlayer.runtime.contract.PortForwardingRule> rules) {
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
            for (io.github.nyzeep.droidlayer.runtime.contract.PortForwardingRule rule : rules) {
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
}
