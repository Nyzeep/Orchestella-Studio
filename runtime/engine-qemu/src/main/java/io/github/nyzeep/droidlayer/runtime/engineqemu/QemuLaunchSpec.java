package io.github.nyzeep.droidlayer.runtime.engineqemu;

import java.nio.file.Path;

/**
 * Builds the fixed-argument QEMU command array for one instance launch.
 *
 * <p>The shape mirrors the proven provisioning smoke harness exactly, minus
 * the virtio-9p transport (the shipped QEMU disables virtfs): provisioning
 * inputs travel inside the initramfs as the embedded segment built by
 * {@link EmbeddedProvisionInput}. The array is executed directly as one
 * argument array by the approved engine seam; no shell is involved and no
 * element is dynamic beyond the caller-supplied paths, socket names, and
 * memory size.
 */
public final class QemuLaunchSpec {
    public static final String GUEST_CMDLINE = "console=ttyAMA0 rdinit=/init panic=-1"
            + " droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input"
            + " droidlayer.bridge_device=/dev/vport0p1 droidlayer.terminal_device=/dev/ttyAMA0";

    private QemuLaunchSpec() {
    }

    public static String[] build(String qemuExecutable, String kernel, String initramfs,
            String rootVolume, String controlSocketName, String terminalSocketName, long memoryMb) {
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
                "-append", GUEST_CMDLINE,
                "-drive", "if=none,id=root,file=" + rootVolume + ",format=raw,cache=unsafe",
                "-device", "virtio-blk-device,drive=root",
                "-device", "virtio-serial-device",
                "-chardev", "socket,id=control,path=" + controlSocketName
                        + ",abstract=on,server=on,wait=off",
                "-device", "virtserialport,chardev=control,name=droidlayer.bridge",
        };
    }
}
