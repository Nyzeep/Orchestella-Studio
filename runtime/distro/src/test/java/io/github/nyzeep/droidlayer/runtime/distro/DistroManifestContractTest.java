package io.github.nyzeep.droidlayer.runtime.distro;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Strategy-neutral, fail-closed rootfs input contract. */
public final class DistroManifestContractTest {
    private static final byte[] VERIFIED_ROOTFS = "official-rootfs-bytes".getBytes(StandardCharsets.US_ASCII);
    private static final String VERIFIED_SHA256 =
            "97c1faa80c4c83770a30e0b88f90d8a7ef0a11b7c18897e15fc3c4d413dfe927";

    public static void main(String[] arguments) throws Exception {
        acceptsOnlyThePinnedInput();
        rejectsTamperedInputBeforeItCanBeProvisioned();
        rejectsADeclaredSizeMismatchBeforeItCanBeProvisioned();
        rejectsAChangingOrNonHttpsSource();
    }

    private static void acceptsOnlyThePinnedInput() throws Exception {
        DistroManifest manifest = manifest();
        DistroManifest.VerifiedInput verified =
                manifest.requireVerified(
                        new ByteArrayInputStream(VERIFIED_ROOTFS), VERIFIED_ROOTFS.length);

        assertEquals("alpine", verified.distroId(), "distro identity");
        assertEquals(VERIFIED_SHA256, verified.sha256(), "verified digest");
        assertEquals((long) VERIFIED_ROOTFS.length, verified.sizeBytes(), "verified size");
    }

    private static void rejectsTamperedInputBeforeItCanBeProvisioned() throws Exception {
        DistroManifest manifest = manifest();
        byte[] tampered = "official-rootfs-bytfs".getBytes(StandardCharsets.US_ASCII);

        DistroManifest.InputRejectedException rejection =
                assertThrows(
                        DistroManifest.InputRejectedException.class,
                        () -> manifest.requireVerified(new ByteArrayInputStream(tampered), tampered.length),
                        "tampered rootfs must be rejected");

        assertEquals("rootfs-checksum-mismatch", rejection.reason(), "clear rejection reason");
    }

    private static void rejectsADeclaredSizeMismatchBeforeItCanBeProvisioned() throws Exception {
        DistroManifest manifest = manifest();

        DistroManifest.InputRejectedException rejection =
                assertThrows(
                        DistroManifest.InputRejectedException.class,
                        () ->
                                manifest.requireVerified(
                                        new ByteArrayInputStream(VERIFIED_ROOTFS),
                                        VERIFIED_ROOTFS.length - 1L),
                        "a short declared input must be rejected");

        assertEquals("rootfs-size-mismatch", rejection.reason(), "clear size rejection reason");
    }

    private static void rejectsAChangingOrNonHttpsSource() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DistroManifest(
                                "alpine",
                                "3.24.1",
                                "aarch64",
                                "http://example.invalid/rootfs.tar.gz",
                                "rootfs.tar.gz",
                                VERIFIED_SHA256,
                                VERIFIED_ROOTFS.length),
                "non-HTTPS source must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DistroManifest(
                                "alpine",
                                "3.24.1",
                                "aarch64",
                                "https://example.invalid/rootfs.tar.gz?latest=true",
                                "rootfs.tar.gz",
                                VERIFIED_SHA256,
                                VERIFIED_ROOTFS.length),
                "query-bearing source must be rejected");
    }

    private static DistroManifest manifest() {
        return new DistroManifest(
                "alpine",
                "3.24.1",
                "aarch64",
                "https://example.invalid/alpine-minirootfs-3.24.1-aarch64.tar.gz",
                "alpine-minirootfs-3.24.1-aarch64.tar.gz",
                VERIFIED_SHA256,
                VERIFIED_ROOTFS.length);
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
