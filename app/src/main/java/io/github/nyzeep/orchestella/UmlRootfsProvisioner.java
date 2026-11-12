package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.Entry;
import io.github.nyzeep.orchestella.runtime.network.download.DownloadEngine;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Lane B: the product-side orchestration seam — the one place where a user
 * entry action becomes a verified, extracted, ready-to-consume UML rootfs.
 * It composes two fail-closed stages around the pinned entry ({@code
 * Entry.alpineMinirootfsAarch64()}): the transport half is {@link
 * RootfsTransport#downloadAndVerify} (download → {@code .part} staging →
 * L1/L2/L3 → atomic publish), and this class adds the product half the
 * transport deliberately does not own: <i>落盘 + 解包就绪</i> — the verified
 * tar lands under {@code filesDir/uml-rootfs/}, and a slip-safe extractor
 * unpacks it to {@code filesDir/uml-rootfs/<name>/} so the fast HOSTFS
 * channel ({@code umroot=<extracted dir> umrootfstype=hostfs}) can consume it.
 *
 * <p><b>Directory layout (Lane B's exclusive subtree, by the round's车道
 * 约定).</b> Under {@code filesDir/uml-rootfs/}:
 * <pre>
 *   alpine-minirootfs-3.22.2-aarch64.tar.gz          the verified artifact (pin attested)
 *   alpine-minirootfs-3.22.2-aarch64/                the extracted tree (HOSTFS umroot target)
 *   alpine-minirootfs-3.22.2-aarch64.verified        the extraction attestation (idempotency key)
 *   alpine-minirootfs-3.22.2-aarch64.part/           transient extraction staging, always swept
 * </pre>
 * The {@code .part} suffix discipline mirrors {@link RootfsTransport}: a
 * {@code .part}-suffixed name is an unverified artifact, never consumed; every
 * failure path sweeps it before propagating.
 *
 * <p><b>Idempotency (pin-sha reconciliation, not file-name trust).</b> Both
 * stages reconcile against the entry's pinned sha256 — the single source of
 * the pin stays {@link UmlKernelAssets}. The download stage skips the network
 * when the formal tar's observed digest equals the pin (a corrupt or stale
 * artifact fails the compare and is replaced by a fresh verified download);
 * the extraction stage skips re-unpacking when the {@code .verified} sidecar
 * attests the same pin. An already-verified product therefore answers without
 * a single transport call — repeated entry actions never waste traffic.
 *
 * <p><b>Slip-safe extraction (the tar is untrusted input all the way down).</b>
 * Even though the tar has passed L1/L2/L3 by the time it is extracted, the
 * extractor treats it as hostile: entry names are normalized and any absolute
 * name or {@code ..} segment is rejected fail-closed ({@code
 * uml-rootfs-entry-escapes-extract-root}); a parent path component that is an
 * existing symbolic link is rejected ({@code
 * uml-rootfs-parent-symlink-traversal}) so a link planted by an earlier entry
 * can never redirect a later one; duplicate normalized names are rejected; a
 * hard link whose target is missing or escapes the extract root is rejected;
 * unsupported entry types (devices, fifos) are rejected. Symlink <i>targets</i>
 * are stored verbatim and never dereferenced during extraction — the official
 * minirootfs ships 335 symlinks, many with absolute guest-namespace targets
 * ({@code bin/sh → /bin/busybox}), which are legitimate guest-owned strings,
 * not host paths this class follows. The exec bit is preserved at the device
 * Java layer ({@code File.setExecutable}) for every regular file whose tar
 * mode carries any execute bit.
 *
 * <p><b>Flag gate.</b> The production overload reads {@code
 * BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED} (gradle property {@code
 * orchestella.uml.rootfsDownload}, default {@code false}). Off answers the
 * disabled receipt before the transport or the filesystem is consulted — zero
 * transport calls, zero reads, zero writes — and is an explicit disabled
 * semantics, never a disguised success. Failures of every stage propagate
 * as-is (transport errors and {@code RootfsRejectionException} from the
 * verification chain, {@link ExtractionRejectionException} from this class's
 * own slip defenses) — nothing is laundered into a success. No product path
 * calls this yet: the entry action's UI wiring is the follow-up round's
 * decision (IA proposal pending), and this class is the seam it will call.
 *
 * <p><b>Honesty boundary of the ready state (2026-11-13 主会话裁决).</b> The
 * official Alpine minirootfs ships no {@code /um-command.sh}: the research
 * initramfs's external-root path demands an executable {@code
 * /newroot/um-command.sh} before it will {@code switch_root} ({@code
 * research/uml-fastboot/initramfs/init}, {@code fail 29 missing_command}),
 * and the official tree contains none. A provisioned extract is therefore
 * exactly "verified + extracted" — never "startable to READY" and never
 * "compile-capable". The compile channel's own honesty edge is {@code
 * CHANNEL_UNWIRED} (Lane A); launch usability of the downloaded rootfs is a
 * fabric-route-round design problem against the real init semantics, not a
 * property this class, its receipt, or its readiness probe claims.
 *
 * <p><b>Threading.</b> Every stage does network and disk IO — callers invoke
 * this off the main thread. The {@link #readiness} probe is the one pure-read
 * answer: no transport, no writes, no directory creation.
 */
public final class UmlRootfsProvisioner {

    /** Lane B's exclusive subtree under the app's {@code filesDir}. */
    public static final String UML_ROOTFS_DIR_NAME = "uml-rootfs";

    /**
     * The extraction staging marker: a {@code .part}-suffixed sibling of the
     * formal extract directory is an unverified in-progress tree, never read
     * as a product — swept on every failure path, moved into place only after
     * the whole archive extracted cleanly.
     */
    public static final String EXTRACT_STAGING_SUFFIX = ".part";

    /** The extraction attestation suffix: {@code <extract dir>.verified}. */
    static final String VERIFIED_SIDECAR_SUFFIX = ".verified";

    /** Stable slip-defense rejection reasons (non-secret, transport-agnostic). */
    public static final String REASON_ENTRY_ESCAPES_EXTRACT_ROOT =
            "uml-rootfs-entry-escapes-extract-root";
    public static final String REASON_PARENT_SYMLINK_TRAVERSAL =
            "uml-rootfs-parent-symlink-traversal";
    public static final String REASON_PARENT_PATH_CONFLICT =
            "uml-rootfs-parent-path-conflict";
    public static final String REASON_DUPLICATE_ENTRY = "uml-rootfs-duplicate-entry";
    public static final String REASON_ENTRY_TYPE_UNSUPPORTED =
            "uml-rootfs-entry-type-unsupported";
    public static final String REASON_HARD_LINK_DANGLING = "uml-rootfs-hard-link-dangling";
    public static final String REASON_MALFORMED_LINK = "uml-rootfs-malformed-link";
    public static final String REASON_ARCHIVE_UNREADABLE = "uml-rootfs-archive-unreadable";

    private UmlRootfsProvisioner() {
    }

    /**
     * The production entry: the pinned Alpine minirootfs, gated by {@code
     * BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED} compiled in. The pin's single
     * source stays {@link UmlKernelAssets} — this class never restates it.
     */
    public static ProvisionReceipt provision(DownloadEngine transport, Path filesDir)
            throws IOException {
        return provision(transport, Entry.alpineMinirootfsAarch64(), filesDir,
                BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED);
    }

    /**
     * The flag-injectable orchestration (tests own the flag; production goes
     * through the single-source {@link #provision(DownloadEngine, Path)}
     * overload). Flag off answers {@linkplain ProvisionReceipt#disabled()
     * disabled} before anything is consulted. Flag on runs: pin-sha
     * reconciliation of any existing formal tar → (skipped or) {@link
     * RootfsTransport#downloadAndVerify} → sidecar reconciliation of any
     * existing extract tree → (skipped or) slip-safe extraction of the
     * verified tar into place → the receipt.
     *
     * @throws IOException the transport's or verification chain's failure, or
     *         an {@link ExtractionRejectionException} with a stable reason
     *         code; in every case the {@code .part} extraction staging is
     *         swept first — a verified tar and any previously attested tree
     *         are never destroyed by a failed re-extraction
     */
    static ProvisionReceipt provision(
            DownloadEngine transport, Entry entry, Path filesDir,
            boolean rootfsDownloadBuildFlag) throws IOException {
        if (!rootfsDownloadBuildFlag) {
            // The gate answers first: a disabled provision must not name a
            // path, call the transport, or touch the filesystem at all.
            return ProvisionReceipt.disabled();
        }
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(filesDir, "filesDir");
        Path umRootfsDir = filesDir.resolve(UmlRootfsProvisioner.UML_ROOTFS_DIR_NAME);
        Files.createDirectories(umRootfsDir);
        Path formalTar = umRootfsDir.resolve(entry.fileName());

        // Stage 1: download (skipped when an artifact already reconciles with
        // the pin — the pin compare is the only idempotency authority; the
        // file name alone never attests anything).
        boolean downloadedThisCall;
        if (Files.exists(formalTar) && pinMatches(entry.sha256Pin(),
                UmlKernelAssets.sha256Hex(formalTar.toFile()))) {
            downloadedThisCall = false;
        } else {
            RootfsTransport.DownloadReceipt receipt = RootfsTransport.downloadAndVerify(
                    transport, entry, umRootfsDir, rootfsDownloadBuildFlag);
            if (!receipt.enabled()) {
                // Unreachable while the flag is on — a defensive seam check:
                // the transport's gate must never disagree with ours.
                throw new IllegalStateException(
                        "rootfs transport answered disabled while the provision gate is open");
            }
            downloadedThisCall = true;
        }

        // Stage 2: extraction (skipped when the sidecar attests the same pin).
        Path extractDir = umRootfsDir.resolve(extractDirName(entry));
        Path sidecar = umRootfsDir.resolve(extractDirName(entry) + VERIFIED_SIDECAR_SUFFIX);
        ExtractResult result;
        if (Files.isDirectory(extractDir) && attestsPin(sidecar, entry.sha256Pin())) {
            result = null;
        } else {
            result = extractVerifiedTar(formalTar, extractDir, entry.sha256Pin());
        }

        return new ProvisionReceipt(
                true,
                !downloadedThisCall && result == null,
                entry.fileName(),
                formalTar,
                Files.size(formalTar),
                UmlKernelAssets.sha256Hex(formalTar.toFile()),
                extractDir,
                result == null ? countedEntries(sidecar) : result.entries(),
                result == null ? countedExecutables(sidecar) : result.executables());
    }

    /**
     * The orchestration's answer. The enabled receipt exists only after the
     * verified tar <i>and</i> the extracted, attested tree both exist: {@code
     * file()} is the pinned artifact, {@code sha256()} its observed digest,
     * {@code extractedDir()} the HOSTFS {@code umroot} target, {@code
     * extractedEntries()}/{@code executableEntries()} the honest extraction
     * counts (executables = regular files extracted with an exec bit). An
     * idempotent hit sets {@code alreadyVerified()} and made no transport
     * call. The disabled receipt carries nothing at all — a disabled answer
     * must not imply a provision that never ran.
     */
    public record ProvisionReceipt(boolean enabled, boolean alreadyVerified, String fileName,
            Path file, long sizeBytes, String sha256, Path extractedDir,
            int extractedEntries, int executableEntries) {

        public ProvisionReceipt {
            if (!enabled && (fileName != null || file != null || sizeBytes != 0L
                    || sha256 != null || extractedDir != null
                    || extractedEntries != 0 || executableEntries != 0)) {
                throw new IllegalArgumentException(
                        "a disabled uml-rootfs provision receipt must not carry"
                                + " a provisioned artifact");
            }
        }

        /** The flag-off answer: no transport ran, nothing was read, nothing exists. */
        public static ProvisionReceipt disabled() {
            return new ProvisionReceipt(false, false, null, null, 0L, null, null, 0, 0);
        }
    }

    /**
     * A slip-defense rejection with a stable reason code — the extraction
     * half's counterpart of {@code RootfsRejectionException}: never a raw
     * archive byte, never laundered into success.
     */
    public static final class ExtractionRejectionException extends IOException {
        private static final long serialVersionUID = 1L;

        private final String reason;

        ExtractionRejectionException(String reason) {
            super(reason);
            this.reason = reason;
        }

        ExtractionRejectionException(String reason, String detail) {
            super(detail == null || detail.isEmpty() ? reason : reason + ": " + detail);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * The side-effect-free readiness answer for the entry card. {@code
     * enabled=false} is the flag-off gate (nothing was read); {@code ready}
     * means both reconciliations hold — the formal tar's observed digest
     * equals the pin <i>and</i> the extracted tree is attested by the same
     * pin — in which case {@code extractedDir()} is the HOSTFS {@code
     * umroot} target and the counts are the honest extraction numbers. A
     * tar without an attested tree is honestly {@code notReady} (provision
     * would repair it without downloading). Ready here never implies
     * startable or compile-capable — see the class honesty boundary.
     */
    public record ReadinessReport(boolean enabled, boolean ready, Path extractedDir,
            int extractedEntries, int executableEntries) {

        public ReadinessReport {
            if (!enabled && (ready || extractedDir != null
                    || extractedEntries != 0 || executableEntries != 0)) {
                throw new IllegalArgumentException(
                        "a disabled readiness report must not carry a provisioned artifact");
            }
            if (!ready && (extractedDir != null
                    || extractedEntries != 0 || executableEntries != 0)) {
                throw new IllegalArgumentException(
                        "a not-ready readiness report must not carry an extracted tree");
            }
        }

        /** The flag-off answer: nothing was read, nothing is claimed. */
        public static ReadinessReport disabled() {
            return new ReadinessReport(false, false, null, 0, 0);
        }

        /** The enabled, not-yet-provisioned answer — no download was started by this probe. */
        public static ReadinessReport notReady() {
            return new ReadinessReport(true, false, null, 0, 0);
        }
    }

    /**
     * The production readiness probe: the pinned Alpine minirootfs, gated by
     * {@code BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED}. Pure reads — no
     * transport call, no write, no directory creation, so a UI may call it
     * for a render decision without side effects.
     */
    public static ReadinessReport readiness(Path filesDir) throws IOException {
        return readiness(Entry.alpineMinirootfsAarch64(), filesDir,
                BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED);
    }

    /**
     * The flag-injectable probe (tests own the flag; production goes through
     * the single-source {@link #readiness(Path)} overload). Both
     * reconciliations run against the pin exactly as {@link #provision}
     * would, minus every side effect.
     */
    static ReadinessReport readiness(Entry entry, Path filesDir,
            boolean rootfsDownloadBuildFlag) throws IOException {
        if (!rootfsDownloadBuildFlag) {
            // The gate answers first: a disabled probe reads nothing.
            return ReadinessReport.disabled();
        }
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(filesDir, "filesDir");
        Path umRootfsDir = filesDir.resolve(UML_ROOTFS_DIR_NAME);
        Path formalTar = umRootfsDir.resolve(entry.fileName());
        if (!Files.isRegularFile(formalTar)
                || !pinMatches(entry.sha256Pin(), UmlKernelAssets.sha256Hex(formalTar.toFile()))) {
            return ReadinessReport.notReady();
        }
        Path extractDir = umRootfsDir.resolve(extractDirName(entry));
        Path sidecar = umRootfsDir.resolve(extractDirName(entry) + VERIFIED_SIDECAR_SUFFIX);
        if (!Files.isDirectory(extractDir) || !attestsPin(sidecar, entry.sha256Pin())) {
            return ReadinessReport.notReady();
        }
        return new ReadinessReport(true, true, extractDir,
                countedEntries(sidecar), countedExecutables(sidecar));
    }

    // ---------------------------------------------------------------------------------
    // Slip-safe extraction: gzip → tar → filesDir/uml-rootfs/<name>/.part → formal dir
    // ---------------------------------------------------------------------------------

    /** The honest extraction counts (entries materialized, exec bits preserved). */
    record ExtractResult(int entries, int executables) {
    }

    /**
     * Metadata payloads (GNU long names, PAX records) are allocated whole —
     * a declared size beyond this bound is a hostile archive, rejected before
     * any allocation. Regular-file payloads never allocate (they stream).
     */
    private static final long MAX_METADATA_PAYLOAD_BYTES = 1024 * 1024;

    /**
     * Package-private for the contract tests: the slip-defense matrix drives
     * this stage directly (attack tars never pass the L1/L2/L3 chain, so the
     * full-pipeline tests cannot reach the extractor's own rejections).
     */
    static ExtractResult extractVerifiedTar(Path formalTar, Path extractDir, String pin)
            throws IOException {
        Path staging = extractDir.resolveSibling(extractDir.getFileName() + EXTRACT_STAGING_SUFFIX);
        try {
            deleteRecursively(staging);
            Files.createDirectories(staging);
            ExtractResult result = extract(formalTar, staging);
            if (Files.exists(extractDir)) {
                // Replace a stale or unattested tree only after the fresh one
                // extracted completely — a failed re-extraction never destroys
                // the previous tree.
                deleteRecursively(extractDir);
            }
            moveIntoPlace(staging, extractDir);
            writeSidecar(extractDir.resolveSibling(
                    extractDir.getFileName() + VERIFIED_SIDECAR_SUFFIX), pin, result);
            return result;
        } catch (IOException failure) {
            deleteRecursively(staging);
            throw failure;
        } catch (RuntimeException failure) {
            deleteRecursively(staging);
            throw failure;
        }
    }

    /**
     * The streaming extractor. One pass over the gzip stream; nothing is
     * buffered whole (the 4 MB tar writes straight through to disk); every
     * structural anomaly fails closed with a stable reason.
     */
    private static ExtractResult extract(Path tarFile, Path staging) throws IOException {
        int entries = 0;
        int executables = 0;
        Set<String> seen = new HashSet<>();
        // Pending overrides carried by GNU 'L'/'K' and PAX 'x' metadata entries.
        String pendingName = null;
        String pendingLinkName = null;
        try (GZIPInputStream gzip = new GZIPInputStream(
                Files.newInputStream(tarFile), 64 * 1024)) {
            byte[] header = new byte[512];
            byte[] block = new byte[512];
            while (true) {
                if (!readBlock(gzip, header)) {
                    throw new ExtractionRejectionException(REASON_ARCHIVE_UNREADABLE,
                            "truncated before a 512-byte header");
                }
                if (isZeroBlock(header)) {
                    break;
                }
                verifyHeaderChecksum(header);
                long size = parseSize(header);
                int type = header[156] & 0xFF;
                boolean metadata = type == 'x' || type == 'g' || type == 'L' || type == 'K';
                if (metadata && size > MAX_METADATA_PAYLOAD_BYTES) {
                    throw new ExtractionRejectionException(REASON_ARCHIVE_UNREADABLE,
                            "oversized metadata payload");
                }
                if (type == 'x' || type == 'g') {
                    // PAX metadata: consume the payload for a path override
                    // ('x' only; 'g' globals are ignored). Pending overrides
                    // are not consumed here — they still apply to the next
                    // real entry after this metadata.
                    byte[] payload = new byte[(int) size];
                    readPayload(gzip, payload, header);
                    if (type == 'x') {
                        String paxPath = paxRecord(payload, "path");
                        if (paxPath != null) {
                            pendingName = paxPath;
                        }
                    }
                    continue;
                }
                if (type == 'L') {
                    byte[] payload = new byte[(int) size];
                    readPayload(gzip, payload, header);
                    pendingName = trimNul(payload, 0, payload.length);
                    continue;
                }
                if (type == 'K') {
                    byte[] payload = new byte[(int) size];
                    readPayload(gzip, payload, header);
                    pendingLinkName = trimNul(payload, 0, payload.length);
                    continue;
                }
                // A real entry: the pending overrides (GNU long name/link name
                // or a PAX path) apply exactly once, here.
                String name = pendingName != null
                        ? pendingName
                        : trimNul(header, 0, 100);
                String linkName = pendingLinkName != null
                        ? pendingLinkName
                        : (type == '1' || type == '2') ? trimNul(header, 157, 100) : null;
                pendingName = null;
                pendingLinkName = null;
                long mode = parseMode(header);
                String normalized = normalizeEntryName(name);
                if (normalized.isEmpty()) {
                    // The archive root ("./") — the staging dir itself.
                    skipPayload(gzip, size, block);
                    continue;
                }
                if (!seen.add(normalized)) {
                    throw new ExtractionRejectionException(REASON_DUPLICATE_ENTRY, normalized);
                }
                Path target = staging.resolve(normalized);
                requireTraversableParent(staging, target.getParent());
                if (type == '0' || type == 0) {
                    Files.createDirectories(target.getParent());
                    try (var out = Files.newOutputStream(target,
                            java.nio.file.StandardOpenOption.CREATE_NEW,
                            java.nio.file.StandardOpenOption.WRITE)) {
                        copyPayload(gzip, out, size, block);
                    }
                    // The exec bit, preserved at the Java layer for every mode
                    // that carries any execute bit — the device-side contract.
                    if ((mode & 0111) != 0 && target.toFile().setExecutable(true, false)) {
                        executables++;
                    }
                    entries++;
                } else if (type == '5') {
                    Files.createDirectories(target);
                    entries++;
                } else if (type == '2') {
                    Files.createDirectories(target.getParent());
                    if (linkName == null || linkName.isEmpty()) {
                        throw new ExtractionRejectionException(REASON_MALFORMED_LINK, name);
                    }
                    try {
                        Files.createSymbolicLink(target, java.nio.file.Paths.get(linkName));
                    } catch (java.nio.file.InvalidPathException malformed) {
                        throw new ExtractionRejectionException(REASON_MALFORMED_LINK, name);
                    }
                    entries++;
                } else if (type == '1') {
                    // A hard link IS dereferenced at creation time — its target
                    // must be a normalized, contained, already-materialized name.
                    String linkTarget = normalizeEntryName(linkName);
                    if (linkTarget.isEmpty()) {
                        throw new ExtractionRejectionException(REASON_ENTRY_ESCAPES_EXTRACT_ROOT,
                                "hard link with empty target: " + name);
                    }
                    if (!seen.contains(linkTarget)) {
                        throw new ExtractionRejectionException(REASON_HARD_LINK_DANGLING,
                                normalized + " -> " + linkTarget);
                    }
                    Files.createDirectories(target.getParent());
                    Files.createLink(target, staging.resolve(linkTarget));
                    entries++;
                } else {
                    throw new ExtractionRejectionException(REASON_ENTRY_TYPE_UNSUPPORTED,
                            "typeflag=" + type + " name=" + name);
                }
                skipPayload(gzip, size, block);
            }
        } catch (java.util.zip.ZipException malformed) {
            throw new ExtractionRejectionException(REASON_ARCHIVE_UNREADABLE,
                    String.valueOf(malformed.getMessage()));
        } catch (java.io.EOFException truncated) {
            // The gzip member itself ended mid-stream — a truncated artifact.
            throw new ExtractionRejectionException(REASON_ARCHIVE_UNREADABLE,
                    "gzip stream ended mid-archive");
        }
        return new ExtractResult(entries, executables);
    }

    // ---------------------------------------------------------------------------------
    // Tar primitives
    // ---------------------------------------------------------------------------------

    /** Reads exactly 512 bytes; short reads mean a truncated archive. */
    private static boolean readBlock(GZIPInputStream in, byte[] block) throws IOException {
        int read = 0;
        while (read < block.length) {
            int count = in.read(block, read, block.length - read);
            if (count < 0) {
                return read == block.length;
            }
            read += count;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The ustar header checksum: unsigned byte sum with the checksum field
     * read as spaces; the signed variant is accepted the way standard readers
     * do. Anything else is a corrupt header — fail closed.
     */
    private static void verifyHeaderChecksum(byte[] header) throws IOException {
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < 512; i++) {
            unsigned += header[i] & 0xFF;
            signed += header[i];
        }
        for (int i = 148; i < 156; i++) {
            unsigned += 0x20 - (header[i] & 0xFF);
            signed += 0x20 - header[i];
        }
        long stored = parseOctal(header, 148, 8);
        if (stored != unsigned && stored != signed) {
            throw new ExtractionRejectionException(REASON_ARCHIVE_UNREADABLE,
                    "bad header checksum");
        }
    }

    private static long parseMode(byte[] header) throws IOException {
        return parseOctal(header, 100, 8);
    }

    private static long parseSize(byte[] header) throws IOException {
        // A base-256 (high-bit) size field is a GNU extension this minimal
        // extractor does not accept — fail closed rather than guess.
        if ((header[124] & 0x80) != 0) {
            throw new ExtractionRejectionException(REASON_ARCHIVE_UNREADABLE,
                    "base-256 size field");
        }
        return parseOctal(header, 124, 12);
    }

    /** Octal field parse; NUL/space padding is tolerated at both ends. */
    private static long parseOctal(byte[] header, int offset, int length) throws IOException {
        int start = offset;
        int end = offset + length;
        while (start < end && (header[start] == ' ' || header[start] == 0)) {
            start++;
        }
        while (end > start && (header[end - 1] == ' ' || header[end - 1] == 0)) {
            end--;
        }
        if (start == end) {
            return 0L;
        }
        long value = 0;
        for (int i = start; i < end; i++) {
            byte b = header[i];
            if (b < '0' || b > '7') {
                throw new ExtractionRejectionException(REASON_ARCHIVE_UNREADABLE,
                        "non-octal digit in numeric field");
            }
            value = (value << 3) + (b - '0');
        }
        return value;
    }

    private static String trimNul(byte[] buffer, int offset, int maxLength) {
        int end = offset;
        int limit = Math.min(offset + maxLength, buffer.length);
        while (end < limit && buffer[end] != 0) {
            end++;
        }
        return new String(buffer, offset, end - offset, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The slip gate for entry names: absolute names and any {@code ..}
     * segment are rejected, {@code .} and empty segments collapse, backslash
     * is an ordinary character (POSIX names, not Windows paths). Returns the
     * normalized relative name; empty means the archive root itself.
     */
    private static String normalizeEntryName(String raw) throws IOException {
        if (raw.indexOf(0) >= 0) {
            throw new ExtractionRejectionException(REASON_ENTRY_ESCAPES_EXTRACT_ROOT,
                    "NUL byte in entry name");
        }
        if (raw.startsWith("/")) {
            throw new ExtractionRejectionException(REASON_ENTRY_ESCAPES_EXTRACT_ROOT,
                    "absolute entry name: " + raw);
        }
        StringBuilder normalized = new StringBuilder();
        for (String segment : raw.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new ExtractionRejectionException(REASON_ENTRY_ESCAPES_EXTRACT_ROOT,
                        "parent segment in entry name: " + raw);
            }
            if (normalized.length() > 0) {
                normalized.append('/');
            }
            normalized.append(segment);
        }
        return normalized.toString();
    }

    /**
     * The symlink-traversal gate: every path component from the extract root
     * down to {@code dir} must be either not-yet-existing (created by this
     * call) or a real directory — an existing symbolic link (planted by an
     * earlier entry) would redirect the write outside the tree. The walk and
     * the write happen in one thread on the app-private filesystem, so the
     * check is stable against this extractor's own materializations.
     */
    private static void requireTraversableParent(Path staging, Path parent) throws IOException {
        Path current = staging;
        for (Path segment : staging.relativize(parent)) {
            current = current.resolve(segment);
            if (!Files.exists(current)) {
                return;
            }
            if (Files.isSymbolicLink(current)) {
                throw new ExtractionRejectionException(REASON_PARENT_SYMLINK_TRAVERSAL,
                        current.toString());
            }
            if (!Files.isDirectory(current)) {
                throw new ExtractionRejectionException(REASON_PARENT_PATH_CONFLICT,
                        current.toString());
            }
        }
    }

    private static String paxRecord(byte[] payload, String key) {
        // POSIX PAX record: "<len> <key>=<value>\n", len counts the whole record.
        int i = 0;
        while (i < payload.length) {
            int space = i;
            while (space < payload.length && payload[space] != ' ') {
                space++;
            }
            int recordLength;
            try {
                recordLength = Integer.parseInt(
                        new String(payload, i, space - i, java.nio.charset.StandardCharsets.US_ASCII));
            } catch (NumberFormatException malformed) {
                return null;
            }
            if (recordLength <= 0 || i + recordLength > payload.length) {
                return null;
            }
            int keyStart = space + 1;
            int equals = keyStart;
            while (equals < i + recordLength && payload[equals] != '=') {
                equals++;
            }
            if (equals >= i + recordLength) {
                i += recordLength;
                continue;
            }
            String recordKey = new String(payload, keyStart, equals - keyStart,
                    java.nio.charset.StandardCharsets.US_ASCII);
            if (recordKey.equals(key)) {
                // The record's trailing newline is not part of the value.
                int valueEnd = i + recordLength - 1;
                if (valueEnd > equals + 1 && payload[valueEnd] == '\n') {
                    valueEnd--;
                }
                return new String(payload, equals + 1, valueEnd - equals,
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            i += recordLength;
        }
        return null;
    }

    /** Copies exactly {@code size} payload bytes to {@code out} (regular-file data). */
    private static void copyPayload(GZIPInputStream in, java.io.OutputStream out, long size,
            byte[] block) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, block.length);
            readFully(in, block, chunk);
            out.write(block, 0, chunk);
            remaining -= chunk;
        }
    }

    /** Reads {@code size} payload bytes into {@code payload} (metadata entries). */
    private static void readPayload(GZIPInputStream in, byte[] payload, byte[] block)
            throws IOException {
        readFully(in, payload, payload.length);
        skipPayload(in, payload.length, block);
    }

    /** Discards the 512-byte padding that follows a payload of {@code size} bytes. */
    private static void skipPayload(GZIPInputStream in, long size, byte[] block)
            throws IOException {
        long padding = (512 - (size % 512)) % 512;
        while (padding > 0) {
            int chunk = (int) Math.min(padding, block.length);
            readFully(in, block, chunk);
            padding -= chunk;
        }
    }

    private static void readFully(GZIPInputStream in, byte[] buffer, int length)
            throws IOException {
        int read = 0;
        while (read < length) {
            int count = in.read(buffer, read, length - read);
            if (count < 0) {
                throw new ExtractionRejectionException(REASON_ARCHIVE_UNREADABLE,
                        "truncated payload");
            }
            read += count;
        }
    }

    // ---------------------------------------------------------------------------------
    // Idempotency: pin reconciliation + the .verified sidecar
    // ---------------------------------------------------------------------------------

    /**
     * The pin compare, constant-time — the same discipline the L2 layer uses;
     * both arguments are always 64 lowercase hex digits in practice.
     */
    private static boolean pinMatches(String pin, String observed) {
        return MessageDigest.isEqual(
                pin.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                observed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The sidecar attestation: plain {@code key=value} lines naming the pin
     * the extracted tree came from plus the honest extraction counts. The pin
     * line is the only reconciliation authority; the counts ride along for
     * receipts of idempotent hits. The pin value is passed straight from the
     * {@link Entry} — this class never restates it.
     */
    private static void writeSidecar(Path sidecar, String pin, ExtractResult result)
            throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("pin=" + pin);
        lines.add("attested=extracted-from-pinned-artifact");
        lines.add("entries=" + result.entries());
        lines.add("executables=" + result.executables());
        Files.write(sidecar, lines);
    }

    /**
     * Whether the sidecar attests {@code pin}. The pin line is stamped at
     * extraction success ({@link #extractVerifiedTar}) and compared
     * constant-time here.
     */
    private static boolean attestsPin(Path sidecar, String pin) throws IOException {
        if (!Files.isRegularFile(sidecar)) {
            return false;
        }
        List<String> lines = Files.readAllLines(sidecar);
        for (String line : lines) {
            if (line.startsWith("pin=")) {
                return pinMatches(pin, line.substring("pin=".length()));
            }
        }
        return false;
    }

    private static int countedEntries(Path sidecar) throws IOException {
        return countedField(sidecar, "entries=");
    }

    private static int countedExecutables(Path sidecar) throws IOException {
        return countedField(sidecar, "executables=");
    }

    private static int countedField(Path sidecar, String prefix) throws IOException {
        for (String line : Files.readAllLines(sidecar)) {
            if (line.startsWith(prefix)) {
                try {
                    return Integer.parseInt(line.substring(prefix.length()));
                } catch (NumberFormatException malformed) {
                    return 0;
                }
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------------------------
    // Filesystem primitives
    // ---------------------------------------------------------------------------------

    /**
     * The extract directory's name for an entry: the formal file name minus
     * its {@code .tar.gz} suffix ({@code alpine-minirootfs-3.22.2-aarch64}),
     * so a pin upgrade re-extracts into a fresh, version-named directory.
     */
    static String extractDirName(Entry entry) {
        String fileName = entry.fileName();
        return fileName.endsWith(".tar.gz") ? fileName.substring(0, fileName.length() - 7)
                : fileName;
    }

    private static void moveIntoPlace(Path staging, Path formal) throws IOException {
        try {
            Files.move(staging, formal, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(staging, formal);
        }
    }

    /**
     * Depth-first recursive delete; directory symlinks are deleted as links
     * (the walk never follows them), so the sweep cannot be redirected
     * outside the tree it is given.
     */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }
}
