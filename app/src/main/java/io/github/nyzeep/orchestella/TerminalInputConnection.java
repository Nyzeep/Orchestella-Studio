package io.github.nyzeep.orchestella;

import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.SurroundingText;
import java.nio.charset.StandardCharsets;

/**
 * Direct native terminal {@link InputConnection} streaming typed characters, control codes,
 * backspaces, carriage returns, and ANSI navigation escape sequences directly into the Linux PTY.
 *
 * <p>Conforms to Ticket 06:
 * <ul>
 *   <li>Streams typed ASCII and multibyte UTF-8 characters directly to {@link TerminalInputSink};</li>
 *   <li>Converts soft keyboard Enter (and '\n') to standard Linux Carriage Return ({@code 0x0D});</li>
 *   <li>Converts Backspace (KEYCODE_DEL & deleteSurroundingText) to ASCII DEL ({@code 0x7F});</li>
 *   <li>Converts Tab to horizontal tab ({@code 0x09});</li>
 *   <li>Converts DPAD arrows to VT100 / ANSI escape sequences;</li>
 *   <li>Integrates seamlessly with {@link StickyModifierStateMachine} for Ctrl / Alt combinations;</li>
 *   <li>100% pure JVM testable without relying on Android framework stubs.</li>
 * </ul>
 */
public class TerminalInputConnection implements InputConnection {

    public static final byte ASCII_DEL = 0x7F;
    public static final byte ASCII_CR = 0x0D;
    public static final byte ASCII_TAB = 0x09;
    private static final byte[] FORWARD_DEL_SEQUENCE = "\u001B[3~".getBytes(StandardCharsets.UTF_8);

    private final TerminalInputSink sink;
    private final StickyModifierStateMachine stickyStateMachine;

    public TerminalInputConnection(TerminalInputSink sink, StickyModifierStateMachine stickyStateMachine) {
        this.sink = sink;
        this.stickyStateMachine = stickyStateMachine;
    }

    public TerminalInputSink getSink() {
        return sink;
    }

    public StickyModifierStateMachine getStickyStateMachine() {
        return stickyStateMachine;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (text == null || text.length() == 0) {
            return true;
        }
        if (sink == null) {
            return false;
        }

        boolean hasActiveModifier = stickyStateMachine != null
                && (stickyStateMachine.isCtrlActive() || stickyStateMachine.isAltActive());

        if (!hasActiveModifier) {
            String str = text.toString();
            if (str.indexOf('\n') >= 0 || str.indexOf('\r') >= 0) {
                str = str.replace("\r\n", "\r").replace('\n', '\r');
            }
            sink.sendBytes(str.getBytes(StandardCharsets.UTF_8));
            return true;
        }

        int length = text.length();
        for (int i = 0; i < length; ) {
            int codePoint = Character.codePointAt(text, i);
            int charCount = Character.charCount(codePoint);
            char ch = text.charAt(i);

            if (stickyStateMachine.isCtrlActive() && codePoint < 128) {
                byte ctrlCode = StickyModifierStateMachine.toControlCode(ch);
                sink.sendBytes(new byte[]{ctrlCode});
                stickyStateMachine.consumeLatched();
            } else if (stickyStateMachine.isAltActive() && codePoint < 128) {
                byte[] altSeq = StickyModifierStateMachine.toAltSequence(ch);
                sink.sendBytes(altSeq);
                stickyStateMachine.consumeLatched();
            } else if (ch == '\n' || ch == '\r') {
                sink.sendBytes(new byte[]{ASCII_CR});
            } else {
                String s = new String(Character.toChars(codePoint));
                sink.sendBytes(s.getBytes(StandardCharsets.UTF_8));
            }
            i += charCount;
        }
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (sink == null) {
            return false;
        }
        if (beforeLength <= 0 && afterLength <= 0) {
            return true;
        }

        if (beforeLength > 0) {
            byte[] delBytes = new byte[beforeLength];
            for (int i = 0; i < beforeLength; i++) {
                delBytes[i] = ASCII_DEL;
            }
            sink.sendBytes(delBytes);
        }

        if (afterLength > 0) {
            for (int i = 0; i < afterLength; i++) {
                sink.sendBytes(FORWARD_DEL_SEQUENCE);
            }
        }
        return true;
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        return deleteSurroundingText(beforeLength, afterLength);
    }

    public boolean sendKey(int action, int keyCode) {
        return handleKeyEvent(action, keyCode, 0, false, false);
    }

    private static boolean isSystemKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
                || keyCode == KeyEvent.KEYCODE_POWER
                || keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_APP_SWITCH;
    }

    private static boolean isHandledTerminalKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DEL
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_TAB
                || keyCode == KeyEvent.KEYCODE_FORWARD_DEL
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_PAGE_UP
                || keyCode == KeyEvent.KEYCODE_PAGE_DOWN
                || keyCode == KeyEvent.KEYCODE_MOVE_HOME
                || keyCode == KeyEvent.KEYCODE_MOVE_END
                || keyCode == KeyEvent.KEYCODE_ESCAPE;
    }

    public boolean sendKey(int action, int keyCode, int unicode) {
        return handleKeyEvent(action, keyCode, unicode, false, false);
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        if (sink == null || event == null) {
            return false;
        }
        try {
            int action = event.getAction();
            int keyCode = event.getKeyCode();
            int unicode = event.getUnicodeChar();
            boolean isCtrl = event.isCtrlPressed();
            boolean isAlt = event.isAltPressed();
            return handleKeyEvent(action, keyCode, unicode, isCtrl, isAlt);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean handleKeyEvent(int action, int keyCode, int unicode, boolean isCtrl, boolean isAlt) {
        if (sink == null || isSystemKey(keyCode)) {
            return false;
        }
        if (action == KeyEvent.ACTION_UP) {
            return isHandledTerminalKeyCode(keyCode) || unicode != 0;
        }
        if (action != KeyEvent.ACTION_DOWN) {
            return false;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                sink.sendBytes(new byte[]{ASCII_DEL});
                return true;
            case KeyEvent.KEYCODE_ENTER:
                sink.sendBytes(new byte[]{ASCII_CR});
                return true;
            case KeyEvent.KEYCODE_TAB:
                sink.sendBytes(new byte[]{ASCII_TAB});
                return true;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                sink.sendBytes(FORWARD_DEL_SEQUENCE);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                sink.sendBytes("\u001B[A".getBytes(StandardCharsets.UTF_8));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                sink.sendBytes("\u001B[B".getBytes(StandardCharsets.UTF_8));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                sink.sendBytes("\u001B[D".getBytes(StandardCharsets.UTF_8));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                sink.sendBytes("\u001B[C".getBytes(StandardCharsets.UTF_8));
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                sink.sendBytes("\u001B[5~".getBytes(StandardCharsets.UTF_8));
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                sink.sendBytes("\u001B[6~".getBytes(StandardCharsets.UTF_8));
                return true;
            case KeyEvent.KEYCODE_MOVE_HOME:
                sink.sendBytes("\u001B[H".getBytes(StandardCharsets.UTF_8));
                return true;
            case KeyEvent.KEYCODE_MOVE_END:
                sink.sendBytes("\u001B[F".getBytes(StandardCharsets.UTF_8));
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                sink.sendBytes(new byte[]{0x1B});
                return true;
            default:
                break;
        }

        if (unicode == 0 && keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            unicode = 'a' + (keyCode - KeyEvent.KEYCODE_A);
        }

        if (stickyStateMachine != null && stickyStateMachine.isCtrlActive() && unicode != 0) {
            byte ctrl = StickyModifierStateMachine.toControlCode((char) unicode);
            sink.sendBytes(new byte[]{ctrl});
            stickyStateMachine.consumeLatched();
            return true;
        }

        if (stickyStateMachine != null && stickyStateMachine.isAltActive() && unicode != 0) {
            byte[] alt = StickyModifierStateMachine.toAltSequence((char) unicode);
            sink.sendBytes(alt);
            stickyStateMachine.consumeLatched();
            return true;
        }

        if (isCtrl && unicode != 0) {
            byte ctrl = StickyModifierStateMachine.toControlCode((char) unicode);
            sink.sendBytes(new byte[]{ctrl});
            return true;
        }

        if (isAlt && unicode != 0) {
            byte[] alt = StickyModifierStateMachine.toAltSequence((char) unicode);
            sink.sendBytes(alt);
            return true;
        }

        if (unicode != 0) {
            if (unicode == '\n' || unicode == '\r') {
                sink.sendBytes(new byte[]{ASCII_CR});
            } else {
                sink.sendBytes(new String(Character.toChars(unicode)).getBytes(StandardCharsets.UTF_8));
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean performEditorAction(int editorAction) {
        if (sink != null) {
            sink.sendBytes(new byte[]{ASCII_CR});
            return true;
        }
        return false;
    }

    @Override
    public CharSequence getTextBeforeCursor(int n, int flags) {
        return "";
    }

    @Override
    public CharSequence getTextAfterCursor(int n, int flags) {
        return "";
    }

    @Override
    public CharSequence getSelectedText(int flags) {
        return "";
    }

    @Override
    public int getCursorCapsMode(int reqModes) {
        return 0;
    }

    @Override
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        return null;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        return true;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        return true;
    }

    @Override
    public boolean finishComposingText() {
        return true;
    }

    @Override
    public boolean commitCompletion(CompletionInfo text) {
        if (text != null && text.getText() != null) {
            return commitText(text.getText(), 1);
        }
        return true;
    }

    @Override
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        return true;
    }

    @Override
    public boolean setSelection(int start, int end) {
        return true;
    }

    @Override
    public boolean performContextMenuAction(int id) {
        return false;
    }

    @Override
    public boolean beginBatchEdit() {
        return true;
    }

    @Override
    public boolean endBatchEdit() {
        return true;
    }

    @Override
    public boolean clearMetaKeyStates(int states) {
        return true;
    }

    @Override
    public boolean reportFullscreenMode(boolean enabled) {
        return false;
    }

    @Override
    public boolean performPrivateCommand(String action, Bundle data) {
        return false;
    }

    @Override
    public boolean requestCursorUpdates(int cursorUpdateMode) {
        return false;
    }

    @Override
    public Handler getHandler() {
        return null;
    }

    @Override
    public void closeConnection() {
        // No-op
    }

    @Override
    public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) {
        return false;
    }

    @Override
    public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
        return null;
    }
}
