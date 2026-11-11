package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.UmlKernelAssets.Entry;
import io.github.nyzeep.orchestella.UmlKernelAssets.FastProfile;
import io.github.nyzeep.orchestella.UmlKernelAssets.FastProfileBundleReport;
import io.github.nyzeep.orchestella.UmlKernelAssets.Inventory;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.DownloadGate;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.RootfsRejectionException;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.SelfCheckStage;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.VerifiedRootfs;
import io.github.nyzeep.orchestella.UmlKernelAssets.Status;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * UML payload productization round: the fast-profile asset family's product
 * contract. Locked here, in the {@link UmlKernelAssetsTest} style: the pin
 * registry's values and grammar (single source for the runtime, the gradle
 * accounting and these tests), the bundled route's four-state seam (flag off
 * → disabled without a filesystem touch; flag on → the same seven honest
 * statuses over the fast-profile pins), and the download skeleton's
 * verification chain (fixed-HTTPS entry, size gate, constant-time pin
 * compare, fail-closed on every mismatch, the declared L3 stage plan). The
 * binaries stay git-ignored build products — every fixture here is a small
 * file the test places, never a "real" kernel.
 */
public final class UmlKernelAssetsFastProfileTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File libDir;
    private File filesDir;
    private UmlFaceLauncher.Environment environment;

    @Before
    public void createDirs() {
        libDir = new File(tmp.getRoot(), "native-lib");
        filesDir = new File(tmp.getRoot(), "files");
        assertTrue(libDir.mkdirs());
        assertTrue(filesDir.mkdirs());
        environment = new UmlFaceLauncher.Environment() {
            @Override public File nativeLibraryDir() {
                return libDir;
            }

            @Override public File filesDir() {
                return filesDir;
            }
        };
    }

    // --- fixtures -----------------------------------------------------------------

    /** A non-empty binary at the given name (the exec bit set where representable). */
    private File placeBinary(String name) throws IOException {
        File file = new File(libDir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write((name + "-image-bytes").getBytes(StandardCharsets.UTF_8));
        }
        // POSIX honours the bit (CI Linux); Windows canExecute() is always
        // true for existing files, so this is a no-op there.
        assertTrue(file.setExecutable(true));
        return file;
    }

    private File placeRootfs() {
        File rootfs = new File(filesDir, UmlFaceLauncher.ROOTFS_DIR_NAME);
        assertTrue(rootfs.mkdirs());
        return rootfs;
    }

    private static Entry entry(Inventory inventory, String name) {
        return inventory.entries().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry for " + name));
    }

    /** A test entry shaped like the production one, with fixture pins the test controls. */
    private static UmlKernelAssets.RootfsDownload.Entry entryWith(String pin, Long sizeBytes) {
        return new UmlKernelAssets.RootfsDownload.Entry("test-rootfs", "1.0", "aarch64",
                URI.create("https://dl-cdn.example.test/pool/" + FastProfile.ROOTFS_TAR),
                FastProfile.ROOTFS_TAR, pin, sizeBytes);
    }

    private static byte[] fixtureBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31);
        }
        return bytes;
    }

    /** A temp file holding the fixture bytes, for hashing through the file path. */
    private File fixtureFile(int length) throws IOException {
        File file = new File(tmp.getRoot(), "fixture-" + length + ".bin");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(fixtureBytes(length));
        }
        return file;
    }

    // --- P1-1: the fast-profile pin registry ----------------------------------------

    @Test
    public void pinManifestParsesThroughTheSameGrammarAsTheFileManifest() throws IOException {
        File manifest = new File(filesDir, "pin-manifest-under-test.sha256");
        java.nio.file.Files.write(manifest.toPath(),
                FastProfile.PIN_MANIFEST.getBytes(StandardCharsets.UTF_8));

        assertEquals(UmlKernelAssets.readManifest(manifest), FastProfile.pins());
        assertEquals(5, FastProfile.pins().size());
    }

    @Test
    public void pinsMatchTheResearchManifestValues() {
        // Drift lock: these literals are research/uml-fastboot/manifest.md §3
        // (ARM64 UP, 2026-11-10). A changed pin must be a conscious change.
        Map<String, String> pins = FastProfile.pins();
        assertEquals("21e1c3f97269a831fa8bab5fd3ff0e8a0e9edc59554d17cce34913924f584389",
                pins.get(FastProfile.KERNEL));
        assertEquals("a5efce2b9197071a2db89735e247fa3cf548644b7d036ea99bf688b14eff17fb",
                pins.get(FastProfile.KERNEL_NOINITRD));
        assertEquals("87666f9167de09d0bf84b414618c39d9f460417a641f212f5f0f28c498a09a12",
                pins.get(FastProfile.STUB));
        assertEquals("7523c9a20b956a3104d04540559422a7b566ef630a592c9d4ca8fdb2e5bd8381",
                pins.get(FastProfile.INITRAMFS));
        assertEquals("6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489",
                pins.get(FastProfile.ROOTFS_TAR));
        assertThrows(UnsupportedOperationException.class, () -> pins.put("x", "y"));
    }

    @Test
    public void artifactsCarryTheResearchSizesAndTheOfficialRootfsSize() {
        List<FastProfile.PinnedArtifact> artifacts = FastProfile.artifacts();

        assertEquals(5, artifacts.size());
        assertEquals(FastProfile.KERNEL, artifacts.get(0).name());
        assertEquals(9_517_760L, artifacts.get(0).sizeBytes().longValue());
        assertEquals(8_862_400L, artifacts.get(1).sizeBytes().longValue());
        assertEquals(2_056L, artifacts.get(2).sizeBytes().longValue());
        assertEquals(661_899L, artifacts.get(3).sizeBytes().longValue());
        // The research manifest pinned the rootfs digest but recorded no byte
        // count; L2L3 closed that gap first-hand against the release CDN
        // (2026-11-12: HEAD Content-Length and a full download both read
        // 3,981,043 and the bytes hash to the pin) — the official number, not
        // a guess.
        assertEquals(FastProfile.ROOTFS_TAR_SIZE_BYTES, artifacts.get(4).sizeBytes().longValue());
        assertEquals(3_981_043L, FastProfile.ROOTFS_TAR_SIZE_BYTES);
        for (FastProfile.PinnedArtifact artifact : artifacts) {
            assertEquals(artifact.sha256(), FastProfile.pins().get(artifact.name()));
        }
    }

    @Test
    public void packagedLibNameMapsTheKernelVariantsAndTheStubOnly() {
        assertEquals(UmlFaceLauncher.KERNEL_LIB_NAME, FastProfile.packagedLibName(FastProfile.KERNEL));
        assertEquals(UmlFaceLauncher.KERNEL_LIB_NAME,
                FastProfile.packagedLibName(FastProfile.KERNEL_NOINITRD));
        assertEquals(UmlFaceLauncher.STUB_LIB_NAME, FastProfile.packagedLibName(FastProfile.STUB));
        assertThrows(IllegalArgumentException.class,
                () -> FastProfile.packagedLibName(FastProfile.ROOTFS_TAR));
        assertThrows(IllegalArgumentException.class,
                () -> FastProfile.packagedLibName(FastProfile.INITRAMFS));
        assertThrows(IllegalArgumentException.class,
                () -> FastProfile.packagedLibName("not-an-artifact"));
    }

    @Test
    public void packagedPinsServeTheDefaultKernelVariantAndTheStub() {
        Map<String, String> pins = FastProfile.packagedPins();

        assertEquals(2, pins.size());
        assertEquals(FastProfile.pins().get(FastProfile.KERNEL), pins.get(UmlFaceLauncher.KERNEL_LIB_NAME));
        assertEquals(FastProfile.pins().get(FastProfile.STUB), pins.get(UmlFaceLauncher.STUB_LIB_NAME));

        Map<String, String> noinitrdPins = FastProfile.packagedPinsFor(FastProfile.KERNEL_NOINITRD);
        assertEquals(FastProfile.pins().get(FastProfile.KERNEL_NOINITRD),
                noinitrdPins.get(UmlFaceLauncher.KERNEL_LIB_NAME));
        assertEquals(FastProfile.pins().get(FastProfile.STUB),
                noinitrdPins.get(UmlFaceLauncher.STUB_LIB_NAME));

        assertThrows(IllegalArgumentException.class, () -> FastProfile.packagedPinsFor(FastProfile.ROOTFS_TAR));
    }

    // --- P1-2: the bundled route's four-state seam -----------------------------------

    @Test
    public void flagOffAnswersDisabledEvenWithNothingOnDisk() throws IOException {
        // No fixture is placed at all — and the answer must not depend on the
        // filesystem: the default build is today's build, zero behavior.
        FastProfileBundleReport report = UmlKernelAssets.verifyBundledFastProfile(environment, false);

        assertFalse(report.enabled());
        assertTrue(report.inventory().entries().isEmpty());
        assertFalse(report.inventory().manifest().present());
        assertTrue(report.inventory().usable());
    }

    @Test
    public void disabledReportRefusesToCarryVerificationEntries() throws IOException {
        placeBinary(UmlFaceLauncher.KERNEL_LIB_NAME);
        placeBinary(UmlFaceLauncher.STUB_LIB_NAME);
        placeRootfs();
        Inventory placed = UmlKernelAssets.verify(environment, Map.of());

        assertThrows(IllegalArgumentException.class,
                () -> new FastProfileBundleReport(false, placed));
    }

    @Test
    public void flagOnWithNothingPlacedAnswersMissingForEveryAsset() throws IOException {
        FastProfileBundleReport report = UmlKernelAssets.verifyBundledFastProfile(environment, true);

        assertTrue(report.enabled());
        Inventory inventory = report.inventory();
        assertEquals(Status.MISSING, entry(inventory, UmlFaceLauncher.KERNEL_LIB_NAME).status());
        assertEquals(Status.MISSING, entry(inventory, UmlFaceLauncher.STUB_LIB_NAME).status());
        assertEquals(Status.MISSING, entry(inventory, UmlFaceLauncher.ROOTFS_DIR_NAME).status());
        // The pins are the fast-profile registry's, carried in the entries.
        assertEquals(FastProfile.pins().get(FastProfile.KERNEL),
                entry(inventory, UmlFaceLauncher.KERNEL_LIB_NAME).expectedSha256());
        assertEquals(FastProfile.pins().get(FastProfile.STUB),
                entry(inventory, UmlFaceLauncher.STUB_LIB_NAME).expectedSha256());
        assertFalse(inventory.usable());
        assertEquals(UmlFaceLauncher.KERNEL_LIB_NAME, inventory.firstProblem().name());
        assertFalse(inventory.describe().isEmpty());
    }

    @Test
    public void flagOnWithFixtureAssetsAnswersDigestMismatchFailClosed() throws IOException {
        placeBinary(UmlFaceLauncher.KERNEL_LIB_NAME);
        placeBinary(UmlFaceLauncher.STUB_LIB_NAME);
        placeRootfs();

        FastProfileBundleReport report = UmlKernelAssets.verifyBundledFastProfile(environment, true);

        Inventory inventory = report.inventory();
        Entry kernel = entry(inventory, UmlFaceLauncher.KERNEL_LIB_NAME);
        assertEquals(Status.DIGEST_MISMATCH, kernel.status());
        assertEquals(FastProfile.pins().get(FastProfile.KERNEL), kernel.expectedSha256());
        assertEquals(UmlKernelAssets.sha256Hex(new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME)),
                kernel.actualSha256());
        assertEquals(Status.DIGEST_MISMATCH, entry(inventory, UmlFaceLauncher.STUB_LIB_NAME).status());
        assertTrue(inventory.firstProblem() == kernel);
        assertFalse(inventory.usable());
    }

    @Test
    public void flagOnWithMatchingPinsAnswersOkThroughTheInjectableSeam() throws IOException {
        File kernel = placeBinary(UmlFaceLauncher.KERNEL_LIB_NAME);
        File stub = placeBinary(UmlFaceLauncher.STUB_LIB_NAME);
        placeRootfs();
        Map<String, String> fixturePins = Map.of(
                UmlFaceLauncher.KERNEL_LIB_NAME, UmlKernelAssets.sha256Hex(kernel),
                UmlFaceLauncher.STUB_LIB_NAME, UmlKernelAssets.sha256Hex(stub));

        FastProfileBundleReport report =
                UmlKernelAssets.verifyBundledFastProfile(environment, true, fixturePins);

        assertTrue(report.enabled());
        Inventory inventory = report.inventory();
        assertEquals(Status.OK, entry(inventory, UmlFaceLauncher.KERNEL_LIB_NAME).status());
        assertEquals(Status.OK, entry(inventory, UmlFaceLauncher.STUB_LIB_NAME).status());
        assertEquals(Status.OK_DIR, entry(inventory, UmlFaceLauncher.ROOTFS_DIR_NAME).status());
        assertTrue(inventory.usable());
        assertNull(inventory.firstProblem());
        assertTrue(inventory.describe().isEmpty());
    }

    @Test
    public void flagOnWithCorruptFixtureAnswersTheHonestCorruptStatuses() throws IOException {
        File emptyKernel = new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME);
        assertTrue(emptyKernel.createNewFile());
        File stub = placeBinary(UmlFaceLauncher.STUB_LIB_NAME);
        stub.setExecutable(false);

        FastProfileBundleReport report = UmlKernelAssets.verifyBundledFastProfile(
                environment, true, Map.of(UmlFaceLauncher.STUB_LIB_NAME,
                        UmlKernelAssets.sha256Hex(stub)));

        Inventory inventory = report.inventory();
        assertEquals(Status.EMPTY, entry(inventory, UmlFaceLauncher.KERNEL_LIB_NAME).status());
        assertTrue(inventory.describe().stream()
                .anyMatch(line -> line.contains("empty files: " + UmlFaceLauncher.KERNEL_LIB_NAME)));
        if (!stub.canExecute()) {
            // POSIX: the cleared bit is representable and must be reported.
            assertEquals(Status.NOT_EXECUTABLE, entry(inventory, UmlFaceLauncher.STUB_LIB_NAME).status());
        } else {
            // Windows: canExecute() stays true for existing files.
            assertEquals(Status.OK, entry(inventory, UmlFaceLauncher.STUB_LIB_NAME).status());
        }
        assertEquals(Status.MISSING, entry(inventory, UmlFaceLauncher.ROOTFS_DIR_NAME).status());
    }

    // --- P1-3: the download route's entry and verification chain ----------------------

    @Test
    public void productionEntryPinsTheOfficialAlpineTarball() {
        UmlKernelAssets.RootfsDownload.Entry entry =
                UmlKernelAssets.RootfsDownload.Entry.alpineMinirootfsAarch64();

        assertEquals(UmlKernelAssets.RootfsDownload.ENTRY_ID, entry.id());
        assertEquals("3.22.2", entry.version());
        assertEquals("aarch64", entry.architecture());
        assertEquals(FastProfile.ROOTFS_TAR, entry.fileName());
        assertEquals(FastProfile.pins().get(FastProfile.ROOTFS_TAR), entry.sha256Pin());
        assertTrue(entry.source().toString().startsWith("https://"));
        assertTrue(entry.source().getPath().endsWith("/" + FastProfile.ROOTFS_TAR));
        // L2L3 locked the size first-hand from the release CDN (2026-11-12:
        // HEAD Content-Length = a full download's byte count = 3,981,043, and
        // the bytes hash to the pin) — the L1 byte gate is now exact.
        assertEquals(FastProfile.ROOTFS_TAR_SIZE_BYTES, entry.sizeBytes().longValue());
    }

    @Test
    public void entryValidationRejectsLooseSourcesAndBadPins() throws java.net.URISyntaxException {
        assertThrows(IllegalArgumentException.class, () -> new UmlKernelAssets.RootfsDownload.Entry("id", "1", "aarch64",
                URI.create("http://dl-cdn.example.test/pool/" + FastProfile.ROOTFS_TAR),
                FastProfile.ROOTFS_TAR, "a".repeat(64), null));
        assertThrows(IllegalArgumentException.class, () -> new UmlKernelAssets.RootfsDownload.Entry("id", "1", "aarch64",
                URI.create("https://dl-cdn.example.test/pool/" + FastProfile.ROOTFS_TAR + "?x=1"),
                FastProfile.ROOTFS_TAR, "a".repeat(64), null));
        // The userinfo URL is assembled through the multi-argument URI
        // constructor on purpose: a spelled-out "user:pass@host" literal is
        // shaped like an email address and trips the sensitive-data gate.
        URI withUserinfo = new URI("https", "user:pass", "dl-cdn.example.test", -1,
                "/pool/" + FastProfile.ROOTFS_TAR, null, null);
        assertThrows(IllegalArgumentException.class, () -> new UmlKernelAssets.RootfsDownload.Entry("id", "1", "aarch64",
                withUserinfo,
                FastProfile.ROOTFS_TAR, "a".repeat(64), null));
        assertThrows(IllegalArgumentException.class, () -> new UmlKernelAssets.RootfsDownload.Entry("id", "1", "aarch64",
                URI.create("https://dl-cdn.example.test/pool/other-file.tar.gz"),
                FastProfile.ROOTFS_TAR, "a".repeat(64), null));
        assertThrows(IllegalArgumentException.class, () -> new UmlKernelAssets.RootfsDownload.Entry("id", "1", "aarch64",
                URI.create("https://dl-cdn.example.test/pool/" + FastProfile.ROOTFS_TAR),
                FastProfile.ROOTFS_TAR, "A".repeat(64), null));
        assertThrows(IllegalArgumentException.class, () -> new UmlKernelAssets.RootfsDownload.Entry("id", "1", "aarch64",
                URI.create("https://dl-cdn.example.test/pool/" + FastProfile.ROOTFS_TAR),
                FastProfile.ROOTFS_TAR, "a".repeat(64), 0L));
    }

    @Test
    public void downloadGateStaysClosedWithoutTheBuildFlag() {
        // The skeleton default: the gradle property defaults to false, so the
        // production wiring passes false and the entry point stays closed.
        assertFalse(DownloadGate.of(false).allowsDownload());
        assertTrue(DownloadGate.of(true).allowsDownload());
    }

    @Test
    public void requireVerifiedAcceptsIntactBytesWithPinnedSize() throws IOException {
        byte[] bytes = fixtureBytes(512);
        UmlKernelAssets.RootfsDownload.Entry entry = entryWith(UmlKernelAssets.sha256Hex(fixtureFile(512)), 512L);

        VerifiedRootfs proof = UmlKernelAssets.RootfsDownload.requireVerified(
                entry, new ByteArrayInputStream(bytes), 512L);

        assertEquals("test-rootfs", proof.entryId());
        assertEquals(entry.sha256Pin(), proof.sha256());
        assertEquals(512L, proof.observedBytes());
        assertTrue(proof.sizeVerified());
    }

    @Test
    public void requireVerifiedWithUnpinnedSizeSkipsTheSizeHonestly() throws IOException {
        byte[] bytes = fixtureBytes(512);
        UmlKernelAssets.RootfsDownload.Entry entry = entryWith(UmlKernelAssets.sha256Hex(fixtureFile(512)), null);

        VerifiedRootfs proof = UmlKernelAssets.RootfsDownload.requireVerified(
                entry, new ByteArrayInputStream(bytes), null);

        assertFalse(proof.sizeVerified());
        assertEquals(entry.sha256Pin(), proof.sha256());
        assertEquals(512L, proof.observedBytes());
    }

    @Test
    public void requireVerifiedRejectsTruncatedStreamsFailClosed() throws IOException {
        byte[] truncated = java.util.Arrays.copyOf(fixtureBytes(512), 100);
        UmlKernelAssets.RootfsDownload.Entry entry = entryWith(UmlKernelAssets.sha256Hex(fixtureFile(512)), 512L);

        RootfsRejectionException rejection = assertThrows(RootfsRejectionException.class,
                () -> UmlKernelAssets.RootfsDownload.requireVerified(
                        entry, new ByteArrayInputStream(truncated), 512L));
        assertEquals(UmlKernelAssets.RootfsDownload.REASON_SIZE_MISMATCH, rejection.reason());
    }

    @Test
    public void requireVerifiedRejectsDeclaredSizeMismatchBeforeReading() {
        UmlKernelAssets.RootfsDownload.Entry entry = entryWith("a".repeat(64), 512L);
        // A stream that fails on read proves the declared-size gate answers
        // before a single byte is consumed (L1 guards L2, not the other way).
        java.io.InputStream hostile = new java.io.InputStream() {
            @Override public int read() throws IOException {
                throw new IOException("must not be read");
            }
        };

        RootfsRejectionException rejection = assertThrows(RootfsRejectionException.class,
                () -> UmlKernelAssets.RootfsDownload.requireVerified(entry, hostile, 511L));
        assertEquals(UmlKernelAssets.RootfsDownload.REASON_SIZE_MISMATCH, rejection.reason());
    }

    @Test
    public void requireVerifiedRejectsTamperedBytesFailClosed() throws IOException {
        byte[] tampered = fixtureBytes(512);
        tampered[0] ^= 0x55;
        UmlKernelAssets.RootfsDownload.Entry entry = entryWith(UmlKernelAssets.sha256Hex(fixtureFile(512)), 512L);

        RootfsRejectionException rejection = assertThrows(RootfsRejectionException.class,
                () -> UmlKernelAssets.RootfsDownload.requireVerified(
                        entry, new ByteArrayInputStream(tampered), 512L));
        assertEquals(UmlKernelAssets.RootfsDownload.REASON_CHECKSUM_MISMATCH, rejection.reason());
    }

    @Test
    public void requireVerifiedWithPinnedSizeRejectsAMissingDeclaration() throws IOException {
        UmlKernelAssets.RootfsDownload.Entry entry = entryWith(UmlKernelAssets.sha256Hex(fixtureFile(512)), 512L);

        RootfsRejectionException rejection = assertThrows(RootfsRejectionException.class,
                () -> UmlKernelAssets.RootfsDownload.requireVerified(
                        entry, new ByteArrayInputStream(fixtureBytes(512)), null));
        assertEquals(UmlKernelAssets.RootfsDownload.REASON_SIZE_MISMATCH, rejection.reason());
    }

    @Test
    public void selfCheckPlanIsTheFixedFiveStageOrderAndImmutable() {
        List<SelfCheckStage> plan = UmlKernelAssets.RootfsDownload.selfCheckPlan();

        List<SelfCheckStage> expected = new ArrayList<>();
        expected.add(SelfCheckStage.MANIFEST_FIELDS);
        expected.add(SelfCheckStage.ARTIFACT_DIGESTS);
        expected.add(SelfCheckStage.PACKAGE_DATABASE);
        expected.add(SelfCheckStage.TREE_DIGEST);
        expected.add(SelfCheckStage.FIRST_BOOT_CLEANLINESS);
        assertEquals(expected, plan);
        assertNotNull(plan);
        assertThrows(UnsupportedOperationException.class, () -> plan.add(SelfCheckStage.TREE_DIGEST));
    }
}
