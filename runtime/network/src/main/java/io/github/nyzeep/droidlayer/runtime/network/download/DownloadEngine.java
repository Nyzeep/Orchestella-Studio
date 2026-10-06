package io.github.nyzeep.droidlayer.runtime.network.download;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Strategy-neutral download engine interface with multi-mirror failover,
 * HTTP Range resumption, and streaming SHA-256 verification (ADR-0006, ADR-0019, spec-m4, Ticket 51).
 */
public interface DownloadEngine {

    /**
     * Downloads an asset to {@code destination}.
     *
     * @param mirrorUrls prioritized list of mirror URLs
     * @param destination path where the final verified file will be stored
     * @param expectedSha256 expected SHA-256 hex string (or null/blank to skip verification)
     * @param callback progress callback reporting bytes, total, speed, ETA, and mirror status
     * @throws IOException on network failures, exhausted retries, or checksum mismatch
     */
    void download(
            List<String> mirrorUrls,
            Path destination,
            String expectedSha256,
            DownloadProgressCallback callback)
            throws IOException;
}
