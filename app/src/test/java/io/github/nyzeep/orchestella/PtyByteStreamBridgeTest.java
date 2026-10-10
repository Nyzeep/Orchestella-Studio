package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import io.github.nyzeep.orchestella.StickyModifierStateMachine.Modifier;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure JVM specification test for Ticket 06:
 * Bridges simulated keyboard events and IME inputs directly to raw VT100/Xterm PTY byte streams.
 * Conforms to Phase 6.0 Ticket 06 Section 88.
 */
public class PtyByteStreamBridgeTest {

    private ByteArrayOutputStream ptyOutputStream;
    private TerminalInputSink ptySink;
    private StickyModifierStateMachine stickyStateMachine;
    private TerminalInputConnection inputConnection;

    @Before
    public void setUp() {
        ptyOutputStream = new ByteArrayOutputStream();
        ptySink = new TerminalInputSink() {
            @Override
            public void sendBytes(byte[] bytes) {
                if (bytes != null) {
                    ptyOutputStream.write(bytes, 0, bytes.length);
                }
            }
        };
        stickyStateMachine = new StickyModifierStateMachine();
        inputConnection = new TerminalInputConnection(ptySink, stickyStateMachine);
    }

    @Test
    public void testLsCommandStreaming() {
        // Spec line 89: 模拟用户键盘输入序列 ls -la\n -> PTY 原始字节序列符合 VT100/Xterm 工业标准
        inputConnection.commitText("ls -la\n", 1);
        byte[] expected = new byte[]{
                'l', 's', ' ', '-', 'l', 'a', 0x0D // 0x0D is Carriage Return '\r'
        };
        assertArrayEquals(expected, ptyOutputStream.toByteArray());
    }

    @Test
    public void testBackspaceDelByte() {
        // Spec line 59: 按下 Backspace 键向 PTY 发送 \x7f (ASCII DEL)
        boolean consumed = inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0, false, false);
        assertTrue(consumed);
        assertArrayEquals(new byte[]{(byte) 0x7F}, ptyOutputStream.toByteArray());
    }

    @Test
    public void testEnterCrByte() {
        // Spec line 58: 按下 Enter 键向 PTY 发送 \r (0x0D)
        boolean consumed = inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, false, false);
        assertTrue(consumed);
        assertArrayEquals(new byte[]{0x0D}, ptyOutputStream.toByteArray());
    }

    @Test
    public void testTabByte() {
        // Spec line 60: 按下 Tab 键向 PTY 发送 \t (0x09)
        boolean consumed = inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, false, false);
        assertTrue(consumed);
        assertArrayEquals(new byte[]{0x09}, ptyOutputStream.toByteArray());
    }

    @Test
    public void testCtrlCInterrupt() {
        // Spec line 87: 点亮 CTRL 键后键入 'c' 自动转化为 0x03 (SIGINT)
        stickyStateMachine.onModifierClicked(Modifier.CTRL);
        assertTrue(stickyStateMachine.isCtrlActive());

        inputConnection.commitText("c", 1);

        assertArrayEquals(new byte[]{0x03}, ptyOutputStream.toByteArray());
        assertFalse(stickyStateMachine.isCtrlActive()); // 自动解除瞬态挂起
    }

    @Test
    public void testHardwareCtrlCInterrupt() {
        // 物理键盘硬件 Ctrl+C
        boolean consumed = inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 'c', true, false);
        assertTrue(consumed);
        assertArrayEquals(new byte[]{0x03}, ptyOutputStream.toByteArray());
    }

    @Test
    public void testAnsiArrowNavigationSequences() {
        // Spec line 89: ANSI 箭头移动
        inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, false, false);
        inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0, false, false);
        inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, false, false);
        inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0, false, false);

        String expected = "\u001B[A\u001B[B\u001B[D\u001B[C";
        assertEquals(expected, new String(ptyOutputStream.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void testSystemNavigationKeysAreNotIntercepted() {
        // 验证系统按键（Back / 音量键 / Home / 电源键）绝不被终端输入拦截，确保系统导航畅通无阻
        boolean backDown = inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, false, false);
        boolean backUp = inputConnection.handleKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, false, false);
        boolean volumeDown = inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0, false, false);
        boolean volumeUp = inputConnection.handleKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, 0, false, false);

        assertFalse("KEYCODE_BACK DOWN must not be consumed by terminal", backDown);
        assertFalse("KEYCODE_BACK UP must not be consumed by terminal", backUp);
        assertFalse("KEYCODE_VOLUME_DOWN must not be consumed by terminal", volumeDown);
        assertFalse("KEYCODE_VOLUME_UP must not be consumed by terminal", volumeUp);
        assertEquals(0, ptyOutputStream.size());
    }

    @Test
    public void testUtf8MultibyteStreaming() {
        // 验证中文与 UTF-8 多字节字符流式直通
        inputConnection.commitText("你好Linux\n", 1);
        String output = new String(ptyOutputStream.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("你好Linux\r", output);
    }
}
