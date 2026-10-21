package io.github.nyzeep.orchestella.runtime.engineqemu;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * P08 contract: every tuning candidate is an independent opt-in switch, the
 * default launch array is byte-identical to the previous behaviour, and
 * {@code cache=unsafe} is never representable.
 */
public final class QemuTuningTest {
    private static final String QEMU = "/native/lib/arm64-v8a/libdroidlayer_qemu.so";
    private static final String KERNEL = "/data/runtime/Image";
    private static final String INITRD = "/data/runtime/initramfs-embedded.cpio.gz";
    private static final String ROOT = "/data/instances/i1/root-volume.raw";
    private static final String CONTROL = "droidlayer-control-i1";
    private static final String TERMINAL = "droidlayer-terminal-i1";
    private static final String PROXY = "orchestella-proxy-i1";
    private static final String ROOT_DRIVE =
            "if=none,id=root,file=" + ROOT + ",format=raw,cache=writeback";
    private static final VmHardwareConfig HARDWARE = VmHardwareConfig.defaultConfig();

    public static void main(String[] args) {
        testDefaultsAreByteIdenticalToTheLegacyOverload();
        testDefaultLaunchArrayMatchesGoldenSnapshot();
        testCpuMaxCandidate();
        testTbSizeIsMib();
        testIothreadCandidate();
        testMultiQueueCandidate();
        testAioThreadsCandidate();
        testDiscardCandidate();
        testDetectZeroesCandidate();
        testRngCandidate();
        testOvercommitCandidate();
        testVcpuCandidate();
        testNewSwitchesStayOffByDefault();
        testInvalidTuningValuesAreRejected();
        testNoDuplicateCpuOrAccelFlags();
        testLegacyConstructorKeepsNewSwitchesOff();
        System.out.println("QEMU_TUNING_TEST_OK");
    }

    private static void testDefaultsAreByteIdenticalToTheLegacyOverload() {
        String[] legacy = QemuLaunchSpec.buildWithPasst(
                QEMU, KERNEL, INITRD, ROOT, CONTROL, TERMINAL, HARDWARE, "12345", null);
        String[] explicit = QemuLaunchSpec.buildWithPasst(
                QEMU, KERNEL, INITRD, ROOT, CONTROL, TERMINAL, HARDWARE, "12345", null,
                QemuTuning.DEFAULTS);
        if (!Arrays.equals(legacy, explicit)) {
            fail("default tuning changed the launch array");
        }
    }

    /** One literal golden array; every candidate below is this array plus one delta. */
    private static void testDefaultLaunchArrayMatchesGoldenSnapshot() {
        assertArgs(QemuTuning.DEFAULTS, goldenDefaults(), "defaults");
    }

    private static void testCpuMaxCandidate() {
        QemuTuning tuning = QemuTuning.builder()
                .cpuModel(QemuTuning.CPU_MAX_SVE_OFF_PAUTH_IMPDEF)
                .build();
        assertArgs(tuning, replace(goldenDefaults(), "cortex-a57",
                QemuTuning.CPU_MAX_SVE_OFF_PAUTH_IMPDEF), "cpu-max");
    }

    private static void testTbSizeIsMib() {
        QemuTuning tuning = QemuTuning.builder().tbSizeMib(64).build();
        assertArgs(tuning, replace(goldenDefaults(), "tcg,thread=multi",
                "tcg,thread=multi,tb-size=64"), "tb-size-mib");
        if (tuning.tbSizeMib() != 64) {
            fail("tb-size must expose MiB");
        }
    }

    private static void testIothreadCandidate() {
        QemuTuning tuning = QemuTuning.builder().iothreadPerDisk(true).build();
        List<String> expected = insertBefore(goldenDefaults(), "-drive",
                "-object", "iothread,id=iothread-root");
        expected = replace(expected, "virtio-blk-device,drive=root",
                "virtio-blk-device,drive=root,iothread=iothread-root");
        assertArgs(tuning, expected, "iothread");
    }

    private static void testMultiQueueCandidate() {
        QemuTuning tuning = QemuTuning.builder().multiQueueVcpu(true).build();
        assertArgs(tuning, replace(goldenDefaults(), "virtio-blk-device,drive=root",
                "virtio-blk-device,drive=root,num-queues=2"), "num-queues");
    }

    private static void testAioThreadsCandidate() {
        QemuTuning tuning = QemuTuning.builder().blockAio("threads").build();
        assertArgs(tuning, replace(goldenDefaults(), ROOT_DRIVE,
                ROOT_DRIVE + ",aio=threads"), "aio-threads");
    }

    private static void testDiscardCandidate() {
        QemuTuning tuning = QemuTuning.builder().discardUnmap(true).build();
        assertArgs(tuning, replace(goldenDefaults(), ROOT_DRIVE,
                ROOT_DRIVE + ",discard=unmap"), "discard-unmap");
    }

    private static void testDetectZeroesCandidate() {
        QemuTuning tuning = QemuTuning.builder().detectZeroesUnmap(true).build();
        assertArgs(tuning, replace(goldenDefaults(), ROOT_DRIVE,
                ROOT_DRIVE + ",detect-zeroes=unmap"), "detect-zeroes");
        if (String.join("\u0000", build(tuning)).contains("discard=unmap")) {
            fail("detect-zeroes candidate must not silently enable discard");
        }
    }

    private static void testRngCandidate() {
        QemuTuning tuning = QemuTuning.builder().rngRandom(true).build();
        List<String> expected = insertAfter(goldenDefaults(), "virtio-gpu-gl-device",
                "-object", "rng-random,id=rng0,filename=/dev/urandom",
                "-device", "virtio-rng-device,rng=rng0");
        assertArgs(tuning, expected, "rng-random");
    }

    private static void testOvercommitCandidate() {
        QemuTuning tuning = QemuTuning.builder().overcommitMemLockOff(true).build();
        List<String> expected = insertAfter(goldenDefaults(), "virt,gic-version=3",
                "-overcommit", "mem-lock=off");
        assertArgs(tuning, expected, "overcommit");
    }

    private static void testVcpuCandidate() {
        QemuTuning tuning = QemuTuning.builder().vcpuCount(4).build();
        assertArgs(tuning, replace(goldenDefaults(), "2", "4"), "vcpu");
    }

    private static void testNewSwitchesStayOffByDefault() {
        String[] spec = build(QemuTuning.DEFAULTS);
        String joined = String.join("\u0000", spec);
        for (String forbidden : new String[] {
                "tb-size=", "aio=", "discard=unmap", "detect-zeroes=unmap",
                "iothread=", "num-queues=", "rng-random", "virtio-rng",
                "-overcommit", "mem-lock="}) {
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

    private static void testInvalidTuningValuesAreRejected() {
        expectInvalid(() -> QemuTuning.builder().cpuModel("cortex-a57;rm").build());
        expectInvalid(() -> QemuTuning.builder().tbSizeMib(-1).build());
        expectInvalid(() -> QemuTuning.builder().tbSizeMib(QemuTuning.MAX_TB_SIZE_MIB + 1).build());
        expectInvalid(() -> QemuTuning.builder().blockAio("unsafe").build());
        expectInvalid(() -> QemuTuning.builder().blockAio("native").build());
        expectInvalid(() -> QemuTuning.builder().vcpuCount(-1).build());
        expectInvalid(() -> QemuTuning.builder().vcpuCount(17).build());
        expectInvalid(() -> new QemuTuning(
                "cortex-a57", 0, null, false, false, 0, "relative.sock"));
        expectInvalid(() -> new QemuTuning(
                "cortex-a57", 0, null, false, false, 0, "/tmp/a,b.sock"));
    }

    private static void testNoDuplicateCpuOrAccelFlags() {
        QemuTuning all = QemuTuning.builder()
                .cpuModel(QemuTuning.CPU_MAX_SVE_OFF)
                .tbSizeMib(64)
                .blockAio("threads")
                .discardUnmap(true)
                .detectZeroesUnmap(true)
                .iothreadPerDisk(true)
                .multiQueueVcpu(true)
                .rngRandom(true)
                .overcommitMemLockOff(true)
                .vcpuCount(4)
                .build();
        String[] args = build(all);
        requireSingle(args, "-cpu");
        requireSingle(args, "-accel");
        requireSingle(args, "-smp");
        String joined = String.join("\u0000", args);
        if (joined.contains("cache=unsafe")) {
            fail("cache=unsafe must never be introduced");
        }
    }

    private static void testLegacyConstructorKeepsNewSwitchesOff() {
        QemuTuning legacy = new QemuTuning("cortex-a57", 0, null, false, false, 0, null);
        assertArgs(legacy, goldenDefaults(), "legacy-constructor");
    }

    private static List<String> goldenDefaults() {
        return new ArrayList<>(Arrays.asList(
                QEMU,
                "-machine", "virt,gic-version=3",
                "-accel", "tcg,thread=multi",
                "-cpu", "cortex-a57",
                "-smp", "2",
                "-m", "2048M",
                "-nodefaults",
                "-display", "none",
                "-monitor", "none",
                "-no-reboot",
                "-chardev", "socket,id=terminal,path=" + TERMINAL + ",abstract=on,server=on,wait=off",
                "-serial", "chardev:terminal",
                "-kernel", KERNEL,
                "-initrd", INITRD,
                "-append", QemuLaunchSpec.GUEST_CMDLINE,
                "-drive", ROOT_DRIVE,
                "-device", "virtio-blk-device,drive=root",
                "-device", "virtio-gpu-gl-device",
                "-netdev", "socket,id=orchestella-net,connect=127.0.0.1:12345",
                "-device", "virtio-net-device,netdev=orchestella-net",
                "-device", "virtio-serial-device",
                "-chardev", "socket,id=control,path=" + CONTROL + ",abstract=on,server=on,wait=off",
                "-device", "virtserialport,chardev=control,name=droidlayer.bridge",
                "-chardev", "socket,id=netproxy,path=" + PROXY + ",abstract=on,server=on,wait=off",
                "-device", "virtserialport,chardev=netproxy,name=orchestella.proxy"));
    }

    private static List<String> replace(List<String> base, String oldValue, String newValue) {
        List<String> copy = new ArrayList<>(base);
        int index = copy.indexOf(oldValue);
        if (index < 0) {
            fail("missing value to replace: " + oldValue);
        }
        copy.set(index, newValue);
        return copy;
    }

    private static List<String> insertBefore(
            List<String> base, String anchor, String... values) {
        List<String> copy = new ArrayList<>(base);
        int index = copy.indexOf(anchor);
        if (index < 0) {
            fail("missing anchor: " + anchor);
        }
        copy.addAll(index, Arrays.asList(values));
        return copy;
    }

    private static List<String> insertAfter(
            List<String> base, String anchor, String... values) {
        List<String> copy = new ArrayList<>(base);
        int index = copy.indexOf(anchor);
        if (index < 0) {
            fail("missing anchor: " + anchor);
        }
        copy.addAll(index + 1, Arrays.asList(values));
        return copy;
    }

    private static String[] build(QemuTuning tuning) {
        return QemuLaunchSpec.buildWithPasst(
                QEMU, KERNEL, INITRD, ROOT, CONTROL, TERMINAL, HARDWARE, "12345", null, tuning);
    }

    private static void assertArgs(QemuTuning tuning, List<String> expected, String label) {
        String[] actual = build(tuning);
        if (!Arrays.equals(actual, expected.toArray(new String[0]))) {
            fail(label + " argv mismatch:\nactual  =" + Arrays.toString(actual)
                    + "\nexpected=" + expected);
        }
    }

    private static void requireArg(String[] args, String flag, String expected) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (flag.equals(args[i]) && expected.equals(args[i + 1])) {
                return;
            }
        }
        fail("missing argument: " + flag + " " + expected);
    }

    private static void requireSingle(String[] args, String flag) {
        int count = 0;
        for (String arg : args) {
            if (flag.equals(arg)) {
                count++;
            }
        }
        if (count != 1) {
            fail("expected exactly one " + flag + ", found " + count);
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
