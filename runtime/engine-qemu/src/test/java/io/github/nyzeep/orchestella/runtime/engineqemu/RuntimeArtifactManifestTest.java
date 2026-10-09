package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

/** Observable installed-native-artifact integrity behavior for the QEMU execution adapter. */
public final class RuntimeArtifactManifestTest {
    private static final String QEMU_RELATIVE_PATH = "lib/arm64-v8a/libdroidlayer_qemu.so";
    private static final String QEMU_BYTES = "synthetic-qemu-elf";
    private static final String QEMU_SHA256 =
            "c9f2195af28982c5dba4324db9bf62db19f5212b02174064f44853d6416d78ab";
    private static final String OTHER_ARTIFACT_SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected one approved test directory");
        }
        Path testDirectory = Path.of(arguments[0]).toAbsolutePath().normalize();
        Files.createDirectories(testDirectory);

        acceptsOnlyAManifestMatchedQemuInsideTheNativeLibraryDirectory(testDirectory);
        rejectsATamperedNativeQemuBeforeLaunch(testDirectory);
        rejectsAManifestPathThatEscapesTheApkLayout(testDirectory);
        verifiesKernelAndInitramfsAssetsBeforeTheyAreUsed();
    }

    private static void acceptsOnlyAManifestMatchedQemuInsideTheNativeLibraryDirectory(Path testDirectory)
            throws Exception {
        Path nativeLibraryDirectory = testDirectory.resolve("valid/native");
        Files.createDirectories(nativeLibraryDirectory);
        Path qemu = nativeLibraryDirectory.resolve("libdroidlayer_qemu.so");
        Files.writeString(qemu, QEMU_BYTES, StandardCharsets.US_ASCII);

        RuntimeArtifactManifest manifest = manifest(QEMU_RELATIVE_PATH);
        Path verified = manifest.requireVerifiedInstalledQemu(nativeLibraryDirectory);

        assertEquals(qemu.toRealPath(), verified, "verified QEMU path");
    }

    private static void rejectsATamperedNativeQemuBeforeLaunch(Path testDirectory) throws Exception {
        Path nativeLibraryDirectory = testDirectory.resolve("tampered/native");
        Files.createDirectories(nativeLibraryDirectory);
        Files.writeString(
                nativeLibraryDirectory.resolve("libdroidlayer_qemu.so"),
                "altered-qemu-elf",
                StandardCharsets.US_ASCII);

        RuntimeArtifactManifest.IntegrityRejectedException rejection =
                assertThrows(
                        RuntimeArtifactManifest.IntegrityRejectedException.class,
                        () -> manifest(QEMU_RELATIVE_PATH).requireVerifiedInstalledQemu(nativeLibraryDirectory),
                        "tampered QEMU must be rejected before launch");

        assertEquals("runtime-artifact-checksum-mismatch", rejection.reason(), "tamper rejection reason");
    }

    private static void rejectsAManifestPathThatEscapesTheApkLayout(Path testDirectory) throws Exception {
        Path nativeLibraryDirectory = testDirectory.resolve("escaped/native");
        Files.createDirectories(nativeLibraryDirectory);
        Files.writeString(
                nativeLibraryDirectory.resolve("libdroidlayer_qemu.so"), QEMU_BYTES, StandardCharsets.US_ASCII);

        RuntimeArtifactManifest.IntegrityRejectedException rejection =
                assertThrows(
                        RuntimeArtifactManifest.IntegrityRejectedException.class,
                        () -> manifest("../libdroidlayer_qemu.so"),
                        "escaped manifest entry must be rejected");

        assertEquals("runtime-artifact-manifest-invalid", rejection.reason(), "path rejection reason");
    }

    private static void verifiesKernelAndInitramfsAssetsBeforeTheyAreUsed() throws Exception {
        byte[] kernel = "synthetic-kernel-image".getBytes(StandardCharsets.US_ASCII);
        byte[] initramfs = "synthetic-initramfs".getBytes(StandardCharsets.US_ASCII);
        RuntimeArtifactManifest manifest = manifestForPackagedAssets(kernel, initramfs);

        manifest.requireVerifiedPackagedAsset("Image", new ByteArrayInputStream(kernel));
        manifest.requireVerifiedPackagedAsset("initramfs.cpio.gz", new ByteArrayInputStream(initramfs));

        RuntimeArtifactManifest.IntegrityRejectedException rejection =
                assertThrows(
                        RuntimeArtifactManifest.IntegrityRejectedException.class,
                        () ->
                                manifest.requireVerifiedPackagedAsset(
                                        "Image",
                                        new ByteArrayInputStream(
                                                "altered-kernel-image"
                                                        .getBytes(StandardCharsets.US_ASCII))),
                        "altered kernel must be rejected before extraction or launch");

        assertEquals("runtime-artifact-checksum-mismatch", rejection.reason(), "kernel tamper reason");
    }

    private static RuntimeArtifactManifest manifest(String qemuPath) throws Exception {
        StringBuilder entries = new StringBuilder();
        for (String path : new String[] {
            "Image",
            "initramfs.cpio.gz",
            "QEMU-BUILD-INPUTS",
            "QEMU-GPL-SOURCES.md",
            "LICENSE.GPL-2.0",
            "NOTICE.QEMU",
            qemuPath
        }) {
            String hash = QEMU_RELATIVE_PATH.equals(path)
                    ? QEMU_SHA256
                    : OTHER_ARTIFACT_SHA256;
            entries.append(hash).append("  ").append(path).append("\n");
        }
        return RuntimeArtifactManifest.parse(
                new ByteArrayInputStream(
                        entries.toString().getBytes(StandardCharsets.US_ASCII)));
    }

    private static RuntimeArtifactManifest manifestForPackagedAssets(byte[] kernel, byte[] initramfs)
            throws Exception {
        StringBuilder entries = new StringBuilder();
        entries.append(sha256(kernel)).append("  Image\n");
        entries.append(sha256(initramfs)).append("  initramfs.cpio.gz\n");
        entries.append(OTHER_ARTIFACT_SHA256).append("  QEMU-BUILD-INPUTS\n");
        entries.append(OTHER_ARTIFACT_SHA256).append("  QEMU-GPL-SOURCES.md\n");
        entries.append(OTHER_ARTIFACT_SHA256).append("  LICENSE.GPL-2.0\n");
        entries.append(OTHER_ARTIFACT_SHA256).append("  NOTICE.QEMU\n");
        entries.append(QEMU_SHA256).append("  ").append(QEMU_RELATIVE_PATH).append("\n");
        return RuntimeArtifactManifest.parse(
                new ByteArrayInputStream(entries.toString().getBytes(StandardCharsets.US_ASCII)));
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest(bytes)) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static <T extends Throwable> T assertThrows(
            Class<T> type, ThrowingRunnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return type.cast(throwable);
            }
            throw new AssertionError(description + ": unexpected exception", throwable);
        }
        throw new AssertionError(description + ": expected exception " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
