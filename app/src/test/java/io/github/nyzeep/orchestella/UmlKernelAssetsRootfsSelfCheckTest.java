package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.Entry;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.RootfsRejectionException;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.SelfCheckReport;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.SelfCheckStage;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.StageResult;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.StageState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The 方案 B L3 content self-check's contract (the follow-up to
 * {@link UmlKernelAssetsFastProfileTest}'s download-skeleton tests): every
 * implemented stage answers pass / defect / missing-field in three states,
 * the declared-only {@code FIRST_BOOT_CLEANLINESS} stage never masquerades as
 * a pass, the tar walker is defensive (checksums, containment, GNU long
 * names, PAX overrides), and the L2→L3 chain fails closed layer by layer.
 *
 * <p>Every fixture is a small tar the test builds in memory with a minimal
 * ustar writer (headers + gzip) — never a real rootfs. The fixture's shape
 * mirrors the official Alpine minirootfs 3.22.2 aarch64 tarball first-hand on
 * 2026-11-12: {@code ./}-prefixed names, all 17 FHS top-level directories as
 * explicit directory entries, {@code etc/alpine-release} = {@code 3.22.2},
 * {@code etc/hostname} = {@code localhost}, no {@code .PKGINFO}, and the apk
 * installed-db grammar in {@code lib/apk/db/installed} (87 files, 98 dirs,
 * 335 symlinks there — three files here).
 */
public final class UmlKernelAssetsRootfsSelfCheckTest {

    // --- the official-shape fixture -------------------------------------------------

    /** The apk installed-db grammar: single-char keys, blank-line stanza separators. */
    private static final String INSTALLED_DB = String.join("\n",
            "C:Q1alumIAzRIMQo4v1lHoHsUyY7cGk=",
            "P:alpine-baselayout",
            "V:3.7.0-r0",
            "A:aarch64",
            "S:8224",
            "T:Alpine base dir structure and init scripts",
            "",
            "P:alpine-keys",
            "V:2.5-r0",
            "T:Alpine Linux repository keys",
            "",
            "P:apk-tools",
            "V:2.14.9-r2",
            "T:Alpine Package Keeper - package management tools",
            "",
            "P:busybox",
            "V:1.37.0-r20",
            "T:Size optimized toolbox of many common UNIX utilities",
            "",
            "P:ca-certificates-bundle",
            "V:20250619-r0",
            "T:Pre-generated ca-certificates bundle",
            "",
            "P:musl",
            "V:1.2.5-r9",
            "T:the musl c library (libc) implementation",
            "");

    /** The 17 top-level directories the official tarball ships as explicit dir entries. */
    private static final List<String> OFFICIAL_TOP_LEVEL = List.of(
            "bin", "dev", "etc", "home", "lib", "media", "mnt", "opt", "proc",
            "root", "run", "sbin", "srv", "sys", "tmp", "usr", "var");

    private static final String OFFICIAL_VERSION = "3.22.2";

    /** One fixture entry: tar name, typeflag, payload. */
    private record Spec(String name, char type, byte[] content) {
        Spec(String name, char type, String text) {
            this(name, type, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The official-shape minirootfs fixture — every stage passes on it. */
    private static List<Spec> officialShape() {
        List<Spec> entries = new ArrayList<>();
        entries.add(new Spec("./", '5', ""));
        for (String top : OFFICIAL_TOP_LEVEL) {
            entries.add(new Spec("./" + top + "/", '5', ""));
        }
        entries.add(new Spec("./etc/alpine-release", '0', OFFICIAL_VERSION + "\n"));
        entries.add(new Spec("./etc/hostname", '0', "localhost\n"));
        entries.add(new Spec("./lib/apk/db/installed", '0', INSTALLED_DB));
        entries.add(new Spec("./usr/bin/binary-helper", '0', "fixture-elf-bytes"));
        entries.add(new Spec("./bin/sh", '2', "/bin/busybox"));
        entries.add(new Spec("./etc/periodic/hourly/fixture", '1', "./usr/bin/binary-helper"));
        return entries;
    }

    /** A file entry replacing/adding to the official shape. */
    private static Spec file(String name, String text) {
        return new Spec(name, '0', text);
    }

    private static Spec dir(String name) {
        return new Spec(name, '5', "");
    }

    private static void replaceByName(List<Spec> entries, String name, Spec replacement) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name().equals(name)) {
                entries.set(i, replacement);
                return;
            }
        }
        throw new IllegalArgumentException("fixture entry not found: " + name);
    }

    // --- the minimal ustar writer ---------------------------------------------------

    private static byte[] gzipTar(List<Spec> entries) throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream zipper = new java.util.zip.GZIPOutputStream(gz)) {
            zipper.write(tarBytes(entries));
        }
        return gz.toByteArray();
    }

    private static byte[] tarBytes(List<Spec> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Spec spec : entries) {
            byte[] header = new byte[512];
            putString(header, 0, 100, spec.name());
            // Links carry their target in the linkname field, not the data.
            boolean isLink = spec.type() == '1' || spec.type() == '2';
            long size = (spec.type() == '5' || isLink) ? 0L : spec.content().length;
            putOctal(header, 100, 8, spec.type() == '5' ? 0755 : 0644);
            putOctal(header, 108, 8, 0); // uid
            putOctal(header, 116, 8, 0); // gid
            putOctal(header, 124, 12, size);
            putOctal(header, 136, 12, 0); // mtime
            for (int i = 148; i < 156; i++) {
                header[i] = ' '; // the checksum field reads as spaces while summing
            }
            header[156] = (byte) spec.type();
            putString(header, 157, 100,
                    isLink ? new String(spec.content(), StandardCharsets.UTF_8) : "");
            putString(header, 257, 6, "ustar"); // POSIX ustar magic; version follows
            header[263] = '0';
            header[264] = '0';
            putString(header, 265, 32, "fixture");
            putString(header, 297, 32, "fixture");
            putOctal(header, 148, 8, checksum(header));
            out.write(header);
            // Only entries whose declared size is non-zero carry data blocks;
            // a link's target lives in the linkname field, not the data.
            if (size > 0) {
                out.write(spec.content());
                int pad = (512 - (spec.content().length % 512)) % 512;
                for (int i = 0; i < pad; i++) {
                    out.write(0);
                }
            }
        }
        out.write(new byte[1024]); // the two zero end blocks
        return out.toByteArray();
    }

    /** GNU long-name preamble ({@code L} typeflag): the data names the next entry. */
    private static Spec gnuLongName(String longName) {
        return new Spec("./././@LongLink", 'L',
                (longName + "\0").getBytes(StandardCharsets.UTF_8));
    }

    /** PAX extended-header preamble ({@code x} typeflag) carrying one {@code path=} override. */
    private static Spec paxPathOverride(String path) {
        // The record is "<len> path=<path>\n" where <len> counts the whole
        // record including the "NN " prefix itself (two digits + a space here).
        String body = "path=" + path + "\n";
        int len = body.length() + 3;
        return new Spec("./PaxHeaders/fixture", 'x',
                (len + " " + body).getBytes(StandardCharsets.UTF_8));
    }

    private static void putString(byte[] header, int offset, int maxLength, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxLength) {
            throw new IllegalArgumentException("fixture name longer than the header field");
        }
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    private static void putOctal(byte[] header, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        int digits = length - 1; // one trailing NUL
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

    private static String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** A test entry shaped like the production one, pinned to the fixture's own bytes. */
    private static Entry pinnedEntry(byte[] artifact, Long sizeBytes)
            throws NoSuchAlgorithmException {
        return new Entry("test-rootfs", OFFICIAL_VERSION, "aarch64",
                URI.create("https://dl-cdn.example.test/pool/"
                        + UmlKernelAssets.FastProfile.ROOTFS_TAR),
                UmlKernelAssets.FastProfile.ROOTFS_TAR, sha256Hex(artifact), sizeBytes);
    }

    private static StageResult stage(SelfCheckReport report, SelfCheckStage stage) {
        return report.stages().stream()
                .filter(result -> result.stage() == stage)
                .findFirst()
                .orElseThrow(() -> new AssertionError("stage missing from report: " + stage));
    }

    // --- the pass state over the official shape --------------------------------------

    @Test
    public void officialShapePassesEveryImplementedStage() throws Exception {
        byte[] artifact = gzipTar(officialShape());
        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(UmlKernelAssets.RootfsDownload.selfCheckPlan().size(),
                report.stages().size());
        assertEquals(StageState.PASS, stage(report, SelfCheckStage.MANIFEST_FIELDS).state());
        assertEquals(StageState.PASS, stage(report, SelfCheckStage.ARTIFACT_DIGESTS).state());
        assertEquals(StageState.PASS, stage(report, SelfCheckStage.PACKAGE_DATABASE).state());
        assertEquals(StageState.PASS, stage(report, SelfCheckStage.TREE_DIGEST).state());
        assertTrue(stage(report, SelfCheckStage.MANIFEST_FIELDS).detail()
                .contains("version=" + OFFICIAL_VERSION));
        assertTrue(stage(report, SelfCheckStage.ARTIFACT_DIGESTS).detail()
                .contains("sha256 ok, " + artifact.length + " bytes (size pinned)"));
        assertTrue(stage(report, SelfCheckStage.PACKAGE_DATABASE).detail().contains("6 packages"));
        assertTrue(stage(report, SelfCheckStage.TREE_DIGEST).detail().contains("17/17"));
        // The implemented stages pass, but the report is not "all passed":
        // FIRST_BOOT_CLEANLINESS is planned and must stay visible.
        assertEquals(List.of(SelfCheckStage.FIRST_BOOT_CLEANLINESS), report.plannedStages());
        assertTrue(report.describe().isEmpty());
    }

    @Test
    public void reportStagesFollowTheDeclaredPlanOrder() throws Exception {
        byte[] artifact = gzipTar(officialShape());
        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(UmlKernelAssets.RootfsDownload.selfCheckPlan(), report.stages().stream()
                .map(StageResult::stage).toList());
    }

    // --- MANIFEST_FIELDS: pass / defect / missing field --------------------------------

    @Test
    public void manifestFieldsFailsWhenAlpineReleaseDisagreesWithThePinnedVersion()
            throws Exception {
        List<Spec> tampered = officialShape();
        replaceByName(tampered, "./etc/alpine-release", file("./etc/alpine-release", "3.22.3\n"));
        byte[] artifact = gzipTar(tampered);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.MANIFEST_FIELDS).state());
        assertTrue(stage(report, SelfCheckStage.MANIFEST_FIELDS).detail()
                .contains("says \"3.22.3\" but the entry pins version " + OFFICIAL_VERSION));
        assertTrue(report.failed());
    }

    @Test
    public void manifestFieldsFailsWhenARequiredIdentityFieldIsMissing() throws Exception {
        List<Spec> missing = officialShape();
        missing.removeIf(spec -> spec.name().equals("./etc/alpine-release"));
        byte[] artifact = gzipTar(missing);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.MANIFEST_FIELDS).state());
        assertTrue(stage(report, SelfCheckStage.MANIFEST_FIELDS).detail()
                .contains("missing required identity file: etc/alpine-release"));
    }

    @Test
    public void manifestFieldsFailsOnABlankHostname() throws Exception {
        List<Spec> blank = officialShape();
        replaceByName(blank, "./etc/hostname", file("./etc/hostname", "  \n"));
        byte[] artifact = gzipTar(blank);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.MANIFEST_FIELDS).state());
        assertTrue(stage(report, SelfCheckStage.MANIFEST_FIELDS).detail()
                .contains("missing or blank required identity file: etc/hostname"));
    }

    @Test
    public void manifestFieldsRejectsPathTraversalEntries() throws Exception {
        List<Spec> escaping = officialShape();
        escaping.add(file("../escape.sh", "not-actually-extracted"));
        byte[] artifact = gzipTar(escaping);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.MANIFEST_FIELDS).state());
        assertTrue(stage(report, SelfCheckStage.MANIFEST_FIELDS).detail()
                .contains("unsafe archive entries"));
        assertTrue(stage(report, SelfCheckStage.MANIFEST_FIELDS).detail().contains("../escape.sh"));
    }

    @Test
    public void manifestFieldsRejectsAbsoluteArchiveRoots() throws Exception {
        List<Spec> rooted = officialShape();
        rooted.add(file("/etc/passwd", "absolute-names-extract-onto-the-host"));
        byte[] artifact = gzipTar(rooted);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.MANIFEST_FIELDS).state());
        assertTrue(stage(report, SelfCheckStage.MANIFEST_FIELDS).detail()
                .contains("/etc/passwd"));
    }

    @Test
    public void absentPkginfoReadsAsTheOfficialShapeNotADefect() throws Exception {
        byte[] artifact = gzipTar(officialShape());

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        // The official v3.22.2 aarch64 minirootfs carries no .PKGINFO — the
        // stage passes with that fact in the detail instead of inventing a
        // requirement the official artifact cannot meet.
        assertTrue(stage(report, SelfCheckStage.MANIFEST_FIELDS).detail()
                .contains(".PKGINFO absent"));
        assertEquals(StageState.PASS, stage(report, SelfCheckStage.MANIFEST_FIELDS).state());
    }

    // --- ARTIFACT_DIGESTS: pass / digest defect / size defect ---------------------------

    @Test
    public void artifactDigestsFailsOnTamperedBytes() throws Exception {
        byte[] intact = gzipTar(officialShape());
        Entry entry = pinnedEntry(intact, (long) intact.length);
        byte[] tampered = intact.clone();
        tampered[tampered.length / 2] ^= 0x55;

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(entry, tampered);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.ARTIFACT_DIGESTS).state());
        assertTrue(stage(report, SelfCheckStage.ARTIFACT_DIGESTS).detail()
                .contains("sha256 mismatch"));
        assertTrue(report.failed());
    }

    @Test
    public void artifactDigestsFailsWhenThePinnedSizeDisagrees() throws Exception {
        byte[] artifact = gzipTar(officialShape());
        Entry wrongSize = new Entry("test-rootfs", OFFICIAL_VERSION, "aarch64",
                URI.create("https://dl-cdn.example.test/pool/"
                        + UmlKernelAssets.FastProfile.ROOTFS_TAR),
                UmlKernelAssets.FastProfile.ROOTFS_TAR, sha256Hex(artifact),
                (long) artifact.length + 1);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(wrongSize, artifact);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.ARTIFACT_DIGESTS).state());
        assertTrue(stage(report, SelfCheckStage.ARTIFACT_DIGESTS).detail()
                .contains("size mismatch"));
    }

    @Test
    public void artifactDigestsWithAnUnpinnedSizeAnswersTheDigestOnly() throws Exception {
        byte[] artifact = gzipTar(officialShape());
        Entry unpinned = new Entry("test-rootfs", OFFICIAL_VERSION, "aarch64",
                URI.create("https://dl-cdn.example.test/pool/"
                        + UmlKernelAssets.FastProfile.ROOTFS_TAR),
                UmlKernelAssets.FastProfile.ROOTFS_TAR, sha256Hex(artifact), null);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(unpinned, artifact);

        assertEquals(StageState.PASS, stage(report, SelfCheckStage.ARTIFACT_DIGESTS).state());
        assertTrue(stage(report, SelfCheckStage.ARTIFACT_DIGESTS).detail()
                .contains("(size unpinned)"));
    }

    // --- PACKAGE_DATABASE: pass / defect / missing --------------------------------------

    @Test
    public void packageDatabaseFailsOnAMalformedStanza() throws Exception {
        List<Spec> malformed = officialShape();
        replaceByName(malformed, "./lib/apk/db/installed", file("./lib/apk/db/installed",
                "P:apk-tools\nV:2.14.9-r2\n\nP:lonely-stanza-without-a-version\n\n"));
        byte[] artifact = gzipTar(malformed);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.PACKAGE_DATABASE).state());
        assertTrue(stage(report, SelfCheckStage.PACKAGE_DATABASE).detail()
                .contains("malformed stanza"));
    }

    @Test
    public void packageDatabaseFailsWhenARequiredPackageIsAbsent() throws Exception {
        List<Spec> thinned = officialShape();
        // Drop the musl stanza (last in the fixture db).
        replaceByName(thinned, "./lib/apk/db/installed", file("./lib/apk/db/installed",
                INSTALLED_DB.substring(0, INSTALLED_DB.indexOf("P:musl"))));
        byte[] artifact = gzipTar(thinned);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.PACKAGE_DATABASE).state());
        assertTrue(stage(report, SelfCheckStage.PACKAGE_DATABASE).detail()
                .contains("required packages absent: musl"));
    }

    @Test
    public void packageDatabaseFailsWhenTheDatabaseFileIsMissing() throws Exception {
        List<Spec> without = officialShape();
        without.removeIf(spec -> spec.name().equals("./lib/apk/db/installed"));
        byte[] artifact = gzipTar(without);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.PACKAGE_DATABASE).state());
        assertTrue(stage(report, SelfCheckStage.PACKAGE_DATABASE).detail()
                .contains("missing package database: lib/apk/db/installed"));
    }

    @Test
    public void packageDatabaseFailsOnAnUnparsableDatabase() throws Exception {
        List<Spec> garbage = officialShape();
        replaceByName(garbage, "./lib/apk/db/installed", file("./lib/apk/db/installed", "\n\n\n"));
        byte[] artifact = gzipTar(garbage);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.PACKAGE_DATABASE).state());
        assertTrue(stage(report, SelfCheckStage.PACKAGE_DATABASE).detail()
                .contains("parsed to zero packages"));
    }

    // --- TREE_DIGEST: pass / defect / not-a-directory ------------------------------------

    @Test
    public void treeDigestFailsWhenARequiredDirectoryIsAbsent() throws Exception {
        List<Spec> withoutProc = officialShape();
        withoutProc.removeIf(spec -> spec.name().equals("./proc/"));
        byte[] artifact = gzipTar(withoutProc);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.TREE_DIGEST).state());
        assertTrue(stage(report, SelfCheckStage.TREE_DIGEST).detail()
                .contains("required top-level directories absent: proc"));
    }

    @Test
    public void treeDigestToleratesExtraTopLevelNames() throws Exception {
        List<Spec> extra = officialShape();
        extra.add(dir("./orchestella-extra/"));
        byte[] artifact = gzipTar(extra);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        // Completeness, not equality: the official shape plus a product
        // directory still passes.
        assertEquals(StageState.PASS, stage(report, SelfCheckStage.TREE_DIGEST).state());
    }

    @Test
    public void treeDigestRequiresTheNameToBeAnExplicitDirectory() throws Exception {
        List<Spec> fileNotDir = officialShape();
        fileNotDir.removeIf(spec -> spec.name().equals("./proc/"));
        fileNotDir.add(file("./proc", "a regular file wearing a directory's name"));
        byte[] artifact = gzipTar(fileNotDir);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        // A file named "proc" is not the directory the official tarball ships;
        // only explicit directory entries count (first-hand: all 17 are '5').
        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.TREE_DIGEST).state());
        assertTrue(stage(report, SelfCheckStage.TREE_DIGEST).detail().contains(": proc"));
    }

    // --- FIRST_BOOT_CLEANLINESS: declared, never faked -----------------------------------

    @Test
    public void firstBootCleanlinessAnswersPlannedAndNeverPass() throws Exception {
        byte[] artifact = gzipTar(officialShape());
        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        StageResult planned = stage(report, SelfCheckStage.FIRST_BOOT_CLEANLINESS);
        assertEquals(StageState.PLANNED, planned.state());
        assertNotEquals(StageState.PASS, planned.state());
        assertNotNull(planned.detail());
        assertTrue(planned.detail().contains("real-device first-boot data"));
        assertEquals(List.of(SelfCheckStage.FIRST_BOOT_CLEANLINESS), report.plannedStages());
        // A planned stage is not a failure — but it is also not a pass.
        assertTrue(report.describe().isEmpty());
    }

    // --- the tar walker's defenses --------------------------------------------------------

    @Test
    public void gnuLongNamesAndPaxOverridesAreHonored() throws Exception {
        // Longer than the 100-byte header name field, so it must travel
        // through the GNU 'L' preamble (the writer refuses longer literals).
        String longName = "./lib/apk/db/a-very-long-fixture-name-"
                + "the-second-segment-of-the-same-fixture-name-"
                + "and-a-third-segment-to-cross-the-ustar-limit-end";
        List<Spec> decorated = new ArrayList<>();
        decorated.add(dir("./"));
        for (String top : OFFICIAL_TOP_LEVEL) {
            decorated.add(dir("./" + top + "/"));
        }
        decorated.add(file("./etc/hostname", "localhost\n"));
        decorated.add(file("./lib/apk/db/installed", INSTALLED_DB));
        decorated.add(gnuLongName(longName));
        decorated.add(file("placeholder-short-name", "content under the long name"));
        decorated.add(paxPathOverride("./etc/alpine-release"));
        decorated.add(file("./dummy-name-for-pax", OFFICIAL_VERSION + "\n"));
        byte[] artifact = gzipTar(decorated);

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        // Both overrides resolved: the long-name content landed under its real
        // name and the PAX override supplied the identity file — no parse
        // error, no unsafe entry, every content stage passes.
        assertEquals(StageState.PASS, stage(report, SelfCheckStage.MANIFEST_FIELDS).state());
        assertTrue(stage(report, SelfCheckStage.MANIFEST_FIELDS).detail()
                .contains("version=" + OFFICIAL_VERSION));
        assertEquals(StageState.PASS, stage(report, SelfCheckStage.PACKAGE_DATABASE).state());
        assertEquals(StageState.PASS, stage(report, SelfCheckStage.TREE_DIGEST).state());
    }

    @Test
    public void aTruncatedArchiveFailsEveryContentStage() throws Exception {
        byte[] intact = tarBytes(officialShape());
        // Cut mid-block (the plain half lands exactly on a header boundary,
        // where a clean end-of-archive read would be legal): a real truncation
        // is a stream defect, not a graceful end.
        byte[] truncated = java.util.Arrays.copyOf(intact, intact.length / 2 + 100);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream zipper = new java.util.zip.GZIPOutputStream(gz)) {
            zipper.write(truncated);
        }
        byte[] artifact = gz.toByteArray();

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        // The bytes hash to their own pin (ARTIFACT_DIGESTS passes) — the L2
        // layer cannot see this defect; the content stages fail closed.
        assertEquals(StageState.PASS, stage(report, SelfCheckStage.ARTIFACT_DIGESTS).state());
        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.MANIFEST_FIELDS).state());
        assertTrue(stage(report, SelfCheckStage.MANIFEST_FIELDS).detail()
                .contains("archive unreadable"));
        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.PACKAGE_DATABASE).state());
        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.TREE_DIGEST).state());
        assertTrue(report.failed());
    }

    @Test
    public void aCorruptTarHeaderFailsClosedWithoutBeingTrusted() throws Exception {
        byte[] tar = tarBytes(officialShape());
        // Flip a byte inside the first header's name field (not the checksum
        // field itself), then re-gzip — the header checksum no longer matches.
        tar[3] ^= 0x55;
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream zipper = new java.util.zip.GZIPOutputStream(gz)) {
            zipper.write(tar);
        }
        byte[] artifact = gz.toByteArray();

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.selfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(StageState.FAIL, stage(report, SelfCheckStage.MANIFEST_FIELDS).state());
        assertTrue(stage(report, SelfCheckStage.MANIFEST_FIELDS).detail()
                .contains("checksum mismatch"));
    }

    // --- fail-closed requireSelfCheck and the L2→L3 chain ---------------------------------

    @Test
    public void requireSelfCheckThrowsFailClosedWithTheStageDetails() throws Exception {
        List<Spec> withoutProc = officialShape();
        withoutProc.removeIf(spec -> spec.name().equals("./proc/"));
        byte[] artifact = gzipTar(withoutProc);

        RootfsRejectionException rejection = assertThrows(RootfsRejectionException.class,
                () -> UmlKernelAssets.RootfsDownload.requireSelfCheck(
                        pinnedEntry(artifact, (long) artifact.length), artifact));

        assertEquals(UmlKernelAssets.RootfsDownload.REASON_SELF_CHECK_FAILED,
                rejection.reason());
        assertTrue(rejection.getMessage().contains(SelfCheckStage.TREE_DIGEST.toString()));
        assertTrue(rejection.getMessage().contains("proc"));
    }

    @Test
    public void requireSelfCheckReturnsTheReportWhenEveryImplementedStagePasses()
            throws Exception {
        byte[] artifact = gzipTar(officialShape());

        SelfCheckReport report = UmlKernelAssets.RootfsDownload.requireSelfCheck(
                pinnedEntry(artifact, (long) artifact.length), artifact);

        assertEquals(StageState.PASS, stage(report, SelfCheckStage.MANIFEST_FIELDS).state());
        assertEquals(List.of(SelfCheckStage.FIRST_BOOT_CLEANLINESS), report.plannedStages());
    }

    @Test
    public void theL2L3ChainFailsClosedLayerByLayer() throws Exception {
        byte[] intact = gzipTar(officialShape());
        Entry intactEntry = pinnedEntry(intact, (long) intact.length);

        // L1→L3 clean: the chain answers with the L3 report.
        SelfCheckReport report = UmlKernelAssets.RootfsDownload.requireVerifiedAndSelfChecked(
                intactEntry, intact, (long) intact.length);
        assertEquals(StageState.PASS, stage(report, SelfCheckStage.ARTIFACT_DIGESTS).state());

        // L1 rejects first: a wrong declared size never reaches the pin or L3.
        RootfsRejectionException sizeRejection = assertThrows(RootfsRejectionException.class,
                () -> UmlKernelAssets.RootfsDownload.requireVerifiedAndSelfChecked(
                        intactEntry, intact, (long) intact.length - 1));
        assertEquals(UmlKernelAssets.RootfsDownload.REASON_SIZE_MISMATCH,
                sizeRejection.reason());

        // L2 rejects before L3: bytes tampered under the intact pin.
        byte[] tampered = intact.clone();
        tampered[0] ^= 0x55;
        RootfsRejectionException digestRejection = assertThrows(RootfsRejectionException.class,
                () -> UmlKernelAssets.RootfsDownload.requireVerifiedAndSelfChecked(
                        intactEntry, tampered, (long) tampered.length));
        assertEquals(UmlKernelAssets.RootfsDownload.REASON_CHECKSUM_MISMATCH,
                digestRejection.reason());

        // L3 catches what L2 cannot: bytes that hash to their own pin but
        // whose content is not the official shape (proc directory missing).
        List<Spec> withoutProc = officialShape();
        withoutProc.removeIf(spec -> spec.name().equals("./proc/"));
        byte[] contentCorrupt = gzipTar(withoutProc);
        Entry selfConsistentPin = pinnedEntry(contentCorrupt, (long) contentCorrupt.length);
        RootfsRejectionException selfCheckRejection =
                assertThrows(RootfsRejectionException.class,
                        () -> UmlKernelAssets.RootfsDownload.requireVerifiedAndSelfChecked(
                                selfConsistentPin, contentCorrupt, (long) contentCorrupt.length));
        assertEquals(UmlKernelAssets.RootfsDownload.REASON_SELF_CHECK_FAILED,
                selfCheckRejection.reason());
    }

    @Test
    public void theChainTreatsAnUnpinnedDeclaredSizeAsTheEntryDeclaresIt() throws Exception {
        byte[] artifact = gzipTar(officialShape());
        Entry unpinned = new Entry("test-rootfs", OFFICIAL_VERSION, "aarch64",
                URI.create("https://dl-cdn.example.test/pool/"
                        + UmlKernelAssets.FastProfile.ROOTFS_TAR),
                UmlKernelAssets.FastProfile.ROOTFS_TAR, sha256Hex(artifact), null);

        // An unpinned entry size skips the L1 declared check honestly (the
        // pinned variant rejects a missing declaration) and still runs L3.
        SelfCheckReport report = UmlKernelAssets.RootfsDownload.requireVerifiedAndSelfChecked(
                unpinned, artifact, null);
        assertEquals(StageState.PASS, stage(report, SelfCheckStage.MANIFEST_FIELDS).state());
        assertTrue(stage(report, SelfCheckStage.ARTIFACT_DIGESTS).detail()
                .contains("(size unpinned)"));
    }
}
