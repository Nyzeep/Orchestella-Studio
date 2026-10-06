package io.github.nyzeep.droidlayer.clipboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for ClipboardBridgeManager payload limits and multi-language UTF-8 handling:
 * - Empty string and null handling
 * - Multi-language UTF-8 integrity (Chinese, full-width punctuation, Emoji)
 * - Maximum safe payload (exactly 256KB = 262,144 bytes)
 * - Over-limit payload rejection (262,145+ bytes rejected at boundary)
 */
public final class ClipboardPayloadLimitTest {

    private FakeSystemClipboard systemClipboard;
    private RecordingRemoteSender remoteSender;
    private ClipboardBridgeManager manager;

    private static final class FakeSystemClipboard implements ClipboardBridgeManager.SystemClipboardSeam {
        String text;
        Runnable listener;

        @Override
        public void setText(String text) {
            this.text = text;
            if (listener != null) {
                listener.run();
            }
        }

        @Override
        public String getText() {
            return text;
        }

        @Override
        public void setListener(Runnable listener) {
            this.listener = listener;
        }

        @Override
        public void clearListener() {
            this.listener = null;
        }

        void simulateUserCopy(String newText) {
            this.text = newText;
            if (listener != null) {
                listener.run();
            }
        }
    }

    private static final class RecordingRemoteSender implements ClipboardBridgeManager.RemoteClipboardSender {
        final List<String> sentTexts = new ArrayList<>();

        @Override
        public boolean sendClipboardPut(String text) {
            sentTexts.add(text);
            return true;
        }
    }

    @Before
    public void setUp() {
        systemClipboard = new FakeSystemClipboard();
        remoteSender = new RecordingRemoteSender();
        manager = new ClipboardBridgeManager(systemClipboard, remoteSender);
        manager.onWindowFocusChanged(true);
    }

    @Test
    public void testEmptyAndNullPayload() {
        // Null remote put must be rejected
        assertFalse(manager.onClipboardPut(null));
        assertEquals(0, remoteSender.sentTexts.size());

        // Empty string handling
        systemClipboard.simulateUserCopy("");
        // Empty string should not be dispatched
        assertEquals(0, remoteSender.sentTexts.size());
    }

    @Test
    public void testMultilingualUtf8Payload() {
        String complexText = "【DroidLayer 剪贴板测试】\n"
                + "多语言混合：Hello World, こんにちは, 안녕하세요, Bonjour, Привет!\n"
                + "Emoji 与符号：🚀 🐧 🇨🇳 💻 🛠️ ✨ 💯\n"
                + "代码段：fn main() { println!(\"Orchestella Studio 2026\"); }\n";

        // Remote put preserves full integrity
        boolean accepted = manager.onClipboardPut(complexText);
        assertTrue(accepted);
        assertEquals(complexText, systemClipboard.getText());
        assertEquals(complexText, manager.onClipboardGet());

        // Local copy dispatches full integrity
        systemClipboard.simulateUserCopy(complexText + " [Updated]");
        assertEquals(1, remoteSender.sentTexts.size());
        assertEquals(complexText + " [Updated]", remoteSender.sentTexts.get(0));
    }

    @Test
    public void testExact256KbPayloadAccepted() {
        // Generate exactly 262,144 bytes of ASCII text
        int targetBytes = ClipboardBridgeManager.MAX_PAYLOAD_BYTES; // 262,144 bytes
        byte[] payloadBytes = new byte[targetBytes];
        Arrays.fill(payloadBytes, (byte) 'A');
        String exact256KbText = new String(payloadBytes, StandardCharsets.UTF_8);
        assertEquals(targetBytes, exact256KbText.getBytes(StandardCharsets.UTF_8).length);

        // Remote put should accept exact 256KB
        boolean remoteAccepted = manager.onClipboardPut(exact256KbText);
        assertTrue("Exact 256KB text should be accepted", remoteAccepted);
        assertEquals(exact256KbText, systemClipboard.getText());

        // Local copy should dispatch exact 256KB
        systemClipboard.simulateUserCopy(exact256KbText);
        // Note: duplicate hash will suppress dispatch of identical content, so modify first byte to 'B'
        byte[] modifiedBytes = new byte[targetBytes];
        Arrays.fill(modifiedBytes, (byte) 'B');
        String modified256KbText = new String(modifiedBytes, StandardCharsets.UTF_8);

        systemClipboard.simulateUserCopy(modified256KbText);
        assertEquals(1, remoteSender.sentTexts.size());
        assertEquals(targetBytes, remoteSender.sentTexts.get(0).getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    public void testOverLimitPayloadRejected() {
        // Generate 262,145 bytes (256KB + 1 byte)
        int overLimitBytes = ClipboardBridgeManager.MAX_PAYLOAD_BYTES + 1;
        byte[] payloadBytes = new byte[overLimitBytes];
        Arrays.fill(payloadBytes, (byte) 'X');
        String overLimitText = new String(payloadBytes, StandardCharsets.UTF_8);

        // Remote put must be rejected
        boolean remoteAccepted = manager.onClipboardPut(overLimitText);
        assertFalse("Over-limit payload (>256KB) must be rejected", remoteAccepted);

        // Local copy over 256KB must NOT be dispatched across Bridge
        systemClipboard.simulateUserCopy(overLimitText);
        assertEquals("Local copy over 256KB must be intercepted and rejected", 0, remoteSender.sentTexts.size());
        assertTrue(manager.getDroppedPayloadCount() >= 1);
    }
}
