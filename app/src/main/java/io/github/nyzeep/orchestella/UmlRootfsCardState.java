package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.Entry;
import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.RootfsRejectionException;
import java.io.IOException;
import java.util.Objects;

/**
 * The distro-tab「UML rootfs (Alpine minirootfs)」entry card's render state
 * machine — pure JVM, no Android types, so the Activity only binds views and
 * every decision here is contract-testable. The card is the candidate-A entry
 * the main session adjudicated (2026-11-13): a readiness card, not a distro
 * card — it provisions the pinned Alpine minirootfs through {@link
 * UmlRootfsProvisioner#provision} and never claims more than happened.
 *
 * <p><b>The honest phases.</b> {@code DISABLED} (flag-off build: the card
 * does not render at all — zero entry), {@code NOT_READY} (entry button +
 * size hint), {@code DOWNLOADING} (real percent against the pinned byte
 * size), {@code READY} ("verified + extracted N items"), {@code FAILED}
 * (stable reason + the retry entry). Ready never means startable or
 * compile-capable: the official Alpine minirootfs ships no {@code
 * /um-command.sh}, so the research initramfs's external-root path would
 * reject it ({@code fail 29 missing_command} in {@code
 * research/uml-fastboot/initramfs/init}) — the copy around the READY state
 * says exactly "verified + extracted", and launch usability is the
 * fabric-route round's design problem. The compile channel's honesty edge is
 * {@code CHANNEL_UNWIRED} (Lane A).
 *
 * <p><b>Failure reasons are stable and non-secret.</b> A {@link
 * RootfsRejectionException} (verification chain) or an {@link
 * UmlRootfsProvisioner.ExtractionRejectionException} (slip defenses) passes
 * its {@code uml-rootfs-*} reason through verbatim; a raw transport failure
 * never does — it is classified as the stable {@code
 * uml-rootfs-transport-failure}, mirroring the transport's own rule that no
 * raw network text reaches a user-facing surface.
 */
public final class UmlRootfsCardState {

    /** The card's five honest phases. */
    public enum Phase {
        /** Flag-off build: the card must not render (zero entry). */
        DISABLED,
        /** Nothing provisioned yet: the entry button and size hint show. */
        NOT_READY,
        /** A provision call is running; percent against the pinned size. */
        DOWNLOADING,
        /** Verified tar + attested extracted tree exist. Nothing more claimed. */
        READY,
        /** The provision call failed; stable reason + retry entry show. */
        FAILED
    }

    /**
     * One render decision. {@code progressPercent} is 0..100 while
     * downloading or {@code -1} (indeterminate) everywhere else; {@code
     * failureReason} is non-null only for {@code FAILED}; {@code
     * extractedEntries}/{@code alreadyVerified} are meaningful only for
     * {@code READY} — the constructor enforces all of it, so a model that
     * exists is a model that is honest by construction.
     */
    public record CardModel(Phase phase, int progressPercent, String failureReason,
            int extractedEntries, boolean alreadyVerified) {

        public static final int PROGRESS_INDETERMINATE = -1;

        public CardModel {
            Objects.requireNonNull(phase, "phase");
            if (progressPercent != PROGRESS_INDETERMINATE
                    && (progressPercent < 0 || progressPercent > 100)) {
                throw new IllegalArgumentException("progress percent out of range: " + progressPercent);
            }
            if (phase != Phase.DOWNLOADING && progressPercent != PROGRESS_INDETERMINATE) {
                throw new IllegalArgumentException(
                        "only DOWNLOADING may carry a percent: " + phase);
            }
            if (phase != Phase.FAILED && failureReason != null) {
                throw new IllegalArgumentException(
                        "only FAILED may carry a failure reason: " + phase);
            }
            if (phase != Phase.READY && (extractedEntries != 0 || alreadyVerified)) {
                throw new IllegalArgumentException(
                        "only READY may carry extraction counts: " + phase);
            }
        }
    }

    private UmlRootfsCardState() {
    }

    /**
     * The render gate: the card exists only in builds that opened the
     * download flag. False → the Activity leaves the card {@code GONE} and
     * binds no listener — a flag-off build has zero entry.
     */
    public static boolean visible(boolean umlRootfsDownloadEnabled) {
        return umlRootfsDownloadEnabled;
    }

    /**
     * The tab-open render decision from a {@linkplain
     * UmlRootfsProvisioner#readiness pure-read probe} the caller performed
     * off the main thread. Flag closed or a disabled probe → {@code
     * DISABLED}; ready → {@code READY} with the attested counts; anything
     * else → {@code NOT_READY}.
     */
    public static CardModel initial(boolean umlRootfsDownloadEnabled,
            UmlRootfsProvisioner.ReadinessReport readiness) {
        Objects.requireNonNull(readiness, "readiness");
        if (!umlRootfsDownloadEnabled || !readiness.enabled()) {
            return disabled();
        }
        if (readiness.ready()) {
            return new CardModel(Phase.READY, CardModel.PROGRESS_INDETERMINATE, null,
                    readiness.extractedEntries(), false);
        }
        return notReady();
    }

    /** The flag-off model (a DISABLED model never renders; it exists for tests and probes). */
    public static CardModel disabled() {
        return new CardModel(Phase.DISABLED, CardModel.PROGRESS_INDETERMINATE, null, 0, false);
    }

    /** The not-provisioned model: entry button + size hint. */
    public static CardModel notReady() {
        return new CardModel(Phase.NOT_READY, CardModel.PROGRESS_INDETERMINATE, null, 0, false);
    }

    /**
     * The downloading model: the percent is normalized from the engine's
     * byte counts, falling back to the pinned size when the engine cannot
     * report a total.
     */
    public static CardModel downloading(long downloadedBytes, long totalBytes) {
        return new CardModel(Phase.DOWNLOADING, progressPercent(downloadedBytes, totalBytes),
                null, 0, false);
    }

    /**
     * The provisioned model, from the receipt (fresh or idempotent hit —
     * {@code alreadyVerified} lets the copy say "already provisioned" when a
     * repeated entry action answered without any network).
     */
    public static CardModel ready(UmlRootfsProvisioner.ProvisionReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        if (!receipt.enabled()) {
            throw new IllegalArgumentException("a disabled receipt cannot render a ready card");
        }
        return new CardModel(Phase.READY, CardModel.PROGRESS_INDETERMINATE, null,
                receipt.extractedEntries(), receipt.alreadyVerified());
    }

    /** The failed model: stable reason passthrough, retry stays available. */
    public static CardModel failed(Throwable failure) {
        return new CardModel(Phase.FAILED, CardModel.PROGRESS_INDETERMINATE, failureReason(failure), 0, false);
    }

    /**
     * Percent 0..100 against the reported total, falling back to the pinned
     * artifact size (the single source in {@link UmlKernelAssets}) when the
     * engine cannot report one; {@code -1} when nothing is computable.
     */
    public static int progressPercent(long downloadedBytes, long totalBytes) {
        long total = totalBytes > 0 ? totalBytes : pinnedTotalBytes();
        if (downloadedBytes < 0 || total <= 0) {
            return CardModel.PROGRESS_INDETERMINATE;
        }
        long percent = downloadedBytes * 100 / total;
        return percent > 100 ? 100 : (int) percent;
    }

    /** The pinned artifact size, read from the single source — never restated here. */
    public static long pinnedTotalBytes() {
        return Entry.alpineMinirootfsAarch64().sizeBytes();
    }

    /**
     * The stable, non-secret failure reason for the card line: {@code
     * uml-rootfs-*} rejection reasons pass through verbatim; transport-class
     * failures map to a fixed reason so no raw network text ever reaches the
     * user-facing surface.
     */
    public static String failureReason(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof RootfsRejectionException rejection) {
            return rejection.reason();
        }
        if (failure instanceof UmlRootfsProvisioner.ExtractionRejectionException rejection) {
            return rejection.reason();
        }
        if (failure instanceof IOException) {
            return "uml-rootfs-transport-failure";
        }
        return "uml-rootfs-unknown-failure";
    }
}
