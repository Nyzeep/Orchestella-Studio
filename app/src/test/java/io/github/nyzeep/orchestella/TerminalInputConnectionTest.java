package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import io.github.nyzeep.orchestella.StickyModifierStateMachine.Modifier;
import io.github.nyzeep.orchestella.StickyModifierStateMachine.State;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for TerminalInputConnection verifying direct translation of IME input,
 * key events, backspace, enter, and sticky modifiers to raw PTY byte streams.
 */
public final class TerminalInputConnectionTest {

    private ByteArrayOutputStream ptyStream;
    private TerminalInputSink inputSink;
    private StickyModifierStateMachine stickyStateMachine;
    private TerminalInputConnection inputConnection;

    @Before
    public void setUp() {
        ptyStream = new ByteArrayOutputStream();
        inputSink = new TerminalInputSink() {
            @Override
            public void sendBytes(byte[] bytes) {
                if (bytes != null) {
                    ptyStream.write(bytes, 0, bytes.length);
                }
            }
        };
        stickyStateMachine = new StickyModifierStateMachine();
        inputConnection = new TerminalInputConnection(inputSink, stickyStateMachine);
    }

    @Test
    public void testCommitAsciiCharactersWithNewlineTranslatesToCarriageReturn() {
        boolean accepted = inputConnection.commitText("ls -l\n", 1);
        assertTrue(accepted);

        byte[] expected = new byte[]{0x6c, 0x73, 0x20, 0x2d, 0x6c, 0x0d};
        assertArrayEquals(expected, ptyStream.toByteArray());
    }

    @Test
    public void testCommitUtf8MultibyteCharacters() {
        boolean accepted = inputConnection.commitText("你好\n", 1);
        assertTrue(accepted);

        byte[] chineseBytes = "你好".getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[chineseBytes.length + 1];
        System.arraycopy(chineseBytes, 0, expected, 0, chineseBytes.length);
        expected[expected.length - 1] = 0x0d;

        assertArrayEquals(expected, ptyStream.toByteArray());
    }

    @Test
    public void testDeleteSurroundingTextSendsDelBytes() {
        boolean accepted = inputConnection.deleteSurroundingText(2, 0);
        assertTrue(accepted);

        byte[] expected = new byte[]{(byte) 0x7f, (byte) 0x7f};
        assertArrayEquals(expected, ptyStream.toByteArray());
    }

    @Test
    public void testKeyEventBackspaceSendsDelByte() {
        boolean accepted = inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL);
        assertTrue(accepted);

        assertArrayEquals(new byte[]{(byte) 0x7f}, ptyStream.toByteArray());
    }

    @Test
    public void testKeyEventEnterSendsCarriageReturn() {
        boolean accepted = inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER);
        assertTrue(accepted);

        assertArrayEquals(new byte[]{0x0d}, ptyStream.toByteArray());
    }

    @Test
    public void testKeyEventTabSendsTabByte() {
        boolean accepted = inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB);
        assertTrue(accepted);

        assertArrayEquals(new byte[]{'\t'}, ptyStream.toByteArray());
    }

    @Test
    public void testKeyEventDirectionArrowsSendAnsiSequences() {
        inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP);
        inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
        inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT);
        inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT);

        String expected = "\u001B[A\u001B[B\u001B[D\u001B[C";
        assertEquals(expected, new String(ptyStream.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void testStickyCtrlModifierWithCommitText() {
        // User clicked CTRL on auxiliary bar -> LATCHED
        stickyStateMachine.onModifierClicked(Modifier.CTRL);
        assertTrue(stickyStateMachine.isCtrlActive());

        // IME commits 'c' -> should produce 0x03 (SIGINT) and consume modifier
        boolean accepted = inputConnection.commitText("c", 1);
        assertTrue(accepted);

        assertArrayEquals(new byte[]{0x03}, ptyStream.toByteArray());
        assertFalse(stickyStateMachine.isCtrlActive());
        assertEquals(State.OFF, stickyStateMachine.getState(Modifier.CTRL));
    }

    @Test
    public void testStickyCtrlModifierWithKeyEvent() {
        stickyStateMachine.onModifierClicked(Modifier.CTRL);
        assertTrue(stickyStateMachine.isCtrlActive());

        // Press 'd' -> EOF (0x04)
        boolean accepted = inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D);
        assertTrue(accepted);

        assertArrayEquals(new byte[]{0x04}, ptyStream.toByteArray());
        assertFalse(stickyStateMachine.isCtrlActive());
    }

    @Test
    public void testHardwareModifierKeysWithHandleKeyEvent() {
        // Hardware physical Ctrl+C
        boolean ctrlC = inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 'c', true, false);
        assertTrue(ctrlC);
        assertArrayEquals(new byte[]{0x03}, ptyStream.toByteArray());

        ptyStream.reset();
        // Hardware physical Alt+B
        boolean altB = inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, 'b', false, true);
        assertTrue(altB);
        assertArrayEquals(new byte[]{0x1B, 'b'}, ptyStream.toByteArray());
    }

    @Test
    public void testStickyAltModifierWithCommitText() {
        stickyStateMachine.onModifierClicked(Modifier.ALT);
        assertTrue(stickyStateMachine.isAltActive());

        // IME commits 'f' -> ESC + 'f'
        boolean accepted = inputConnection.commitText("f", 1);
        assertTrue(accepted);

        assertArrayEquals(new byte[]{0x1B, 'f'}, ptyStream.toByteArray());
        assertFalse(stickyStateMachine.isAltActive());
        assertEquals(State.OFF, stickyStateMachine.getState(Modifier.ALT));
    }

    @Test
    public void testPerformEditorActionSendsCarriageReturn() {
        boolean handledSend = inputConnection.performEditorAction(EditorInfo.IME_ACTION_SEND);
        boolean handledDone = inputConnection.performEditorAction(EditorInfo.IME_ACTION_DONE);

        assertTrue(handledSend);
        assertTrue(handledDone);
        assertArrayEquals(new byte[]{0x0d, 0x0d}, ptyStream.toByteArray());
    }

    @Test public void compositionReplacementCommitsExactlyOnce() {
        inputConnection.setComposingText("n", 1);
        inputConnection.setComposingText("ni", 1);
        inputConnection.setComposingText("你", 1);
        assertEquals("你", inputConnection.getTextBeforeCursor(10, 0));
        assertEquals(0, ptyStream.size());
        inputConnection.finishComposingText();
        inputConnection.finishComposingText();
        assertEquals("你", new String(ptyStream.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test public void committingCandidateReplacesCompositionAndDoesNotDoubleSend() {
        inputConnection.setComposingText("ni", 1);
        inputConnection.commitText("你", 1);
        inputConnection.finishComposingText();
        assertEquals("你", new String(ptyStream.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test public void composingBackspaceStaysLocalUntilGuestDeletionIsRequired() {
        inputConnection.setComposingText("ab😀", 1);
        assertTrue(inputConnection.deleteSurroundingTextInCodePoints(1, 0));
        assertEquals("ab", inputConnection.getTextBeforeCursor(20, 0));
        assertEquals(0, ptyStream.size());
        assertTrue(inputConnection.deleteSurroundingText(3, 0));
        assertArrayEquals(new byte[]{0x7f}, ptyStream.toByteArray());
        assertFalse(inputConnection.deleteSurroundingText(-1, 0));
    }

    @Test public void ctrlAndAltCombineWithFullUnicodeAndConsumeOnce() {
        stickyStateMachine.onModifierClicked(Modifier.CTRL);
        stickyStateMachine.onModifierClicked(Modifier.ALT);
        inputConnection.commitText("cx", 1);
        assertArrayEquals(new byte[]{0x1b, 3, 'x'}, ptyStream.toByteArray());
        ptyStream.reset();
        stickyStateMachine.onModifierClicked(Modifier.ALT);
        inputConnection.commitText("😀", 1);
        assertEquals("\u001b😀", new String(ptyStream.toByteArray(), StandardCharsets.UTF_8));
        assertFalse(stickyStateMachine.isAltActive());
    }

    @Test public void lockedCtrlPreservesUnmappedUnicodeRatherThanTruncating() {
        stickyStateMachine.onModifierClicked(Modifier.CTRL, 1000);
        stickyStateMachine.onModifierClicked(Modifier.CTRL, 1100);
        inputConnection.commitText("你好", 1);
        assertEquals("你好", new String(ptyStream.toByteArray(), StandardCharsets.UTF_8));
        assertTrue(stickyStateMachine.isCtrlActive());
    }

    @Test public void arrowsHonorApplicationModeAndCombinedModifiers() {
        inputConnection.setTerminalModes(() -> true, () -> false);
        inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP);
        stickyStateMachine.onModifierClicked(Modifier.CTRL);
        inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT);
        inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0, true, true, true);
        assertEquals("\u001bOA\u001b[1;5D\u001b[1;8C", new String(ptyStream.toByteArray(), StandardCharsets.UTF_8));
        assertFalse(stickyStateMachine.isCtrlActive());
    }

    @Test public void realTabShiftTabAndFunctionKeysReachGuest() {
        inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB);
        inputConnection.handleKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, false, false, true);
        inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1);
        inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F12);
        assertEquals("\t\u001b[Z\u001bOP\u001b[24~", new String(ptyStream.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test public void pasteNormalizesNewlinesAndBypassesLatchedCtrl() {
        inputConnection.setTerminalModes(() -> false, () -> true);
        stickyStateMachine.onModifierClicked(Modifier.CTRL);
        inputConnection.pasteText("echo 1\r\necho 2\n\u001b[201~");
        assertEquals("\u001b[200~echo 1\recho 2\r[201~\u001b[201~", new String(ptyStream.toByteArray(), StandardCharsets.UTF_8));
        assertTrue(stickyStateMachine.isCtrlActive());
    }

    @Test public void closeDropsUncommittedCompositionAndRefusesFutureInput() {
        inputConnection.setComposingText("secret", 1);
        inputConnection.closeConnection();
        assertFalse(inputConnection.commitText("x", 1));
        assertFalse(inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        assertEquals(0, ptyStream.size());
    }

    @Test public void systemBackDoesNotSendEscapeOrConsumeModifiers() {
        stickyStateMachine.onModifierClicked(Modifier.CTRL);
        assertFalse(inputConnection.sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        assertEquals(0, ptyStream.size());
        assertTrue(stickyStateMachine.isCtrlActive());
    }

    @Test
    public void testNullOrEmptyCommitTextIsNoOp() {
        assertTrue(inputConnection.commitText(null, 1));
        assertTrue(inputConnection.commitText("", 1));
        assertEquals(0, ptyStream.size());
    }
}
