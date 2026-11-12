package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.network.download.DownloadEngine;
import io.github.nyzeep.orchestella.runtime.network.download.DownloadProgressCallback;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The product-side progress tap on the download engine. {@link
 * RootfsTransport#downloadAndVerify} hands the engine a {@code null}
 * progress callback on purpose — its contract answers a receipt, not a
 * progress stream — so the entry card cannot see byte progress through the
 * orchestration seam. This decorator wraps the production engine ({@code
 * ResilientDownloadEngine}, the same constructor pattern the distro
 * download's existing consumer uses) and attaches its own callback; the
 * card's percent is then normalized against the pinned size by {@link
 * UmlRootfsCardState#progressPercent}. A callback the caller passed in is
 * still forwarded unchanged on every event. No other behavior is altered:
 * mirrors, retries, backoff, and pin purging stay the wrapped engine's own.
 */
public final class UmlRootfsProgressEngine implements DownloadEngine {

    /**
     * The card's view of progress: raw byte counts as the engine reports
     * them — normalization (percent, pin-size fallback) is {@link
     * UmlRootfsCardState}'s job, not this tap's.
     */
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    private final DownloadEngine inner;
    private final ProgressListener listener;

    public UmlRootfsProgressEngine(DownloadEngine inner, ProgressListener listener) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void download(List<String> mirrorUrls, Path destination, String expectedSha256,
            DownloadProgressCallback callback) throws IOException {
        Objects.requireNonNull(mirrorUrls, "mirrorUrls");
        Objects.requireNonNull(destination, "destination");
        inner.download(mirrorUrls, destination, expectedSha256, new DownloadProgressCallback() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes, double speedMBps,
                    long etaSeconds, String currentMirror) {
                // The caller's callback (transport always passes null) still
                // sees every event unchanged, before the tap fires.
                if (callback != null) {
                    callback.onProgress(downloadedBytes, totalBytes, speedMBps,
                            etaSeconds, currentMirror);
                }
                listener.onProgress(downloadedBytes, totalBytes);
            }
        });
    }
}
