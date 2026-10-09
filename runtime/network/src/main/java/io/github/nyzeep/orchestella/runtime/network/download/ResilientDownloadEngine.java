package io.github.nyzeep.orchestella.runtime.network.download;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Resilient multi-mirror download engine with HTTP Range resumption, exponential backoff,
 * and streaming SHA-256 integrity verification (ADR-0006, ADR-0019, spec-m4, Ticket 51).
 */
public class ResilientDownloadEngine implements DownloadEngine {

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000; // 10s
    public static final int DEFAULT_READ_TIMEOUT_MS = 15_000;    // 15s
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final long DEFAULT_BACKOFF_BASE_MS = 1_000L;    // 1s -> 2s -> 4s
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_REDIRECTS = 5;

    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxRetriesPerMirror;
    private final long backoffBaseMillis;
    private final Sleeper sleeper;
    private final HttpConnectionFactory connectionFactory;

    public ResilientDownloadEngine() {
        this(
                DEFAULT_CONNECT_TIMEOUT_MS,
                DEFAULT_READ_TIMEOUT_MS,
                DEFAULT_MAX_RETRIES,
                DEFAULT_BACKOFF_BASE_MS,
                Sleeper.SYSTEM,
                HttpConnectionFactory.SYSTEM);
    }

    public ResilientDownloadEngine(
            int connectTimeoutMillis,
            int readTimeoutMillis,
            int maxRetriesPerMirror,
            long backoffBaseMillis,
            Sleeper sleeper,
            HttpConnectionFactory connectionFactory) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxRetriesPerMirror = maxRetriesPerMirror;
        this.backoffBaseMillis = backoffBaseMillis;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper cannot be null");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory cannot be null");
    }

    /**
     * Convenience overload for single URL download.
     */
    public void download(
            String mirrorUrl,
            Path destination,
            String expectedSha256,
            DownloadProgressCallback callback)
            throws IOException {
        download(Collections.singletonList(mirrorUrl), destination, expectedSha256, callback);
    }

    @Override
    public void download(
            List<String> mirrorUrls,
            Path destination,
            String expectedSha256,
            DownloadProgressCallback callback)
            throws IOException {
        Objects.requireNonNull(destination, "destination cannot be null");
        DownloadProgressCallback cb = callback != null ? callback : (d, t, s, eta, m) -> {};

        if (mirrorUrls == null || mirrorUrls.isEmpty()) {
            throw new IllegalArgumentException("mirrorUrls must contain at least one mirror URL");
        }

        // Fast-path: Check if destination already exists and matches expected SHA-256
        if (expectedSha256 != null && !expectedSha256.trim().isEmpty() && Files.exists(destination)) {
            try {
                String existingSha256 = calculateFileSha256(destination);
                if (existingSha256.equalsIgnoreCase(expectedSha256.trim())) {
                    long size = Files.size(destination);
                    cb.onProgress(size, size, 0.0, 0L, mirrorUrls.get(0));
                    return;
                }
            } catch (Exception ignored) {
                // If checking fails, proceed with downloading to overwrite
            }
        }

        Path parentDir = destination.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }

        Path partPath = destination.resolveSibling(destination.getFileName().toString() + ".part");
        IOException lastError = null;

        for (int mIndex = 0; mIndex < mirrorUrls.size(); mIndex++) {
            String currentMirror = mirrorUrls.get(mIndex);
            int attempt = 0;

            while (attempt <= maxRetriesPerMirror) {
                try {
                    downloadFromMirror(currentMirror, partPath, destination, expectedSha256, cb);
                    return; // Download succeeded!
                } catch (ChecksumMismatchException cme) {
                    // Fail-fast on checksum mismatch - tampering or unrecoverable corruption
                    throw cme;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download interrupted for " + destination.getFileName(), ie);
                } catch (IOException e) {
                    lastError = e;
                    if (attempt < maxRetriesPerMirror) {
                        long backoff = backoffBaseMillis * (1L << attempt);
                        cb.onRetry(currentMirror, attempt + 1, backoff, e);
                        try {
                            sleeper.sleep(backoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Download backoff interrupted", ie);
                        }
                        attempt++;
                    } else {
                        // Max retries exhausted for this mirror; notify failover
                        String nextMirror = (mIndex + 1 < mirrorUrls.size()) ? mirrorUrls.get(mIndex + 1) : null;
                        cb.onMirrorFailover(currentMirror, nextMirror, e);
                        break;
                    }
                }
            }
        }

        throw new IOException(
                "All " + mirrorUrls.size() + " mirrors exhausted for " + destination.getFileName()
                        + ". Last error: " + (lastError != null ? lastError.getMessage() : "unknown"),
                lastError);
    }

    private void downloadFromMirror(
            String mirrorUrl,
            Path partPath,
            Path destination,
            String expectedSha256,
            DownloadProgressCallback callback)
            throws IOException, InterruptedException {

        long existingLength = Files.exists(partPath) ? Files.size(partPath) : 0L;
        HttpURLConnection conn = null;

        try {
            conn = openConnectionWithRedirects(mirrorUrl, existingLength);
            int responseCode = conn.getResponseCode();

            // Handle HTTP 416 Range Not Satisfiable: reset part file and reconnect
            if (responseCode == 416) {
                conn.disconnect();
                Files.deleteIfExists(partPath);
                existingLength = 0L;
                conn = openConnectionWithRedirects(mirrorUrl, 0L);
                responseCode = conn.getResponseCode();
            }

            // Verify status code
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + responseCode + " " + conn.getResponseMessage() + " for " + mirrorUrl);
            }

            boolean isPartial = (responseCode == HttpURLConnection.HTTP_PARTIAL);
            long totalBytes;
            StandardOpenOption openOption;
            MessageDigest digest = createSha256Digest();

            if (isPartial) {
                // HTTP 206 Partial Content: append mode
                openOption = StandardOpenOption.APPEND;
                String contentRange = conn.getHeaderField("Content-Range");
                long parsedTotal = parseTotalFromContentRange(contentRange);
                if (parsedTotal > 0) {
                    totalBytes = parsedTotal;
                } else {
                    long remaining = conn.getContentLengthLong();
                    totalBytes = remaining >= 0 ? existingLength + remaining : -1L;
                }

                // Feed existing bytes into SHA-256 digest
                if (existingLength > 0) {
                    try (InputStream partIn = Files.newInputStream(partPath)) {
                        byte[] preBuf = new byte[BUFFER_SIZE];
                        long remaining = existingLength;
                        int r;
                        while (remaining > 0 && (r = partIn.read(preBuf, 0, (int) Math.min(preBuf.length, remaining))) != -1) {
                            digest.update(preBuf, 0, r);
                            remaining -= r;
                        }
                    }
                }
            } else {
                // HTTP 200 OK: either fresh or server does not support Range (graceful fallback)
                openOption = StandardOpenOption.TRUNCATE_EXISTING;
                existingLength = 0L;
                totalBytes = conn.getContentLengthLong();
                digest.reset();
            }

            long currentDownloaded = existingLength;
            long lastReportTime = System.currentTimeMillis();
            long lastReportBytes = currentDownloaded;

            callback.onProgress(currentDownloaded, totalBytes, 0.0, -1L, mirrorUrl);

            try (InputStream in = new BufferedInputStream(conn.getInputStream(), BUFFER_SIZE);
                 OutputStream out = Files.newOutputStream(partPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, openOption)) {

                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    digest.update(buffer, 0, bytesRead);
                    currentDownloaded += bytesRead;

                    long now = System.currentTimeMillis();
                    long elapsed = now - lastReportTime;
                    if (elapsed >= 200) {
                        double speedMBps = ((currentDownloaded - lastReportBytes) / (1024.0 * 1024.0)) / (elapsed / 1000.0);
                        long etaSeconds = -1L;
                        if (totalBytes > 0 && speedMBps > 0.001 && currentDownloaded < totalBytes) {
                            etaSeconds = (long) ((totalBytes - currentDownloaded) / (speedMBps * 1024.0 * 1024.0));
                        }
                        callback.onProgress(currentDownloaded, totalBytes, speedMBps, etaSeconds, mirrorUrl);
                        lastReportTime = now;
                        lastReportBytes = currentDownloaded;
                    }
                }
                out.flush();
            }

            // Validate total size if known
            if (totalBytes > 0 && currentDownloaded != totalBytes) {
                throw new IOException("Stream ended prematurely: expected " + totalBytes + " bytes, received " + currentDownloaded);
            }

            // Validate SHA-256 integrity
            if (expectedSha256 != null && !expectedSha256.trim().isEmpty()) {
                String actualSha256 = bytesToHex(digest.digest());
                if (!actualSha256.equalsIgnoreCase(expectedSha256.trim())) {
                    // Physical purge of contaminated files
                    Files.deleteIfExists(partPath);
                    Files.deleteIfExists(destination);
                    throw new ChecksumMismatchException(
                            "Checksum mismatch for " + destination.getFileName() + ": expected "
                                    + expectedSha256.trim() + ", got " + actualSha256,
                            expectedSha256.trim(),
                            actualSha256);
                }
            }

            // Atomic move to final destination
            try {
                Files.move(partPath, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partPath, destination, StandardCopyOption.REPLACE_EXISTING);
            }

            // Final completion callback
            callback.onProgress(currentDownloaded, currentDownloaded, 0.0, 0L, mirrorUrl);

        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection openConnectionWithRedirects(String urlString, long rangeOffset)
            throws IOException {
        String targetUrl = urlString;
        int redirectCount = 0;

        while (true) {
            URL url = new URL(targetUrl);
            HttpURLConnection conn = connectionFactory.open(url);
            conn.setConnectTimeout(connectTimeoutMillis);
            conn.setReadTimeout(readTimeoutMillis);
            conn.setInstanceFollowRedirects(false); // Manual redirect handling across protocols
            conn.setRequestProperty("User-Agent", "Orchestella-Studio/0.4.0");

            if (rangeOffset > 0) {
                conn.setRequestProperty("Range", "bytes=" + rangeOffset + "-");
            }

            int responseCode = conn.getResponseCode();
            if (isRedirectCode(responseCode)) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("Redirect response " + responseCode + " missing Location header");
                }
                if (++redirectCount > MAX_REDIRECTS) {
                    throw new IOException("Too many redirects (limit " + MAX_REDIRECTS + ")");
                }
                URL resolved = new URL(url, location);
                targetUrl = resolved.toString();
                continue;
            }
            return conn;
        }
    }

    private static boolean isRedirectCode(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308;
    }

    private static long parseTotalFromContentRange(String contentRange) {
        if (contentRange == null) return -1L;
        int slash = contentRange.lastIndexOf('/');
        if (slash >= 0 && slash < contentRange.length() - 1) {
            String totalStr = contentRange.substring(slash + 1).trim();
            if (!"*".equals(totalStr)) {
                try {
                    return Long.parseLong(totalStr);
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1L;
    }

    private static MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private static String calculateFileSha256(Path file) throws IOException {
        MessageDigest digest = createSha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int r;
            while ((r = in.read(buf)) != -1) {
                digest.update(buf, 0, r);
            }
        }
        return bytesToHex(digest.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
