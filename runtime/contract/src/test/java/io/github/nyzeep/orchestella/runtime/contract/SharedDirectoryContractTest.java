package io.github.nyzeep.orchestella.runtime.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.util.List;

/**
 * Strategy-neutral contract tests for shared directory specification, 9p mount options,
 * host path validations, and read-only physical enforcement (ADR-0009, ADR-0010, Ticket 30).
 */
public final class SharedDirectoryContractTest {

    public static void main(String[] args) throws Exception {
        defaultDownloadsSpecHasExpectedBaselineParameters();
        rejectsWritableMountOptionsPerAdr0010();
        validatesHostPathAndIdentifiers();
        rejectsShellInjectionCharactersInHostPath();
        supportsCustomConfigurationThroughBuilder();
        formatsGuestMountCommandAndFstabEntry();
        readOnlyFileSystemSemanticsVerification();
        System.out.println("SHARED_DIRECTORY_CONTRACT_TEST_OK");
    }

    private static void defaultDownloadsSpecHasExpectedBaselineParameters() {
        String hostPath = "/storage/emulated/0/Download";
        SharedDirectoryContract.SharedDirectorySpec spec =
                SharedDirectoryContract.SharedDirectorySpec.defaultDownloads(hostPath);

        assertEquals("fsdev0", spec.fsdevId(), "default fsdevId");
        assertEquals("downloads", spec.mountTag(), "default mountTag");
        assertEquals("/mnt/downloads", spec.guestMountPoint(), "default guestMountPoint");
        assertEquals(hostPath, spec.hostPath(), "hostPath");
        assertEquals(SharedDirectoryContract.DeviceType.VIRTIO_9P_PCI, spec.deviceType(), "default deviceType");

        MountOptions options = spec.mountOptions();
        assertTrue(options.isReadOnly(), "mount must be strictly read-only per ADR-0010");
        assertEquals(MountOptions.SecurityModel.NONE, options.securityModel(), "default security model");
        assertEquals("9p2000.L", options.version(), "default 9p version");
    }

    private static void rejectsWritableMountOptionsPerAdr0010() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MountOptions.builder().readOnly(false).build(),
                "configuring readOnly=false must be rejected per ADR-0010");
    }

    private static void validatesHostPathAndIdentifiers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SharedDirectoryContract.SharedDirectorySpec.defaultDownloads(null),
                "null hostPath must be rejected");

        assertThrows(
                IllegalArgumentException.class,
                () -> SharedDirectoryContract.SharedDirectorySpec.defaultDownloads(""),
                "empty hostPath must be rejected");

        assertThrows(
                IllegalArgumentException.class,
                () -> SharedDirectoryContract.SharedDirectorySpec.defaultDownloads("relative/path"),
                "relative hostPath must be rejected");

        assertThrows(
                IllegalArgumentException.class,
                () -> SharedDirectoryContract.SharedDirectorySpec.builder()
                        .hostPath("/valid/path")
                        .fsdevId("fsdev 0")
                        .build(),
                "fsdevId with whitespace must be rejected");

        assertThrows(
                IllegalArgumentException.class,
                () -> SharedDirectoryContract.SharedDirectorySpec.builder()
                        .hostPath("/valid/path")
                        .mountTag("bad tag!")
                        .build(),
                "mountTag with invalid characters must be rejected");

        assertThrows(
                IllegalArgumentException.class,
                () -> SharedDirectoryContract.SharedDirectorySpec.builder()
                        .hostPath("/valid/path")
                        .guestMountPoint("non-absolute")
                        .build(),
                "relative guestMountPoint must be rejected");
    }

    private static void rejectsShellInjectionCharactersInHostPath() {
        String[] dangerousPaths = {
                "/storage/Download;rm -rf /",
                "/storage/Download&&calc",
                "/storage/Download|cat",
                "/storage/Download`echo 1`",
                "/storage/Download$DIR",
                "/storage/Download\nnewline"
        };
        for (String dangerous : dangerousPaths) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SharedDirectoryContract.SharedDirectorySpec.defaultDownloads(dangerous),
                    "hostPath with shell metacharacter must be rejected: " + dangerous);
        }
    }

    private static void supportsCustomConfigurationThroughBuilder() {
        SharedDirectoryContract.SharedDirectorySpec customSpec =
                SharedDirectoryContract.SharedDirectorySpec.builder()
                        .fsdevId("fsdev_custom")
                        .hostPath("/data/user/0/custom/files")
                        .mountTag("custom_tag")
                        .guestMountPoint("/media/downloads")
                        .deviceType(SharedDirectoryContract.DeviceType.VIRTIO_9P_DEVICE)
                        .mountOptions(MountOptions.readOnly(MountOptions.SecurityModel.NONE))
                        .build();

        assertEquals("fsdev_custom", customSpec.fsdevId(), "custom fsdevId");
        assertEquals("/data/user/0/custom/files", customSpec.hostPath(), "custom hostPath");
        assertEquals("custom_tag", customSpec.mountTag(), "custom mountTag");
        assertEquals("/media/downloads", customSpec.guestMountPoint(), "custom guestMountPoint");
        assertEquals(SharedDirectoryContract.DeviceType.VIRTIO_9P_DEVICE, customSpec.deviceType(), "custom deviceType");
        assertTrue(customSpec.mountOptions().isReadOnly(), "custom mountOptions readOnly");
    }

    private static void formatsGuestMountCommandAndFstabEntry() {
        SharedDirectoryContract.SharedDirectorySpec spec =
                SharedDirectoryContract.SharedDirectorySpec.defaultDownloads("/storage/emulated/0/Download");

        assertEquals(
                "mount -t 9p -o trans=virtio,version=9p2000.L,ro downloads /mnt/downloads",
                spec.toGuestMountCommand(),
                "guest mount command");

        assertEquals(
                "downloads\t/mnt/downloads\t9p\ttrans=virtio,version=9p2000.L,ro,nofail\t0\t0",
                spec.toGuestFstabEntry(),
                "guest fstab entry");
    }

    /**
     * Verifies AC-1 (reading host files) and AC-2 (physical write rejection)
     * using the contract's ReadOnlyView.
     */
    private static void readOnlyFileSystemSemanticsVerification() throws IOException {
        Path tempDir = Files.createTempDirectory("droidlayer-share-test-");
        try {
            // AC-1: Placing files in host directory allows Guest to immediately list and read them
            Path testFile1 = tempDir.resolve("hello.txt");
            Files.writeString(testFile1, "Hello from Android Downloads");
            Path subDir = tempDir.resolve("subdir");
            Files.createDirectory(subDir);
            Path testFile2 = subDir.resolve("document.pdf");
            Files.write(testFile2, new byte[] {0x25, 0x50, 0x44, 0x46});

            SharedDirectoryContract.SharedDirectorySpec spec =
                    SharedDirectoryContract.SharedDirectorySpec.defaultDownloads(tempDir.toString());

            SharedDirectoryContract.ReadOnlyView view = spec.createReadOnlyView();

            List<String> entries = view.listRelativePaths();
            assertTrue(entries.contains("hello.txt"), "hello.txt must be listed");
            assertTrue(entries.contains("subdir/document.pdf"), "nested document.pdf must be listed");

            String content = view.readString("hello.txt");
            assertEquals("Hello from Android Downloads", content, "file content must match");

            byte[] bytes = view.readBytes("subdir/document.pdf");
            assertEquals(4, bytes.length, "binary file length");
            assertEquals((byte) 0x25, bytes[0], "binary magic byte");

            // AC-2: Guest write attempts must be rejected with Read-only file system (EROFS)
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> view.createFile("malicious.sh", "#!/bin/sh\n"),
                    "file creation in read-only share must throw ReadOnlyFileSystemException");

            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> view.writeBytes("hello.txt", new byte[] {0x00}),
                    "file modification in read-only share must throw ReadOnlyFileSystemException");

            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> view.delete("hello.txt"),
                    "file deletion in read-only share must throw ReadOnlyFileSystemException");

            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> view.createDirectory("new_dir"),
                    "directory creation in read-only share must throw ReadOnlyFileSystemException");

        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected != null && expected.equals(actual)) {
            return;
        }
        throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T extends Throwable> void assertThrows(
            Class<T> expectedType, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expectedType.isInstance(actual)) {
                return;
            }
            throw new AssertionError(
                    message + ": expected " + expectedType.getName() + " but got " + actual.getClass().getName(),
                    actual);
        }
        throw new AssertionError(message + ": expected " + expectedType.getName() + " but nothing was thrown");
    }
}
