package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.droidlayer.runtime.network.download.HttpConnectionFactory;
import io.github.nyzeep.droidlayer.runtime.network.download.MirrorPriorityList;
import io.github.nyzeep.droidlayer.runtime.network.download.ResilientDownloadEngine;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * App-level unit tests for ResilientDownloadEngine integration (Ticket 51, spec-m4).
 */
public final class ResilientDownloadEngineAppTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private static byte[] sampleData(int length) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) ((i * 37 + 11) & 0xFF);
        }
        return b;
    }

    private static String hexSha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Test
    public void downloadsAssetWithMirrorFailover() throws Exception {
        int size = 8_000;
        byte[] payload = sampleData(size);
        String expectedHash = hexSha256(payload);

        Path dest = tempFolder.newFile("app_download.bin").toPath();
        Files.deleteIfExists(dest);

        // Good server responds 200 OK with payload
        java.util.concurrent.atomic.AtomicBoolean serverRunning = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (ServerSocket goodServer = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
            int goodPort = goodServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                while (serverRunning.get()) {
                    try {
                        Socket client = goodServer.accept();
                        try (OutputStream out = client.getOutputStream()) {
                            String header = "HTTP/1.1 200 OK\r\n"
                                    + "Content-Length: " + payload.length + "\r\n"
                                    + "Connection: close\r\n\r\n";
                            out.write(header.getBytes(StandardCharsets.US_ASCII));
                            out.write(payload);
                            out.flush();
                        } finally {
                            client.close();
                        }
                    } catch (Exception ignored) {}
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            // Port 1 will be rejected immediately (connection refused)
            String deadMirror = "http://127.0.0.1:1/offline";
            String goodMirror = "http://127.0.0.1:" + goodPort + "/asset.bin";

            List<Long> retries = new ArrayList<>();
            ResilientDownloadEngine engine = new ResilientDownloadEngine(
                    1000, 1000, 3, 100L, retries::add, HttpConnectionFactory.SYSTEM);

            List<String> mirrors = Arrays.asList(deadMirror, goodMirror);
            engine.download(mirrors, dest, expectedHash, (d, t, s, eta, m) -> {});

            assertTrue(Files.exists(dest));
            assertEquals(size, Files.size(dest));
            assertArrayEquals(payload, Files.readAllBytes(dest));
            assertEquals(Arrays.asList(100L, 200L, 400L), retries);
            serverRunning.set(false);
        }
    }

    @Test
    public void githubMirrorPriorityListExpandsCorrectly() {
        String base = "https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.3.0-alpha/alpine.tar.gz";
        List<String> list = MirrorPriorityList.forGitHubRelease(base);

        assertEquals(3, list.size());
        assertEquals(base, list.get(0));
        assertTrue(list.get(1).contains("download.fastgit.org"));
        assertTrue(list.get(2).contains("ghproxy.net"));
    }
}
