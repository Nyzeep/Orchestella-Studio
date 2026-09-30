package io.github.nyzeep.droidlayer.runtime.engineqemu;

/** Contract tests for the fixed-argument QEMU launch specification. */
public final class QemuLaunchSpecTest {
    public static void main(String[] args) throws Exception {
        String[] spec = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                512L);

        String joined = String.join("\u0000", spec);

        // Fixed machine shape (mirrors the proven provisioning smoke harness).
        String[][] expectedPairs = {
                {"-machine", "virt,gic-version=3"},
                {"-accel", "tcg,thread=multi"},
                {"-cpu", "cortex-a57"},
                {"-smp", "2"},
                {"-m", "512"},
        };
        for (String[] pair : expectedPairs) {
            requireArg(spec, pair[0], pair[1]);
        }
        for (String flag : new String[] {"-nodefaults", "-display", "none", "-monitor", "none", "-no-reboot"}) {
            if (!joined.contains("\u0000" + flag + "\u0000") && !spec[spec.length - 1].equals(flag)
                    && !joined.startsWith(flag + "\u0000")) {
                boolean found = false;
                for (String s : spec) {
                    if (s.equals(flag)) {
                        found = true;
                    }
                }
                if (!found) {
                    fail("missing flag: " + flag);
                }
            }
        }

        // Kernel/initrd/root volume wiring.
        requireArg(spec, "-kernel", "/data/runtime/Image");
        requireArg(spec, "-initrd", "/data/runtime/initramfs-embedded.cpio.gz");
        requireArg(spec, "-drive", "if=none,id=root,file=/data/instances/i1/root-volume.raw,format=raw,cache=unsafe");
        requireArg(spec, "-device", "virtio-blk-device,drive=root");

        // Bridge and terminal channels use abstract sockets served by QEMU.
        requireArg(spec, "-chardev",
                "socket,id=terminal,path=droidlayer-terminal-i1,abstract=on,server=on,wait=off");
        requireArg(spec, "-serial", "chardev:terminal");
        requireArg(spec, "-chardev",
                "socket,id=control,path=droidlayer-control-i1,abstract=on,server=on,wait=off");
        requireArg(spec, "-device", "virtserialport,chardev=control,name=droidlayer.bridge");
        requireArg(spec, "-device", "virtio-serial-device");

        // Guest provisioning cmdline matches the proven harness.
        requireArg(spec, "-append", "console=ttyAMA0 rdinit=/init panic=-1"
                + " droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input"
                + " droidlayer.bridge_device=/dev/vport0p1 droidlayer.terminal_device=/dev/ttyAMA0");

        // No 9p transport may leak into the launch (virtfs is disabled in the build).
        if (joined.contains("9p") || joined.contains("fsdev") || joined.contains("virtio-9p")) {
            fail("9p transport present");
        }
        // No shell: every element must be argument-safe.
        for (String s : spec) {
            if (s.contains(";") || s.contains("&") || s.contains("|") || s.contains("`")) {
                fail("shell metacharacter in argument");
            }
        }
        if (!spec[0].endsWith("libdroidlayer_qemu.so")) {
            fail("executable is not the packaged QEMU entry");
        }

        System.out.println("QEMU_LAUNCH_SPEC_TEST_OK args=" + spec.length + " no_9p=1 no_shell=1 fixed_shape=1");
    }

    private static void requireArg(String[] spec, String flag, String value) {
        for (int i = 0; i < spec.length - 1; i++) {
            if (spec[i].equals(flag) && spec[i + 1].equals(value)) {
                return;
            }
        }
        fail("missing flag/value pair: " + flag + "=" + value);
    }

    private static void fail(String reason) {
        System.out.println("QEMU_LAUNCH_SPEC_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
