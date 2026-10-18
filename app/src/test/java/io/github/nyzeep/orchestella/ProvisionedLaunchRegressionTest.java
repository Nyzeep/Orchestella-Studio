package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.engineqemu.EmbeddedProvisionInput;
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * T8 device-free regression gate: the provisioned launch path must not open or
 * read the distro tarball, and its embedded segment must stay under the
 * credentials-only ceiling. The same fixture is deliberately large enough that
 * a revert to full embedding fails the assertions below.
 */
public final class ProvisionedLaunchRegressionTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String PASSWORD = "SyntheticSecret2026";
    private static final String ROOTFS_NAME = "rootfs.tar.gz";
    private static final byte[] GUEST_INIT = "#!/bin/sh\ntrue\n".getBytes(StandardCharsets.US_ASCII);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void provisionedLaunchNeverOpensOrReadsTheRootfs() throws Exception {
        Fixture fixture = new Fixture();
        AtomicLong opens = new AtomicLong();
        AtomicLong bytesRead = new AtomicLong();
        fixture.bootstrapper = fixture.bootstrapper(path -> {
            opens.incrementAndGet();
            return counting(Files.newInputStream(path), bytesRead);
        });
        java.util.List<String> lines = new java.util.ArrayList<>();
        LaunchTimeline timeline = new LaunchTimeline("instance-t8", "synthetic", 512, 2,
                Clock.fixed(Instant.parse("2026-10-18T08:00:00Z"), ZoneOffset.UTC), lines::add);
        timeline.mark(LaunchTimeline.Stage.LAUNCH_START);

        QemuRuntimeBootstrapper.RuntimePaths paths = fixture.bootstrapper.bootstrap(
                ROOTFS_NAME, TOKEN, PASSWORD, fixture.rootVolume,
                null, null, null, "dev-box", "developer", false, timeline, true);

        assertEquals("provisioned launch must not open the distro tarball", 0, opens.get());
        assertEquals("provisioned launch must not read a rootfs byte", 0, bytesRead.get());
        Map<String, byte[]> entries = fixture.entries(paths.initramfs);
        assertFalse("provisioned initrd must not embed the rootfs tarball",
                entries.containsKey(EmbeddedProvisionInput.EMBEDDED_DIR + "/" + ROOTFS_NAME));
        long segmentBytes = Files.size(paths.initramfs) - fixture.baseInitramfs.length;
        assertTrue("provisioned segment exceeded the hard cap: " + segmentBytes,
                segmentBytes <= EmbeddedProvisionInput.CREDENTIALS_ONLY_MAX_BYTES);
        assertTrue(timeline.finish());
        assertTrue("timeline must expose the zero-rootfs contract",
                lines.get(0).contains("\"rootfsBytes\":0"));
    }

    @Test
    public void theSameFixtureWouldFailIfFullEmbeddingReturned() throws Exception {
        Fixture fixture = new Fixture();
        AtomicLong opens = new AtomicLong();
        fixture.bootstrapper = fixture.bootstrapper(path -> {
            opens.incrementAndGet();
            return Files.newInputStream(path);
        });

        QemuRuntimeBootstrapper.RuntimePaths paths = fixture.bootstrapper.bootstrap(
                ROOTFS_NAME, TOKEN, PASSWORD, fixture.rootVolume,
                null, null, null, null, null, false, null, false);

        assertEquals(1, opens.get());
        Map<String, byte[]> entries = fixture.entries(paths.initramfs);
        assertTrue(entries.containsKey(EmbeddedProvisionInput.EMBEDDED_DIR + "/" + ROOTFS_NAME));
        long segmentBytes = Files.size(paths.initramfs) - fixture.baseInitramfs.length;
        assertTrue("the regression fixture must be large enough to trip the cap, was " + segmentBytes,
                segmentBytes > EmbeddedProvisionInput.CREDENTIALS_ONLY_MAX_BYTES);
    }

    private static InputStream counting(InputStream delegate, AtomicLong bytes) {
        return new InputStream() {
            @Override public int read() throws IOException {
                int value = delegate.read();
                if (value >= 0) bytes.incrementAndGet();
                return value;
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                int count = delegate.read(buffer, offset, length);
                if (count > 0) bytes.addAndGet(count);
                return count;
            }
            @Override public void close() throws IOException { delegate.close(); }
        };
    }

    private final class Fixture {
        final Path work = temporaryFolder.newFolder().toPath();
        final Path runtimeDir = Files.createDirectories(work.resolve("runtime"));
        final Path distroInputs = Files.createDirectories(work.resolve("distro-inputs"));
        final Path rootVolume = work.resolve("instance.raw");
        final byte[] kernel = synthetic(4096, 0x5a);
        final byte[] baseInitramfs = synthetic(2048, 0xa5);
        final byte[] tarball = incompressible(256 * 1024, 0x11);
        final DistroManifest distro = new DistroManifest("synthetic", "1", "aarch64",
                "https://example.invalid/" + ROOTFS_NAME, ROOTFS_NAME, sha256(tarball), tarball.length);
        QemuRuntimeBootstrapper bootstrapper;

        Fixture() throws Exception {
            Files.write(distroInputs.resolve(ROOTFS_NAME), tarball);
            Files.write(rootVolume, new byte[64]);
            bootstrapper = bootstrapper(Files::newInputStream);
        }

        QemuRuntimeBootstrapper bootstrapper(QemuRuntimeBootstrapper.RootfsStreamOpener opener)
                throws Exception {
            return new QemuRuntimeBootstrapper(manifest(kernel),
                    name -> new ByteArrayInputStream(kernel), this::resource, runtimeDir, distroInputs,
                    List.of(distro), opener);
        }

        ByteArrayInputStream resource(String name) {
            if ("guest_init".equals(name)) return new ByteArrayInputStream(GUEST_INIT);
            return "initramfs.cpio.gz".equals(name) ? new ByteArrayInputStream(baseInitramfs) : null;
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
                if ("TRAILER!!!".equals(name)) return entries;
                position = (start + fileSize + 3) / 4 * 4;
            }
            throw new AssertionError("newc trailer missing");
        }
    }

    private static RuntimeArtifactManifest manifest(byte[] kernel) throws Exception {
        return RuntimeArtifactManifest.parse(new ByteArrayInputStream(
                (entry("Image", kernel) + entry("initramfs.cpio.gz", synthetic(2048, 0xa5))
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

    private static byte[] incompressible(int size, int seed) {
        byte[] output = new byte[size];
        new Random(seed).nextBytes(output);
        return output;
    }
}
