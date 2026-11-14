package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.network.download.DownloadEngine;
import io.github.nyzeep.orchestella.runtime.network.download.DownloadProgressCallback;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The embedded rootfs channel (2026-11-14 single-package round): the pinned
 * baseline tar ships inside the APK and the provisioner consumes it BEFORE
 * the CDN transport — same pin, byte-identical asset, zero transport — while
 * the download chain (L1-L3, unchanged) stays the repair channel for an
 * absent, corrupt, or unreadable embedded copy. The tests own the flag and
 * the source, exactly like the CDN-channel contract tests do.
 */
public final class UmlRootfsEmbeddedChannelTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    /** A transport that counts downloads and serves the pinned payload. */
    private static final class CountingTransport implements DownloadEngine {
        final byte[] payload;
        final AtomicInteger downloads = new AtomicInteger();

        CountingTransport(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public void download(List<String> mirrorUrls, Path destination, String expectedSha256,
                DownloadProgressCallback callback) throws IOException {
            downloads.incrementAndGet();
            Files.write(destination, payload);
        }
    }

    @Test
    public void anEmbeddedAssetMatchingThePinProvisionsWithoutAnyTransportCall() throws Exception {
        byte[] payload = readinessPayload();
        CountingTransport transport = new CountingTransport(payload);
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.provision(
                transport, readinessEntry(payload), filesDir, true,
                entry -> new ByteArrayInputStream(payload));
        assertTrue(receipt.enabled());
        assertEquals("the embedded line must satisfy the pin without a transport call",
                0, transport.downloads.get());
        assertEquals(readinessEntry(payload).sha256Pin(), receipt.sha256());
        // The provisioned extract is real, not a stub.
        assertTrue(Files.isDirectory(receipt.extractedDir()));
        assertFalse(receipt.alreadyVerified());
    }

    @Test
    public void anIdempotentEmbeddedProvisionDoesNotReCopyTheAsset() throws Exception {
        byte[] payload = readinessPayload();
        CountingTransport transport = new CountingTransport(payload);
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.EmbeddedRootfsSource source =
                entry -> new ByteArrayInputStream(payload);
        UmlRootfsProvisioner.provision(transport, readinessEntry(payload), filesDir, true, source);
        UmlRootfsProvisioner.ProvisionReceipt second =
                UmlRootfsProvisioner.provision(transport, readinessEntry(payload), filesDir, true, source);
        assertTrue(second.enabled());
        assertTrue(second.alreadyVerified());
        assertEquals(0, transport.downloads.get());
    }

    @Test
    public void aCorruptEmbeddedAssetFallsThroughToTheCdnRepairChannel() throws Exception {
        byte[] payload = readinessPayload();
        CountingTransport transport = new CountingTransport(payload);
        Path filesDir = tempFolder.newFolder("files").toPath();
        byte[] wrongBytes = "not-the-pinned-tar".getBytes(StandardCharsets.UTF_8);
        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.provision(
                transport, readinessEntry(payload), filesDir, true,
                entry -> new ByteArrayInputStream(wrongBytes));
        assertTrue("the CDN repair channel must still provision the pin",
                receipt.enabled());
        assertEquals("the corrupt embedded copy must hand over to exactly one download",
                1, transport.downloads.get());
        assertEquals(readinessEntry(payload).sha256Pin(), receipt.sha256());
        // No .part staging file may survive the fall-through.
        Path umlRootfsDir = filesDir.resolve(UmlRootfsProvisioner.UML_ROOTFS_DIR_NAME);
        try (var files = Files.list(umlRootfsDir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString()
                    .endsWith(UmlRootfsProvisioner.EXTRACT_STAGING_SUFFIX)));
        }
    }

    @Test
    public void anAbsentEmbeddedAssetGoesStraightToTheDownloadChannel() throws Exception {
        byte[] payload = readinessPayload();
        CountingTransport transport = new CountingTransport(payload);
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.provision(
                transport, readinessEntry(payload), filesDir, true, entry -> null);
        assertTrue(receipt.enabled());
        assertEquals(1, transport.downloads.get());
        assertEquals(readinessEntry(payload).sha256Pin(), receipt.sha256());
    }

    @Test
    public void anUnreadableEmbeddedAssetFallsThroughWithoutLeavingAStagingFile() throws Exception {
        byte[] payload = readinessPayload();
        CountingTransport transport = new CountingTransport(payload);
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.provision(
                transport, readinessEntry(payload), filesDir, true,
                entry -> {
                    throw new IOException("embedded asset unreadable");
                });
        assertTrue("the read failure must not abort the provision — the CDN channel repairs it",
                receipt.enabled());
        assertEquals(1, transport.downloads.get());
        Path umlRootfsDir = filesDir.resolve(UmlRootfsProvisioner.UML_ROOTFS_DIR_NAME);
        try (var files = Files.list(umlRootfsDir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString()
                    .endsWith(UmlRootfsProvisioner.EXTRACT_STAGING_SUFFIX)));
        }
    }

    @Test
    public void theFourArgOverloadStaysTheCdnOnlyShape() throws Exception {
        byte[] payload = readinessPayload();
        CountingTransport transport = new CountingTransport(payload);
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.provision(
                transport, readinessEntry(payload), filesDir, true);
        assertTrue(receipt.enabled());
        assertEquals("the delegating overload must never consult an embedded source",
                1, transport.downloads.get());
    }

    @Test
    public void thePackagedSourceMirrorsClasspathReality() throws Exception {
        // The production source answers the classpath, never a guess: with no
        // packaged tar visible it must answer null (the split-build signal —
        // null is what sends the CDN channel to work), and with one visible
        // (a packaging build stages it into src/main/resources, which lands on
        // the unit-test classpath) it must hand out a readable stream. The
        // expected side is derived from the same lookup, so this holds on a
        // bare checkout AND after a full-package staging without being
        // environment-fragile.
        boolean resourcePresent = UmlRootfsEmbeddedChannelTest.class
                .getResourceAsStream("/" + UmlKernelAssets.FastProfile.ROOTFS_TAR) != null;
        try (InputStream stream = UmlRootfsProvisioner.PACKAGED_ROOTFS_SOURCE.openEmbeddedTar(
                readinessEntry(readinessPayload()))) {
            if (resourcePresent) {
                assertTrue("a visible packaged tar must be served, not hidden",
                        stream != null);
            } else {
                assertNull("an absent packaged tar must answer null (the split-build "
                        + "signal), not throw", stream);
            }
        }
    }

    // --- shared fixtures (same shape the card-state contract tests use) -------

    private static byte[] readinessPayload() throws IOException {
        // The symlink-free official shape: extraction runs on this host, and
        // Windows test sandboxes cannot create symbolic links without
        // privilege (the same constraint the CDN-channel contract tests work
        // around; the channel under test here is the transport stage anyway).
        List<MiniRootfsFixture.Spec> specs = new ArrayList<>(MiniRootfsFixture.officialShape());
        specs.removeIf(spec -> spec.type() == '2');
        return MiniRootfsFixture.gzipTar(specs);
    }

    private static io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.Entry readinessEntry(
            byte[] payload) throws Exception {
        return new io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.Entry(
                io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.ENTRY_ID,
                MiniRootfsFixture.VERSION,
                io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.ENTRY_ARCHITECTURE,
                java.net.URI.create(io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.ROOTFS_TAR_URL),
                UmlKernelAssets.FastProfile.ROOTFS_TAR,
                MiniRootfsFixture.sha256Hex(payload), (long) payload.length);
    }
}
