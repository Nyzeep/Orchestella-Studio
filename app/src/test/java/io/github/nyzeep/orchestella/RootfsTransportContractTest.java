package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.Entry;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.RootfsRejectionException;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.SelfCheckReport;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.SelfCheckStage;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.StageState;
import io.github.nyzeep.orchestella.runtime.network.download.DownloadEngine;
import io.github.nyzeep.orchestella.runtime.network.download.DownloadProgressCallback;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The transport wiring's contract ({@link RootfsTransport#downloadAndVerify}):
 * the four fail-closed states — success, transport failure, L2 pin mismatch,
 * L3 content rejection (plus the L1 size gate) — and the no-half-products
 * rule: every failure sweeps the staged {@code .part} names, so a rejected
 * download leaves {@code destDir} byte-identical to its state before the call;
 * the flag gate answers before the transport or the filesystem is touched.
 *
 * <p>Every transport in this file is a fake in-memory byte source fulfilling
 * the {@link DownloadEngine} contract (bytes land at the requested
 * destination) — no network anywhere. The engine's own HTTP behavior (Range
 * resume, no If-Range, pin-purging) is contract-tested against a loopback fake
 * CDN in {@code RootfsDownloadResumeContractTest}.
 */
public final class RootfsTransportContractTest {

    private static final String FILE_NAME = UmlKernelAssets.FastProfile.ROOTFS_TAR;

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    /**
     * The fake transport: an in-memory byte source that fulfils the engine's
     * success contract (payload written to the requested destination) or
     * fails, and records every call for the seam-contract assertions.
     */
    private static final class FakeTransport implements DownloadEngine {
        final List<String> requestedUrls = new ArrayList<>();
        final List<Path> requestedDestinations = new ArrayList<>();
        final List<String> requestedPins = new ArrayList<>();
        byte[] payload;
        IOException failure;
        /** Simulates the engine's own inner {@code .part} leftover after a network failure. */
        boolean leavesEnginePartResidue;

        @Override
        public void download(List<String> mirrorUrls, Path destination, String expectedSha256,
                DownloadProgressCallback callback) throws IOException {
            requestedUrls.addAll(mirrorUrls);
            requestedDestinations.add(destination);
            requestedPins.add(expectedSha256);
            if (failure != null) {
                if (leavesEnginePartResidue) {
                    // What ResilientDownloadEngine leaves behind on a network
                    // failure: its inner <destination>.part with partial bytes.
                    try {
                        Files.write(destination.resolveSibling(
                                destination.getFileName() + RootfsTransport.STAGING_SUFFIX),
                                "residue".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                        // Not the scenario under test.
                    }
                }
                throw failure;
            }
            Files.write(destination, payload);
        }
    }

    /** The production-shaped entry, pinned to the given bytes and (optionally) size. */
    private static Entry entryFor(byte[] artifact, Long sizeBytes) throws Exception {
        return new Entry(UmlKernelAssets.RootfsDownload.ENTRY_ID,
                MiniRootfsFixture.VERSION, UmlKernelAssets.RootfsDownload.ENTRY_ARCHITECTURE,
                URI.create(UmlKernelAssets.RootfsDownload.ROOTFS_TAR_URL),
                FILE_NAME, MiniRootfsFixture.sha256Hex(artifact), sizeBytes);
    }

    private static Path staged(Path destDir) {
        return destDir.resolve(FILE_NAME + RootfsTransport.STAGING_SUFFIX);
    }

    private static Path enginePart(Path destDir) {
        return destDir.resolve(FILE_NAME
                + RootfsTransport.STAGING_SUFFIX + RootfsTransport.STAGING_SUFFIX);
    }

    private static void assertNothingLeftBehind(Path destDir) throws Exception {
        try (Stream<Path> leftovers = Files.list(destDir)) {
            List<String> names = leftovers.map(path -> path.getFileName().toString()).toList();
            assertTrue("destDir must hold no half products, saw: " + names, names.isEmpty());
        }
    }

    // --- D-4: the flag gate --------------------------------------------------------------

    @Test
    public void aDisabledFlagAnswersTheDisabledReceiptWithoutTransportOrFilesystem()
            throws Exception {
        FakeTransport transport = new FakeTransport();
        Path destDir = tempFolder.getRoot().toPath().resolve("gate-off-never-created");

        RootfsTransport.DownloadReceipt receipt = RootfsTransport.downloadAndVerify(
                transport, entryFor(MiniRootfsFixture.gzipTar(), null), destDir, false);

        assertFalse(receipt.enabled());
        assertNull(receipt.fileName());
        assertNull(receipt.file());
        assertEquals(0L, receipt.sizeBytes());
        assertNull(receipt.sha256());
        assertNull(receipt.selfCheck());
        // Zero transport calls, zero filesystem touches — the gate answers
        // before anything else runs.
        assertTrue(transport.requestedUrls.isEmpty());
        assertFalse(Files.exists(destDir));
    }

    @Test
    public void theProductionGateAnswersDisabledInThisDefaultBuild() throws Exception {
        // The default build compiles orchestella.uml.rootfsDownload=false into
        // BuildConfig — this test locks that default: flipping the flag on is
        // a conscious ACR decision that must revisit this precondition, and
        // until then the production entry is unreachable by construction.
        assertFalse("the shipped default must keep the rootfs download gate closed",
                BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED);

        FakeTransport transport = new FakeTransport();
        Path destDir = tempFolder.getRoot().toPath().resolve("buildconfig-gate-never-created");

        RootfsTransport.DownloadReceipt receipt = RootfsTransport.downloadAndVerify(
                transport, entryFor(MiniRootfsFixture.gzipTar(), null), destDir);

        assertFalse(receipt.enabled());
        assertTrue(transport.requestedUrls.isEmpty());
        assertFalse(Files.exists(destDir));
    }

    @Test
    public void anOpenFlagDrivesTheFullLoop() throws Exception {
        byte[] artifact = MiniRootfsFixture.gzipTar();
        FakeTransport transport = new FakeTransport();
        transport.payload = artifact;
        Path destDir = tempFolder.newFolder("gate-open").toPath();

        RootfsTransport.DownloadReceipt receipt = RootfsTransport.downloadAndVerify(
                transport, entryFor(artifact, (long) artifact.length), destDir, true);

        assertTrue(receipt.enabled());
        assertEquals(FILE_NAME, receipt.fileName());
        assertTrue(receipt.file().toFile().isFile());
    }

    // --- the seam's transport contract ----------------------------------------------------

    @Test
    public void theFixedSingleSourceAndThePinAreHandedToTheTransport() throws Exception {
        byte[] artifact = MiniRootfsFixture.gzipTar();
        Entry entry = entryFor(artifact, (long) artifact.length);
        FakeTransport transport = new FakeTransport();
        transport.payload = artifact;
        Path destDir = tempFolder.newFolder("seam").toPath();

        RootfsTransport.downloadAndVerify(transport, entry, destDir, true);

        // Mirror discipline: exactly one URL — the pinned entry's own fixed
        // HTTPS source. No mirror list, no rotation at the orchestration level;
        // network resilience is the engine's retry loop.
        assertEquals(List.of(entry.source().toString()), transport.requestedUrls);
        // The pin rides along: the engine runs its own streaming SHA-256 gate
        // (defense in depth — a pin mismatch never leaves the transport).
        assertEquals(List.of(entry.sha256Pin()), transport.requestedPins);
        // The staged .part name is the destination the engine was handed.
        assertEquals(List.of(staged(destDir)), transport.requestedDestinations);
    }

    // --- D-3 state 1: success --------------------------------------------------------------

    @Test
    public void aVerifiedDownloadPublishesTheFormalNameAndReturnsTheReceipt() throws Exception {
        byte[] artifact = MiniRootfsFixture.gzipTar();
        FakeTransport transport = new FakeTransport();
        transport.payload = artifact;
        Path destDir = tempFolder.newFolder("success").toPath();

        RootfsTransport.DownloadReceipt receipt = RootfsTransport.downloadAndVerify(
                transport, entryFor(artifact, (long) artifact.length), destDir, true);

        assertTrue(receipt.enabled());
        assertEquals(FILE_NAME, receipt.fileName());
        Path formal = destDir.resolve(FILE_NAME);
        assertEquals(formal.toAbsolutePath(), receipt.file().toAbsolutePath());
        assertEquals(artifact.length, receipt.sizeBytes());
        assertEquals(MiniRootfsFixture.sha256Hex(artifact), receipt.sha256());
        assertArrayEquals(artifact, Files.readAllBytes(formal));

        // The receipt's stage report is the L3 answer: implemented stages
        // pass, the declared-only stage stays honestly PLANNED.
        SelfCheckReport report = receipt.selfCheck();
        for (SelfCheckStage stage : UmlKernelAssets.RootfsDownload.selfCheckPlan()) {
            StageState state = report.stages().stream()
                    .filter(result -> result.stage() == stage)
                    .findFirst().orElseThrow().state();
            assertTrue(stage + " must not fail", state != StageState.FAIL);
        }
        // Lane E moved FIRST_BOOT_CLEANLINESS from PLANNED to implemented: no stage plans anymore.
        assertEquals(List.of(), report.plannedStages());
        assertTrue(report.describe().isEmpty());

        // Publish consumed the staging: only the formal name remains.
        assertFalse(Files.exists(staged(destDir)));
        assertFalse(Files.exists(enginePart(destDir)));
        try (Stream<Path> entries = Files.list(destDir)) {
            assertEquals(List.of(FILE_NAME),
                    entries.map(path -> path.getFileName().toString()).toList());
        }
    }

    // --- D-3 state 2: transport failure ------------------------------------------------------

    @Test
    public void aTransportFailureSweepsTheStagedNamesAndLeavesNoHalfProducts() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.failure = new IOException("connection reset by peer");
        transport.leavesEnginePartResidue = true;
        Path destDir = tempFolder.newFolder("transport-failure").toPath();

        IOException failure = assertThrows(IOException.class,
                () -> RootfsTransport.downloadAndVerify(transport,
                        entryFor(MiniRootfsFixture.gzipTar(), null), destDir, true));

        assertEquals("connection reset by peer", failure.getMessage());
        // The engine's residue and the staged name are both swept; the formal
        // name never appears.
        assertNothingLeftBehind(destDir);
        assertFalse(Files.exists(staged(destDir)));
        assertFalse(Files.exists(enginePart(destDir)));
    }

    // --- D-3 state 3: L2 pin mismatch --------------------------------------------------------

    @Test
    public void aChecksumMismatchReceivesTheStableReasonAndSweepsTheStaging() throws Exception {
        byte[] intact = MiniRootfsFixture.gzipTar();
        byte[] tampered = intact.clone();
        tampered[tampered.length / 2] ^= 0x55;
        FakeTransport transport = new FakeTransport();
        transport.payload = tampered;
        Path destDir = tempFolder.newFolder("pin-mismatch").toPath();

        RootfsRejectionException rejection = assertThrows(RootfsRejectionException.class,
                () -> RootfsTransport.downloadAndVerify(transport,
                        entryFor(intact, (long) intact.length), destDir, true));

        assertEquals(UmlKernelAssets.RootfsDownload.REASON_CHECKSUM_MISMATCH,
                rejection.reason());
        assertNothingLeftBehind(destDir);
    }

    // --- D-3 state 4: L3 content rejection ----------------------------------------------------

    @Test
    public void aSelfCheckRejectionReceivesTheStableReasonAndSweepsTheStaging() throws Exception {
        // Bytes whose own digest is the pin (L2 self-consistent) but whose
        // content is not the official shape (proc/ absent) — exactly the case
        // only L3 can see.
        byte[] contentCorrupt = MiniRootfsFixture.gzipTar(
                MiniRootfsFixture.officialShapeWithoutTopLevelDir("proc"));
        FakeTransport transport = new FakeTransport();
        transport.payload = contentCorrupt;
        Path destDir = tempFolder.newFolder("self-check-rejection").toPath();

        RootfsRejectionException rejection = assertThrows(RootfsRejectionException.class,
                () -> RootfsTransport.downloadAndVerify(transport,
                        entryFor(contentCorrupt, (long) contentCorrupt.length), destDir, true));

        assertEquals(UmlKernelAssets.RootfsDownload.REASON_SELF_CHECK_FAILED,
                rejection.reason());
        assertTrue(rejection.getMessage().contains(SelfCheckStage.TREE_DIGEST.toString()));
        assertNothingLeftBehind(destDir);
    }

    // --- the L1 byte gate on the received count ------------------------------------------------

    @Test
    public void aPinnedSizeDisagreementReceivesTheSizeReasonAndSweepsTheStaging()
            throws Exception {
        byte[] artifact = MiniRootfsFixture.gzipTar();
        // A transport that delivers the right bytes for a wrong declared size:
        // the L1 gate rejects before L2 or L3 run.
        Entry wrongSize = new Entry(UmlKernelAssets.RootfsDownload.ENTRY_ID,
                MiniRootfsFixture.VERSION, UmlKernelAssets.RootfsDownload.ENTRY_ARCHITECTURE,
                URI.create(UmlKernelAssets.RootfsDownload.ROOTFS_TAR_URL),
                FILE_NAME, MiniRootfsFixture.sha256Hex(artifact), artifact.length + 1L);
        FakeTransport transport = new FakeTransport();
        transport.payload = artifact;
        Path destDir = tempFolder.newFolder("size-gate").toPath();

        RootfsRejectionException rejection = assertThrows(RootfsRejectionException.class,
                () -> RootfsTransport.downloadAndVerify(transport, wrongSize, destDir, true));

        assertEquals(UmlKernelAssets.RootfsDownload.REASON_SIZE_MISMATCH, rejection.reason());
        assertNothingLeftBehind(destDir);
    }
}
