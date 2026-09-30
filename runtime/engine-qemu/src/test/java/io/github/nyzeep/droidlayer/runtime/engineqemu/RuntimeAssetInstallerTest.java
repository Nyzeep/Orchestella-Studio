package io.github.nyzeep.droidlayer.runtime.engineqemu;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

/** Contract tests for the manifest-verified runtime asset installer. */
public final class RuntimeAssetInstallerTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("RUNTIME_ASSET_INSTALLER_TEST_FAIL reason=usage");
            System.exit(2);
        }
        Path work = Path.of(args[0]);
        Files.createDirectories(work);

        byte[] kernel = synthetic(64 * 1024, 0x5A);
        byte[] initramfs = synthetic(32 * 1024, 0xA5);
        RuntimeArtifactManifest manifest = RuntimeArtifactManifest.parse(manifestStream(
                entry("Image", kernel) + entry("initramfs.cpio.gz", initramfs)
                        + entry("lib/arm64-v8a/libdroidlayer_qemu.so", synthetic(1024, 0x00))
                        + entry("QEMU-BUILD-INPUTS", synthetic(128, 0x01))
                        + entry("QEMU-GPL-SOURCES.md", synthetic(128, 0x02))
                        + entry("LICENSE.GPL-2.0", synthetic(128, 0x03))
                        + entry("NOTICE.QEMU", synthetic(128, 0x04))));

        RuntimeAssetInstaller installer = new RuntimeAssetInstaller();

        // Positive: verified bytes land at the target path.
        Path target = work.resolve("positive");
        Files.createDirectories(target);
        Path kernelOut = installer.install(manifest, "Image", () -> new ByteArrayInputStream(kernel), target);
        if (!java.util.Arrays.equals(kernel, Files.readAllBytes(kernelOut))) {
            fail("positive bytes mismatch");
        }

        // Negative: tampered stream is rejected and leaves no partial or final file.
        Path tamperTarget = work.resolve("tamper");
        Files.createDirectories(tamperTarget);
        byte[] tampered = kernel.clone();
        tampered[9] ^= 0x7F;
        try (InputStream in = new ByteArrayInputStream(tampered)) {
            installer.install(manifest, "Image", () -> in, tamperTarget);
            fail("tampered stream accepted");
        } catch (RuntimeArtifactManifest.IntegrityRejectedException e) {
            requireReason(e, "runtime-artifact-checksum-mismatch");
        }
        if (Files.exists(tamperTarget.resolve("Image"))
                || Files.exists(tamperTarget.resolve("Image.partial"))) {
            fail("tampered install left files behind");
        }

        // Negative: artifact path absent from the manifest is rejected.
        Path absentTarget = work.resolve("absent");
        Files.createDirectories(absentTarget);
        try (InputStream in = new ByteArrayInputStream(initramfs)) {
            installer.install(manifest, "unknown.bin", () -> in, absentTarget);
            fail("absent manifest entry accepted");
        } catch (RuntimeArtifactManifest.IntegrityRejectedException e) {
            requireReason(e, "runtime-artifact-manifest-invalid");
        }

        System.out.println("RUNTIME_ASSET_INSTALLER_TEST_OK positive=1 tamper_rejected=1 absent_rejected=1");
    }

    private static void requireReason(RuntimeArtifactManifest.IntegrityRejectedException e, String expected) {
        if (!expected.equals(e.reason())) {
            fail("rejection reason mismatch: wanted " + expected + " got " + e.reason());
        }
    }

    private static void fail(String reason) {
        System.out.println("RUNTIME_ASSET_INSTALLER_TEST_FAIL reason=" + reason);
        System.exit(1);
    }

    private static byte[] synthetic(int size, int seed) {
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = (byte) (seed + i * 31);
        }
        return out;
    }

    private static String entry(String path, byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex + "  " + path + "\n";
    }

    private static InputStream manifestStream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
    }
}
