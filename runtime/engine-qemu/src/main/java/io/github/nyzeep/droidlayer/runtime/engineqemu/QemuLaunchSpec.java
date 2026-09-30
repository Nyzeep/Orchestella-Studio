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
 * ADR-0010). The array is executed directly as one argument array by the
 * approved engine seam; no shell is involved and no element is dynamic beyond
 * the caller-supplied paths, socket names, memory size, and network parameters.
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

        return new String[] {
                qemuExecutable,
                "-machine", "virt,gic-version=3",
                "-accel", "tcg,thread=multi",
                "-cpu", "cortex-a57",
                "-smp", "2",
                "-m", Long.toString(memoryMb),
                "-nodefaults",
                "-display", "none",
                "-monitor", "none",
                "-no-reboot",
                "-chardev", "socket,id=terminal,path=" + terminalSocketName
                        + ",abstract=on,server=on,wait=off",
                "-serial", "chardev:terminal",
                "-kernel", kernel,
                "-initrd", initramfs,
                "-append", cmdline,
                "-drive", "if=none,id=root,file=" + rootVolume + ",format=raw,cache=unsafe",
                "-device", "virtio-blk-device,drive=root",
                "-netdev", networkSpec.toNetdevArg(),
                "-device", networkSpec.toDeviceArg(),
                "-device", "virtio-serial-device",
                "-chardev", "socket,id=control,path=" + controlSocketName
                        + ",abstract=on,server=on,wait=off",
                "-device", "virtserialport,chardev=control,name=droidlayer.bridge",
        };
    }
}
