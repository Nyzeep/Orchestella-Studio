package io.github.nyzeep.droidlayer.runtime.contract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests strategy-neutral Bridge v1 clipboard protocol parsing, safety boundaries,
 * and seam dispatching.
 */
public final class BridgeControlClipboardTest {
    public static void main(String[] arguments) throws Exception {
        identifiesClipboardCommand();
        dispatchesValidClipboardPut();
        dispatchesValidClipboardPutWithUnicodeAndMultiline();
        dispatchesValidClipboardPutWithEmptyPayload();
        enforcesExact256KbBoundaryOnPut();
        rejectsPayloadExceeding256Kb();
        rejectsMalformedPutMissingPrefix();
        rejectsMalformedPutInvalidBase64();
        rejectsMalformedPutInvalidUtf8();
        rejectsMalformedSubcommandsAndMissingArgs();
        dispatchesValidClipboardGet();
        handlesEmptyClipboardGet();
        rejectsClipboardWhenSeamUnavailable();
        integratesWithSeamsAggregation();
        integratesWithBridgeClientPutAndGet();
        rejectsBridgeClientPutExceedingLimit();
        System.out.println("BRIDGE_CONTROL_CLIPBOARD_TEST_OK");
    }

    private static void identifiesClipboardCommand() {
        assertTrue(BridgeControl.isClipboardCommand("DROIDLAYER/CLIPBOARD PUT base64:aGVsbG8=\n"), "identifies put");
        assertTrue(BridgeControl.isClipboardCommand("DROIDLAYER/CLIPBOARD GET\n"), "identifies get");
        assertTrue(BridgeControl.isClipboardCommand("DROIDLAYER/CLIPBOARD\n"), "identifies command prefix");
        assertFalse(BridgeControl.isClipboardCommand("DROIDLAYER/PING\n"), "does not match ping");
        assertFalse(BridgeControl.isClipboardCommand(null), "null is not clipboard command");
    }

    private static void dispatchesValidClipboardPut() {
        AtomicReference<String> receivedText = new AtomicReference<>();
        BridgeControl.Clipboard clipboard = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                receivedText.set(text);
                return true;
            }

            @Override
            public String onClipboardGet() {
                return null;
            }
        };

        String plainText = "Hello, Orchestella Clipboard!";
        String base64 = Base64.getEncoder().encodeToString(plainText.getBytes(StandardCharsets.UTF_8));
        String requestLine = "DROIDLAYER/CLIPBOARD PUT base64:" + base64 + "\n";

        BridgeControl.Result result = BridgeControl.dispatchClipboard(requestLine, clipboard);
        assertTrue(result.accepted(), "accepted clipboard put");
        assertEquals("DROIDLAYER/OK clipboard\n", result.wireResponse(), "wire response matches ok");
        assertEquals(plainText, receivedText.get(), "received text matches original");
    }

    private static void dispatchesValidClipboardPutWithUnicodeAndMultiline() {
        AtomicReference<String> receivedText = new AtomicReference<>();
        BridgeControl.Clipboard clipboard = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                receivedText.set(text);
                return true;
            }

            @Override
            public String onClipboardGet() {
                return null;
            }
        };

        String complexText = "第一行：Android 宿主与 Linux 虚拟机深度互通 🎉\n第二行：Special symbols: <>&'\"\\n\r\t\n第三行：EOF";
        String base64 = Base64.getEncoder().encodeToString(complexText.getBytes(StandardCharsets.UTF_8));
        String requestLine = "DROIDLAYER/CLIPBOARD PUT base64:" + base64 + "\r\n";

        BridgeControl.Result result = BridgeControl.dispatchClipboard(requestLine, clipboard);
        assertTrue(result.accepted(), "accepted multiline unicode put");
        assertEquals("DROIDLAYER/OK clipboard\n", result.wireResponse(), "wire response");
        assertEquals(complexText, receivedText.get(), "multiline text preserved exactly");
    }

    private static void dispatchesValidClipboardPutWithEmptyPayload() {
        AtomicReference<String> receivedText = new AtomicReference<>();
        BridgeControl.Clipboard clipboard = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                receivedText.set(text);
                return true;
            }

            @Override
            public String onClipboardGet() {
                return null;
            }
        };

        String requestLine = "DROIDLAYER/CLIPBOARD PUT base64:\n";
        BridgeControl.Result result = BridgeControl.dispatchClipboard(requestLine, clipboard);
        assertTrue(result.accepted(), "accepted empty payload put");
        assertEquals("DROIDLAYER/OK clipboard\n", result.wireResponse(), "wire response");
        assertEquals("", receivedText.get(), "empty payload received");
    }

    private static void enforcesExact256KbBoundaryOnPut() {
        AtomicReference<String> receivedText = new AtomicReference<>();
        BridgeControl.Clipboard clipboard = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                receivedText.set(text);
                return true;
            }

            @Override
            public String onClipboardGet() {
                return null;
            }
        };

        // Exactly 256KB = 262,144 bytes of ASCII data
        byte[] exact256Kb = new byte[262144];
        Arrays.fill(exact256Kb, (byte) 'A');
        String text256Kb = new String(exact256Kb, StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(exact256Kb);

        String requestLine = "DROIDLAYER/CLIPBOARD PUT base64:" + base64 + "\n";
        BridgeControl.Result result = BridgeControl.dispatchClipboard(requestLine, clipboard);
        assertTrue(result.accepted(), "exact 256KB payload accepted");
        assertEquals("DROIDLAYER/OK clipboard\n", result.wireResponse(), "ok response");
        assertEquals(262144, receivedText.get().length(), "received exactly 262144 chars");
    }

    private static void rejectsPayloadExceeding256Kb() {
        BridgeControl.Clipboard clipboard = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                return true;
            }

            @Override
            public String onClipboardGet() {
                return null;
            }
        };

        // 256KB + 1 byte = 262,145 bytes
        byte[] overLimit = new byte[262145];
        Arrays.fill(overLimit, (byte) 'B');
        String base64 = Base64.getEncoder().encodeToString(overLimit);

        String requestLine = "DROIDLAYER/CLIPBOARD PUT base64:" + base64 + "\n";
        BridgeControl.Result result = BridgeControl.dispatchClipboard(requestLine, clipboard);
        assertFalse(result.accepted(), "overlimit payload rejected");
        assertEquals("DROIDLAYER/ERROR payload-too-large\n", result.wireResponse(), "returns payload-too-large");
    }

    private static void rejectsMalformedPutMissingPrefix() {
        BridgeControl.Clipboard clipboard = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                return true;
            }

            @Override
            public String onClipboardGet() {
                return null;
            }
        };

        String requestLine = "DROIDLAYER/CLIPBOARD PUT rawtext_without_base64_prefix\n";
        BridgeControl.Result result = BridgeControl.dispatchClipboard(requestLine, clipboard);
        assertFalse(result.accepted(), "missing base64 prefix rejected");
        assertEquals("DROIDLAYER/ERROR malformed-command\n", result.wireResponse(), "returns malformed-command");
    }

    private static void rejectsMalformedPutInvalidBase64() {
        BridgeControl.Clipboard clipboard = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                return true;
            }

            @Override
            public String onClipboardGet() {
                return null;
            }
        };

        String requestLine = "DROIDLAYER/CLIPBOARD PUT base64:not_valid_base64_characters!@#$%\n";
        BridgeControl.Result result = BridgeControl.dispatchClipboard(requestLine, clipboard);
        assertFalse(result.accepted(), "invalid base64 rejected");
        assertEquals("DROIDLAYER/ERROR malformed-command\n", result.wireResponse(), "returns malformed-command");
    }

    private static void rejectsMalformedPutInvalidUtf8() {
        BridgeControl.Clipboard clipboard = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                return true;
            }

            @Override
            public String onClipboardGet() {
                return null;
            }
        };

        // Invalid UTF-8 sequence: lone continuation byte 0x80 or invalid 0xFF
        byte[] invalidUtf8 = new byte[] {(byte) 0xC3, (byte) 0x28}; // 0xC3 expects continuation 0x80..0xBF, but 0x28 is ASCII '('
        String base64 = Base64.getEncoder().encodeToString(invalidUtf8);
        String requestLine = "DROIDLAYER/CLIPBOARD PUT base64:" + base64 + "\n";

        BridgeControl.Result result = BridgeControl.dispatchClipboard(requestLine, clipboard);
        assertFalse(result.accepted(), "invalid utf8 rejected");
        assertEquals("DROIDLAYER/ERROR malformed-command\n", result.wireResponse(), "returns malformed-command");
    }

    private static void rejectsMalformedSubcommandsAndMissingArgs() {
        BridgeControl.Clipboard clipboard = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                return true;
            }

            @Override
            public String onClipboardGet() {
                return null;
            }
        };

        BridgeControl.Result r1 = BridgeControl.dispatchClipboard("DROIDLAYER/CLIPBOARD\n", clipboard);
        assertFalse(r1.accepted(), "missing subcommand");
        assertEquals("DROIDLAYER/ERROR malformed-command\n", r1.wireResponse(), "malformed-command");

        BridgeControl.Result r2 = BridgeControl.dispatchClipboard("DROIDLAYER/CLIPBOARD PUT\n", clipboard);
        assertFalse(r2.accepted(), "missing put payload");
        assertEquals("DROIDLAYER/ERROR malformed-command\n", r2.wireResponse(), "malformed-command");

        BridgeControl.Result r3 = BridgeControl.dispatchClipboard("DROIDLAYER/CLIPBOARD UNKNOWN\n", clipboard);
        assertFalse(r3.accepted(), "unknown subcommand");
        assertEquals("DROIDLAYER/ERROR malformed-command\n", r3.wireResponse(), "malformed-command");

        BridgeControl.Result r4 = BridgeControl.dispatchClipboard("DROIDLAYER/CLIPBOARD GET EXTRA\n", clipboard);
        assertFalse(r4.accepted(), "get with extra arguments");
        assertEquals("DROIDLAYER/ERROR malformed-command\n", r4.wireResponse(), "malformed-command");
    }

    private static void dispatchesValidClipboardGet() {
        BridgeControl.Clipboard clipboard = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                return false;
            }

            @Override
            public String onClipboardGet() {
                return "Linux Guest Copied Text";
            }
        };

        BridgeControl.Result result = BridgeControl.dispatchClipboard("DROIDLAYER/CLIPBOARD GET\n", clipboard);
        assertTrue(result.accepted(), "get accepted");
        String expectedBase64 = Base64.getEncoder().encodeToString("Linux Guest Copied Text".getBytes(StandardCharsets.UTF_8));
        assertEquals("DROIDLAYER/OK clipboard base64:" + expectedBase64 + "\n", result.wireResponse(), "get response");
    }

    private static void handlesEmptyClipboardGet() {
        BridgeControl.Clipboard clipboard = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                return false;
            }

            @Override
            public String onClipboardGet() {
                return null;
            }
        };

        BridgeControl.Result result = BridgeControl.dispatchClipboard("DROIDLAYER/CLIPBOARD GET\n", clipboard);
        assertTrue(result.accepted(), "get with null returns empty");
        assertEquals("DROIDLAYER/OK clipboard base64:\n", result.wireResponse(), "empty get response");
    }

    private static void rejectsClipboardWhenSeamUnavailable() {
        BridgeControl.Result rPut = BridgeControl.dispatch("DROIDLAYER/CLIPBOARD PUT base64:aGVsbG8=\n");
        assertFalse(rPut.accepted(), "put without clipboard rejected");
        assertEquals("DROIDLAYER/ERROR clipboard-unavailable\n", rPut.wireResponse(), "clipboard-unavailable");

        BridgeControl.Result rGet = BridgeControl.dispatch("DROIDLAYER/CLIPBOARD GET\n");
        assertFalse(rGet.accepted(), "get without clipboard rejected");
        assertEquals("DROIDLAYER/ERROR clipboard-unavailable\n", rGet.wireResponse(), "clipboard-unavailable");
    }

    private static void integratesWithSeamsAggregation() {
        AtomicReference<String> receivedText = new AtomicReference<>();
        BridgeControl.Clipboard clipboard = new BridgeControl.Clipboard() {
            @Override
            public boolean onClipboardPut(String text) {
                receivedText.set(text);
                return true;
            }

            @Override
            public String onClipboardGet() {
                return "from seam";
            }
        };

        BridgeControl.Seams seams = BridgeControl.Seams.of(clipboard);
        assertEquals(clipboard, seams.clipboard(), "clipboard seam accessible");

        BridgeControl.Result rPut = BridgeControl.dispatch("DROIDLAYER/CLIPBOARD PUT base64:c2VhbQ==\n", seams);
        assertTrue(rPut.accepted(), "seams dispatch put");
        assertEquals("seam", receivedText.get(), "received text via seams");

        BridgeControl.Result rGet = BridgeControl.dispatch("DROIDLAYER/CLIPBOARD GET\n", seams);
        assertTrue(rGet.accepted(), "seams dispatch get");
        String expectedBase64 = Base64.getEncoder().encodeToString("from seam".getBytes(StandardCharsets.UTF_8));
        assertEquals("DROIDLAYER/OK clipboard base64:" + expectedBase64 + "\n", rGet.wireResponse(), "seams get response");
    }

    private static void integratesWithBridgeClientPutAndGet() throws IOException {
        ByteArrayOutputStream outbound = new ByteArrayOutputStream();
        ByteArrayInputStream inbound = new ByteArrayInputStream(
                ("DROIDLAYER/OK clipboard\n"
                + "DROIDLAYER/OK clipboard base64:"
                + Base64.getEncoder().encodeToString("client get test".getBytes(StandardCharsets.UTF_8))
                + "\n").getBytes(StandardCharsets.US_ASCII));

        BridgeClient client = new BridgeClient(inbound, outbound);
        BridgeClient.Result putResult = client.clipboardPut("client put test");
        assertTrue(putResult.accepted(), "client put accepted");
        assertEquals("ok-clipboard", putResult.responseKind(), "put response kind");

        BridgeClient.Result getResult = client.clipboardGet();
        assertTrue(getResult.accepted(), "client get accepted");
        assertEquals("ok-clipboard", getResult.responseKind(), "get response kind");
        assertEquals("client get test", getResult.payload(), "get payload matches");

        String sentWire = outbound.toString(StandardCharsets.US_ASCII);
        String expectedPutWire = "DROIDLAYER/CLIPBOARD PUT base64:"
                + Base64.getEncoder().encodeToString("client put test".getBytes(StandardCharsets.UTF_8))
                + "\n";
        String expectedGetWire = "DROIDLAYER/CLIPBOARD GET\n";
        assertTrue(sentWire.contains(expectedPutWire), "sent wire contains put command");
        assertTrue(sentWire.contains(expectedGetWire), "sent wire contains get command");
    }

    private static void rejectsBridgeClientPutExceedingLimit() {
        ByteArrayOutputStream outbound = new ByteArrayOutputStream();
        ByteArrayInputStream inbound = new ByteArrayInputStream(new byte[0]);
        BridgeClient client = new BridgeClient(inbound, outbound);

        byte[] overLimit = new byte[262145];
        Arrays.fill(overLimit, (byte) 'C');
        String largeText = new String(overLimit, StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> client.clipboardPut(largeText), "client rejects > 256KB");
        assertThrows(IllegalArgumentException.class, () -> client.clipboardPut(null), "client rejects null text");
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static <T extends Throwable> void assertThrows(
            Class<T> type, ThrowingRunnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return;
            }
            throw new AssertionError(description + ": unexpected exception", throwable);
        }
        throw new AssertionError(description + ": expected exception " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
