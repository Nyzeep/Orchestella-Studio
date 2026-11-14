package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.Entry;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.RootfsRejectionException;
import io.github.nyzeep.orchestella.runtime.network.download.DownloadEngine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Lane B's orchestration seam contract ({@link UmlRootfsProvisioner}): the
 * four fail-closed states of the product call (flag off with zero side
 * effects, verified download + slip-safe extraction, transport failure,
 * checksum mismatch) — plus pin-sha idempotency (a repeated entry action
 * never wastes traffic), the sidecar attestation discipline, the exec-bit
 * preservation, and the extractor's own slip-defense matrix (absolute names,
 * {@code ..} segments, symlink parent traversal, duplicate names, dangling
 * or escaping hard links, unsupported entry types, corrupt headers,
 * truncation).
 *
 * <p>Every transport is a fake in-memory byte source fulfilling the {@link
 * DownloadEngine} contract; every tar is built in memory by the minimal
 * writer below — no network, no real rootfs binary. The full-pipeline tests
 * run the transport's real L1/L2/L3 chain on the official-shape fixture
 * ({@code MiniRootfsFixture}), so a provision receipt's verdict is the same
 * chain production would run. Slip-defense tests drive the extractor directly
 * ({@code extractVerifiedTar}, package-private for exactly this): an attack
 * tar never passes the verification chain, so the full pipeline cannot reach
 * the extractor's own rejections.
 *
 * <p>The external-root launcher injection (the 2026-11-13 candidate-A
 * ruling) is locked end to end: the owned constant keeps the research probe
 * contract (shebang, U1 markers, busybox-true T3, um.hold_ms/um.shell), a
 * fresh provision lands it executable 0755 at the extract root, a repeated
 * provision never rewrites it (mtime-frozen proof), a tampered or deleted
 * launcher is repaired to the owned bytes over the same attested extraction
 * with zero transport calls, an extraction failure injects nothing, and an
 * injection failure propagates fail-closed with the stable reason while its
 * {@code .part} staging is swept.
 *
 * <p>Symlink-materialization tests probe the filesystem first ({@code
 * assumeTrue}) — hosts without symlink privilege (e.g. a Windows CI runner)
 * skip them honestly; the device (Linux) always runs them.
 */
public final class UmlRootfsProvisionerContractTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    // --- fakes -----------------------------------------------------------------------------

    /** In-memory byte source fulfilling the engine contract; records every call. */
    private static final class FakeTransport implements DownloadEngine {
        final List<String> requestedUrls = new ArrayList<>();
        final List<Path> requestedDestinations = new ArrayList<>();
        byte[] payload;
        IOException failure;

        @Override
        public void download(List<String> mirrorUrls, Path destination, String expectedSha256,
                io.github.nyzeep.orchestella.runtime.network.download.DownloadProgressCallback callback)
                throws IOException {
            requestedUrls.addAll(mirrorUrls);
            requestedDestinations.add(destination);
            if (failure != null) {
                throw failure;
            }
            Files.write(destination, payload);
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private static final String FILE_NAME = UmlKernelAssets.FastProfile.ROOTFS_TAR;
    private static final String EXTRACT_DIR_NAME = "alpine-minirootfs-3.22.2-aarch64";

    /** The production-shaped entry, pinned to the given payload's digest and size. */
    private static Entry entryFor(byte[] payload) throws Exception {
        return new Entry(UmlKernelAssets.RootfsDownload.ENTRY_ID,
                MiniRootfsFixture.VERSION, UmlKernelAssets.RootfsDownload.ENTRY_ARCHITECTURE,
                URI.create(UmlKernelAssets.RootfsDownload.ROOTFS_TAR_URL),
                FILE_NAME, MiniRootfsFixture.sha256Hex(payload), (long) payload.length);
    }

    /** The official-shape fixture minus its one symlink entry (host-portable). */
    private static byte[] officialShapeWithoutSymlink() throws IOException {
        List<MiniRootfsFixture.Spec> specs = new ArrayList<>(MiniRootfsFixture.officialShape());
        specs.removeIf(spec -> spec.type() == '2');
        return MiniRootfsFixture.gzipTar(specs);
    }

    private static Path umRootfsDir(Path filesDir) {
        return filesDir.resolve(UmlRootfsProvisioner.UML_ROOTFS_DIR_NAME);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static List<String> listing(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static boolean symlinksSupported(Path dir) {
        try {
            Path link = dir.resolve("symlink-probe");
            Files.createSymbolicLink(link, Path.of("probe-target"));
            Files.deleteIfExists(link);
            return true;
        } catch (IOException | UnsupportedOperationException unsupported) {
            return false;
        }
    }

    private static boolean posixPermissionsSupported() {
        return java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix");
    }

    // --- the minimal tar writer (modes, GNU long names, PAX paths, attack shapes) ----------

    /** One tar entry: name, typeflag, payload, mode (null = type default), link target. */
    private record TarSpec(String name, char type, byte[] content, Long mode, String linkName) {
        static TarSpec file(String name, String text) {
            return new TarSpec(name, '0', text.getBytes(StandardCharsets.UTF_8), 0644L, null);
        }

        static TarSpec fileMode(String name, long mode, String text) {
            return new TarSpec(name, '0', text.getBytes(StandardCharsets.UTF_8), mode, null);
        }

        static TarSpec dir(String name) {
            return new TarSpec(name, '5', new byte[0], 0755L, null);
        }

        static TarSpec dirMode(String name, long mode) {
            return new TarSpec(name, '5', new byte[0], mode, null);
        }

        static TarSpec symlink(String name, String target) {
            return new TarSpec(name, '2', new byte[0], 0777L, target);
        }

        static TarSpec hardLink(String name, String target) {
            return new TarSpec(name, '1', new byte[0], 0644L, target);
        }

        static TarSpec rawType(char type, String name, byte[] content) {
            return new TarSpec(name, type, content, 0644L, null);
        }

        /** A PAX 'x' header carrying {@code path=<override>} for the next entry. */
        static TarSpec paxPath(String override) {
            return new TarSpec("", 'x', paxRecord("path", override), 0644L, null);
        }
    }

    private static byte[] paxRecord(String key, String value) {
        String body = key + "=" + value + "\n";
        // The record length counts the whole record: "<len> <body>".
        int length = body.length() + 1 + String.valueOf(body.length() + 1).length();
        return (length + " " + body).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzipTar(List<TarSpec> specs) throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream zipper = new java.util.zip.GZIPOutputStream(gz)) {
            zipper.write(tarBytes(specs));
        }
        return gz.toByteArray();
    }

    private static byte[] tarBytes(List<TarSpec> specs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (TarSpec spec : specs) {
            if (spec.type() == 'x' || spec.type() == 'L' || spec.type() == 'K') {
                writeHeader(out, spec.type() == 'L' ? "././@LongLink" : "PaxHeader",
                        spec.type(), spec.content().length, 0644L, null);
                writePayload(out, spec.content());
                continue;
            }
            String name = spec.name();
            if (name.length() > 100) {
                // GNU 'L' long name: the real name rides the metadata payload,
                // the header keeps a truncated placeholder.
                byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
                writeHeader(out, "././@LongLink", 'L', nameBytes.length, 0644L, null);
                writePayload(out, nameBytes);
            }
            boolean isLink = spec.type() == '1' || spec.type() == '2';
            long size = isLink || spec.type() == '5' ? 0L : spec.content().length;
            writeHeader(out, name.length() > 100 ? name.substring(0, 100) : name,
                    spec.type(), size,
                    spec.mode() == null ? (spec.type() == '5' ? 0755L : 0644L) : spec.mode(),
                    isLink ? spec.linkName() : null);
            if (size > 0) {
                writePayload(out, spec.content());
            }
        }
        out.write(new byte[1024]);
        return out.toByteArray();
    }

    private static void writeHeader(ByteArrayOutputStream out, String name, char type, long size,
            long mode, String linkName) throws IOException {
        byte[] header = new byte[512];
        putString(header, 0, 100, name);
        putOctal(header, 100, 8, mode);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, size);
        putOctal(header, 136, 12, 0);
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = (byte) type;
        putString(header, 157, 100, linkName == null ? "" : linkName);
        putString(header, 257, 6, "ustar");
        header[263] = '0';
        header[264] = '0';
        putString(header, 265, 32, "fixture");
        putString(header, 297, 32, "fixture");
        putOctal(header, 148, 8, checksum(header));
        out.write(header);
    }

    private static void writePayload(ByteArrayOutputStream out, byte[] content) throws IOException {
        out.write(content);
        int pad = (512 - (content.length % 512)) % 512;
        for (int i = 0; i < pad; i++) {
            out.write(0);
        }
    }

    private static void putString(byte[] header, int offset, int maxLength, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxLength) {
            throw new IllegalArgumentException("fixture value longer than the header field");
        }
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    private static void putOctal(byte[] header, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        int digits = length - 1;
        if (octal.length() > digits) {
            throw new IllegalArgumentException("fixture number does not fit the field");
        }
        for (int i = 0; i < digits; i++) {
            header[offset + i] = (byte) (i < digits - octal.length() ? '0' : octal.charAt(
                    i - (digits - octal.length())));
        }
        header[offset + length - 1] = 0;
    }

    private static long checksum(byte[] header) {
        long sum = 0;
        for (int i = 0; i < 512; i++) {
            sum += (i >= 148 && i < 156) ? 0x20 : (header[i] & 0xFF);
        }
        return sum;
    }

    private static byte[] gzipTar(TarSpec... specs) throws IOException {
        return gzipTar(List.of(specs));
    }

    // --- state 1: the flag gate ------------------------------------------------------------

    @Test
    public void aDisabledFlagAnswersDisabledWithZeroTransportCallsAndZeroFilesystemTouches()
            throws Exception {
        FakeTransport transport = new FakeTransport();
        Path filesDir = tempFolder.getRoot().toPath().resolve("never-created");

        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.provision(
                transport, entryFor(MiniRootfsFixture.gzipTar()), filesDir, false);

        assertFalse(receipt.enabled());
        assertFalse(receipt.alreadyVerified());
        assertFalse(receipt.injectedLauncher());
        assertEquals(null, receipt.fileName());
        assertEquals(null, receipt.file());
        assertEquals(0L, receipt.sizeBytes());
        assertEquals(null, receipt.sha256());
        assertEquals(null, receipt.extractedDir());
        assertEquals(0, receipt.extractedEntries());
        assertEquals(0, receipt.executableEntries());
        assertTrue("no transport call may happen while the gate is closed",
                transport.requestedUrls.isEmpty());
        assertFalse("the disabled answer must not even create the lane's directory",
                Files.exists(filesDir));
    }

    @Test
    public void theDisabledReceiptCarriesNothingByConstruction() {
        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.ProvisionReceipt.disabled();
        assertFalse(receipt.enabled());
        assertFalse(receipt.injectedLauncher());
        assertThrows(IllegalArgumentException.class,
                () -> new UmlRootfsProvisioner.ProvisionReceipt(false, false,
                        "name", null, 0L, null, null, 0, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new UmlRootfsProvisioner.ProvisionReceipt(false, false,
                        null, null, 0L, null, null, 0, 0, true));
    }

    // --- state 2: verified download + extraction ------------------------------------------

    @Test
    public void aVerifiedDownloadLandsThePinnedTarAndExtractsTheTree() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        Entry entry = entryFor(transport.payload);
        Path filesDir = tempFolder.newFolder("files").toPath();

        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.provision(
                transport, entry, filesDir, true);

        assertTrue(receipt.enabled());
        assertFalse(receipt.alreadyVerified());
        assertEquals(FILE_NAME, receipt.fileName());
        assertEquals(umRootfsDir(filesDir).resolve(FILE_NAME), receipt.file());
        assertEquals((long) transport.payload.length, receipt.sizeBytes());
        assertEquals(entry.sha256Pin(), receipt.sha256());
        Path extractDir = umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME);
        assertEquals(extractDir, receipt.extractedDir());
        // 17 explicit top-level dirs + 5 regular files; the archive root ("./")
        // is the extract dir itself and is not counted.
        assertEquals(22, receipt.extractedEntries());
        assertEquals(0, receipt.executableEntries());
        assertArrayEquals(transport.payload, Files.readAllBytes(receipt.file()));
        assertEquals(MiniRootfsFixture.VERSION,
                new String(Files.readAllBytes(extractDir.resolve("etc/alpine-release")),
                        StandardCharsets.UTF_8).trim());
        assertTrue("the sidecar attestation must exist beside the extracted tree",
                Files.isRegularFile(umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME + ".verified")));
        assertTrue(new String(Files.readAllBytes(umRootfsDir(filesDir).resolve(
                EXTRACT_DIR_NAME + ".verified")), StandardCharsets.UTF_8)
                .startsWith("pin=" + entry.sha256Pin()));
        assertEquals(List.of(EXTRACT_DIR_NAME, FILE_NAME, EXTRACT_DIR_NAME + ".verified"),
                listing(umRootfsDir(filesDir)));
    }

    @Test
    public void aStaleExtractionStagingDirIsSweptByASuccessfulProvision() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        Path filesDir = tempFolder.newFolder("files").toPath();
        Path junk = umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME + ".part");
        Files.createDirectories(junk.resolve("leftover"));

        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true);

        assertTrue(receipt.enabled());
        assertFalse("the swept staging name must not survive a successful provision",
                Files.exists(junk));
        assertTrue(Files.isDirectory(umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME)));
    }

    @Test
    public void execBitsArePreservedFromTheTarModeField() throws Exception {
        // Extractor-level: this custom fixture has no apk database etc., so it
        // would be rejected by the transport's L3 chain before extraction —
        // the exec-bit contract is the extractor's, tested directly.
        Path filesDir = tempFolder.newFolder("exec-bits").toPath();
        byte[] tar = gzipTar(
                TarSpec.dir("bin/"),
                TarSpec.fileMode("bin/run.sh", 0755L, "#!/bin/sh\n"),
                TarSpec.fileMode("bin/tool", 0700L, "elf"),
                TarSpec.file("etc/plain.conf", "plain"));

        UmlRootfsProvisioner.ExtractResult result = extractIn(filesDir, tar);

        Path extractDir = filesDir.resolve("extracted");
        assertEquals(4, result.entries());
        assertEquals("both 0755 and 0700 carry an execute bit; 0644 does not",
                2, result.executables());
        if (posixPermissionsSupported()) {
            Set<java.nio.file.attribute.PosixFilePermission> perms = Files.getPosixFilePermissions(
                    extractDir.resolve("bin/run.sh"));
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
            Set<java.nio.file.attribute.PosixFilePermission> plainPerms = Files.getPosixFilePermissions(
                    extractDir.resolve("etc/plain.conf"));
            assertFalse(plainPerms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    public void fileModesRideTheExtractionTooSoGuestNonRootUsersKeepAccess() throws Exception {
        // The device finding's other half: regular files landed 0600 (tar 0644
        // unapplied) — hostfs presents that to the guest, so distccd's
        // non-root uid had zero access to every tree file. Tar 0644 must come
        // out readable-by-others and tar 0755 executable-by-others.
        Path filesDir = tempFolder.newFolder("file-modes").toPath();
        extractIn(filesDir, gzipTar(
                TarSpec.file("etc/plain.conf", "content"),
                TarSpec.file("bin/tool", "elf")));
        Path extracted = filesDir.resolve("extracted");
        assumeTrue("Windows dev host cannot assert POSIX modes; CI/Linux does",
                posixPermissionsSupported());
        Set<java.nio.file.attribute.PosixFilePermission> plain = Files.getPosixFilePermissions(
                extracted.resolve("etc/plain.conf"));
        assertTrue("a tar 0644 file must stay world-readable for guest non-root users",
                plain.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
        Set<java.nio.file.attribute.PosixFilePermission> tool = Files.getPosixFilePermissions(
                extracted.resolve("bin/tool"));
        assertTrue("a tar 0755 file must stay world-executable for guest non-root users",
                tool.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE));
    }

    @Test
    public void directoryModesRideTheExtractionForGuestDaemons() throws Exception {
        // Device-measured 2026-11-14 (Gate 2 E2E): createDirectories left the
        // Alpine tree's world-writable /tmp (tar 1777) at the umask's 0700,
        // and distccd — dropping to its own uid before opening its log — hit
        // hostfs EACCES on every path it needs to write. The extractor now
        // applies a directory entry's tar rwx bits (best-effort, POSIX view).
        Path filesDir = tempFolder.newFolder("dir-modes").toPath();
        UmlRootfsProvisioner.ExtractResult result = extractIn(filesDir, gzipTar(
                TarSpec.dirMode("tmp/", 01777L),
                TarSpec.file("tmp/probe", "x")));
        Path tmp = filesDir.resolve("extracted").resolve("tmp");
        assertTrue(Files.isDirectory(tmp));
        assertEquals(2, result.entries());
        assumeTrue("Windows dev host cannot assert POSIX modes; CI/Linux does",
                posixPermissionsSupported());
        Set<java.nio.file.attribute.PosixFilePermission> perms = Files.getPosixFilePermissions(tmp);
        assertTrue("a tar 1777 directory must be world-writable for guest daemons",
                perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE));
        assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE));
    }

    @Test
    public void theArchiveRootDirModeRidesTheExtractionSoGuestNonRootUsersCanTraverse() throws Exception {
        // Device-measured 2026-11-14 (Lane A E2E): the extractor skipped the
        // tar's archive-root entry ("./"), so the extract dir kept the host
        // umask's 0700 — hostfs presented that mode to the guest and distccd's
        // dropped uid could not even LOOK UP /tmp/distccd.log (strace:
        // openat("/tmp/distccd.log", O_WRONLY|O_CREAT|O_APPEND) = -1 EACCES
        // with /tmp itself 0777). The root entry's tar mode rides like every
        // other entry's.
        Path filesDir = tempFolder.newFolder("root-mode").toPath();
        extractIn(filesDir, gzipTar(
                TarSpec.dirMode("./", 0755L),
                TarSpec.file("bin/tool", "elf")));
        Path extractDir = filesDir.resolve("extracted");
        assertTrue(Files.isDirectory(extractDir.resolve("bin")));
        assumeTrue("Windows dev host cannot assert POSIX modes; CI/Linux does",
                posixPermissionsSupported());
        Set<java.nio.file.attribute.PosixFilePermission> root =
                Files.getPosixFilePermissions(extractDir);
        assertTrue("the guest's / must be other-traversable (the 0700-root EACCES lesson)",
                root.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE)
                        && root.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
    }

    @Test
    public void theProvisionRepairsALegacyExtractTreeWhoseRootIsUntraversable() throws Exception {
        // Trees extracted before the root-mode fix carry the umask's 0700
        // root, and their sidecar still attests the pin — the idempotent
        // provision never re-extracts, so the repair stage is what makes
        // existing device trees provision-safe again.
        Path filesDir = tempFolder.newFolder("root-repair").toPath();
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        UmlRootfsProvisioner.provision(transport, entryFor(transport.payload), filesDir, true);
        Path extractDir = umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME);
        assumeTrue("Windows dev host cannot assert POSIX modes; CI/Linux does",
                posixPermissionsSupported());
        Files.setPosixFilePermissions(extractDir, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true);
        assertTrue(receipt.alreadyVerified());
        Set<java.nio.file.attribute.PosixFilePermission> root =
                Files.getPosixFilePermissions(extractDir);
        assertTrue("the repaired root must be other-traversable again",
                root.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE)
                        && root.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
    }

    @Test
    public void gnuLongNamesAndPaxPathOverridesLandAtTheirRealPaths() throws Exception {
        String longName = "usr/share/deeply/nested/" + "segment-".repeat(12)
                + "final-name-exceeding-ustar-hundred-byte-field.txt";
        assertTrue(longName.length() > 100);
        Path filesDir = tempFolder.newFolder("long-names").toPath();
        byte[] tar = gzipTar(
                TarSpec.dir("usr/"),
                TarSpec.dir("usr/share/"),
                TarSpec.dir("usr/share/deeply/"),
                TarSpec.dir("usr/share/deeply/nested/"),
                TarSpec.dir("usr/share/deeply/nested/" + "segment-".repeat(11)),
                TarSpec.file(longName, "long-name-content"),
                TarSpec.paxPath("opt/pax-overridden-name.txt"),
                TarSpec.file("opt/pax-overridden-name.txt".substring(0, 20), "pax-content"));

        UmlRootfsProvisioner.ExtractResult result = extractIn(filesDir, tar);

        Path extractDir = filesDir.resolve("extracted");
        assertTrue(Files.isDirectory(extractDir));
        assertEquals("long-name-content", new String(
                Files.readAllBytes(extractDir.resolve(longName)), StandardCharsets.UTF_8));
        assertEquals("pax-content", new String(
                Files.readAllBytes(extractDir.resolve("opt/pax-overridden-name.txt")),
                StandardCharsets.UTF_8));
        assertEquals(7, result.entries());
    }

    @Test
    public void symlinksAreMaterializedVerbatimNeverDereferenced() throws Exception {
        Path probe = tempFolder.newFolder("probe").toPath();
        assumeTrue("host cannot create symlinks; the device (Linux) always can",
                symlinksSupported(probe));
        FakeTransport transport = new FakeTransport();
        transport.payload = gzipTar(
                TarSpec.dir("bin/"),
                TarSpec.file("bin/busybox", "elf"),
                TarSpec.symlink("bin/sh", "/bin/busybox"));
        Path filesDir = tempFolder.newFolder("files").toPath();

        // The extractor-level contract, through the same direct seam the slip
        // matrix uses. The provision-level symlink integration is covered on
        // the device window: the real CDN minirootfs carries 335 symlinks and
        // extracted 519 items — the full chain's L3 identity stages demand the
        // official tarball's identity files (etc/alpine-release, etc/hostname,
        // the apk database), which a minimal fixture cannot carry without
        // becoming the official artifact (CI 2026-11-13: this test through the
        // full provision chain failed MANIFEST_FIELDS on Linux for exactly
        // that reason).
        UmlRootfsProvisioner.ExtractResult result = extractIn(filesDir, transport.payload);

        Path link = filesDir.resolve("extracted").resolve("bin/sh");
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("/bin/busybox", Files.readSymbolicLink(link).toString());
        assertEquals(3, result.entries());
    }

    // --- state 3: transport failure ---------------------------------------------------------

    @Test
    public void aTransportFailurePropagatesAndLeavesNoHalfProducts() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.failure = new IOException("network dead");
        transport.payload = officialShapeWithoutSymlink();
        Path filesDir = tempFolder.newFolder("files").toPath();

        IOException failure = assertThrows(IOException.class, () -> UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true));

        assertEquals("network dead", failure.getMessage());
        assertEquals(1, transport.requestedUrls.size());
        assertEquals(List.of(), listing(umRootfsDir(filesDir)));
    }

    // --- state 4: checksum mismatch ---------------------------------------------------------

    @Test
    public void aChecksumMismatchFailsClosedAndLeavesNoHalfProducts() throws Exception {
        byte[] pinned = officialShapeWithoutSymlink();
        // Same byte count, different content — the L1 size gate must pass so
        // the L2 pin compare is what catches this.
        byte[] tampered = pinned.clone();
        tampered[tampered.length / 2] ^= 0x55;
        FakeTransport transport = new FakeTransport();
        transport.payload = tampered;
        Path filesDir = tempFolder.newFolder("files").toPath();

        RootfsRejectionException failure = assertThrows(RootfsRejectionException.class,
                () -> UmlRootfsProvisioner.provision(transport, entryFor(pinned), filesDir, true));

        assertEquals(UmlKernelAssets.RootfsDownload.REASON_CHECKSUM_MISMATCH, failure.reason());
        assertEquals(List.of(), listing(umRootfsDir(filesDir)));
    }

    // --- the double gate: a slip tar is rejected by the verification chain first ------------

    @Test
    public void aTarWithParentSegmentEntriesIsRejectedByTheVerificationChainBeforeExtraction()
            throws Exception {
        List<MiniRootfsFixture.Spec> attack = new ArrayList<>(MiniRootfsFixture.officialShape());
        attack.add(new MiniRootfsFixture.Spec("../evil", '0', "escaped".getBytes(
                StandardCharsets.UTF_8)));
        FakeTransport transport = new FakeTransport();
        transport.payload = MiniRootfsFixture.gzipTar(attack);
        Path filesDir = tempFolder.newFolder("files").toPath();

        IOException failure = assertThrows(IOException.class, () -> UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true));

        assertTrue(failure instanceof RootfsRejectionException);
        assertEquals(UmlKernelAssets.RootfsDownload.REASON_SELF_CHECK_FAILED,
                ((RootfsRejectionException) failure).reason());
        assertEquals(List.of(), listing(umRootfsDir(filesDir)));
        assertTrue("the pinned artifact must never publish for a rejected tar",
                !Files.exists(umRootfsDir(filesDir).resolve(FILE_NAME)));
    }

    // --- idempotency: pin-sha reconciliation -------------------------------------------------

    @Test
    public void anAlreadyVerifiedProductAnswersWithoutAnyTransportCallOrReExtraction()
            throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.provision(transport, entryFor(transport.payload), filesDir, true);
        transport.requestedUrls.clear();

        UmlRootfsProvisioner.ProvisionReceipt second = UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true);

        assertTrue(second.enabled());
        assertTrue(second.alreadyVerified());
        assertTrue("the idempotent hit must not touch the network",
                transport.requestedUrls.isEmpty());
        assertEquals(22, second.extractedEntries());
        assertTrue(Files.isDirectory(second.extractedDir()));
    }

    @Test
    public void aMissingExtractionRebuildsFromTheExistingPinnedTarWithoutDownloading()
            throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.provision(transport, entryFor(transport.payload), filesDir, true);
        transport.requestedUrls.clear();
        deleteTree(umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME));
        deleteTree(umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME + ".verified"));

        UmlRootfsProvisioner.ProvisionReceipt second = UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true);

        assertTrue(second.enabled());
        assertFalse(second.alreadyVerified());
        assertTrue("the pinned tar reconciles — no network may happen",
                transport.requestedUrls.isEmpty());
        assertEquals(22, second.extractedEntries());
        assertTrue(Files.isDirectory(second.extractedDir()));
    }

    @Test
    public void aMissingSidecarReattestsByReExtractingThePinnedTarWithoutDownloading()
            throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.provision(transport, entryFor(transport.payload), filesDir, true);
        transport.requestedUrls.clear();
        deleteTree(umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME + ".verified"));

        UmlRootfsProvisioner.ProvisionReceipt second = UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true);

        assertTrue(second.enabled());
        assertFalse(second.alreadyVerified());
        assertTrue(transport.requestedUrls.isEmpty());
        assertEquals(22, second.extractedEntries());
        assertTrue(new String(Files.readAllBytes(umRootfsDir(filesDir).resolve(
                EXTRACT_DIR_NAME + ".verified")), StandardCharsets.UTF_8).startsWith("pin="));
    }

    @Test
    public void aCorruptArtifactFailsPinReconciliationAndIsReplacedByAFreshVerifiedDownload()
            throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.provision(transport, entryFor(transport.payload), filesDir, true);
        transport.requestedUrls.clear();
        Files.write(umRootfsDir(filesDir).resolve(FILE_NAME),
                "corrupt bytes".getBytes(StandardCharsets.UTF_8));

        UmlRootfsProvisioner.ProvisionReceipt second = UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true);

        assertTrue("the pin compare must fail for the corrupt artifact and re-download",
                !transport.requestedUrls.isEmpty());
        assertFalse(second.alreadyVerified());
        assertArrayEquals(transport.payload, Files.readAllBytes(second.file()));
        assertEquals(22, second.extractedEntries());
    }

    // --- the external-root launcher: /um-command.sh injection (ACR §8 candidate A) -----------

    /** The extract tree's launcher path for the standard fixture's extract dir. */
    private static Path launcherPath(Path filesDir) {
        return umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME)
                .resolve(UmlRootfsProvisioner.LAUNCHER_FILE_NAME);
    }

    @Test
    public void theOwnedLauncherKeepsTheResearchProbeContract() {
        String launcher = UmlRootfsProvisioner.UM_COMMAND_LAUNCHER;
        assertEquals("the first line is the contract shebang",
                "#!/bin/busybox sh", launcher.split("\n", -1)[0]);
        assertTrue("a POSIX script ends with a newline", launcher.endsWith("\n"));
        assertTrue("the guarded-flags discipline is kept", launcher.contains("set -u"));
        assertTrue("the U1_INFO marker is the research contract's",
                launcher.contains("printf 'U1_INFO kernel=%s ncpus=%s\\n'"));
        assertTrue("the U1_T2_READY marker is the research contract's",
                launcher.contains("printf 'U1_T2_READY uptime=%s\\n'"));
        assertTrue("the U1_READY marker is the research contract's",
                launcher.contains("printf 'U1_READY uptime=%s\\n'"));
        assertTrue("the U1_CMD_ERROR marker is the research contract's",
                launcher.contains("printf 'U1_CMD_ERROR cmd=busybox_true\\n' >&2"));
        assertTrue("the T3 probe runs a real binary (busybox true), not a builtin",
                launcher.contains("if /bin/busybox true; then"));
        assertTrue("the um.shell test-interface branch is kept",
                launcher.contains("um.shell"));
        assertTrue("the um.hold_ms batch-hold branch is kept",
                launcher.contains("um.hold_ms"));
        assertTrue("the provenance header names the injection owner",
                launcher.contains("# External-root edition"));
    }

    @Test
    public void theOwnedLauncherV2AddsTheGuestNetworkBootBranchUnderTheUmNetKnob() {
        String launcher = UmlRootfsProvisioner.UM_COMMAND_LAUNCHER;
        // The v2 guard reads the knob the same way um.shell does (the ruling's
        // param style), only around the boot branch.
        int guard = launcher.indexOf("if [ \"$(param um.net || :)\" = 1 ]; then");
        assertTrue("the um.net=1 guard is the branch's only entry", guard >= 0);
        int linkUp = launcher.indexOf("ip link set vec0 up 2>/dev/null || :");
        int dhcp = launcher.indexOf("udhcpc -i vec0 -q 2>/dev/null");
        int apkAdd = launcher.indexOf("apk add distcc 2>/dev/null");
        int daemon = launcher.indexOf(
                "distccd --daemon --user hostfs --listen 0.0.0.0 --port 3632 "
                        + "--allow 10.0.2.0/24 --log-file /tmp/distccd.log");
        assertTrue("the branch brings vec0 up before the lease", linkUp > guard);
        assertTrue("the lease runs right after the link is up", dhcp > linkUp);
        assertTrue("distccd installs from the CDN through passt", apkAdd > dhcp);
        assertTrue("the daemon binds 0.0.0.0:3632 for the passt forward to 10.0.2.0/24",
                daemon > apkAdd);
        assertTrue("the DHCP failure is an honest named marker",
                launcher.contains("UM_NET_ERROR step=dhcp detail=udhcpc failed on vec0"));
        assertTrue("the apk failure is an honest named marker",
                launcher.contains("UM_NET_ERROR step=apk detail=apk add distcc failed"));
        assertTrue("the daemon failure is an honest named marker with captured diagnostics",
                launcher.contains("UM_NET_ERROR step=distccd detail=rc=")
                        && launcher.contains("$(tail -1 /tmp/distccd.err 2>/dev/null)"));
        assertTrue("the daemon start drops to the hostfs-owner-mapped uid (the Lane A reopen-EACCES lesson)",
                launcher.contains("--user hostfs") && launcher.contains("--log-file /tmp/distccd.log"));
        assertTrue("the hostfs owner ids come from the tree itself (stat), never restated",
                launcher.contains("stat -c '%u' /um-command.sh")
                        && launcher.contains("stat -c '%g' /um-command.sh"));
        assertTrue("the guest passwd gains the mapped entry once and idempotently",
                launcher.contains("grep -q '^hostfs:' /etc/passwd")
                        && launcher.contains("hostfs-owner-mapped"));
        assertTrue("the listening line states the mapped uid for the device window",
                launcher.contains("UM_NET distccd listening port=3632 user=hostfs uid="));
        assertTrue("the missing-binary cascade is an honest named marker",
                launcher.contains("UM_NET_ERROR step=distccd detail=distccd binary missing"));
        assertTrue("a bound lease is announced with the guest address",
                launcher.contains("UM_NET dhcp bound ip="));
        assertTrue("the resident milestone closes the branch's boot story",
                launcher.contains("UM_NET resident: wired face holding"));
        // Residency: exactly one added sleep-forever loop, inside the branch —
        // the branch never falls through to the v1 poweroff path.
        assertEquals("exactly three sleep-forever loops: T3-fail, the v2 branch, the v1 tail",
                3, launcher.split("while :; do sleep 3600; done", -1).length - 1);
        assertTrue("the branch's resident loop precedes the v1 tail (mutually exclusive paths)",
                launcher.indexOf("UM_NET resident") < launcher.indexOf(V1_HOLD_POWEROFF_TAIL));
        // The wired branch holds resident: between the um.net guard and the v1
        // tail no poweroff exists — the branch's only exit is its own
        // sleep-forever loop, never a probe poweroff.
        String branch = launcher.substring(guard, launcher.indexOf(V1_HOLD_POWEROFF_TAIL));
        assertFalse("the wired branch never powers the guest off", branch.contains("poweroff"));
    }

    /**
     * The v1 tail, byte for byte: under the default (no um.net) the launcher's
     * hold_ms → sync → poweroff path is the research original, untouched — the
     * v2 branch is strictly an addition before it.
     */
    private static final String V1_HOLD_POWEROFF_TAIL = """
            if [ "$hold_ms" -ge 1000 ]; then
                sleep $((hold_ms / 1000)) 2>/dev/null || sleep 1
            else
                sleep "0.$(printf '%03d' "$hold_ms")" 2>/dev/null || sleep 1
            fi
            sync 2>/dev/null || :
            poweroff -f 2>/dev/null || :
            while :; do sleep 3600; done
            """;

    @Test
    public void theOwnedLauncherKeepsTheV1ProbePathVerbatimWhenUmNetIsAbsent() {
        String launcher = UmlRootfsProvisioner.UM_COMMAND_LAUNCHER;
        // The T3 probe contract (busybox true → U1_READY) is untouched and
        // still precedes everything the v2 branch adds.
        int ready = launcher.indexOf("printf 'U1_READY uptime=%s");
        int guard = launcher.indexOf("param um.net");
        int tail = launcher.indexOf(V1_HOLD_POWEROFF_TAIL);
        assertTrue(ready >= 0);
        assertTrue(guard > ready);
        assertTrue("the v1 hold→poweroff tail survives byte for byte", tail > guard);
        // The original research body (up to and including U1_READY) is still
        // verbatim: every line below the provenance header minus the branch.
        assertTrue(launcher.contains("PATH=/bin:/sbin:/usr/bin:/usr/sbin"));
        assertTrue(launcher.contains("hold_ms=$(param um.hold_ms || printf 200)"));
        assertTrue(launcher.contains("ncpus=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null || :)"));
        assertTrue(launcher.contains("if [ \"$(param um.shell || :)" + "\" = 1 ]; then"));
        assertTrue(launcher.contains("exec /bin/sh"));
        assertTrue(launcher.contains("if /bin/busybox true; then"));
        assertTrue(launcher.contains("printf 'U1_CMD_ERROR cmd=busybox_true\\n' >&2"));
        // The final poweroff stays the DEFAULT exit (the probe still powers
        // off after hold_ms when um.net is absent).
        assertTrue("the tail still ends in sync→poweroff, the v1 default exit",
                tail == launcher.lastIndexOf("poweroff -f 2>/dev/null || :\n            while")
                        || launcher.regionMatches(
                                tail + V1_HOLD_POWEROFF_TAIL.length() - "while :; do sleep 3600; done".length() - 1,
                                "while :; do sleep 3600; done", 0,
                                "while :; do sleep 3600; done".length()));
    }

    @Test
    public void aV1ShapedLauncherTreeMigratesToTheOwnedV2ContentOnReProvision() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        Path filesDir = tempFolder.newFolder("files").toPath();
        // A v1-shaped tree: the launcher carries a pre-v2 provenance header (no
        // v2 note, no um.net branch) and therefore a digest the owned constant
        // no longer matches. The exact v1 bytes live only in git history
        // (8581c77f); what migrates a tree is the digest mismatch itself — the
        // same repair the real v1 tree takes on its first re-provision.
        String v1Shaped = "#!/bin/busybox sh\n"
                + "# External-root edition — injected into the extracted minirootfs tree by\n"
                + "# UmlRootfsProvisioner (v1: probe contract only, hold_ms to poweroff).\n"
                + "set -u\n";
        UmlRootfsProvisioner.provision(transport, entryFor(transport.payload), filesDir, true);
        Files.write(launcherPath(filesDir), v1Shaped.getBytes(StandardCharsets.UTF_8));
        transport.requestedUrls.clear();

        UmlRootfsProvisioner.ProvisionReceipt second = UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true);

        assertTrue("the repair ran over the same attested extraction — no network",
                second.alreadyVerified());
        assertTrue(second.injectedLauncher());
        assertTrue(transport.requestedUrls.isEmpty());
        assertEquals("the owned v2 content replaces the v1 bytes, byte for byte",
                UmlRootfsProvisioner.UM_COMMAND_LAUNCHER,
                new String(Files.readAllBytes(launcherPath(filesDir)), StandardCharsets.UTF_8));
        assertTrue("the migrated launcher carries the v2 network boot branch",
                new String(Files.readAllBytes(launcherPath(filesDir)), StandardCharsets.UTF_8)
                        .contains("param um.net"));
        assertTrue("the migrated launcher satisfies the init's `-x` contract",
                Files.isExecutable(launcherPath(filesDir)));
    }

    @Test
    public void aFreshProvisionInjectsTheOwnedLauncherExecutableWithTheContractShebang()
            throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        Path filesDir = tempFolder.newFolder("files").toPath();

        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true);

        Path launcher = launcherPath(filesDir);
        assertTrue(receipt.enabled());
        assertTrue("the enabled receipt attests the injected launcher",
                receipt.injectedLauncher());
        assertTrue("the launcher lands at the extract root (the /um-command.sh contract)",
                Files.isRegularFile(launcher));
        assertEquals("the launcher is the owned constant, byte for byte",
                UmlRootfsProvisioner.UM_COMMAND_LAUNCHER,
                new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8));
        assertTrue("the init's `-x /um-command.sh` contract",
                Files.isExecutable(launcher));
        if (posixPermissionsSupported()) {
            assertEquals("0755 by construction: owner rwx, group/other r-x",
                    java.util.EnumSet.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                            java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                            java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                            java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                            java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE),
                    Files.getPosixFilePermissions(launcher));
        }
        assertFalse("the .part staging never survives a successful injection",
                Files.exists(umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME)
                        .resolve(UmlRootfsProvisioner.LAUNCHER_FILE_NAME + ".part")));
        assertEquals("the launcher is not a tar entry — the extraction counts are untouched",
                22, receipt.extractedEntries());
    }

    @Test
    public void aRepeatedProvisionNeverRewritesTheOwnedLauncher() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.provision(transport, entryFor(transport.payload), filesDir, true);
        transport.requestedUrls.clear();
        Path launcher = launcherPath(filesDir);
        // Freeze the mtime at a known past instant: any rewrite would move it.
        java.nio.file.attribute.FileTime frozen =
                java.nio.file.attribute.FileTime.fromMillis(1000L);
        Files.setLastModifiedTime(launcher, frozen);

        UmlRootfsProvisioner.ProvisionReceipt second = UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true);

        assertTrue(second.enabled());
        assertTrue(second.alreadyVerified());
        assertTrue("the idempotent hit still attests the owned launcher",
                second.injectedLauncher());
        assertTrue("a digest-matching launcher is skipped, never rewritten (mtime frozen)",
                Files.getLastModifiedTime(launcher).equals(frozen));
        assertEquals("and its bytes stay the owned constant",
                UmlRootfsProvisioner.UM_COMMAND_LAUNCHER,
                new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8));
        assertTrue("no network for an idempotent hit",
                transport.requestedUrls.isEmpty());
        assertFalse("no .part staging may survive a skipped injection",
                Files.exists(umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME)
                        .resolve(UmlRootfsProvisioner.LAUNCHER_FILE_NAME + ".part")));
    }

    @Test
    public void aTamperedLauncherIsRepairedToTheOwnedContentOnReProvision() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.provision(transport, entryFor(transport.payload), filesDir, true);
        transport.requestedUrls.clear();
        Path launcher = launcherPath(filesDir);
        byte[] tampered = "#!/bin/sh\necho planted\n".getBytes(StandardCharsets.UTF_8);
        Files.write(launcher, tampered);

        UmlRootfsProvisioner.ProvisionReceipt second = UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true);

        assertTrue("the repair ran over the same attested extraction — no network, no re-extract",
                second.alreadyVerified());
        assertTrue(second.injectedLauncher());
        assertTrue("no network for a repair over a reconciled pin",
                transport.requestedUrls.isEmpty());
        assertEquals("the owned content is restored, byte for byte — this class owns the name",
                UmlRootfsProvisioner.UM_COMMAND_LAUNCHER,
                new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8));
        assertTrue("the restored launcher satisfies the init's `-x` contract",
                Files.isExecutable(launcher));
    }

    @Test
    public void aDeletedLauncherIsReprovisionedWithoutAnyNetworkCall() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.provision(transport, entryFor(transport.payload), filesDir, true);
        transport.requestedUrls.clear();
        Files.delete(launcherPath(filesDir));

        UmlRootfsProvisioner.ProvisionReceipt second = UmlRootfsProvisioner.provision(
                transport, entryFor(transport.payload), filesDir, true);

        assertTrue(second.enabled());
        assertTrue(second.injectedLauncher());
        assertTrue(transport.requestedUrls.isEmpty());
        assertTrue(Files.isExecutable(launcherPath(filesDir)));
        assertEquals(UmlRootfsProvisioner.UM_COMMAND_LAUNCHER,
                new String(Files.readAllBytes(launcherPath(filesDir)), StandardCharsets.UTF_8));
    }

    @Test
    public void anExtractionFailureInjectsNoLauncherAndLeavesNoTree() throws Exception {
        // A sha-consistent tar carrying a duplicate entry name passes the
        // transport chain (L1 size, L2 pin, L3 identity) and dies in the
        // extractor's slip defenses — no tree, no launcher, no receipt.
        List<MiniRootfsFixture.Spec> specs = new ArrayList<>(MiniRootfsFixture.officialShape());
        specs.removeIf(spec -> spec.type() == '2');
        specs.add(new MiniRootfsFixture.Spec("./etc/hostname", '0', "duplicate\n"));
        byte[] payload = MiniRootfsFixture.gzipTar(specs);
        FakeTransport transport = new FakeTransport();
        transport.payload = payload;
        Path filesDir = tempFolder.newFolder("files").toPath();

        UmlRootfsProvisioner.ExtractionRejectionException failure =
                assertThrows(UmlRootfsProvisioner.ExtractionRejectionException.class,
                        () -> UmlRootfsProvisioner.provision(
                                transport, entryFor(payload), filesDir, true));

        assertEquals(UmlRootfsProvisioner.REASON_DUPLICATE_ENTRY, failure.reason());
        assertEquals("only the published tar may exist — no extract tree, no launcher",
                List.of(FILE_NAME), listing(umRootfsDir(filesDir)));
        assertFalse(Files.exists(umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME)));
    }

    @Test
    public void aLauncherInjectionFailurePropagatesFailClosedAndSweepsItsStaging()
            throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.payload = officialShapeWithoutSymlink();
        Path filesDir = tempFolder.newFolder("files").toPath();
        UmlRootfsProvisioner.provision(transport, entryFor(transport.payload), filesDir, true);
        transport.requestedUrls.clear();
        // A planted non-empty directory occupying the launcher's name: the
        // atomic move cannot replace it, so the write path must fail closed,
        // sweep its .part staging, and never answer ready.
        Path extractDir = umRootfsDir(filesDir).resolve(EXTRACT_DIR_NAME);
        Path planted = extractDir.resolve(UmlRootfsProvisioner.LAUNCHER_FILE_NAME);
        Files.delete(planted);
        Files.createDirectories(planted.resolve("planted-child"));

        IOException failure = assertThrows(IOException.class,
                () -> UmlRootfsProvisioner.provision(
                        transport, entryFor(transport.payload), filesDir, true));

        assertTrue("the failure carries the stable injection reason",
                failure instanceof UmlRootfsProvisioner.ExtractionRejectionException);
        assertEquals(UmlRootfsProvisioner.REASON_LAUNCHER_INJECTION_FAILED,
                ((UmlRootfsProvisioner.ExtractionRejectionException) failure).reason());
        assertTrue("the planted block is not our data to delete — it stays",
                Files.isDirectory(planted));
        assertFalse("the .part staging is swept on the failure path",
                Files.exists(extractDir.resolve(
                        UmlRootfsProvisioner.LAUNCHER_FILE_NAME + ".part")));
        assertTrue("the attested extract tree itself is never destroyed by a failed injection",
                Files.isRegularFile(umRootfsDir(filesDir)
                        .resolve(EXTRACT_DIR_NAME + ".verified")));
    }

    // --- the extractor's slip-defense matrix (direct stage tests) ----------------------------

    private UmlRootfsProvisioner.ExtractResult extractIn(Path filesDir, byte[] tar)
            throws Exception {
        Path tarPath = filesDir.resolve("fixture.tar.gz");
        Files.write(tarPath, tar);
        Path extractDir = filesDir.resolve("extracted");
        return UmlRootfsProvisioner.extractVerifiedTar(tarPath, extractDir,
                MiniRootfsFixture.sha256Hex(tar));
    }

    @Test
    public void anAbsoluteEntryNameIsRejectedAndSwept() throws Exception {
        Path filesDir = tempFolder.newFolder("slip-absolute").toPath();
        byte[] tar = gzipTar(TarSpec.file("/etc/evil", "escaped"));

        UmlRootfsProvisioner.ExtractionRejectionException failure =
                assertThrows(UmlRootfsProvisioner.ExtractionRejectionException.class,
                        () -> extractIn(filesDir, tar));

        assertEquals(UmlRootfsProvisioner.REASON_ENTRY_ESCAPES_EXTRACT_ROOT, failure.reason());
        assertEquals(List.of("fixture.tar.gz"), listing(filesDir));
    }

    @Test
    public void aParentSegmentEntryIsRejectedAndSwept() throws Exception {
        Path filesDir = tempFolder.newFolder("slip-dotdot").toPath();
        byte[] tar = gzipTar(
                TarSpec.dir("good/"),
                TarSpec.file("good/../../evil", "escaped"));

        UmlRootfsProvisioner.ExtractionRejectionException failure =
                assertThrows(UmlRootfsProvisioner.ExtractionRejectionException.class,
                        () -> extractIn(filesDir, tar));

        assertEquals(UmlRootfsProvisioner.REASON_ENTRY_ESCAPES_EXTRACT_ROOT, failure.reason());
        assertEquals(List.of("fixture.tar.gz"), listing(filesDir));
    }

    @Test
    public void aDuplicateEntryNameIsRejectedAndSwept() throws Exception {
        Path filesDir = tempFolder.newFolder("slip-dup").toPath();
        byte[] tar = gzipTar(
                TarSpec.file("etc/conf", "first"),
                TarSpec.file("etc/conf", "second"));

        UmlRootfsProvisioner.ExtractionRejectionException failure =
                assertThrows(UmlRootfsProvisioner.ExtractionRejectionException.class,
                        () -> extractIn(filesDir, tar));

        assertEquals(UmlRootfsProvisioner.REASON_DUPLICATE_ENTRY, failure.reason());
        assertEquals(List.of("fixture.tar.gz"), listing(filesDir));
    }

    @Test
    public void anUnsupportedEntryTypeIsRejectedAndSwept() throws Exception {
        Path filesDir = tempFolder.newFolder("slip-type").toPath();
        byte[] tar = gzipTar(TarSpec.rawType('3', "dev/console", new byte[0]));

        UmlRootfsProvisioner.ExtractionRejectionException failure =
                assertThrows(UmlRootfsProvisioner.ExtractionRejectionException.class,
                        () -> extractIn(filesDir, tar));

        assertEquals(UmlRootfsProvisioner.REASON_ENTRY_TYPE_UNSUPPORTED, failure.reason());
        assertEquals(List.of("fixture.tar.gz"), listing(filesDir));
    }

    @Test
    public void aBadHeaderChecksumIsRejectedAndSwept() throws Exception {
        Path filesDir = tempFolder.newFolder("slip-checksum").toPath();
        byte[] raw = tarBytes(List.of(TarSpec.file("etc/plain.conf", "plain")));
        // Corrupt the stored checksum of the first header, then gzip — the
        // reader must fail on the header checksum, not on gzip integrity.
        raw[148] ^= 0x01;
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream zipper = new java.util.zip.GZIPOutputStream(gz)) {
            zipper.write(raw);
        }
        byte[] tar = gz.toByteArray();

        UmlRootfsProvisioner.ExtractionRejectionException failure =
                assertThrows(UmlRootfsProvisioner.ExtractionRejectionException.class,
                        () -> extractIn(filesDir, tar));

        assertEquals(UmlRootfsProvisioner.REASON_ARCHIVE_UNREADABLE, failure.reason());
        assertEquals(List.of("fixture.tar.gz"), listing(filesDir));
    }

    @Test
    public void aTruncatedPayloadIsRejectedAndSwept() throws Exception {
        Path filesDir = tempFolder.newFolder("slip-truncated").toPath();
        byte[] tar = gzipTar(TarSpec.file("etc/plain.conf", "plain"));
        byte[] cut = new byte[tar.length - 16];
        System.arraycopy(tar, 0, cut, 0, cut.length);

        UmlRootfsProvisioner.ExtractionRejectionException failure =
                assertThrows(UmlRootfsProvisioner.ExtractionRejectionException.class,
                        () -> extractIn(filesDir, cut));

        assertEquals(UmlRootfsProvisioner.REASON_ARCHIVE_UNREADABLE, failure.reason());
        assertEquals(List.of("fixture.tar.gz"), listing(filesDir));
    }

    @Test
    public void anEscapingHardLinkTargetIsRejectedAndSwept() throws Exception {
        Path filesDir = tempFolder.newFolder("slip-hardlink-escape").toPath();
        byte[] tar = gzipTar(
                TarSpec.file("bin/busybox", "elf"),
                TarSpec.hardLink("bin/sh", "/etc/passwd"));

        UmlRootfsProvisioner.ExtractionRejectionException failure =
                assertThrows(UmlRootfsProvisioner.ExtractionRejectionException.class,
                        () -> extractIn(filesDir, tar));

        assertEquals(UmlRootfsProvisioner.REASON_ENTRY_ESCAPES_EXTRACT_ROOT, failure.reason());
        assertEquals(List.of("fixture.tar.gz"), listing(filesDir));
    }

    @Test
    public void aDanglingHardLinkIsRejectedAndSwept() throws Exception {
        Path filesDir = tempFolder.newFolder("slip-hardlink-dangling").toPath();
        byte[] tar = gzipTar(
                TarSpec.file("bin/busybox", "elf"),
                TarSpec.hardLink("bin/sh", "missing/target"));

        UmlRootfsProvisioner.ExtractionRejectionException failure =
                assertThrows(UmlRootfsProvisioner.ExtractionRejectionException.class,
                        () -> extractIn(filesDir, tar));

        assertEquals(UmlRootfsProvisioner.REASON_HARD_LINK_DANGLING, failure.reason());
        assertEquals(List.of("fixture.tar.gz"), listing(filesDir));
    }

    @Test
    public void aSymlinkPlantedByAnEarlierEntryCannotRedirectALaterOne() throws Exception {
        Path probe = tempFolder.newFolder("slip-symlink-probe").toPath();
        assumeTrue("host cannot create symlinks; the device (Linux) always can",
                symlinksSupported(probe));
        Path filesDir = tempFolder.newFolder("slip-symlink").toPath();
        byte[] tar = gzipTar(
                TarSpec.symlink("outside", ".."),
                TarSpec.file("outside/evil", "escaped-through-link"));

        UmlRootfsProvisioner.ExtractionRejectionException failure =
                assertThrows(UmlRootfsProvisioner.ExtractionRejectionException.class,
                        () -> extractIn(filesDir, tar));

        assertEquals(UmlRootfsProvisioner.REASON_PARENT_SYMLINK_TRAVERSAL, failure.reason());
        assertEquals(List.of("fixture.tar.gz"), listing(filesDir));
    }

    @Test
    public void aContainedHardLinkMaterializesAsALink() throws Exception {
        Path filesDir = tempFolder.newFolder("hardlink-ok").toPath();
        byte[] tar = gzipTar(
                TarSpec.dir("bin/"),
                TarSpec.file("bin/busybox", "elf"),
                TarSpec.hardLink("bin/sh", "bin/busybox"));

        UmlRootfsProvisioner.ExtractResult result = extractIn(filesDir, tar);

        assertEquals(3, result.entries());
        Path extractDir = filesDir.resolve("extracted");
        assertEquals("elf", new String(
                Files.readAllBytes(extractDir.resolve("bin/sh")), StandardCharsets.UTF_8));
        assertEquals("elf", new String(
                Files.readAllBytes(extractDir.resolve("bin/busybox")), StandardCharsets.UTF_8));
    }

    // --- a production-shaped reminder: the production overload reads the build flag ----------

    @Test
    public void theProductionEntryPinsTheOfficialArtifactFromTheSingleSource() throws Exception {
        Entry production = Entry.alpineMinirootfsAarch64();
        assertEquals(FILE_NAME, production.fileName());
        assertEquals(UmlKernelAssets.FastProfile.ROOTFS_TAR_SIZE_BYTES,
                production.sizeBytes().longValue());
        assertEquals(UmlKernelAssets.FastProfile.pins().get(UmlKernelAssets.FastProfile.ROOTFS_TAR),
                production.sha256Pin());
        // The extract dir derives from the entry's own file name — no name is
        // restated in the provisioner.
        assertEquals(EXTRACT_DIR_NAME, UmlRootfsProvisioner.extractDirName(production));
    }
}
