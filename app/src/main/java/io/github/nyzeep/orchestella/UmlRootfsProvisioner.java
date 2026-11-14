package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.Entry;
import io.github.nyzeep.orchestella.runtime.network.download.DownloadEngine;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
 *   alpine-minirootfs-3.22.2-aarch64/um-command.sh   the injected external-root launcher (0755)
 *   alpine-minirootfs-3.22.2-aarch64.verified        the extraction attestation (idempotency key)
 *   alpine-minirootfs-3.22.2-aarch64.part/           transient extraction staging, always swept
 * </pre>
 * The {@code .part} suffix discipline mirrors {@link RootfsTransport}: a
 * {@code .part}-suffixed name is an unverified artifact, never consumed; every
 * failure path sweeps it before propagating.
 *
 * <p><b>Idempotency (pin-sha reconciliation, not file-name trust).</b> All
 * three stages reconcile by content digest — the single source of the pin
 * stays {@link UmlKernelAssets}. The download stage skips the network
 * when the formal tar's observed digest equals the pin (a corrupt or stale
 * artifact fails the compare and is replaced by a fresh verified download);
 * the extraction stage skips re-unpacking when the {@code .verified} sidecar
 * attests the same pin; the launcher injection skips the write when the
 * extract tree's {@code um-command.sh} already carries the owned bytes' digest
 * (a missing, tampered, or foreign launcher is replaced by the owned content —
 * this class is the launcher's single owner). An already-verified product
 * therefore answers without a single transport call and without a single
 * launcher rewrite — repeated entry actions never waste traffic.
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
 * <p><b>The external-root launcher (2026-11-13 主会话裁决, ACR §8 candidate
 * A).</b> The official Alpine minirootfs ships no {@code /um-command.sh}, but
 * the research initramfs's external-root path demands an executable {@code
 * /newroot/um-command.sh} before it will {@code switch_root} ({@code
 * research/uml-fastboot/initramfs/init}, {@code fail 29 missing_command}).
 * After a successful extraction this provisioner therefore <i>injects</i> the
 * owned launcher into the extract tree's root ({@code 0755}, shebang {@code
 * #!/bin/busybox sh}) — a byte-faithful port of the research probe script
 * {@code research/uml-fastboot/initramfs/um-command.sh}, embedded as {@link
 * #UM_COMMAND_LAUNCHER} (no assets dependency, the pinned kernel stays
 * untouched). Since the launcher's v2 the script also carries the guest-
 * network boot branch under the {@code um.net=1} kernel knob (wired compile-
 * route arm only: passt DHCP, apk add distcc, distccd daemonized on 3632,
 * resident instead of powering off — see the constant's provenance header);
 * without the knob the v1 probe behavior is unchanged line for line. The
 * injection is a <i>launch-chain preparation</i>, not a capability promise.
 *
 * <p><b>Honesty boundary of the ready state (2026-11-13 主会话裁决).</b>
 * A provisioned extract is exactly "verified + extracted" — never "startable
 * to READY" and never "compile-capable", with or without the injected
 * launcher: reaching READY on a device over the hostfs external-root channel
 * is a face-launch-chain result the device window must attest, not something
 * this class, its receipt, or its readiness probe claims. The readiness
 * answer's semantics are deliberately unchanged by the injection (still the
 * two reconciliations of the pin and the sidecar), and the {@code .verified}
 * sidecar keeps meaning extraction only — it is written before any injection
 * and never by one. The compile channel's own honesty edge is {@code
 * CHANNEL_UNWIRED} (Lane A); launch usability of the downloaded rootfs is a
 * fabric-route-round design problem against the real init semantics.
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

    /**
     * The external-root launcher's name at the extract tree's root — the exact
     * path the research init's {@code switch_root} contract demands ({@code
     * [ -x /newroot/um-command.sh ]}). Never renamed by a pin upgrade: the
     * contract path is fixed.
     */
    public static final String LAUNCHER_FILE_NAME = "um-command.sh";

    /**
     * Stable rejection reason for the launcher injection stage's own failures
     * (write, chmod, move): an {@link ExtractionRejectionException} with this
     * reason propagates fail-closed — the provision never answers ready over a
     * launcher that is not in place.
     */
    public static final String REASON_LAUNCHER_INJECTION_FAILED =
            "uml-rootfs-launcher-injection-failed";

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
     * verified tar into place → (skipped or) launcher injection into the
     * extract tree's root → the receipt.
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

        // Stage 3: launcher injection (skipped when the extract tree's
        // /um-command.sh already carries the owned bytes' digest). The sidecar
        // was written before this point and is never touched here — the
        // attestation keeps meaning extraction only.
        ensureUmCommandLauncher(extractDir);

        return new ProvisionReceipt(
                true,
                !downloadedThisCall && result == null,
                entry.fileName(),
                formalTar,
                Files.size(formalTar),
                UmlKernelAssets.sha256Hex(formalTar.toFile()),
                extractDir,
                result == null ? countedEntries(sidecar) : result.entries(),
                result == null ? countedExecutables(sidecar) : result.executables(),
                true);
    }

    /**
     * The orchestration's answer. The enabled receipt exists only after the
     * verified tar <i>and</i> the extracted, attested tree <i>and</i> the
     * external-root launcher are all in place: {@code file()} is the pinned
     * artifact, {@code sha256()} its observed digest, {@code extractedDir()}
     * the HOSTFS {@code umroot} target, {@code
     * extractedEntries()}/{@code executableEntries()} the honest extraction
     * counts (executables = regular files extracted with an exec bit — the
     * injected launcher is never counted, it is not a tar entry), and {@code
     * injectedLauncher()} attests that the extract tree's {@code
     * /um-command.sh} is the provisioner-owned launcher, digest-verified
     * before the receipt exists — a launch-chain preparation, never a
     * startability claim. An idempotent hit sets {@code alreadyVerified()}
     * and made no transport call and no launcher rewrite. The disabled
     * receipt carries nothing at all — a disabled answer must not imply a
     * provision that never ran.
     */
    public record ProvisionReceipt(boolean enabled, boolean alreadyVerified, String fileName,
            Path file, long sizeBytes, String sha256, Path extractedDir,
            int extractedEntries, int executableEntries, boolean injectedLauncher) {

        public ProvisionReceipt {
            if (!enabled && (fileName != null || file != null || sizeBytes != 0L
                    || sha256 != null || extractedDir != null
                    || extractedEntries != 0 || executableEntries != 0 || injectedLauncher)) {
                throw new IllegalArgumentException(
                        "a disabled uml-rootfs provision receipt must not carry"
                                + " a provisioned artifact or an injected launcher");
            }
        }

        /** The flag-off answer: no transport ran, nothing was read, nothing exists. */
        public static ProvisionReceipt disabled() {
            return new ProvisionReceipt(false, false, null, null, 0L, null, null, 0, 0, false);
        }
    }

    /**
     * A rejection with a stable reason code carrying this class's own fail-
     * closed verdicts — the extraction slip-defense matrix, and the launcher
     * injection stage's write/chmod/move failures ({@link
     * #REASON_LAUNCHER_INJECTION_FAILED}): never a raw archive byte, never a
     * raw filesystem error surfaced as a success, never laundered into ready.
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

        ExtractionRejectionException(String reason, String detail, Throwable cause) {
            super(detail == null || detail.isEmpty() ? reason : reason + ": " + detail, cause);
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
                    applyDirModeBestEffort(target, mode);
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

    /**
     * Best-effort application of a directory entry's tar mode. Device-measured
     * 2026-11-14 (Gate 2 E2E): {@link Files#createDirectories} leaves the
     * host's umask mode in place, so the Alpine tree's world-writable
     * {@code /tmp} (tar 1777) landed as 0700 — and guest-side daemons running
     * as a non-root user (distccd drops to its own uid before opening its
     * log) then hit hostfs EACCES on every path they need to write. The rwx
     * bits ride the POSIX attribute view when the host filesystem supports
     * it; the special bits (sticky/setuid) are best-effort-skipped — Android
     * exposes no unix-extended attribute view for them, and a regular 0777
     * {@code /tmp} serves the daemons the same. Every failure is swallowed on
     * purpose: a mode that does not stick is an honest runtime EACCES at the
     * daemon's own error site, never a reason to fail a verified extraction.
     */
    private static void applyDirModeBestEffort(Path dir, long mode) {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()
                .contains("posix")) {
            return;
        }
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                    java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class);
            if ((mode & 0400) != 0) {
                perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
            }
            if ((mode & 0200) != 0) {
                perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            }
            if ((mode & 0100) != 0) {
                perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            }
            if ((mode & 0040) != 0) {
                perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_READ);
            }
            if ((mode & 0020) != 0) {
                perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE);
            }
            if ((mode & 0010) != 0) {
                perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            }
            if ((mode & 0004) != 0) {
                perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
            }
            if ((mode & 0002) != 0) {
                perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
            }
            if ((mode & 0001) != 0) {
                perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            }
            Files.setPosixFilePermissions(dir, perms);
        } catch (RuntimeException | IOException bestEffortFailure) {
            // The host filesystem refused the mode — the guest daemon's own
            // error site carries the evidence; extraction stays verified.
        }
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
    // The external-root launcher: the /um-command.sh injection (ACR §8 candidate A)
    // ---------------------------------------------------------------------------------

    /**
     * The owned external-root launcher (v2) — a byte-faithful port of the
     * research probe script {@code research/uml-fastboot/initramfs/um-command.sh}:
     * every original line is verbatim, and the only addition is the v2
     * guest-network boot branch after the {@code U1_READY} marker, guarded by
     * the {@code um.net=1} kernel knob (the provenance header after the
     * shebang carries the version and the change note). The official
     * minirootfs ships no {@code /um-command.sh}, and the init's external-root
     * path refuses to {@code switch_root} without an executable one — so the
     * provisioner writes this constant into the extract tree's root. Embedded
     * as a constant on purpose: no assets dependency (the content survives a
     * missing-payload checkout), and this class is its single owner.
     * Package-private for the contract tests.
     *
     * <p>The script runs inside the <i>new root</i> after {@code switch_root}
     * (the init execs {@code /bin/busybox sh /um-command.sh}), where the guest
     * PATH carries busybox — so the port needs no hostfs-specific edit: the
     * U1 marker contract ({@code U1_INFO}/{@code U1_T2_READY}/{@code
     * U1_READY}), the busybox-true T3 probe, and the {@code um.hold_ms}/{@code
     * um.shell} branches are the research script's own semantics, kept as-is.
     *
     * <p><b>v2 semantics.</b> Without {@code um.net=1} the v1 probe path runs
     * unchanged — the hold_ms sleep, then sync and poweroff. With {@code
     * um.net=1} (the wired compile-route arm appends it via the extraArgs
     * seam, see {@code UmlRootfsExtraArgs}): the branch brings vec0 up, takes
     * the passt DHCP lease (the Alpine tree's own {@code
     * /usr/share/udhcpc/default.script} applies address, route and
     * resolv.conf), installs distccd through the passt→WAN CDN path when
     * missing, daemonizes it on 0.0.0.0:3632 for 10.0.2.0/24 — the D-1 lane's
     * dial target — and then holds resident instead of powering off: a wired
     * face is a long-lived compile host. Every step is best-effort with an
     * honest {@code UM_NET_ERROR} marker; nothing but the T3 probe failure
     * ever powers the guest off. A pre-v2 tree migrates by the injection's
     * ordinary digest repair (the owned digest no longer matches the v1
     * bytes, so the next provision rewrites the owned content).
     */
    static final String UM_COMMAND_LAUNCHER = """
            #!/bin/busybox sh
            # External-root edition v2 — injected into the extracted minirootfs tree by
            # UmlRootfsProvisioner: the official Alpine minirootfs ships no /um-command.sh,
            # and the fast init's external-root path refuses to switch_root without an
            # executable one (fail 29 missing_command, research/uml-fastboot/initramfs/init).
            # It runs inside the new root after switch_root, launched as
            # /bin/busybox sh /um-command.sh, where busybox is on PATH.
            #
            # v2 (2026-11-13, Gate 2 E2E): with um.net=1 on the kernel command line (the
            # wired compile-route arm appends it next to umroot=/umrootfstype=hostfs) the
            # script adds a guest-network boot branch after the T3 probe — vec0 up, a
            # passt DHCP lease (10.0.2.15; the tree's own /usr/share/udhcpc/default.script
            # applies it), apk add distcc when missing (Alpine CDN through passt→WAN), and
            # distccd daemonized on 0.0.0.0:3632 for 10.0.2.0/24 — then holds resident
            # instead of the hold_ms→poweroff path: a wired face is a long-lived compile
            # host, not a probe run. Every branch step is best-effort and honestly marked
            # (UM_NET milestones, UM_NET_ERROR lines); a failure never blocks the residency.
            # Without um.net=1 the v1 probe behavior is unchanged: every original line
            # stays verbatim, only this branch was added.
            #
            # Orchestella Studio — U1 fast-shell T3 probe.
            #
            # Runs as the first user command after the fast init.  It proves that the
            # guest can execute a real binary (busybox true) and prints the T3 marker with
            # the guest uptime.  Then it either drops to a shell (um.shell=1) or powers
            # the guest off after um.hold_ms (default 200 ms) so a batch harness can reap
            # a clean exit.
            #
            # Markers (parsed by bench-fast.py):
            #   U1_INFO kernel=<release> ncpus=<n>
            #   U1_T2_READY uptime=<seconds>   (root + pseudo-fs ready; this script is the
            #                                  first user command, after any switch_root)
            #   U1_READY uptime=<seconds>
            #   U1_CMD_ERROR ...              (a real command failed; run is failed)
            #   UM_*                          (init contract; see initramfs/init)

            set -u
            PATH=/bin:/sbin:/usr/bin:/usr/sbin
            export PATH

            param() {
                for word in $(cat /proc/cmdline 2>/dev/null); do
                    case "$word" in
                        "$1"=*) printf '%s' "${word#*=}"; return 0 ;;
                    esac
                done
                return 1
            }

            hold_ms=$(param um.hold_ms || printf 200)
            case "$hold_ms" in
                ''|*[!0-9]*) hold_ms=200 ;;
            esac

            ncpus=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null || :)
            [ -n "$ncpus" ] || ncpus=1
            kernel=$(uname -r 2>/dev/null || printf unknown)
            uptime=$(cut -d' ' -f1 /proc/uptime 2>/dev/null || printf unknown)
            printf 'U1_INFO kernel=%s ncpus=%s\\n' "$kernel" "$ncpus"
            # T2 is printed here, after the root (initramfs or switch_root target) and
            # pseudo-filesystems are ready.  The init no longer prints it: for the
            # umroot/switch_root path that would have been before the root switch.
            printf 'U1_T2_READY uptime=%s\\n' "$uptime"

            if [ "$(param um.shell || :)" = 1 ]; then
                exec /bin/sh
            fi

            # T3 = a real command, not just a shell builtin, completed successfully.
            if /bin/busybox true; then
                :
            else
                printf 'U1_CMD_ERROR cmd=busybox_true\\n' >&2
                poweroff -f 2>/dev/null || :
                while :; do sleep 3600; done
            fi

            printf 'U1_READY uptime=%s\\n' "$uptime"

            # launcher v2: the guest-network boot branch, entered only under the
            # um.net=1 knob (the wired compile-route arm).  Best-effort at every
            # step — a failure prints its UM_NET_ERROR line and the branch still
            # ends in the resident loop below, so the face stays up, honest and
            # diagnosable (never a faked-ready guest, never a probe poweroff).
            if [ "$(param um.net || :)" = 1 ]; then
                ip link set vec0 up 2>/dev/null || :
                if udhcpc -i vec0 -q 2>/dev/null; then
                    leased=$(ip -4 addr show dev vec0 2>/dev/null | sed -n 's/.*inet \\([0-9.]*\\).*/\\1/p' | head -n 1)
                    printf 'UM_NET dhcp bound ip=%s\\n' "${leased:-unknown}"
                else
                    printf 'UM_NET_ERROR step=dhcp detail=udhcpc failed on vec0\\n' >&2
                fi

                if command -v distccd >/dev/null 2>&1; then
                    printf 'UM_NET distccd present\\n'
                else
                    if apk add distcc 2>/dev/null; then
                        printf 'UM_NET distccd installed\\n'
                    else
                        printf 'UM_NET_ERROR step=apk detail=apk add distcc failed\\n' >&2
                    fi
                fi

                if command -v distccd >/dev/null 2>&1; then
                    if distccd --daemon --user distcc --listen 0.0.0.0 --port 3632 --allow 10.0.2.0/24 --log-file /tmp/distccd.log --pid-file /tmp/distccd.pid 2>/tmp/distccd.err; then
                        printf 'UM_NET distccd listening port=3632 user=distcc\\n'
                    elif distccd --daemon --user nobody --listen 0.0.0.0 --port 3632 --allow 10.0.2.0/24 --log-file /tmp/distccd.log --pid-file /tmp/distccd.pid 2>/tmp/distccd.err; then
                        printf 'UM_NET distccd listening port=3632 user=nobody\\n'
                    else
                        printf 'UM_NET_ERROR step=distccd detail=rc=%s err=%s\\n' "$?" "$(tail -1 /tmp/distccd.err 2>/dev/null)" >&2
                    fi
                else
                    printf 'UM_NET_ERROR step=distccd detail=distccd binary missing after install\\n' >&2
                fi

                printf 'UM_NET resident: wired face holding, distccd boot done\\n'
                while :; do sleep 3600; done
            fi

            if [ "$hold_ms" -ge 1000 ]; then
                sleep $((hold_ms / 1000)) 2>/dev/null || sleep 1
            else
                sleep "0.$(printf '%03d' "$hold_ms")" 2>/dev/null || sleep 1
            fi
            sync 2>/dev/null || :
            poweroff -f 2>/dev/null || :
            while :; do sleep 3600; done

            """;

    /** The owned launcher's own digest — the injection stage's idempotency authority. */
    private static final String LAUNCHER_SHA256 =
            sha256Hex(UM_COMMAND_LAUNCHER.getBytes(StandardCharsets.UTF_8));

    /** The SHA-256 of raw bytes as lowercase hex (the launcher constant's digest). */
    private static String sha256Hex(byte[] bytes) {
        try {
            return toLowerHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("every JVM carries SHA-256", impossible);
        }
    }

    private static String toLowerHex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * The launcher injection stage: the extract tree's root must carry the
     * owned {@code /um-command.sh}, executable, before the provision may
     * answer — the init fails {@code 29 missing_command} without it.
     * Idempotent by digest: a regular file already carrying the owned bytes'
     * digest is skipped without a rewrite (a repeated provision never
     * rewrites the launcher); anything else — missing, tampered, foreign, a
     * directory planted on the name — is replaced by the owned content, the
     * single source of which is {@link #UM_COMMAND_LAUNCHER}. The write goes
     * through a {@code .part} staging name (never consumed, swept first and
     * on every failure) that is chmod'ed executable <i>before</i> an atomic
     * move into place, so the formal name only ever holds a complete,
     * executable file. Every failure propagates fail-closed as an {@link
     * ExtractionRejectionException} with {@link #REASON_LAUNCHER_INJECTION_FAILED}
     * — never laundered into a receipt.
     */
    private static void ensureUmCommandLauncher(Path extractDir) throws IOException {
        Path target = extractDir.resolve(LAUNCHER_FILE_NAME);
        if (Files.isRegularFile(target)
                && pinMatches(LAUNCHER_SHA256, UmlKernelAssets.sha256Hex(target.toFile()))) {
            // Already the owned launcher — nothing to write, nothing to touch.
            return;
        }
        Path staging = extractDir.resolve(LAUNCHER_FILE_NAME + EXTRACT_STAGING_SUFFIX);
        try {
            // The .part discipline: sweep any leftover staging, then create
            // the payload with CREATE_NEW — nothing pre-existing is followed
            // or truncated, and the formal name only ever gains a complete
            // file by an atomic move.
            deleteRecursively(staging);
            Files.write(staging, UM_COMMAND_LAUNCHER.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            // 0755 by construction: the owner keeps its created rw, and every
            // class gains read + execute — the init's `-x /um-command.sh`
            // contract. The exec bit precedes the move, so the formal name
            // never holds a non-executable file.
            File launcher = staging.toFile();
            if (!launcher.setReadable(true, false) || !launcher.setExecutable(true, false)) {
                throw new ExtractionRejectionException(REASON_LAUNCHER_INJECTION_FAILED,
                        "chmod failed: " + staging);
            }
            try {
                Files.move(staging, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (ExtractionRejectionException rejection) {
            deleteRecursively(staging);
            throw rejection;
        } catch (IOException failure) {
            deleteRecursively(staging);
            throw new ExtractionRejectionException(REASON_LAUNCHER_INJECTION_FAILED,
                    failure.getMessage(), failure);
        } catch (RuntimeException failure) {
            deleteRecursively(staging);
            throw failure;
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
