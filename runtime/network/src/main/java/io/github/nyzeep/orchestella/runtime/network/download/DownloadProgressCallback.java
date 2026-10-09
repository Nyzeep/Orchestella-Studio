package io.github.nyzeep.orchestella.runtime.network.download;

/**
 * Progress and lifecycle callback interface for {@link DownloadEngine}.
 */
@FunctionalInterface
public interface DownloadProgressCallback {

    /**
     * Periodically reports download progress.
     *
     * @param downloadedBytes bytes downloaded so far (including resumed part)
     * @param totalBytes total expected bytes, or -1 if unknown
     * @param speedMBps instantaneous download speed in megabytes per second (MB/s)
     * @param etaSeconds estimated remaining seconds until completion, or -1 if unknown
     * @param currentMirror URL of the mirror currently serving the request
     */
    void onProgress(
            long downloadedBytes,
            long totalBytes,
            double speedMBps,
            long etaSeconds,
            String currentMirror);

    /**
     * Called when a download attempt fails on the current mirror and is retrying after backoff.
     *
     * @param currentMirror current mirror URL
     * @param attempt current retry attempt index (1, 2, 3...)
     * @param backoffMillis backoff delay before next attempt
     * @param error the underlying cause of the failure
     */
    default void onRetry(String currentMirror, int attempt, long backoffMillis, Throwable error) {}

    /**
     * Called when retries are exhausted on a mirror and the engine switches to the next mirror.
     *
     * @param failedMirror the mirror that exhausted retries
     * @param nextMirror the next mirror in the priority list, or null if all exhausted
     * @param error the last error encountered on the failed mirror
     */
    default void onMirrorFailover(String failedMirror, String nextMirror, Throwable error) {}
}
