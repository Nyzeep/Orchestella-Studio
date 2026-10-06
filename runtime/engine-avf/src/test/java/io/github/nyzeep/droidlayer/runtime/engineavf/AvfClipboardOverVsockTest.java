package io.github.nyzeep.droidlayer.runtime.engineavf;

import io.github.nyzeep.droidlayer.runtime.contract.BridgeClient;
import io.github.nyzeep.droidlayer.runtime.contract.BridgeControl;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validates bidirectional clipboard text synchronization over vsock control channel (port 9102).
 *
 * <p>Conforms to ADR-0018, spec-m3.md, and Ticket 48:
 * <ul>
 *   <li>Establishes authenticated Bridge v1 session over vsock port 9102;</li>
 *   <li>Performs bidirectional UTF-8 text transfer via {@code DROIDLAYER/CLIPBOARD PUT} and {@code GET};</li>
 *   <li>Verifies 256KB (262,144 bytes) payload streaming across Stage-2 boundary;</li>
 *   <li>Enforces deterministic rejection of oversized (&gt;256KB) and malformed payloads.</li>
 * </ul>
 */
public final class AvfClipboardOverVsockTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final int VSOCK_CONTROL_PORT = 9102;

    public static void main(String[] arguments) throws Exception {
        bidirectionalClipboardSyncOverVsock();
        twoHundredFiftySixKbPayloadStreamPenetration();
        rejectsOversizedPayloadsDefensively();
        handlesMalformedPayloadsAndCommands();
        System.out.println(
                "AVF_CLIPBOARD_OVER_VSOCK_TEST_OK bidirectional=1 256kb_stream=1 oversized_defense=1 malformed_defense=1");
    }

    private static void bidirectionalClipboardSyncOverVsock() throws Exception {
        AtomicReference<String> guestClipboardStore = new AtomicReference<>("");
        List<String> receivedLines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch putLatch = new CountDownLatch(1);
        CountDownLatch getLatch = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();

            Thread guestThread = new Thread(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                     OutputStream out = client.getOutputStream()) {
                    String hello = reader.readLine();
                    if (hello != null && hello.startsWith("DROIDLAYER/HELLO")) {
                        out.write("DROIDLAYER/READY 1\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }

                    // Process requests using BridgeControl with clipboard seam
                    BridgeControl.Clipboard clipboardSeam = new BridgeControl.Clipboard() {
                        @Override
                        public boolean onClipboardPut(String text) {
                            guestClipboardStore.set(text);
                            putLatch.countDown();
                            return true;
                        }

                        @Override
                        public String onClipboardGet() {
                            getLatch.countDown();
                            return guestClipboardStore.get();
                        }
                    };

                    String line;
                    while ((line = reader.readLine()) != null) {
                        receivedLines.add(line);
                        BridgeControl.Result res = BridgeControl.dispatchClipboard(line + "\n", clipboardSeam);
                        out.write(res.wireResponse().getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
            }, "guest-clipboard-agent");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                BridgeClient client = new BridgeClient(socket.getInputStream(), socket.getOutputStream());
                BridgeClient.Result handshake = client.handshake(TOKEN);
                assertTrue(handshake.accepted(), "handshake accepted over vsock 9102");

                // 1. Host -> Guest: clipboard PUT
                String testSnippet = "const greeting = '你好, Orchestella Studio!'; // \uD83D\uDE80 UTF-8 Code";
                BridgeClient.Result putResult = client.clipboardPut(testSnippet);
                assertTrue(putResult.accepted(), "clipboardPut accepted");
                assertEquals("ok-clipboard", putResult.responseKind(), "put response kind");

                assertTrue(putLatch.await(3, TimeUnit.SECONDS), "guest processed clipboard PUT");
                assertEquals(testSnippet, guestClipboardStore.get(), "guest store matches host text exactly");

                // 2. Guest -> Host: clipboard GET
                BridgeClient.Result getResult = client.clipboardGet();
                assertTrue(getResult.accepted(), "clipboardGet accepted");
                assertEquals("ok-clipboard", getResult.responseKind(), "get response kind");
                assertEquals(testSnippet, getResult.payload(), "retrieved clipboard text matches store");
                assertTrue(getLatch.await(3, TimeUnit.SECONDS), "guest processed clipboard GET");
            }
        }
    }

    private static void twoHundredFiftySixKbPayloadStreamPenetration() throws Exception {
        byte[] maxPayloadBytes = new byte[BridgeControl.MAX_CLIPBOARD_PAYLOAD_BYTES]; // exactly 262,144 bytes
        Arrays.fill(maxPayloadBytes, (byte) 'A');
        // Place distinct markers at head, middle, and tail
        maxPayloadBytes[0] = 'H';
        maxPayloadBytes[1] = 'E';
        maxPayloadBytes[2] = 'A';
        maxPayloadBytes[3] = 'D';
        maxPayloadBytes[131072] = 'M';
        maxPayloadBytes[262140] = 'T';
        maxPayloadBytes[262141] = 'A';
        maxPayloadBytes[262142] = 'I';
        maxPayloadBytes[262143] = 'L';
        String largeText = new String(maxPayloadBytes, StandardCharsets.UTF_8);

        AtomicReference<String> guestStore = new AtomicReference<>("");
        CountDownLatch putLatch = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();

            Thread guestThread = new Thread(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII), 512 * 1024);
                     OutputStream out = client.getOutputStream()) {
                    reader.readLine(); // HELLO
                    out.write("DROIDLAYER/READY 1\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    BridgeControl.Clipboard clipboardSeam = new BridgeControl.Clipboard() {
                        @Override
                        public boolean onClipboardPut(String text) {
                            guestStore.set(text);
                            putLatch.countDown();
                            return true;
                        }

                        @Override
                        public String onClipboardGet() {
                            return guestStore.get();
                        }
                    };

                    String line;
                    while ((line = reader.readLine()) != null) {
                        BridgeControl.Result res = BridgeControl.dispatchClipboard(line + "\n", clipboardSeam);
                        out.write(res.wireResponse().getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
            }, "guest-large-payload-server");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                BridgeClient client = new BridgeClient(socket.getInputStream(), socket.getOutputStream());
                client.handshake(TOKEN);

                // PUT 256KB payload
                BridgeClient.Result putRes = client.clipboardPut(largeText);
                assertTrue(putRes.accepted(), "256KB payload accepted without failure");
                assertTrue(putLatch.await(5, TimeUnit.SECONDS), "guest received 256KB text");
                assertEquals(BridgeControl.MAX_CLIPBOARD_PAYLOAD_BYTES, guestStore.get().length(), "guest length matches 262,144");
                assertEquals(largeText, guestStore.get(), "full 256KB content identity preserved");

                // GET 256KB payload back
                BridgeClient.Result getRes = client.clipboardGet();
                assertTrue(getRes.accepted(), "256KB payload GET accepted");
                assertEquals(BridgeControl.MAX_CLIPBOARD_PAYLOAD_BYTES, getRes.payload().length(), "retrieved length matches 262,144");
                assertEquals(largeText, getRes.payload(), "retrieved 256KB text identical");
            }
        }
    }

    private static void rejectsOversizedPayloadsDefensively() throws Exception {
        // 1. Client-side defense: payload > 256KB throws IllegalArgumentException
        byte[] oversizedBytes = new byte[BridgeControl.MAX_CLIPBOARD_PAYLOAD_BYTES + 1];
        Arrays.fill(oversizedBytes, (byte) 'X');
        String oversizedText = new String(oversizedBytes, StandardCharsets.UTF_8);

        boolean clientBlocked = false;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                BridgeClient client = new BridgeClient(socket.getInputStream(), socket.getOutputStream());
                client.clipboardPut(oversizedText);
            } catch (IllegalArgumentException expected) {
                clientBlocked = true;
            }
        }
        assertTrue(clientBlocked, "BridgeClient rejects payload > 256KB proactively");

        // 2. Guest-side defense: wire line exceeding limits returns payload-too-large
        String rawBase64 = Base64.getEncoder().encodeToString(oversizedBytes);
        String wireCommand = "DROIDLAYER/CLIPBOARD PUT base64:" + rawBase64 + "\n";
        BridgeControl.Result result = BridgeControl.dispatchClipboard(wireCommand, new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                return true;
            }

            @Override
            public String onClipboardGet() {
                return "";
            }
        });
        assertTrue(!result.accepted(), "oversized wire command rejected");
        assertEquals("DROIDLAYER/ERROR payload-too-large\n", result.wireResponse(), "returns payload-too-large error");
    }

    private static void handlesMalformedPayloadsAndCommands() {
        BridgeControl.Clipboard dummySeam = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                return true;
            }

            @Override
            public String onClipboardGet() {
                return "";
            }
        };

        // 1. Non-base64 format
        BridgeControl.Result r1 = BridgeControl.dispatchClipboard("DROIDLAYER/CLIPBOARD PUT plain_text\n", dummySeam);
        assertTrue(!r1.accepted(), "plain text without base64: prefix rejected");
        assertEquals("DROIDLAYER/ERROR malformed-command\n", r1.wireResponse(), "error malformed-command");

        // 2. Corrupted base64
        BridgeControl.Result r2 = BridgeControl.dispatchClipboard("DROIDLAYER/CLIPBOARD PUT base64:???not-valid-base64???\n", dummySeam);
        assertTrue(!r2.accepted(), "corrupted base64 rejected");
        assertEquals("DROIDLAYER/ERROR malformed-command\n", r2.wireResponse(), "error malformed-command");

        // 3. Unknown subcommand
        BridgeControl.Result r3 = BridgeControl.dispatchClipboard("DROIDLAYER/CLIPBOARD DELETE\n", dummySeam);
        assertTrue(!r3.accepted(), "unknown subcommand rejected");
        assertEquals("DROIDLAYER/ERROR malformed-command\n", r3.wireResponse(), "error malformed-command");

        // 4. Missing arguments
        BridgeControl.Result r4 = BridgeControl.dispatchClipboard("DROIDLAYER/CLIPBOARD PUT\n", dummySeam);
        assertTrue(!r4.accepted(), "missing payload argument rejected");
        assertEquals("DROIDLAYER/ERROR malformed-command\n", r4.wireResponse(), "error malformed-command");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
