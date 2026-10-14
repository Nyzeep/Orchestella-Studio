package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.engineqemu.EmbeddedProvisionInput;
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class QemuRuntimeBootstrapperTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ROOTFS_NAME = "rootfs.tar.gz";
    private static final byte[] GUEST_INIT = "#!/bin/sh\ntrue\n".getBytes(StandardCharsets.US_ASCII);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void bootstrapMustReturnTheInstanceRootVolumeThatExistsBeforeQemuExec() throws Exception {
        Fixture fixture = new Fixture();
        QemuRuntimeBootstrapper.RuntimePaths paths = fixture.bootstrapper.bootstrap(ROOTFS_NAME, TOKEN, fixture.rootVolume);
        assertEquals(fixture.rootVolume, paths.rootVolume);
        assertTrue(Files.isRegularFile(paths.rootVolume));
    }

    @Test
    public void bootstrapRejectsAMissingInstanceRootVolumeBeforeQemuExec() throws Exception {
        Fixture fixture = new Fixture();
        Files.delete(fixture.rootVolume);
        expectRejected(fixture, "root-volume-missing", ROOTFS_NAME);
    }

    @Test
    public void bootstrapSupportsGuestPasswordWithoutExposingItInDiagnostics() throws Exception {
        Fixture fixture = new Fixture();
        QemuRuntimeBootstrapper.RuntimePaths paths = fixture.bootstrapper.bootstrap(
                ROOTFS_NAME, TOKEN, "SyntheticGuestPassword", fixture.rootVolume);
        Map<String, byte[]> entries = fixture.entries(paths.initramfs);
        assertEntry(entries, EmbeddedProvisionInput.EMBEDDED_DIR + "/guest.password", "SyntheticGuestPassword\n");
        assertEntry(entries, EmbeddedProvisionInput.EMBEDDED_DIR + "/rootfs.sha256", fixture.distro.sha256() + "\n");
    }

    @Test
    public void bootstrapInjectsManifestTrustIntoEmbeddedAndLegacyInputsWithGuestInit() throws Exception {
        Fixture fixture = new Fixture();
        QemuRuntimeBootstrapper.RuntimePaths paths = fixture.bootstrapper.bootstrap(ROOTFS_NAME, TOKEN, fixture.rootVolume);
        Map<String, byte[]> entries = fixture.entries(paths.initramfs);
        for (String directory : new String[] {EmbeddedProvisionInput.EMBEDDED_DIR, "etc/droidlayer"}) {
            assertEntry(entries, directory + "/rootfs.file", fixture.distro.rootfsFileName() + "\n");
            assertEntry(entries, directory + "/rootfs.sha256", fixture.distro.sha256() + "\n");
            assertEntry(entries, directory + "/rootfs.size", fixture.distro.sizeBytes() + "\n");
        }
        assertTrue(Arrays.equals(GUEST_INIT, entries.get("init")));
        assertTrue(Arrays.equals(fixture.tarball, entries.get(EmbeddedProvisionInput.EMBEDDED_DIR + "/" + ROOTFS_NAME)));
    }

    @Test
    public void bootstrapRejectsSameSizeHashTamperingWithoutPublishingOrReplacingOutput() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bootstrapper.bootstrap(ROOTFS_NAME, TOKEN, fixture.rootVolume);
        Path published = fixture.runtimeDir.resolve("initramfs-embedded.cpio.gz");
        byte[] previous = Files.readAllBytes(published);
        byte[] altered = fixture.tarball.clone();
        altered[altered.length / 2] ^= 1;
        Files.write(fixture.distroInputs.resolve(ROOTFS_NAME), altered);
        expectRejected(fixture, "rootfs-checksum-mismatch", ROOTFS_NAME);
        assertTrue("rejected download must not replace a previously verified image",
                Arrays.equals(previous, Files.readAllBytes(published)));
    }

    @Test
    public void bootstrapRejectsTruncatedAndOversizedDownloads() throws Exception {
        for (int difference : new int[] {-1, 1}) {
            Fixture fixture = new Fixture();
            Files.write(fixture.distroInputs.resolve(ROOTFS_NAME), Arrays.copyOf(fixture.tarball, fixture.tarball.length + difference));
            expectRejected(fixture, "rootfs-size-mismatch", ROOTFS_NAME);
            assertFalse(Files.exists(fixture.runtimeDir.resolve("initramfs-embedded.cpio.gz")));
        }
    }

    @Test
    public void bootstrapRejectsUnknownManifestAndTraversalBeforeReadingAssets() throws Exception {
        Fixture fixture = new Fixture();
        Files.write(fixture.distroInputs.resolve("unknown.tar.gz"), fixture.tarball);
        fixture.bootstrapper = fixture.bootstrapper(List.of(fixture.distro), name -> {
            throw new AssertionError("unknown manifest must be rejected before resource access");
        });
        for (String fileName : new String[] {"unknown.tar.gz", "../rootfs.tar.gz", "..\\rootfs.tar.gz", "rootfs.tar.gz\n"}) {
            expectRejected(fixture, "rootfs-manifest-unknown", fileName);
        }
    }

    @Test
    public void defaultCatalogCannotTrustAnArbitraryDownloadedFile() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bootstrapper = new QemuRuntimeBootstrapper(manifest(fixture.kernel, fixture.baseInitramfs),
                name -> { throw new AssertionError("unrecognized download must not access assets"); },
                name -> { throw new AssertionError("unrecognized download must not access resources"); },
                fixture.runtimeDir, fixture.distroInputs);
        expectRejected(fixture, "rootfs-manifest-unknown", ROOTFS_NAME);
    }

    @Test
    public void bootstrapRejectsAmbiguousManifestInsteadOfSelectingTheFirstDigest() throws Exception {
        Fixture fixture = new Fixture();
        DistroManifest conflicting = new DistroManifest("conflicting", "1", "aarch64",
                "https://example.invalid/" + ROOTFS_NAME, ROOTFS_NAME, "0".repeat(64), fixture.tarball.length);
        fixture.bootstrapper = fixture.bootstrapper(List.of(fixture.distro, conflicting), fixture::resource);
        expectRejected(fixture, "rootfs-manifest-ambiguous", ROOTFS_NAME);
    }

    @Test
    public void bootstrapRejectsInvalidArchiveOverridesAndRemovesPartialOutput() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.bootstrapper.bootstrap(ROOTFS_NAME, TOKEN, "SyntheticGuestPassword", fixture.rootVolume,
                    "../rootfs.sha256", "0".repeat(64).getBytes(StandardCharsets.US_ASCII), null);
            fail("archive path override must be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("SyntheticGuestPassword"));
        }
        assertFalse(Files.exists(fixture.runtimeDir.resolve("initramfs-embedded.cpio.gz.partial")));
        assertFalse(Files.exists(fixture.runtimeDir.resolve("initramfs-embedded.cpio.gz")));
    }

    @Test
    public void oversizedGuestScriptCannotPublishAnEmbeddedRuntime() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bootstrapper = fixture.bootstrapper(List.of(fixture.distro), name ->
                "guest_init".equals(name)
                        ? new ByteArrayInputStream(new byte[1024 * 1024 + 1])
                        : fixture.resource(name));
        expectRejected(fixture, "guest-init-unavailable", ROOTFS_NAME);
        assertFalse(Files.exists(fixture.runtimeDir.resolve("initramfs-embedded.cpio.gz")));
    }

    private static void expectRejected(Fixture fixture, String reason, String fileName) throws Exception {
        try {
            fixture.bootstrapper.bootstrap(fileName, TOKEN, "SyntheticGuestPassword", fixture.rootVolume);
            fail("unverified input must not be launched");
        } catch (IOException expected) {
            assertEquals(reason, expected.getMessage());
            assertFalse(expected.getMessage().contains("SyntheticGuestPassword"));
        }
        assertFalse("partial image must not remain usable", Files.exists(fixture.runtimeDir.resolve("initramfs-embedded.cpio.gz.partial")));
    }

    private final class Fixture {
        final Path work = temporaryFolder.newFolder().toPath();
        final Path runtimeDir = Files.createDirectories(work.resolve("runtime"));
        final Path distroInputs = Files.createDirectories(work.resolve("distro-inputs"));
        final Path rootVolume = work.resolve("instance.raw");
        final byte[] kernel = synthetic(4096, 0x5a);
        final byte[] baseInitramfs = synthetic(2048, 0xa5);
        final byte[] tarball = synthetic(1024, 0x11);
        final DistroManifest distro = new DistroManifest("synthetic", "1", "aarch64",
                "https://example.invalid/" + ROOTFS_NAME, ROOTFS_NAME, sha256(tarball), tarball.length);
        QemuRuntimeBootstrapper bootstrapper;

        Fixture() throws Exception {
            Files.write(distroInputs.resolve(ROOTFS_NAME), tarball);
            Files.write(rootVolume, new byte[64]);
            bootstrapper = bootstrapper(List.of(distro), this::resource);
        }

        ByteArrayInputStream resource(String name) {
            if ("guest_init".equals(name)) {
                return new ByteArrayInputStream(GUEST_INIT);
            }
            return "initramfs.cpio.gz".equals(name) ? new ByteArrayInputStream(baseInitramfs) : null;
        }

        QemuRuntimeBootstrapper bootstrapper(List<DistroManifest> distros,
                java.util.function.Function<String, java.io.InputStream> resources) throws Exception {
            return new QemuRuntimeBootstrapper(manifest(kernel, baseInitramfs),
                    name -> new ByteArrayInputStream(kernel), resources, runtimeDir, distroInputs, distros);
        }

        Map<String, byte[]> entries(Path image) throws IOException {
            byte[] imageBytes = Files.readAllBytes(image);
            byte[] segment = Arrays.copyOfRange(imageBytes, baseInitramfs.length, imageBytes.length);
            byte[] cpio;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(segment))) {
                cpio = in.readAllBytes();
            }
            Map<String, byte[]> entries = new LinkedHashMap<>();
            int position = 0;
            while (position + 110 <= cpio.length) {
                assertEquals("070701", new String(cpio, position, 6, StandardCharsets.US_ASCII));
                int nameSize = Integer.parseInt(new String(cpio, position + 94, 8, StandardCharsets.US_ASCII), 16);
                int fileSize = Integer.parseInt(new String(cpio, position + 54, 8, StandardCharsets.US_ASCII), 16);
                String name = new String(cpio, position + 110, nameSize - 1, StandardCharsets.US_ASCII);
                int start = (position + 110 + nameSize + 3) / 4 * 4;
                assertFalse("duplicate archive entries", entries.containsKey(name));
                entries.put(name, Arrays.copyOfRange(cpio, start, start + fileSize));
                if ("TRAILER!!!".equals(name)) {
                    return entries;
                }
                position = (start + fileSize + 3) / 4 * 4;
            }
            throw new AssertionError("newc trailer missing");
        }
    }

    private static void assertEntry(Map<String, byte[]> entries, String name, String expected) {
        assertTrue("embedded entry mismatch: " + name,
                Arrays.equals(expected.getBytes(StandardCharsets.US_ASCII), entries.get(name)));
    }

    private static RuntimeArtifactManifest manifest(byte[] kernel, byte[] initramfs) throws Exception {
        return RuntimeArtifactManifest.parse(new ByteArrayInputStream(
                (entry("Image", kernel) + entry("initramfs.cpio.gz", initramfs)
                        + entry("lib/arm64-v8a/libdroidlayer_qemu.so", synthetic(128, 0x00))
                        + entry("QEMU-BUILD-INPUTS", synthetic(32, 0x01))
                        + entry("QEMU-GPL-SOURCES.md", synthetic(32, 0x02))
                        + entry("LICENSE.GPL-2.0", synthetic(32, 0x03))
                        + entry("NOTICE.QEMU", synthetic(32, 0x04))).getBytes(StandardCharsets.US_ASCII)));
    }

    private static String entry(String path, byte[] bytes) throws Exception {
        return sha256(bytes) + "  " + path + "\n";
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static byte[] synthetic(int size, int seed) {
        byte[] output = new byte[size];
        for (int i = 0; i < size; i++) {
            output[i] = (byte) (seed + i * 31);
        }
        return output;
    }
}
