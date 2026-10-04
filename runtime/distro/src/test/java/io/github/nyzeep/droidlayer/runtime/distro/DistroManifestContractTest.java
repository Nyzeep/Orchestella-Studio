package io.github.nyzeep.droidlayer.runtime.distro;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Strategy-neutral, fail-closed rootfs input contract. */
public final class DistroManifestContractTest {
    private static final byte[] VERIFIED_ROOTFS = "official-rootfs-bytes".getBytes(StandardCharsets.US_ASCII);
    private static final String VERIFIED_SHA256 =
            "97c1faa80c4c83770a30e0b88f90d8a7ef0a11b7c18897e15fc3c4d413dfe927";

    private static final byte[] VERIFIED_DEBIAN_ROOTFS = "official-debian-rootfs-bytes".getBytes(StandardCharsets.US_ASCII);
    private static final String VERIFIED_DEBIAN_SHA256 =
            "62e6516791a9d7a798d64c4bafea5a2741969334ba465f71c324e9745f4e7f70";

    public static void main(String[] arguments) throws Exception {
        acceptsOnlyThePinnedInput();
        rejectsTamperedInputBeforeItCanBeProvisioned();
        rejectsADeclaredSizeMismatchBeforeItCanBeProvisioned();
        rejectsAChangingOrNonHttpsSource();
        acceptsOfficialDebianManifest();
        verifiesDebianInputWhenShaAndSizeMatch();
        rejectsTamperedDebianInputBeforeItCanBeProvisioned();
        rejectsDebianDeclaredSizeMismatchBeforeItCanBeProvisioned();
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

    private static void acceptsOfficialDebianManifest() {
        DistroManifest debian = OfficialDebianCatalog.debian();
        assertEquals("debian", debian.id(), "official debian id");
        assertEquals("13", debian.version(), "official debian version");
        assertEquals("aarch64", debian.architecture(), "official debian architecture");
        assertEquals("rootfs.tar.xz", debian.rootfsFileName(), "official debian rootfs file name");
        assertEquals(
                "7677f708be8b4df232ebdd177147b1b77beef7c784ce0fa3fbef5dd3e85ab461",
                debian.sha256(),
                "official debian sha256");
        assertEquals(90_371_792L, debian.sizeBytes(), "official debian sizeBytes");
    }

    private static void verifiesDebianInputWhenShaAndSizeMatch() throws Exception {
        DistroManifest manifest = debianManifest();
        DistroManifest.VerifiedInput verified =
                manifest.requireVerified(
                        new ByteArrayInputStream(VERIFIED_DEBIAN_ROOTFS), VERIFIED_DEBIAN_ROOTFS.length);

        assertEquals("debian", verified.distroId(), "debian distro identity");
        assertEquals(VERIFIED_DEBIAN_SHA256, verified.sha256(), "verified debian digest");
        assertEquals((long) VERIFIED_DEBIAN_ROOTFS.length, verified.sizeBytes(), "verified debian size");
    }

    private static void rejectsTamperedDebianInputBeforeItCanBeProvisioned() throws Exception {
        DistroManifest manifest = debianManifest();
        byte[] tampered = "official-debian-rootfs-bytex".getBytes(StandardCharsets.US_ASCII);

        DistroManifest.InputRejectedException rejection =
                assertThrows(
                        DistroManifest.InputRejectedException.class,
                        () -> manifest.requireVerified(new ByteArrayInputStream(tampered), tampered.length),
                        "tampered debian rootfs must be rejected");

        assertEquals("rootfs-checksum-mismatch", rejection.reason(), "clear rejection reason");
    }

    private static void rejectsDebianDeclaredSizeMismatchBeforeItCanBeProvisioned() throws Exception {
        DistroManifest manifest = debianManifest();

        DistroManifest.InputRejectedException rejection =
                assertThrows(
                        DistroManifest.InputRejectedException.class,
                        () ->
                                manifest.requireVerified(
                                        new ByteArrayInputStream(VERIFIED_DEBIAN_ROOTFS),
                                        VERIFIED_DEBIAN_ROOTFS.length + 1L),
                        "an oversized declared debian input must be rejected");

        assertEquals("rootfs-size-mismatch", rejection.reason(), "clear size rejection reason");
    }

    private static DistroManifest debianManifest() {
        return new DistroManifest(
                "debian",
                "13",
                "aarch64",
                "https://example.invalid/rootfs.tar.xz",
                "rootfs.tar.xz",
                VERIFIED_DEBIAN_SHA256,
                VERIFIED_DEBIAN_ROOTFS.length);
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
