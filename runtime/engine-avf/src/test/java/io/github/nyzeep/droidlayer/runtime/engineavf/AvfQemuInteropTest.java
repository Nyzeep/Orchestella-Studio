package io.github.nyzeep.droidlayer.runtime.engineavf;

import io.github.nyzeep.droidlayer.runtime.engineqemu.QemuLaunchSpec;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ADR-0001 asset-interop promise at the spec level: one raw root volume, one
 * kernel Image and one embedded-provision initramfs are consumed by BOTH
 * strategies with identical binary identity — no conversion, no copy. The
 * launch of the same instance through the other engine is a path swap only.
 */
public final class AvfQemuInteropTest {
    public static void main(String[] arguments) throws Exception {
        sameAssetsAreReferencedByBothLaunchSpecs();
        guestInputTagIsSharedAcrossEngines();
    }

    private static void sameAssetsAreReferencedByBothLaunchSpecs() throws Exception {
        Path temp = Files.createTempDirectory("avf-qemu-interop");
        Path kernel = temp.resolve("Image");
        Path initramfs = temp.resolve("initramfs-embedded.cpio.gz");
        Path rootVolume = temp.resolve("root.raw");
        Files.write(kernel, new byte[] {1});
        Files.write(initramfs, new byte[] {2});
        Files.write(rootVolume, new byte[] {3, 4, 5, 6});

        String[] qemuArgs = QemuLaunchSpec.build(
                "libdroidlayer_qemu.so",
                kernel.toString(),
                initramfs.toString(),
                rootVolume.toString(),
                "droidlayer-control-x",
                "droidlayer-terminal-x",
                512);

        String driveValue = valueAfter(qemuArgs, "-drive");
        assertTrue(
                driveValue.contains("file=" + rootVolume + ","),
                "QEMU drive must reference the exact raw root volume: " + driveValue);
        assertEquals(kernel.toString(), valueAfter(qemuArgs, "-kernel"), "QEMU kernel path");
        assertEquals(initramfs.toString(), valueAfter(qemuArgs, "-initrd"), "QEMU initrd path");

        AvfLaunchSpec avfSpec = AvfLaunchSpec.builder(
                        "interop-1", kernel.toString(), initramfs.toString(), rootVolume.toString())
                .build();
        assertEquals(kernel.toString(), avfSpec.kernelPath(), "AVF kernel path");
        assertEquals(initramfs.toString(), avfSpec.initramfsPath(), "AVF initramfs path");
        assertEquals(rootVolume.toString(), avfSpec.rootVolumePath(), "AVF root volume path");

        // The interop point: the SAME path string (hence the same file bytes)
        // feeds both engines; switching strategy never rewrites the asset.
        assertTrue(
                driveValue.contains("file=" + avfSpec.rootVolumePath() + ","),
                "both engines consume the identical root volume path");
        System.out.println("AVF_QEMU_INTEROP_TEST_OK same_kernel=1 same_initramfs=1 same_root_volume=1");
    }

    private static void guestInputTagIsSharedAcrossEngines() throws Exception {
        Path temp = Files.createTempDirectory("avf-qemu-interop-tag");
        AvfLaunchSpec avfSpec = AvfLaunchSpec.builder(
                        "interop-2",
                        temp.resolve("Image").toString(),
                        temp.resolve("initramfs-embedded.cpio.gz").toString(),
                        temp.resolve("root.raw").toString())
                .build();
        String qemuAppend = QemuLaunchSpec.GUEST_CMDLINE;

        assertTrue(
                qemuAppend.contains("droidlayer.input_tag=droidlayer-input")
                        && avfSpec.guestCmdline().contains("droidlayer.input_tag=droidlayer-input"),
                "both engines announce the same embedded-provision input tag");
        assertTrue(
                avfSpec.guestCmdline().contains("droidlayer.root_device=/dev/vda"),
                "both engines expose the root volume as the first virtio-blk device");
    }

    private static String valueAfter(String[] args, String flag) {
        for (int index = 0; index < args.length - 1; index++) {
            if (flag.equals(args[index])) {
                return args[index + 1];
            }
        }
        return fail("missing flag " + flag);
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            fail(description);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static String fail(String reason) {
        System.out.println("AVF_QEMU_INTEROP_TEST_FAIL reason=" + reason);
        System.exit(1);
        return reason;
    }
}
