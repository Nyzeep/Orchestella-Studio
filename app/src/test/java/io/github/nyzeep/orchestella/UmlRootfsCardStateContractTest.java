package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.RootfsRejectionException;
import io.github.nyzeep.orchestella.runtime.network.download.DownloadEngine;
import io.github.nyzeep.orchestella.runtime.network.download.DownloadProgressCallback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The distro-tab UML rootfs entry card's contract: the render gate (flag-off
 * builds keep zero entry), the honest phase transitions (initial from the
 * pure-read readiness probe, downloading percent against the pinned size,
 * ready counts, failed stable reasons), and the model discipline (a model
 * that exists is honest by construction). Plus the progress tap on the
 * download engine — byte counts forwarded to both the caller's callback and
 * the card's listener, every other engine behavior delegated unchanged.
 */
public final class UmlRootfsCardStateContractTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    // --- the render gate --------------------------------------------------------------------

    @Test
    public void theCardRendersOnlyInBuildsThatOpenedTheFlag() {
        assertTrue(UmlRootfsCardState.visible(true));
        assertFalse(UmlRootfsCardState.visible(false));
    }

    // --- initial phase from the readiness probe ----------------------------------------------

    @Test
    public void aFlagOffBuildIsDisabledRegardlessOfAnyProbe() {
        assertEquals(UmlRootfsCardState.Phase.DISABLED, UmlRootfsCardState.initial(
                false, UmlRootfsProvisioner.ReadinessReport.notReady()).phase());
        assertEquals(UmlRootfsCardState.Phase.DISABLED, UmlRootfsCardState.initial(
                false, UmlRootfsProvisioner.ReadinessReport.disabled()).phase());
        assertEquals(UmlRootfsCardState.Phase.DISABLED, UmlRootfsCardState.initial(
                false, UmlRootfsCardStateContractTest.readyReport()).phase());
    }

    @Test
    public void aDisabledProbeAnswersDisabledEvenWhenTheFlagIsOpen() {
        assertEquals(UmlRootfsCardState.Phase.DISABLED, UmlRootfsCardState.initial(
                true, UmlRootfsProvisioner.ReadinessReport.disabled()).phase());
    }

    @Test
    public void aReadyProbeRendersReadyWithTheAttestedCountsAndNothingMore() {
        UmlRootfsCardState.CardModel model = UmlRootfsCardState.initial(
                true, UmlRootfsCardStateContractTest.readyReport());
        assertEquals(UmlRootfsCardState.Phase.READY, model.phase());
        assertEquals(520, model.extractedEntries());
        assertFalse(model.alreadyVerified());
        assertEquals(UmlRootfsCardState.CardModel.PROGRESS_INDETERMINATE, model.progressPercent());
        assertNull(model.failureReason());
    }

    @Test
    public void aNotReadyProbeRendersTheEntryState() {
        assertEquals(UmlRootfsCardState.Phase.NOT_READY, UmlRootfsCardState.initial(
                true, UmlRootfsProvisioner.ReadinessReport.notReady()).phase());
    }

    // --- downloading: percent against the pinned size ----------------------------------------

    @Test
    public void thePercentIsComputedFromTheEngineByteCounts() {
        assertEquals(50, UmlRootfsCardState.downloading(500, 1000).progressPercent());
        assertEquals(0, UmlRootfsCardState.downloading(0, 1000).progressPercent());
        assertEquals(100, UmlRootfsCardState.downloading(1000, 1000).progressPercent());
        assertEquals(100, UmlRootfsCardState.downloading(1500, 1000).progressPercent());
    }

    @Test
    public void anUnknownEngineTotalFallsBackToThePinnedSize() {
        long pinned = UmlRootfsCardState.pinnedTotalBytes();
        assertEquals(UmlKernelAssets.FastProfile.ROOTFS_TAR_SIZE_BYTES, pinned);
        // Integer floor: 1,990,521 / 3,981,043 is just under one half.
        assertEquals(49, UmlRootfsCardState.downloading(pinned / 2, -1).progressPercent());
        assertEquals(0, UmlRootfsCardState.downloading(0, -1).progressPercent());
    }

    @Test
    public void anUncomputableProgressIsHonestIndeterminate() {
        // A known pin size makes zero bytes an honest 0%, not indeterminate.
        assertEquals(0, UmlRootfsCardState.downloading(0, 0).progressPercent());
        // Only a nonsensical byte count is indeterminate.
        assertEquals(UmlRootfsCardState.CardModel.PROGRESS_INDETERMINATE,
                UmlRootfsCardState.downloading(-5, 1000).progressPercent());
    }

    // --- ready: receipt counts and the idempotent-hit passthrough -----------------------------

    @Test
    public void aFreshProvisionReceiptRendersReadyWithoutTheIdempotentClaim()
            throws Exception {
        UmlRootfsProvisioner.ProvisionReceipt receipt = new UmlRootfsProvisioner.ProvisionReceipt(
                true, false, "name", null, 10L, "sha", null, 520, 87);
        UmlRootfsCardState.CardModel model = UmlRootfsCardState.ready(receipt);
        assertEquals(UmlRootfsCardState.Phase.READY, model.phase());
        assertEquals(520, model.extractedEntries());
        assertFalse(model.alreadyVerified());
    }

    @Test
    public void anIdempotentHitRendersReadyWithTheAlreadyProvisionedClaim() throws Exception {
        UmlRootfsProvisioner.ProvisionReceipt receipt = new UmlRootfsProvisioner.ProvisionReceipt(
                true, true, "name", null, 10L, "sha", null, 520, 87);
        assertTrue(UmlRootfsCardState.ready(receipt).alreadyVerified());
    }

    @Test
    public void aDisabledReceiptCannotRenderReady() {
        assertThrows(IllegalArgumentException.class,
                () -> UmlRootfsCardState.ready(UmlRootfsProvisioner.ProvisionReceipt.disabled()));
    }

    // --- failed: stable reason passthrough, never raw transport text ---------------------------

    @Test
    public void rejectionReasonsPassThroughVerbatim() {
        RootfsRejectionException verification = new RootfsRejectionException(
                UmlKernelAssets.RootfsDownload.REASON_CHECKSUM_MISMATCH);
        assertEquals(UmlKernelAssets.RootfsDownload.REASON_CHECKSUM_MISMATCH,
                UmlRootfsCardState.failed(verification).failureReason());
        UmlRootfsProvisioner.ExtractionRejectionException slip =
                new UmlRootfsProvisioner.ExtractionRejectionException(
                        UmlRootfsProvisioner.REASON_ENTRY_ESCAPES_EXTRACT_ROOT,
                        "absolute entry name: /etc/passwd");
        assertEquals(UmlRootfsProvisioner.REASON_ENTRY_ESCAPES_EXTRACT_ROOT,
                UmlRootfsCardState.failed(slip).failureReason());
    }

    @Test
    public void transportClassFailuresMapToAStableNonSecretReason() {
        IOException network = new IOException("Connection reset by peer at 1.2.3.4: raw detail");
        assertEquals("uml-rootfs-transport-failure",
                UmlRootfsCardState.failed(network).failureReason());
        RuntimeException other = new RuntimeException("boom");
        assertEquals("uml-rootfs-unknown-failure",
                UmlRootfsCardState.failed(other).failureReason());
    }

    // --- model discipline ----------------------------------------------------------------------

    @Test
    public void aModelThatExistsIsHonestByConstruction() {
        // Only DOWNLOADING may carry a percent.
        assertThrows(IllegalArgumentException.class,
                () -> new UmlRootfsCardState.CardModel(
                        UmlRootfsCardState.Phase.NOT_READY, 42, null, 0, false));
        // Only FAILED may carry a reason.
        assertThrows(IllegalArgumentException.class,
                () -> new UmlRootfsCardState.CardModel(
                        UmlRootfsCardState.Phase.READY, -1, "uml-rootfs-x", 520, false));
        // Only READY may carry counts.
        assertThrows(IllegalArgumentException.class,
                () -> new UmlRootfsCardState.CardModel(
                        UmlRootfsCardState.Phase.FAILED, -1, null, 520, false));
        // Percent stays in range.
        assertThrows(IllegalArgumentException.class,
                () -> new UmlRootfsCardState.CardModel(
                        UmlRootfsCardState.Phase.DOWNLOADING, 101, null, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new UmlRootfsCardState.CardModel(
                        UmlRootfsCardState.Phase.DOWNLOADING, -2, null, 0, false));
    }

    // --- the readiness report's own discipline --------------------------------------------------

    @Test
    public void aDisabledReadinessReportCarriesNothing() {
        assertThrows(IllegalArgumentException.class,
                () -> new UmlRootfsProvisioner.ReadinessReport(false, true, null, 520, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new UmlRootfsProvisioner.ReadinessReport(false, false, null, 520, 0));
    }

    @Test
    public void aNotReadyReadinessReportCarriesNoTree() {
        assertThrows(IllegalArgumentException.class,
                () -> new UmlRootfsProvisioner.ReadinessReport(true, false, null, 520, 0));
    }

    // --- readiness probe (pure reads) ------------------------------------------------------------

    /** An in-memory byte source fulfilling the engine contract (same shape as the B1 tests). */
    private static final class FakeTransport implements DownloadEngine {
        byte[] payload;

        @Override
        public void download(List<String> mirrorUrls, Path destination, String expectedSha256,
                DownloadProgressCallback callback) throws IOException {
            Files.write(destination, payload);
        }
    }

    private static byte[] readinessPayload() throws IOException {
        return officialShapeWithoutSymlink();
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

    @Test
    public void aDisabledReadinessProbeReadsNothingAndTouchesNothing() throws Exception {
        Path filesDir = tempFolder.getRoot().toPath().resolve("never-created");
        UmlRootfsProvisioner.ReadinessReport report = UmlRootfsProvisioner.readiness(
                readinessEntry(readinessPayload()), filesDir, false);
        assertFalse(report.enabled());
        assertFalse(report.ready());
        assertFalse("the disabled probe must not even create the lane's directory",
                Files.exists(filesDir));
    }

    @Test
    public void anUnprovisionedTreeReadsNotReadyWithoutSideEffects() throws Exception {
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.ReadinessReport report = UmlRootfsProvisioner.readiness(
                readinessEntry(readinessPayload()), filesDir, true);
        assertTrue(report.enabled());
        assertFalse(report.ready());
        assertNull(report.extractedDir());
    }

    @Test
    public void aProvisionedTreeReadsReadyWithTheAttestedCounts() throws Exception {
        byte[] payload = readinessPayload();
        FakeTransport transport = new FakeTransport();
        transport.payload = payload;
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.provision(
                transport, readinessEntry(payload), filesDir, true);
        assertTrue(receipt.enabled());

        UmlRootfsProvisioner.ReadinessReport report = UmlRootfsProvisioner.readiness(
                readinessEntry(payload), filesDir, true);
        assertTrue(report.enabled());
        assertTrue(report.ready());
        assertEquals(receipt.extractedDir(), report.extractedDir());
        assertEquals(receipt.extractedEntries(), report.extractedEntries());
        assertEquals(receipt.executableEntries(), report.executableEntries());
    }

    @Test
    public void aCorruptTarReadsNotReady() throws Exception {
        byte[] payload = readinessPayload();
        FakeTransport transport = new FakeTransport();
        transport.payload = payload;
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.provision(transport, readinessEntry(payload), filesDir, true);
        Files.write(filesDir.resolve(UmlRootfsProvisioner.UML_ROOTFS_DIR_NAME)
                .resolve("alpine-minirootfs-3.22.2-aarch64.tar.gz"),
                "corrupt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(UmlRootfsProvisioner.readiness(readinessEntry(payload), filesDir, true).ready());
    }

    @Test
    public void aMissingSidecarReadsNotReady() throws Exception {
        byte[] payload = readinessPayload();
        FakeTransport transport = new FakeTransport();
        transport.payload = payload;
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.provision(transport, readinessEntry(payload), filesDir, true);
        Files.delete(filesDir.resolve(UmlRootfsProvisioner.UML_ROOTFS_DIR_NAME)
                .resolve("alpine-minirootfs-3.22.2-aarch64.verified"));
        assertFalse(UmlRootfsProvisioner.readiness(readinessEntry(payload), filesDir, true).ready());
    }

    private static byte[] officialShapeWithoutSymlink() throws IOException {
        List<MiniRootfsFixture.Spec> specs = new ArrayList<>(MiniRootfsFixture.officialShape());
        specs.removeIf(spec -> spec.type() == '2');
        return MiniRootfsFixture.gzipTar(specs);
    }

    private static UmlRootfsProvisioner.ReadinessReport readyReport() {
        return new UmlRootfsProvisioner.ReadinessReport(true, true, null, 520, 0);
    }

    // --- the progress tap ------------------------------------------------------------------------

    /** An engine that records what it was asked and emits canned progress events. */
    private static final class RecordingEngine implements DownloadEngine {
        List<String> requestedUrls = new ArrayList<>();
        Path requestedDestination;
        String requestedPin;
        DownloadProgressCallback receivedCallback;
        IOException failure;
        long[] progressEvents;

        @Override
        public void download(List<String> mirrorUrls, Path destination, String expectedSha256,
                DownloadProgressCallback callback) throws IOException {
            requestedUrls.addAll(mirrorUrls);
            requestedDestination = destination;
            requestedPin = expectedSha256;
            receivedCallback = callback;
            if (failure != null) {
                throw failure;
            }
            if (progressEvents != null) {
                for (long event : progressEvents) {
                    callback.onProgress(event, 1000, 9.9, 1, "mirror");
                }
            }
        }
    }

    @Test
    public void theTapForwardsBytesToTheListenerAndKeepsTheEngineContract() throws Exception {
        RecordingEngine inner = new RecordingEngine();
        inner.progressEvents = new long[] {100, 500, 1000};
        List<String> seen = new ArrayList<>();
        UmlRootfsProgressEngine tap = new UmlRootfsProgressEngine(inner,
                (downloaded, total) -> seen.add(downloaded + "/" + total));
        Path destination = tempFolder.newFolder("tap").toPath().resolve("staged.part");

        tap.download(List.of("https://mirror.example/a"), destination, "pin", null);

        assertEquals(List.of("100/1000", "500/1000", "1000/1000"), seen);
        assertEquals(List.of("https://mirror.example/a"), inner.requestedUrls);
        assertEquals(destination, inner.requestedDestination);
        assertEquals("pin", inner.requestedPin);
        assertTrue("the tap must attach its own callback to the engine call",
                inner.receivedCallback != null);
    }

    @Test
    public void theTapForwardsTheCallersCallbackEventsUnchanged() throws Exception {
        RecordingEngine inner = new RecordingEngine();
        inner.progressEvents = new long[] {42};
        List<String> callerEvents = new ArrayList<>();
        DownloadProgressCallback callerCallback = new DownloadProgressCallback() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes, double speedMBps,
                    long etaSeconds, String currentMirror) {
                callerEvents.add(downloadedBytes + "@" + currentMirror);
            }
        };
        UmlRootfsProgressEngine tap = new UmlRootfsProgressEngine(inner, (d, t) -> { });

        tap.download(List.of("https://mirror.example/a"),
                tempFolder.newFolder("tap2").toPath().resolve("s.part"), "pin", callerCallback);

        assertEquals(List.of("42@mirror"), callerEvents);
    }

    @Test
    public void theTapPropagatesEngineFailuresUnchanged() throws Exception {
        RecordingEngine inner = new RecordingEngine();
        inner.failure = new IOException("mirror exhausted");
        UmlRootfsProgressEngine tap = new UmlRootfsProgressEngine(inner, (d, t) -> { });
        Path destination = tempFolder.newFolder("tap3").toPath().resolve("s.part");

        IOException failure = assertThrows(IOException.class,
                () -> tap.download(List.of("https://mirror.example/a"), destination, "pin", null));
        assertEquals("mirror exhausted", failure.getMessage());
    }

    @Test
    public void theTapRefusesANullListenerOrInnerEngine() {
        assertThrows(NullPointerException.class,
                () -> new UmlRootfsProgressEngine(null, (d, t) -> { }));
        RecordingEngine inner = new RecordingEngine();
        assertThrows(NullPointerException.class, () -> new UmlRootfsProgressEngine(inner, null));
    }
}
