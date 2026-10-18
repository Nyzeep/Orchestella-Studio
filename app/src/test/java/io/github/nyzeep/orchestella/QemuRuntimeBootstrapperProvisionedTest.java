package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.engineqemu.EmbeddedProvisionInput;
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
 * T3 contract: an instance whose persisted guest evidence matches the current catalog
 * digest must launch without opening the distribution tarball, while every other case
 * keeps the byte-identical full embedding path.
 */
public final class QemuRuntimeBootstrapperProvisionedTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String PASSWORD = "SyntheticSecret2026";
    private static final String ROOTFS_NAME = "rootfs.tar.gz";
    private static final byte[] GUEST_INIT = "#!/bin/sh\ntrue\n".getBytes(StandardCharsets.US_ASCII);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void credentialsOnlyNeverOpensTheRootfsAndReportsZeroBytes() throws Exception {
        Fixture fixture = new Fixture();
        AtomicLong opens = new AtomicLong();
        AtomicLong bytesRead = new AtomicLong();
        fixture.bootstrapper = fixture.bootstrapper(path -> {
            opens.incrementAndGet();
            return counting(Files.newInputStream(path), bytesRead);
        });
        java.util.List<String> lines = new java.util.ArrayList<>();
        LaunchTimeline timeline = timeline(lines);

        fixture.bootstrapper.bootstrap(ROOTFS_NAME, TOKEN, PASSWORD, fixture.rootVolume,
                "sources.list", "deb mirror\n".getBytes(StandardCharsets.US_ASCII), null,
                "dev-box", "developer", false, timeline, true);

        assertEquals("credentials-only must not open the rootfs", 0, opens.get());
        assertEquals(0, bytesRead.get());
        assertTrue(timeline.finish());
        assertTrue(lines.get(0).contains("\"rootfsBytes\":0"));
    }

    @Test
    public void credentialsOnlyContainsNoRootfsPayloadAndStaysUnderTheHardCap() throws Exception {
        Fixture fixture = new Fixture();
        QemuRuntimeBootstrapper.RuntimePaths paths = fixture.bootstrapper.bootstrap(
                ROOTFS_NAME, TOKEN, PASSWORD, fixture.rootVolume,
                "sources.list", "deb mirror\n".getBytes(StandardCharsets.US_ASCII),
                "#!/bin/sh\n# firstboot\n".getBytes(StandardCharsets.US_ASCII),
                "dev-box", "developer", true, null, true);

        Map<String, byte[]> entries = fixture.entries(paths.initramfs);
        assertFalse(entries.containsKey(EmbeddedProvisionInput.EMBEDDED_DIR + "/" + ROOTFS_NAME));
        assertEntry(entries, EmbeddedProvisionInput.EMBEDDED_DIR + "/bridge.token", TOKEN + "\n");
        assertEntry(entries, EmbeddedProvisionInput.EMBEDDED_DIR + "/guest.password", PASSWORD + "\n");
        assertEntry(entries, EmbeddedProvisionInput.EMBEDDED_DIR + "/guest.user", "developer\n");
        assertEntry(entries, EmbeddedProvisionInput.EMBEDDED_DIR + "/guest.sudo", "1\n");
        assertEntry(entries, EmbeddedProvisionInput.EMBEDDED_DIR + "/hostname", "dev-box\n");
        assertEntry(entries, EmbeddedProvisionInput.EMBEDDED_DIR + "/sources.list", "deb mirror\n");
        assertEntry(entries, EmbeddedProvisionInput.EMBEDDED_DIR + "/firstboot.sh", "#!/bin/sh\n# firstboot\n");
        long segmentBytes = Files.size(paths.initramfs) - fixture.baseInitramfs.length;
        assertTrue("credentials-only segment must stay bounded, was " + segmentBytes,
                segmentBytes <= EmbeddedProvisionInput.CREDENTIALS_ONLY_MAX_BYTES);
        System.out.println("T3_CREDENTIALS_ONLY_SEGMENT_BYTES=" + segmentBytes);
    }

    @Test
    public void credentialsOnlyDoesNotRequireTheRootfsFileToExist() throws Exception {
        Fixture fixture = new Fixture();
        Files.delete(fixture.distroInputs.resolve(ROOTFS_NAME));

        QemuRuntimeBootstrapper.RuntimePaths paths = fixture.bootstrapper.bootstrap(
                ROOTFS_NAME, TOKEN, PASSWORD, fixture.rootVolume,
                null, null, null, null, null, false, null, true);

        assertFalse(fixture.entries(paths.initramfs)
                .containsKey(EmbeddedProvisionInput.EMBEDDED_DIR + "/" + ROOTFS_NAME));
    }

    @Test
    public void fullPathReadsTheTarballAndIsByteIdenticalToTheExistingSegmentBuilder() throws Exception {
        Fixture fixture = new Fixture();
        AtomicLong opens = new AtomicLong();
        AtomicLong bytesRead = new AtomicLong();
        fixture.bootstrapper = fixture.bootstrapper(path -> {
            opens.incrementAndGet();
            return counting(Files.newInputStream(path), bytesRead);
        });

        QemuRuntimeBootstrapper.RuntimePaths paths = fixture.bootstrapper.bootstrap(
                ROOTFS_NAME, TOKEN, PASSWORD, fixture.rootVolume,
                "sources.list", "deb mirror\n".getBytes(StandardCharsets.US_ASCII),
                "#!/bin/sh\n# firstboot\n".getBytes(StandardCharsets.US_ASCII),
                "dev-box", "developer", false, null, false);

        assertEquals(1, opens.get());
        assertEquals(fixture.tarball.length, bytesRead.get());
        byte[] expectedSegment = fullSegment(fixture);
        System.out.println("T3_FULL_SEGMENT_BYTES=" + expectedSegment.length);
        byte[] expected = new byte[fixture.baseInitramfs.length + expectedSegment.length];
        System.arraycopy(fixture.baseInitramfs, 0, expected, 0, fixture.baseInitramfs.length);
        System.arraycopy(expectedSegment, 0, expected, fixture.baseInitramfs.length, expectedSegment.length);
        assertArrayEquals(expected, Files.readAllBytes(paths.initramfs));
        // Golden snapshot of the full-path output for this fixture. It catches future
        // refactors of the shared segment writer that the same-writer comparison cannot.
        assertEquals("4cbca127067763b0afb93ca2aa96f43f29e59830be15c2d4064f313687b75c7c",
                sha256(Files.readAllBytes(paths.initramfs)));
    }

    @Test
    public void missingRootfsStillFailsOnTheFullPath() throws Exception {
        Fixture fixture = new Fixture();
        Files.delete(fixture.distroInputs.resolve(ROOTFS_NAME));
        try {
            fixture.bootstrapper.bootstrap(ROOTFS_NAME, TOKEN, PASSWORD, fixture.rootVolume,
                    null, null, null, null, null, false, null, false);
            fail("full path must require the tarball");
        } catch (IOException expected) {
            assertEquals("rootfs-file-missing", expected.getMessage());
        }
    }

    @Test
    public void oversizedCredentialsOnlySegmentIsRejectedBeforePublication() throws Exception {
        Fixture fixture = new Fixture();
        byte[] bulk = new byte[512 * 1024];
        new Random(7).nextBytes(bulk);
        try {
            fixture.bootstrapper.bootstrap(ROOTFS_NAME, TOKEN, PASSWORD, fixture.rootVolume,
                    "mirror.bin", bulk, null, null, null, false, null, true);
            fail("an oversized segment must not be published");
        } catch (IOException expected) {
            assertEquals("credentials-only-initramfs-too-large", expected.getMessage());
        }
        assertFalse(Files.exists(fixture.runtimeDir.resolve("initramfs-embedded.cpio.gz")));
        assertFalse(Files.exists(fixture.runtimeDir.resolve("initramfs-embedded.cpio.gz.partial")));
    }

    @Test
    public void persistedEvidenceMustMatchTheCurrentDigestAndMarkerVersion() throws Exception {
        Fixture fixture = new Fixture();
        Path instances = temporaryFolder.newFolder("instances").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(instances);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("synthetic", "1"),
                fixture.rootVolume, VmHardwareConfig.defaultConfig(),
                new InstanceUserConfig("developer", PASSWORD, false, "Dev-Box"));

        assertFalse("no evidence must stay conservative",
                QemuInstanceLauncher.isProvisionedForInstance(instances, created.id(), fixture.distro.sha256()));

        controller.recordProvisioning(created.id(), new InstanceLifecycle.ProvisioningEvidence(
                1, fixture.distro.sha256(), 1_700_000_000_000L));
        assertTrue("matching evidence unlocks the fast path",
                QemuInstanceLauncher.isProvisionedForInstance(instances, created.id(), fixture.distro.sha256()));
        assertFalse("a different distro digest must fall back",
                QemuInstanceLauncher.isProvisionedForInstance(instances, created.id(), "f".repeat(64)));

        controller.recordProvisioning(created.id(), new InstanceLifecycle.ProvisioningEvidence(
                2, fixture.distro.sha256(), 1_700_000_000_001L));
        assertFalse("an unsupported marker version must fall back",
                QemuInstanceLauncher.isProvisionedForInstance(instances, created.id(), fixture.distro.sha256()));

        controller.recordProvisioning(created.id(), new InstanceLifecycle.ProvisioningEvidence(
                1, null, 1_700_000_000_002L));
        assertFalse("evidence without a digest must fall back",
                QemuInstanceLauncher.isProvisionedForInstance(instances, created.id(), fixture.distro.sha256()));
    }

    @Test
    public void unknownRootfsFileNameCannotUnlockTheCredentialsOnlyPath() throws Exception {
        Fixture fixture = new Fixture();
        Path instances = temporaryFolder.newFolder("instances-unknown").toPath();
        assertFalse(QemuInstanceLauncher.credentialsOnlyLaunch(instances, "missing", "unknown.tar.gz"));
        assertFalse(QemuInstanceLauncher.credentialsOnlyLaunch(instances, null, ROOTFS_NAME));
    }

    private static LaunchTimeline timeline(java.util.List<String> lines) {
        LaunchTimeline timeline = new LaunchTimeline("instance-t3", "synthetic", 512, 2,
                Clock.fixed(Instant.parse("2026-10-18T08:00:00Z"), ZoneOffset.UTC), lines::add);
        timeline.mark(LaunchTimeline.Stage.LAUNCH_START);
        return timeline;
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

    private byte[] fullSegment(Fixture fixture) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EmbeddedProvisionInput.writeSegment(new ByteArrayInputStream(fixture.tarball), fixture.tarball.length,
                ROOTFS_NAME, TOKEN, PASSWORD, GUEST_INIT, null, "dev-box", "developer", false,
                "sources.list", "deb mirror\n".getBytes(StandardCharsets.US_ASCII),
                "#!/bin/sh\n# firstboot\n".getBytes(StandardCharsets.US_ASCII),
                fixture.distro.sha256(), out);
        return out.toByteArray();
    }

    private final class Fixture {
        final Path work = temporaryFolder.newFolder().toPath();
        final Path runtimeDir = Files.createDirectories(work.resolve("runtime"));
        final Path distroInputs = Files.createDirectories(work.resolve("distro-inputs"));
        final Path rootVolume = work.resolve("instance.raw");
        final byte[] kernel = synthetic(4096, 0x5a);
        final byte[] baseInitramfs = synthetic(2048, 0xa5);
        final byte[] tarball = synthetic(4096, 0x11);
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

    private static void assertEntry(Map<String, byte[]> entries, String name, String expected) {
        assertTrue("embedded entry missing: " + name, entries.containsKey(name));
        assertArrayEquals("embedded entry mismatch: " + name,
                expected.getBytes(StandardCharsets.US_ASCII), entries.get(name));
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
}
