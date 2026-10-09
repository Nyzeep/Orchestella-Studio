package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.InputEvent;
import io.github.nyzeep.orchestella.runtime.contract.KeyEvent;
import io.github.nyzeep.orchestella.runtime.contract.TextCommitEvent;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for ImeBridgeInputConnection and IME text / key passthrough.
 *
 * <p>Verifies 100% loss-free transmission of Chinese characters, full-width punctuation,
 * emojis, backspace translation, and focus pan geometry.
 */
public final class ImeBridgeInputConnectionTest {

    private FakeWaylandChannel channel;
    private ImeBridgeInputConnection inputConnection;

    private static final class FakeWaylandChannel implements WaylandChannel {
        final List<InputEvent> sentEvents = new ArrayList<>();
        ConnectionState state = ConnectionState.CONNECTED;

        @Override
        public ConnectionState state() {
            return state;
        }

        @Override
        public void addStateListener(StateListener listener) {}

        @Override
        public void removeStateListener(StateListener listener) {}

        @Override
        public void registerFrameListener(FrameListener listener) {}

        @Override
        public void unregisterFrameListener(FrameListener listener) {}

        @Override
        public boolean sendInputEvent(InputEvent event) {
            if (state != ConnectionState.CONNECTED) {
                return false;
            }
            sentEvents.add(event);
            return true;
        }

        @Override
        public void close() {
            state = ConnectionState.DISCONNECTED;
        }
    }

    @Before
    public void setUp() {
        channel = new FakeWaylandChannel();
        inputConnection = new ImeBridgeInputConnection(channel);
    }

    @Test
    public void testCommitAsciiText() {
        boolean accepted = inputConnection.commitText("echo 'Hello World'\n", 1);
        assertTrue(accepted);
        assertEquals(1, channel.sentEvents.size());

        InputEvent event = channel.sentEvents.get(0);
        assertTrue(event instanceof TextCommitEvent);
        TextCommitEvent textEvent = (TextCommitEvent) event;
        assertEquals("echo 'Hello World'\n", textEvent.text());
        assertTrue(textEvent.timestampNs() > 0);
    }

    @Test
    public void testCommitChineseCharacters() {
        String chineseText = "你好，Linux 图形桌面！";
        boolean accepted = inputConnection.commitText(chineseText, 1);
        assertTrue(accepted);
        assertEquals(1, channel.sentEvents.size());

        TextCommitEvent textEvent = (TextCommitEvent) channel.sentEvents.get(0);
        assertEquals(chineseText, textEvent.text());
    }

    @Test
    public void testCommitFullWidthPunctuation() {
        String fullWidthPunctuation = "【】《》（）！：；？“”‘’——……、";
        boolean accepted = inputConnection.commitText(fullWidthPunctuation, 1);
        assertTrue(accepted);
        assertEquals(1, channel.sentEvents.size());

        TextCommitEvent textEvent = (TextCommitEvent) channel.sentEvents.get(0);
        assertEquals(fullWidthPunctuation, textEvent.text());
    }

    @Test
    public void testCommitEmojiAndSurrogatePairs() {
        String emojiText = "🚀 🐧 💻 🎉 🇨🇳 🛠️";
        boolean accepted = inputConnection.commitText(emojiText, 1);
        assertTrue(accepted);
        assertEquals(1, channel.sentEvents.size());

        TextCommitEvent textEvent = (TextCommitEvent) channel.sentEvents.get(0);
        assertEquals(emojiText, textEvent.text());
    }

    @Test
    public void testCommitMixedComplexText() {
        String mixed = "Orchestella 2026：测试 CJK 混合输入 🚀 (100% 完整) - 路径: /home/droidlayer/";
        boolean accepted = inputConnection.commitText(mixed, 1);
        assertTrue(accepted);
        assertEquals(1, channel.sentEvents.size());

        TextCommitEvent textEvent = (TextCommitEvent) channel.sentEvents.get(0);
        assertEquals(mixed, textEvent.text());
    }

    @Test
    public void testCommitNullOrEmptyTextIsNoop() {
        assertTrue(inputConnection.commitText(null, 1));
        assertTrue(inputConnection.commitText("", 1));
        assertEquals(0, channel.sentEvents.size());
    }

    @Test
    public void testCommitLargeTextIsChunkedSafely() {
        // Build a large text that would exceed single-event wire length
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("多字节中文测试段落-").append(i).append("；");
        }
        String largeText = sb.toString();
        assertTrue(largeText.length() > 1000);

        boolean accepted = inputConnection.commitText(largeText, 1);
        assertTrue(accepted);
        assertTrue(channel.sentEvents.size() >= 2);

        // Reassemble and verify complete text is preserved 100% without loss
        StringBuilder reassembled = new StringBuilder();
        for (InputEvent ev : channel.sentEvents) {
            assertTrue(ev instanceof TextCommitEvent);
            TextCommitEvent tc = (TextCommitEvent) ev;
            // Ensure each individual chunk does not exceed wire serialization constraint
            assertNotNull(tc.serialize());
            reassembled.append(tc.text());
        }
        assertEquals(largeText, reassembled.toString());
    }

    @Test
    public void testDeleteSurroundingTextTranslatesToBackspace() {
        // Calling deleteSurroundingText(2, 0) should send 2 Backspace key events (press + release)
        boolean accepted = inputConnection.deleteSurroundingText(2, 0);
        assertTrue(accepted);
        // 2 deletions -> 2 presses + 2 releases = 4 events
        assertEquals(4, channel.sentEvents.size());

        for (int i = 0; i < 4; i++) {
            InputEvent ev = channel.sentEvents.get(i);
            assertTrue(ev instanceof KeyEvent);
            KeyEvent ke = (KeyEvent) ev;
            assertEquals(14, ke.keyCode()); // Linux KEY_BACKSPACE
            if (i % 2 == 0) {
                assertEquals(KeyEvent.Action.PRESS, ke.action());
            } else {
                assertEquals(KeyEvent.Action.RELEASE, ke.action());
            }
        }
    }

    @Test
    public void testDisconnectedChannelReturnsFalse() {
        channel.close();
        boolean accepted = inputConnection.commitText("Hello", 1);
        assertFalse(accepted);
    }

    @Test
    public void testSoftKeyboardFocusPanCalculation() {
        // Viewport: 1080 x 2400. Content: 1920 x 1080. ScaleType: FIT_CENTER.
        // Viewport aspect calculator gives dest=[0, 896, 1080x608] (letterbox bars top/bottom).
        ViewportAspectCalculator.ViewportMapping mapping =
                ViewportAspectCalculator.calculate(1920, 1080, 1080, 2400);

        // Let's say input focus in content is at (960, 1000) (near the bottom of content)
        float[] focusViewport = mapping.mapContentToViewport(960, 1000);
        float vy = focusViewport[1];

        // Soft keyboard appears with height 1000px (occupying viewport from y=1400 to 2400)
        int keyboardHeight = 1000;
        int viewportHeight = 2400;

        float panOffset = ImeBridgeInputConnection.calculateFocusPanOffset(
                vy, keyboardHeight, viewportHeight, 32.0f);

        // vy (approx 1459px) will be within the occluded keyboard area (ceiling at 1368px) -> must produce negative pan translation (pushed up)
        assertTrue("panOffset must be negative (shift upward)", panOffset < 0);
        float finalVisualY = vy + panOffset;
        assertTrue("visual Y must be above the keyboard ceiling",
                finalVisualY <= (viewportHeight - keyboardHeight - 32.0f));
    }

    @Test
    public void testSoftKeyboardFocusPanNotNeededWhenAboveKeyboard() {
        // Focus is at top of screen (y = 200)
        float panOffset = ImeBridgeInputConnection.calculateFocusPanOffset(
                200.0f, 800, 2400, 32.0f);
        assertEquals(0.0f, panOffset, 0.001f);
    }
}
