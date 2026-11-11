package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.UmlKernelAssets.RootfsDownload.Entry;
import io.github.nyzeep.orchestella.runtime.network.download.ChecksumMismatchException;
import io.github.nyzeep.orchestella.runtime.network.download.HttpConnectionFactory;
import io.github.nyzeep.orchestella.runtime.network.download.ResilientDownloadEngine;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The L1 transport contract between {@link ResilientDownloadEngine} and an
 * official-CDN-shaped server (the pipeline research note §2 contract: {@code
 * Accept-Ranges: bytes} + a strong quoted {@code ETag}, {@code Range} → {@code
 * 206} + {@code Content-Range}), exercised over loopback only — no network.
 *
 * <p><b>The recorded engine finding.</b> The pipeline note (§2, and R2 §3.2)
 * planned resume as "Range + If-Range (strong ETag) → 206/200 semantics", but
 * the engine — read-only this round — sends {@code Range} and <b>never {@code
 * If-Range}</b>: it does not consult the ETag at all. Per RFC 7233 a range
 * request without {@code If-Range} is served from the server's <i>current</i>
 * representation, so a CDN that swapped the artifact between the interrupted
 * first segment and the resume would be appended onto stale bytes — and that
 * is exactly the case the L2 pin catches: the mixed bytes fail the streaming
 * SHA-256 gate, the engine purges both staged names and fails closed (locked
 * by {@link #resumingAcrossAChangedArtifactFailsClosedThroughThePin}). Resume
 * safety rides on the pin, not on the ETag; if the ACR ever wants true
 * If-Range semantics it is an engine change, recorded here as the open item.
 *
 * <p>The closed-loop test drives the whole seam — engine + {@code
 * RootfsTransport.downloadAndVerify} + the L1/L2/L3 chain — through the
 * interrupted-first-segment scenario: drop → resume → complete receipt →
 * verified publish.
 */
public final class RootfsDownloadResumeContractTest {

    /** Segment size of the simulated interrupted first segment. */
    private static final int FIRST_SEGMENT_BYTES = 1_234;

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    /**
     * A fake release CDN shaped like the official Alpine one (the §2 contract
     * side): every response carries {@code Accept-Ranges: bytes} and a strong
     * quoted ETag (quoted, non-{@code W/} — the official artifact's ETag
     * shape), honors {@code Range: bytes=N-} with {@code 206} + {@code
     * Content-Range: bytes N-(total-1)/total}, and can simulate a mid-stream
     * interruption on its first connection (200 OK declared full, K bytes
     * sent, connection closed). Requests are handled serially on the accept
     * thread — deterministic against the engine's sequential retries.
     */
    private static final class FakeCdnServer implements Closeable {

        private static final String ETAG = "\"orchestella-fixture-strong-etag-68e62ee0\"";

        private final ServerSocket socket;
        private final Thread listenerThread;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<Map<String, String>> requestHeaders =
                Collections.synchronizedList(new ArrayList<>());
        private final List<Map<String, String>> sentHeaderNames =
                Collections.synchronizedList(new ArrayList<>());
        private volatile byte[] payload;
        private volatile int dropFirstSegmentBytes = 0;
        private volatile boolean honorRange = true;
        private volatile int connections = 0;

        FakeCdnServer(byte[] payload) throws IOException {
            this.payload = payload.clone();
            this.socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            this.listenerThread = new Thread(this::acceptLoop,
                    "FakeCdnServer-" + socket.getLocalPort());
            this.listenerThread.setDaemon(true);
            this.listenerThread.start();
        }

        int getPort() {
            return socket.getLocalPort();
        }

        int connections() {
            return connections;
        }

        List<Map<String, String>> requestHeaders() {
            synchronized (requestHeaders) {
                return new ArrayList<>(requestHeaders);
            }
        }

        List<Map<String, String>> sentHeaderNames() {
            synchronized (sentHeaderNames) {
                return new ArrayList<>(sentHeaderNames);
            }
        }

        void setPayload(byte[] payload) {
            this.payload = payload.clone();
        }

        /** Interrupts the first connection after {@code bytes} of the payload. */
        void dropFirstSegmentAfter(int bytes) {
            this.dropFirstSegmentBytes = bytes;
        }

        /** Serves 200 OK regardless of Range — the rangeless-server fallback. */
        void ignoreRange() {
            this.honorRange = false;
        }

        private void acceptLoop() {
            while (running.get()) {
                try (Socket client = socket.accept()) {
                    connections++;
                    handle(client);
                } catch (IOException e) {
                    if (!running.get()) {
                        break;
                    }
                }
            }
        }

        private void handle(Socket client) throws IOException {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(),
                            line.substring(colon + 1).trim());
                }
            }
            requestHeaders.add(headers);

            byte[] current = payload;
            boolean dropThisConnection = dropFirstSegmentBytes > 0 && connections == 1;
            String range = headers.get("range");
            OutputStream out = client.getOutputStream();

            if (dropThisConnection) {
                sentHeaderNames.add(Map.of("accept-ranges", "y", "etag", "y"));
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Accept-Ranges: bytes\r\n"
                        + "ETag: " + ETAG + "\r\n"
                        + "Content-Length: " + current.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                int bytes = Math.min(dropFirstSegmentBytes, current.length);
                out.write(current, 0, bytes);
                out.flush();
                client.close(); // the interrupted first segment: no more bytes, connection gone
                return;
            }

            if (honorRange && range != null && range.startsWith("bytes=")) {
                long offset = parseRangeStart(range);
                if (offset >= current.length) {
                    sentHeaderNames.add(Map.of("accept-ranges", "y"));
                    out.write(("HTTP/1.1 416 Range Not Satisfiable\r\n"
                            + "Accept-Ranges: bytes\r\n"
                            + "ETag: " + ETAG + "\r\n"
                            + "Content-Range: bytes */" + current.length + "\r\n"
                            + "Content-Length: 0\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                }
                int start = (int) offset;
                int length = current.length - start;
                sentHeaderNames.add(Map.of("accept-ranges", "y", "etag", "y",
                        "content-range", "y"));
                out.write(("HTTP/1.1 206 Partial Content\r\n"
                        + "Accept-Ranges: bytes\r\n"
                        + "ETag: " + ETAG + "\r\n"
                        + "Content-Range: bytes " + start + "-" + (current.length - 1)
                        + "/" + current.length + "\r\n"
                        + "Content-Length: " + length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(current, start, length);
                out.flush();
                return;
            }

            sentHeaderNames.add(Map.of("accept-ranges", "y", "etag", "y"));
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "ETag: " + ETAG + "\r\n"
                    + "Content-Length: " + current.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(current);
            out.flush();
        }

        private static long parseRangeStart(String rangeHeader) {
            try {
                String value = rangeHeader.substring("bytes=".length()).trim();
                int dash = value.indexOf('-');
                return dash >= 0 && dash > 0 ? Long.parseLong(value.substring(0, dash)) : 0L;
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        @Override
        public void close() {
            running.set(false);
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing a test server.
            }
        }
    }

    private static byte[] payloadOf(int size, int hash) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * hash + 11) & 0xFF);
        }
        return data;
    }

    private static ResilientDownloadEngine engineOver(HttpConnectionFactory factory) {
        return new ResilientDownloadEngine(2000, 2000, 3, 1L, delay -> { }, factory);
    }

    /** Redirects the pinned https entry to the loopback fake CDN (test interception seam). */
    private static HttpConnectionFactory redirectTo(FakeCdnServer server, String path) {
        return url -> (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getPort() + path).openConnection();
    }

    private static Path enginePartOf(Path destination) {
        return destination.resolveSibling(destination.getFileName() + ".part");
    }

    // --- the closed loop: interrupted first segment → resume → verified receipt -------------

    @Test
    public void thePipelineClosesTheLoopAfterAnInterruptedFirstSegment() throws Exception {
        byte[] artifact = MiniRootfsFixture.gzipTar();
        // The gzip fixture is small (a mostly-zero tar compresses hard), so the
        // interrupted first segment is a third of it — strictly partial.
        int firstSegment = Math.max(1, artifact.length / 3);
        Entry entry = new Entry(UmlKernelAssets.RootfsDownload.ENTRY_ID,
                MiniRootfsFixture.VERSION, UmlKernelAssets.RootfsDownload.ENTRY_ARCHITECTURE,
                URI.create(UmlKernelAssets.RootfsDownload.ROOTFS_TAR_URL),
                UmlKernelAssets.FastProfile.ROOTFS_TAR,
                MiniRootfsFixture.sha256Hex(artifact), (long) artifact.length);
        Path destDir = tempFolder.newFolder("closed-loop").toPath();

        try (FakeCdnServer cdn = new FakeCdnServer(artifact)) {
            cdn.dropFirstSegmentAfter(firstSegment);
            ResilientDownloadEngine engine = engineOver(redirectTo(cdn,
                    "/" + UmlKernelAssets.FastProfile.ROOTFS_TAR));

            RootfsTransport.DownloadReceipt receipt = RootfsTransport.downloadAndVerify(
                    engine, entry, destDir, true);

            // 完整收货 → 校验通过 → 正式名发布: the full loop answered.
            assertTrue(receipt.enabled());
            assertArrayEquals(artifact,
                    Files.readAllBytes(destDir.resolve(entry.fileName())));
            assertEquals(MiniRootfsFixture.sha256Hex(artifact), receipt.sha256());
            assertEquals(artifact.length, receipt.sizeBytes());
            assertTrue(receipt.selfCheck().describe().isEmpty());
            // The interrupted download never touched the formal name's siblings:
            // no .part, no engine residue survives a completed publish.
            assertFalse(Files.exists(destDir.resolve(entry.fileName() + ".part")));
            // 首段中断 → 续传: exactly one drop and one ranged resume request.
            assertEquals(2, cdn.connections());
            assertEquals("bytes=" + firstSegment + "-",
                    cdn.requestHeaders().get(1).get("range"));
        }
    }

    // --- the engine's resume contract against the CDN shape -----------------------------------

    @Test
    public void engineResumesByRangeAndNeverSendsIfRange() throws Exception {
        byte[] payload = payloadOf(10_000, 19);
        Path dest = tempFolder.getRoot().toPath().resolve("resume.bin");
        Files.deleteIfExists(dest);

        try (FakeCdnServer cdn = new FakeCdnServer(payload)) {
            cdn.dropFirstSegmentAfter(FIRST_SEGMENT_BYTES);
            ResilientDownloadEngine engine = engineOver(HttpConnectionFactory.SYSTEM);

            engine.download(List.of("http://127.0.0.1:" + cdn.getPort() + "/fixture.bin"),
                    dest, MiniRootfsFixture.sha256Hex(payload), null);

            // The resumed stream is byte-identical to the artifact.
            assertArrayEquals(payload, Files.readAllBytes(dest));

            // Contract shape (the §2 CDN side): every response offered
            // Accept-Ranges and the strong ETag — and the first one declared a
            // full Content-Length it then failed to deliver.
            for (Map<String, String> sent : cdn.sentHeaderNames()) {
                assertTrue("the fake CDN must advertise Accept-Ranges: bytes",
                        sent.containsKey("accept-ranges"));
                assertTrue("the fake CDN must carry a strong quoted ETag", sent.containsKey("etag"));
            }

            // The finding: resume rides on Range alone. First request had no
            // Range; the retry resumed from the first segment's byte count;
            // If-Range is never sent even though a strong ETag was on offer.
            List<Map<String, String>> requests = cdn.requestHeaders();
            assertEquals(2, requests.size());
            assertFalse(requests.get(0).containsKey("range"));
            assertEquals("bytes=" + FIRST_SEGMENT_BYTES + "-",
                    requests.get(1).get("range"));
            assertFalse("the engine never consults the ETag (If-Range unimplemented)",
                    requests.get(1).containsKey("if-range"));
        }
    }

    @Test
    public void aRangelessServerFallsBackToAFreshFullDownload() throws Exception {
        byte[] payload = payloadOf(8_000, 23);
        Path dest = tempFolder.getRoot().toPath().resolve("fallback.bin");
        Path enginePart = enginePartOf(dest);
        Files.deleteIfExists(dest);
        // A stale half product from a "previous session" of a rangeless server.
        Files.write(enginePart, payloadOf(FIRST_SEGMENT_BYTES, 19));

        try (FakeCdnServer cdn = new FakeCdnServer(payload)) {
            cdn.ignoreRange();
            ResilientDownloadEngine engine = engineOver(HttpConnectionFactory.SYSTEM);

            engine.download(List.of("http://127.0.0.1:" + cdn.getPort() + "/fixture.bin"),
                    dest, MiniRootfsFixture.sha256Hex(payload), null);

            // The 200 answer truncated the stale part and downloaded fresh.
            assertArrayEquals(payload, Files.readAllBytes(dest));
            assertEquals(1, cdn.connections());
            assertEquals("bytes=" + FIRST_SEGMENT_BYTES + "-",
                    cdn.requestHeaders().get(0).get("range"));
            assertFalse(Files.exists(enginePart));
        }
    }

    @Test
    public void resumingAcrossAChangedArtifactFailsClosedThroughThePin() throws Exception {
        byte[] oldArtifact = payloadOf(10_000, 19);
        byte[] swapped = payloadOf(10_000, 29); // same length, different bytes
        Path dest = tempFolder.getRoot().toPath().resolve("changed.bin");
        Path enginePart = enginePartOf(dest);
        Files.deleteIfExists(dest);
        // The interrupted first segment of the OLD representation sits on disk.
        Files.write(enginePart,
                java.util.Arrays.copyOf(oldArtifact, FIRST_SEGMENT_BYTES));

        try (FakeCdnServer cdn = new FakeCdnServer(swapped)) {
            ResilientDownloadEngine engine = engineOver(HttpConnectionFactory.SYSTEM);

            // The engine sends Range without If-Range, so the CDN serves the
            // swapped representation's range onto the old first segment — the
            // mixed stream fails the pin, the engine purges and fails fast.
            ChecksumMismatchException mismatch = assertThrows(ChecksumMismatchException.class,
                    () -> engine.download(
                            List.of("http://127.0.0.1:" + cdn.getPort() + "/fixture.bin"),
                            dest, MiniRootfsFixture.sha256Hex(oldArtifact), null));

            assertEquals(MiniRootfsFixture.sha256Hex(oldArtifact), mismatch.getExpectedHash());
            // Fail-fast on a pin mismatch: one request, no mirror retry, and
            // both staged names physically purged — the fail-closed answer
            // that substitutes for the unimplemented If-Range.
            assertEquals(1, cdn.connections());
            assertEquals("bytes=" + FIRST_SEGMENT_BYTES + "-",
                    cdn.requestHeaders().get(0).get("range"));
            assertFalse(Files.exists(dest));
            assertFalse(Files.exists(enginePart));
        }
    }
}
