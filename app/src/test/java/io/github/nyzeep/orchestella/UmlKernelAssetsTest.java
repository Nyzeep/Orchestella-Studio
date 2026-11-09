package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.UmlKernelAssets.Entry;
import io.github.nyzeep.orchestella.UmlKernelAssets.Inventory;
import io.github.nyzeep.orchestella.UmlKernelAssets.Status;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * C lane: the UML kernel assets' reconciliation contract — the runtime
 * counterpart of the build-side payload check (manifest + per-entry SHA-256 +
 * honest missing/empty/mismatch categories). Locked here: the placement
 * layout parity with the launcher gate ({@code firstMissingName} names the
 * same assets in the same fixed priority), the manifest parser's tolerance
 * matching the build-side behaviour, every fixed status including the honest
 * {@code OK_EXISTENCE_ONLY} tier, and the report's payload-style remediation
 * text. The assets themselves are git-ignored build products — these tests
 * only ever judge files the test places, never fabricate a "real" kernel.
 */
public final class UmlKernelAssetsTest {

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

    private File placeManifest(String... lines) throws IOException {
        File manifest = new File(filesDir, UmlKernelAssets.MANIFEST_FILE_NAME);
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        Files.write(manifest.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        return manifest;
    }

    private static Map<String, String> pins(String name, String digest) {
        return Map.of(name, digest);
    }

    private static Entry entry(Inventory inventory, String name) {
        return inventory.entries().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry for " + name));
    }

    private static final String DIGEST_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    // --- placement parity with the launcher gate -----------------------------------

    @Test
    public void firstMissingNameFollowsTheFixedPriorityKernelStubRootfs() throws IOException {
        assertEquals(UmlFaceLauncher.KERNEL_LIB_NAME, UmlKernelAssets.firstMissingName(environment));
        placeBinary(UmlFaceLauncher.KERNEL_LIB_NAME);
        assertEquals(UmlFaceLauncher.STUB_LIB_NAME, UmlKernelAssets.firstMissingName(environment));
        placeBinary(UmlFaceLauncher.STUB_LIB_NAME);
        assertEquals(UmlFaceLauncher.ROOTFS_DIR_NAME, UmlKernelAssets.firstMissingName(environment));
        placeRootfs();
        assertNull(UmlKernelAssets.firstMissingName(environment));
    }

    @Test
    public void launcherGateAnswersTheSameNameTheParityCheckNames() throws IOException {
        // Same environment, same answer: the launcher's MISSING_ASSET detail is
        // the parity check's first missing name (delegation, single source).
        UmlFaceLauncher.KeepAlive keepAlive = new UmlFaceLauncher.KeepAlive() {
            @Override public void acquire() {
            }

            @Override public void release() {
            }
        };
        UmlFaceLauncher face = new UmlFaceLauncher(
                true, environment, UmlFaceLauncher.LaunchParams.defaults(),
                keepAlive, UmlFaceLauncher.ProcessSpawner.processBuilder(), null);

        UmlFaceLauncher.StartResult result = face.start();

        assertEquals(UmlFaceLauncher.StartCode.MISSING_ASSET, result.code());
        assertEquals(UmlKernelAssets.firstMissingName(environment), result.detail());
    }

    // --- manifest parsing: build-side parity ---------------------------------------

    @Test
    public void manifestParserAcceptsOnlyTheHexTwoSpacesNameShape() throws IOException {
        File manifest = placeManifest(
            DIGEST_A + "  libuml_kernel.so",
            "",
            "not-a-manifest-line",
            "abc123  sloppy",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  libuml_stub.so");

        Map<String, String> parsed = UmlKernelAssets.readManifest(manifest);

        assertEquals(2, parsed.size());
        assertEquals(DIGEST_A, parsed.get(UmlFaceLauncher.KERNEL_LIB_NAME));
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                parsed.get(UmlFaceLauncher.STUB_LIB_NAME));
    }

    @Test
    public void missingManifestIsAnEmptyPinSetNotAnError() throws IOException {
        File absent = new File(filesDir, UmlKernelAssets.MANIFEST_FILE_NAME);
        assertTrue(UmlKernelAssets.readManifest(absent).isEmpty());
    }

    // --- verification statuses ------------------------------------------------------

    @Test
    public void allPlacedWithoutManifestVerifiesExistenceOnlyHonestly() throws IOException {
        placeBinary(UmlFaceLauncher.KERNEL_LIB_NAME);
        placeBinary(UmlFaceLauncher.STUB_LIB_NAME);
        placeRootfs();

        Inventory inventory = UmlKernelAssets.verify(environment);

        assertFalse(inventory.manifest().present());
        assertTrue(inventory.usable());
        assertEquals(Status.OK_EXISTENCE_ONLY, entry(inventory, UmlFaceLauncher.KERNEL_LIB_NAME).status());
        assertEquals(Status.OK_EXISTENCE_ONLY, entry(inventory, UmlFaceLauncher.STUB_LIB_NAME).status());
        assertEquals(Status.OK_DIR, entry(inventory, UmlFaceLauncher.ROOTFS_DIR_NAME).status());
        assertNull(inventory.firstProblem());
        assertTrue(inventory.describe().isEmpty());
    }

    @Test
    public void pinnedDigestMatchAnswersOk() throws IOException {
        File kernel = placeBinary(UmlFaceLauncher.KERNEL_LIB_NAME);
        placeBinary(UmlFaceLauncher.STUB_LIB_NAME);
        placeRootfs();
        String digest = UmlKernelAssets.sha256Hex(kernel);

        Inventory inventory = UmlKernelAssets.verify(
                environment, pins(UmlFaceLauncher.KERNEL_LIB_NAME, digest));

        Entry kernelEntry = entry(inventory, UmlFaceLauncher.KERNEL_LIB_NAME);
        assertEquals(Status.OK, kernelEntry.status());
        assertEquals(digest, kernelEntry.actualSha256());
        // The unpinned stub and the rootfs are honest tiers, not problems.
        assertEquals(Status.OK_EXISTENCE_ONLY, entry(inventory, UmlFaceLauncher.STUB_LIB_NAME).status());
        assertTrue(inventory.usable());
        assertNull(inventory.firstProblem());
    }

    @Test
    public void digestMismatchCarriesBothSidesAndIsAProblem() throws IOException {
        placeBinary(UmlFaceLauncher.KERNEL_LIB_NAME);

        Inventory inventory = UmlKernelAssets.verify(
                environment, pins(UmlFaceLauncher.KERNEL_LIB_NAME, DIGEST_A));

        Entry kernel = entry(inventory, UmlFaceLauncher.KERNEL_LIB_NAME);
        assertEquals(Status.DIGEST_MISMATCH, kernel.status());
        assertEquals(DIGEST_A, kernel.expectedSha256());
        assertNotNull(kernel.actualSha256());
        assertTrue(inventory.firstProblem() == kernel);
        assertFalse(inventory.describe().isEmpty());
    }

    @Test
    public void missingStubAndRootfsAnswerTheFixedMissingStatus() throws IOException {
        placeBinary(UmlFaceLauncher.KERNEL_LIB_NAME);

        Inventory inventory = UmlKernelAssets.verify(environment, Map.of());

        assertEquals(Status.OK_EXISTENCE_ONLY, entry(inventory, UmlFaceLauncher.KERNEL_LIB_NAME).status());
        assertEquals(Status.MISSING, entry(inventory, UmlFaceLauncher.STUB_LIB_NAME).status());
        assertEquals(Status.MISSING, entry(inventory, UmlFaceLauncher.ROOTFS_DIR_NAME).status());
        assertEquals(UmlFaceLauncher.STUB_LIB_NAME, inventory.firstProblem().name());
    }

    @Test
    public void emptyAndNonExecutableBinariesAreHonestProblems() throws IOException {
        File emptyKernel = new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME);
        assertTrue(emptyKernel.createNewFile());
        File stub = placeBinary(UmlFaceLauncher.STUB_LIB_NAME);
        stub.setExecutable(false);

        Inventory inventory = UmlKernelAssets.verify(environment, Map.of());

        assertEquals(Status.EMPTY, entry(inventory, UmlFaceLauncher.KERNEL_LIB_NAME).status());
        List<String> lines = inventory.describe();
        assertTrue(lines.stream().anyMatch(line -> line.contains("empty files: libuml_kernel.so")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("missing files: rootfs")));
        if (!stub.canExecute()) {
            // POSIX: the cleared bit is representable and must be reported.
            assertEquals(Status.NOT_EXECUTABLE, entry(inventory, UmlFaceLauncher.STUB_LIB_NAME).status());
            assertTrue(lines.stream().anyMatch(line -> line.contains("not executable: libuml_stub.so")));
        } else {
            // Windows: canExecute() stays true for existing files, so the
            // honest status here is the existence-only tier.
            assertEquals(Status.OK_EXISTENCE_ONLY, entry(inventory, UmlFaceLauncher.STUB_LIB_NAME).status());
        }
    }

    @Test
    public void presentManifestCarriesItsPinCount() throws IOException {
        placeManifest(DIGEST_A + "  libuml_kernel.so");

        Inventory inventory = UmlKernelAssets.verify(
                environment, pins(UmlFaceLauncher.KERNEL_LIB_NAME, DIGEST_A));

        assertTrue(inventory.manifest().present());
        assertEquals(1, inventory.manifest().pinnedEntries());
    }

    // --- the honest report ----------------------------------------------------------

    @Test
    public void describeNamesTheProblemCategoriesAndTheRemediation() throws IOException {
        placeBinary(UmlFaceLauncher.KERNEL_LIB_NAME);

        Inventory inventory = UmlKernelAssets.verify(environment, Map.of());

        List<String> lines = inventory.describe();
        assertFalse(lines.isEmpty());
        assertTrue(lines.get(0).contains(UmlKernelAssets.MANIFEST_FILE_NAME));
        assertTrue(lines.stream().anyMatch(line -> line.contains("missing files: libuml_stub.so")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("rootfs")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("prepare flow")));
    }

    @Test
    public void digestMismatchLineCarriesExpectedAndActual() throws IOException {
        placeBinary(UmlFaceLauncher.KERNEL_LIB_NAME);

        Inventory inventory = UmlKernelAssets.verify(
                environment, pins(UmlFaceLauncher.KERNEL_LIB_NAME, DIGEST_A));

        String mismatchLine = inventory.describe().stream()
                .filter(line -> line.contains("sha256 mismatch"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no mismatch line"));
        assertTrue(mismatchLine.contains(DIGEST_A));
        assertTrue(mismatchLine.contains("actual="));
    }

    @Test
    public void requiredNamesAreTheThreeAssetsInGatePriority() {
        List<String> names = UmlKernelAssets.requiredNames();
        assertEquals(3, names.size());
        assertEquals(UmlFaceLauncher.KERNEL_LIB_NAME, names.get(0));
        assertEquals(UmlFaceLauncher.STUB_LIB_NAME, names.get(1));
        assertEquals(UmlFaceLauncher.ROOTFS_DIR_NAME, names.get(2));
    }
}
