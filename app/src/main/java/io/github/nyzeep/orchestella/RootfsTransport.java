package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.Entry;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.SelfCheckReport;
import io.github.nyzeep.orchestella.runtime.network.download.DownloadEngine;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * Lane D: the transport wiring — the one place where the {@code runtime:network}
 * download engine and the 方案 B verification chain meet, closing the loop
 * <i>download → {@code .part} atomic staging → received bytes → L1/L2/L3 →
 * atomic publish</i>. This class orchestrates only: it invents no bytes, adds
 * no mirrors and never retries past what the transport itself retries — every
 * verdict comes from {@link UmlKernelAssets.RootfsDownload#requireVerifiedAndSelfChecked},
 * which fails closed on any layer.
 *
 * <p><b>The loop, and who owns which half.</b> The {@link DownloadEngine}
 * (production: {@code ResilientDownloadEngine}) owns L1's transport half: it
 * pulls {@link Entry#source()} into the staged {@code .part} name through its
 * own atomic-write protocol (an inner {@code <staged>.part} file, HTTP
 * {@code Range} resume across its retry loop, {@code Content-Length} parity,
 * a streaming SHA-256 gate that purges and fails fast on a pin mismatch). This
 * class then owns the verification half on the received bytes: the declared
 * size handed to {@code requireVerified} is the received byte count — the
 * transport-declared {@code Content-Length} parity was already the engine's
 * job, so a transport that reports X and delivers Y never reaches here. L2
 * (constant-time pin) and L3 (five-stage content self-check) run through the
 * chain call; only after both pass are the staged bytes atomically renamed to
 * the formal {@link Entry#fileName()} under {@code destDir}, and only the
 * receipt ({@link DownloadReceipt}) says so.
 *
 * <p><b>Fail-closed staging ({@code 不留半成品}).</b> The staged name is
 * {@code destDir/<fileName>.part} — anything wearing the {@code .part} suffix
 * is by contract an unverified artifact, never read as a product. Every
 * failure path (transport error, L1 size rejection, L2 pin mismatch, L3
 * content rejection) sweeps the staged name <i>and</i> the engine's inner
 * {@code <staged>.part} before the failure propagates, so a rejected download
 * leaves {@code destDir} byte-identical to its state before the call. The
 * sweep consciously trades away the engine's cross-call {@code .part} resume:
 * resume within one call's retry loop (the engine's own backoff loop against
 * the CDN's {@code Accept-Ranges}) is preserved and contract-tested, but an
 * orchestration that failed must not leave half products behind for the next
 * one to silently trust. Engine behavior findings (notably: the engine sends
 * {@code Range} but never {@code If-Range} — resume safety across a changed
 * CDN representation rides on the L2 pin instead) are locked by
 * {@code RootfsDownloadResumeContractTest} and recorded in the download
 * verification pipeline research note §6.
 *
 * <p><b>Flag gate ({@code width-0} until the ACR lands).</b> The download
 * entry is reachable only when the build opened it — the production overload
 * reads {@code BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED} (gradle property
 * {@code orchestella.uml.rootfsDownload}, default {@code false}); the
 * flag-injectable core exists so tests — and a future wiring owner with a
 * different gate — can drive both branches. Off means the disabled receipt,
 * zero transport calls, zero filesystem touches: the shipped default build
 * behaves byte-identically to before this class existed. No product path calls
 * this yet; the ACR wiring round owns that call site.
 *
 * <p><b>Memory shape (Lane C 未决 #4, consciously unchanged).</b> The chain
 * consumes {@code byte[]}; this round's official artifact is the 3.8 MB
 * Alpine minirootfs tar, where a single in-memory read is the right size. A
 * {@code File}-streaming chain overload for 100 MB-scale prebaked tars stays
 * an open item with the pipeline note — it is not built here.
 */
public final class RootfsTransport {

    /**
     * The staging marker: a {@code .part}-suffixed name is an unverified
     * artifact by contract — the engine's own atomic-write protocol ends here,
     * and only {@link #downloadAndVerify}'s verified atomic publish may remove
     * the suffix by renaming.
     */
    public static final String STAGING_SUFFIX = ".part";

    private RootfsTransport() {
    }

    /**
     * The production entry: gated by {@code BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED}
     * — the gradle property {@code orchestella.uml.rootfsDownload} compiled in,
     * default off. The gate is inside the entry on purpose: no call site can
     * reach the transport without passing it.
     */
    public static DownloadReceipt downloadAndVerify(
            DownloadEngine transport, Entry entry, Path destDir) throws IOException {
        return downloadAndVerify(transport, entry, destDir, BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED);
    }

    /**
     * The flag-injectable orchestration (tests own the flag; production always
     * goes through the single-source {@link #downloadAndVerify(DownloadEngine,
     * Entry, Path)} overload). Flag off answers the {@linkplain
     * DownloadReceipt#disabled() disabled receipt} before the transport or the
     * filesystem is consulted at all. Flag on runs the full loop: transport →
     * staged {@code .part} → received bytes → L1/L2/L3 fail-closed chain →
     * atomic publish under the formal name.
     *
     * @throws IOException the transport's failure or a {@code
     *         UmlKernelAssets.RootfsDownload.RootfsRejectionException} with a
     *         stable reason code; in every case the staged {@code .part} names
     *         are swept first — {@code destDir} never keeps a half product
     */
    public static DownloadReceipt downloadAndVerify(
            DownloadEngine transport, Entry entry, Path destDir,
            boolean rootfsDownloadBuildFlag) throws IOException {
        if (!rootfsDownloadBuildFlag) {
            // The gate answers first: a disabled download must not even name a
            // path — zero transport calls, zero filesystem touches.
            return DownloadReceipt.disabled();
        }
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(destDir, "destDir");
        Path staged = destDir.resolve(entry.fileName() + STAGING_SUFFIX);
        // The engine's inner part file, derived by the engine's own rule
        // (destination sibling + ".part") — read-only reuse, the rule is the
        // engine's, not ours.
        Path enginePart = staged.resolveSibling(staged.getFileName() + STAGING_SUFFIX);
        try {
            // One fixed HTTPS source — the pinned entry's own URL. No mirror
            // list, no rotation: the distro-catalog discipline keeps candidate
            // identity to the pinned source, and network-level resilience is
            // the engine's retry loop, not a second URL.
            transport.download(List.of(entry.source().toString()), staged,
                    entry.sha256Pin(), null);
            byte[] received = Files.readAllBytes(staged);
            SelfCheckReport report = UmlKernelAssets.RootfsDownload.requireVerifiedAndSelfChecked(
                    entry, received, (long) received.length);
            Path formal = destDir.resolve(entry.fileName());
            atomicPublish(staged, formal);
            // The receipt's sha is re-read from the published file — the
            // digest of the bytes actually sitting at the formal name.
            return new DownloadReceipt(true, entry.fileName(), formal, received.length,
                    UmlKernelAssets.sha256Hex(formal.toFile()), report);
        } catch (IOException failure) {
            sweepStaging(staged, enginePart);
            throw failure;
        } catch (RuntimeException failure) {
            sweepStaging(staged, enginePart);
            throw failure;
        }
    }

    /**
     * The verified atomic publish: the staged bytes pass every layer only
     * once, and the formal name must not exist before that moment — an old or
     * corrupt file at the formal name is replaced by the verified one, never
     * left standing beside a refusal.
     */
    private static void atomicPublish(Path staged, Path formal) throws IOException {
        try {
            Files.move(staged, formal,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(staged, formal, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The fail-closed sweep: both staged names (this class's {@code .part} and
     * the engine's inner one) are best-effort deleted before any failure
     * propagates. A cleanup failure must not mask the primary failure, so it
     * is swallowed here — the no-half-products contract is asserted by the
     * contract tests against the working filesystem, not by this swallow.
     */
    private static void sweepStaging(Path staged, Path enginePart) {
        try {
            Files.deleteIfExists(staged);
        } catch (IOException ignored) {
            // Best effort: the primary failure propagates.
        }
        try {
            Files.deleteIfExists(enginePart);
        } catch (IOException ignored) {
            // Best effort: the primary failure propagates.
        }
    }

    /**
     * The orchestration's answer. The enabled receipt exists only after the
     * formal name does: {@link #file()} is the published path, {@link #sha256()}
     * its observed digest, {@link #selfCheck()} the L3 report (with the
     * declared-only stage honestly {@code PLANNED}). The disabled receipt
     * carries nothing at all — mirroring {@code
     * UmlKernelAssets.FastProfileBundleReport}'s rule that a disabled answer
     * must not imply a verification that never ran.
     */
    public record DownloadReceipt(boolean enabled, String fileName, Path file, long sizeBytes,
            String sha256, SelfCheckReport selfCheck) {

        public DownloadReceipt {
            if (!enabled && (fileName != null || file != null || sizeBytes != 0L
                    || sha256 != null || selfCheck != null)) {
                throw new IllegalArgumentException(
                        "a disabled rootfs-download receipt must not carry a staged"
                                + " or verified artifact");
            }
        }

        /** The flag-off answer: no transport ran, nothing was downloaded, nothing exists. */
        public static DownloadReceipt disabled() {
            return new DownloadReceipt(false, null, null, 0L, null, null);
        }
    }
}
