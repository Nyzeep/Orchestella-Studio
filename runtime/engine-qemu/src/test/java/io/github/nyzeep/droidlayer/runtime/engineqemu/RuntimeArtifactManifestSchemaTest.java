package io.github.nyzeep.droidlayer.runtime.engineqemu;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Policy-level runtime artifact-manifest completeness behavior. */
public final class RuntimeArtifactManifestSchemaTest {
    private static final String SYNTHETIC_SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        acceptsACompleteReleaseManifest();
        rejectsAManifestMissingRequiredReleaseArtifacts();
    }

    private static void acceptsACompleteReleaseManifest() throws Exception {
        RuntimeArtifactManifest manifest = RuntimeArtifactManifest.parse(completeManifest());

        assertTrue(manifest != null, "a complete release manifest must parse");
    }

    private static void rejectsAManifestMissingRequiredReleaseArtifacts() throws Exception {
        RuntimeArtifactManifest.IntegrityRejectedException rejection =
                assertThrows(
                        RuntimeArtifactManifest.IntegrityRejectedException.class,
                        () ->
                                RuntimeArtifactManifest.parse(
                                        new ByteArrayInputStream(
                                                (SYNTHETIC_SHA256
                                                                + "  "
                                                                + RuntimeArtifactManifest.QEMU_ARTIFACT_PATH
                                                                + "\n")
                                                        .getBytes(StandardCharsets.US_ASCII))),
                        "a manifest missing kernel, initramfs, and GPL records must be rejected");

        assertEquals(
                "runtime-artifact-manifest-incomplete",
                rejection.reason(),
                "incomplete manifest rejection reason");
    }

    private static ByteArrayInputStream completeManifest() {
        StringBuilder entries = new StringBuilder();
        for (String path : new String[] {
            "Image",
            "initramfs.cpio.gz",
            "QEMU-BUILD-INPUTS",
            "QEMU-GPL-SOURCES.md",
            "LICENSE.GPL-2.0",
            "NOTICE.QEMU",
            "lib/arm64-v8a/libdroidlayer_qemu.so"
        }) {
            entries.append(SYNTHETIC_SHA256).append("  ").append(path).append("\n");
        }
        return new ByteArrayInputStream(entries.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
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
