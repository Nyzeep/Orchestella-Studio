package io.github.nyzeep.orchestella.runtime.engineqemu;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.util.Arrays;

/** T7 contract: tuning knobs never change defaults and never enable unsafe caching. */
public final class QemuTuningTest {
    private static final String QEMU = "/native/lib/arm64-v8a/libdroidlayer_qemu.so";
    private static final String KERNEL = "/data/runtime/Image";
    private static final String INITRD = "/data/runtime/initramfs-embedded.cpio.gz";
    private static final String ROOT = "/data/instances/i1/root-volume.raw";
    private static final String CONTROL = "droidlayer-control-i1";
    private static final String TERMINAL = "droidlayer-terminal-i1";
    private static final VmHardwareConfig HARDWARE = VmHardwareConfig.defaultConfig();

    public static void main(String[] args) {
        testDefaultsAreByteIdenticalToTheLegacyOverload();
        testEverySingleVariableOverrideIsExplicit();
        testDefaultSpecHasNoTuningOptionsAndNoUnsafeCache();
        testUnixPasstTransportIsExplicit();
        testInvalidTuningValuesAreRejected();
        System.out.println("QEMU_TUNING_TEST_OK");
    }

    private static void testDefaultsAreByteIdenticalToTheLegacyOverload() {
        String[] legacy = QemuLaunchSpec.buildWithPasst(
                QEMU, KERNEL, INITRD, ROOT, CONTROL, TERMINAL, HARDWARE, "12345", null);
        String[] explicit = QemuLaunchSpec.buildWithPasst(
                QEMU, KERNEL, INITRD, ROOT, CONTROL, TERMINAL, HARDWARE, "12345", null,
                QemuTuning.defaults());
        if (!Arrays.equals(legacy, explicit)) {
            fail("default tuning changed the launch array");
        }
        requireArg(explicit, "-cpu", "cortex-a57");
        requireArg(explicit, "-smp", "2");
        requireArg(explicit, "-accel", "tcg,thread=multi");
        requireArg(explicit, "-drive",
                "if=none,id=root,file=" + ROOT + ",format=raw,cache=writeback");
        requireArg(explicit, "-netdev", "socket,id=orchestella-net,connect=127.0.0.1:12345");
    }

    private static void testEverySingleVariableOverrideIsExplicit() {
        String[] cpu = build(new QemuTuning("neoverse-n1", 0, null, false, false, 0, null));
        requireArg(cpu, "-cpu", "neoverse-n1");
        requireAbsent(cpu, "tb-size");

        String[] tcg = build(new QemuTuning("cortex-a57", 64, null, false, false, 0, null));
        requireArg(tcg, "-accel", "tcg,thread=multi,tb-size=64");

        String[] disk = build(new QemuTuning(
                "cortex-a57", 0, "io_uring", true, true, 0, null));
        requireArg(disk, "-drive",
                "if=none,id=root,file=" + ROOT
                        + ",format=raw,cache=writeback,aio=io_uring,discard=unmap,detect-zeroes=unmap");

        String[] vcpu = build(new QemuTuning("cortex-a57", 0, null, false, false, 4, null));
        requireArg(vcpu, "-smp", "4");
    }

    private static void testDefaultSpecHasNoTuningOptionsAndNoUnsafeCache() {
        String[] spec = build(QemuTuning.defaults());
        String joined = String.join("\u0000", spec);
        for (String forbidden : new String[] {"tb-size=", "aio=", "discard=unmap", "detect-zeroes=unmap"}) {
            if (joined.contains(forbidden)) {
                fail("default spec contains tuning option: " + forbidden);
            }
        }
        if (joined.contains("cache=unsafe")) {
            fail("cache=unsafe must never be introduced");
        }
        if (!joined.contains("cache=writeback")) {
            fail("root volume lost cache=writeback");
        }
    }

    private static void testUnixPasstTransportIsExplicit() {
        String[] spec = build(new QemuTuning(
                "cortex-a57", 0, null, false, false, 0, "/data/runtime/passt.sock"));
        requireArg(spec, "-netdev",
                "stream,id=orchestella-net,addr.type=unix,addr.path=/data/runtime/passt.sock");
        requireAbsent(spec, "connect=127.0.0.1");
    }

    private static void testInvalidTuningValuesAreRejected() {
        expectInvalid(() -> new QemuTuning("cortex-a57;rm", 0, null, false, false, 0, null));
        expectInvalid(() -> new QemuTuning("cortex-a57", -1, null, false, false, 0, null));
        expectInvalid(() -> new QemuTuning("cortex-a57", 0, "unsafe", false, false, 0, null));
        expectInvalid(() -> new QemuTuning("cortex-a57", 0, null, false, false, 0, "relative.sock"));
        expectInvalid(() -> new QemuTuning("cortex-a57", 0, null, false, false, 0, "/tmp/a,b.sock"));
    }

    private static String[] build(QemuTuning tuning) {
        return QemuLaunchSpec.buildWithPasst(
                QEMU, KERNEL, INITRD, ROOT, CONTROL, TERMINAL, HARDWARE, "12345", null, tuning);
    }

    private static void requireArg(String[] args, String flag, String expected) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (flag.equals(args[i]) && expected.equals(args[i + 1])) {
                return;
            }
        }
        fail("missing argument: " + flag + " " + expected);
    }

    private static void requireAbsent(String[] args, String fragment) {
        for (String arg : args) {
            if (arg.contains(fragment)) {
                fail("unexpected argument fragment: " + fragment);
            }
        }
    }

    private static void expectInvalid(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        fail("invalid tuning value was accepted");
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }
}
