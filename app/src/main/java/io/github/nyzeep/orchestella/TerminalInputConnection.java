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
    private final io.github.nyzeep.orchestella.terminal.ImeCompositionBuffer composition =
            new io.github.nyzeep.orchestella.terminal.ImeCompositionBuffer();
    private java.util.function.BooleanSupplier applicationCursorKeys = () -> false;
    private java.util.function.BooleanSupplier bracketedPaste = () -> false;
    private java.util.function.IntPredicate contextMenuHandler = id -> false;
    private boolean closed;

    public void setTerminalModes(java.util.function.BooleanSupplier applicationCursorKeys,
            java.util.function.BooleanSupplier bracketedPaste) {
        this.applicationCursorKeys = applicationCursorKeys;
        this.bracketedPaste = bracketedPaste;
    }

    public void setContextMenuHandler(java.util.function.IntPredicate handler) {
        contextMenuHandler = handler;
    }

    /** Paste bypasses sticky shortcuts and is framed only when the guest enabled DECSET 2004. */
    public boolean pasteText(CharSequence text) {
        if (closed || sink == null) return false;
        if (text == null || text.length() == 0) return true;
        finishComposingText();
        String value = text.toString().replace("\r\n", "\r").replace('\n', '\r');
        // ESC must not be able to terminate a bracketed paste early.
        value = value.replace("\u001b", "");
        if (bracketedPaste.getAsBoolean()) value = "\u001b[200~" + value + "\u001b[201~";
        sink.sendBytes(value.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    public TerminalInputConnection(TerminalInputSink sink, StickyModifierStateMachine stickyStateMachine) {
        this.sink = sink;
        this.stickyStateMachine = stickyStateMachine;
    }

    public boolean isClosed() { return closed; }

    public TerminalInputSink getSink() {
        return sink;
    }

    public StickyModifierStateMachine getStickyStateMachine() {
        return stickyStateMachine;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (closed) return false;
        if (text == null || text.length() == 0) {
            composition.clear();
            return true;
        }
        if (sink == null) return false;
        composition.clear();
        String value = text.toString().replace("\r\n", "\r").replace('\n', '\r');
        if (stickyStateMachine == null
                || (!stickyStateMachine.isCtrlActive() && !stickyStateMachine.isAltActive())) {
            sink.sendBytes(value.getBytes(StandardCharsets.UTF_8));
            return true;
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            writeCodePoint(codePoint, false, false);
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private void writeCodePoint(int codePoint, boolean ctrl, boolean alt) {
        ctrl |= stickyStateMachine != null && stickyStateMachine.isCtrlActive();
        alt |= stickyStateMachine != null && stickyStateMachine.isAltActive();
        if (codePoint == '\n') codePoint = '\r';
        if (ctrl) codePoint = StickyModifierStateMachine.toControlCodePoint(codePoint);
        String value = (alt ? "\u001b" : "") + new String(Character.toChars(codePoint));
        sink.sendBytes(value.getBytes(StandardCharsets.UTF_8));
        consumeModifiers();
    }

    private void consumeModifiers() {
        if (stickyStateMachine != null) stickyStateMachine.consumeLatched();
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        return deleteText(beforeLength, afterLength, false);
    }

    private boolean deleteText(int beforeLength, int afterLength, boolean codePoints) {
        if (closed || sink == null || beforeLength < 0 || afterLength < 0) return false;
        int[] remaining = composition.delete(beforeLength, afterLength, codePoints);
        for (int i = 0; i < remaining[0]; i++) sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL);
        for (int i = 0; i < remaining[1]; i++) sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL);
        return true;
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        return deleteText(beforeLength, afterLength, true);
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
            // Android's modified unicode can be zero for Ctrl keys, and Alt may compose
            // a layout character. Encode the terminal modifiers ourselves from the base key.
            int meta = event.getMetaState() & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK);
            int unicode = event.getUnicodeChar(meta);
            return handleKeyEvent(action, keyCode, unicode, event.isCtrlPressed(),
                    event.isAltPressed(), event.isShiftPressed());
        } catch (Exception e) {
            return false;
        }
    }

    public boolean handleKeyEvent(int action, int keyCode, int unicode, boolean isCtrl, boolean isAlt) {
        return handleKeyEvent(action, keyCode, unicode, isCtrl, isAlt, false);
    }

    public boolean handleKeyEvent(int action, int keyCode, int unicode,
            boolean ctrl, boolean alt, boolean shift) {
        if (closed || sink == null || isSystemKey(keyCode)) return false;
        if (action == KeyEvent.ACTION_UP) return isHandledTerminalKeyCode(keyCode) || unicode != 0;
        if (action != KeyEvent.ACTION_DOWN) return false;
        ctrl |= stickyStateMachine != null && stickyStateMachine.isCtrlActive();
        alt |= stickyStateMachine != null && stickyStateMachine.isAltActive();
        int modifier = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);
        String sequence = null;
        String finalKey = null;
        int tildeKey = 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: sequence = (alt ? "\u001b" : "") + (ctrl ? "\b" : "\u007f"); break;
            case KeyEvent.KEYCODE_ENTER: sequence = (alt ? "\u001b" : "") + "\r"; break;
            case KeyEvent.KEYCODE_TAB: sequence = shift ? "\u001b[Z" : (alt ? "\u001b\t" : "\t"); break;
            case KeyEvent.KEYCODE_ESCAPE: sequence = alt ? "\u001b\u001b" : "\u001b"; break;
            case KeyEvent.KEYCODE_DPAD_UP: finalKey = "A"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: finalKey = "B"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: finalKey = "C"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: finalKey = "D"; break;
            case KeyEvent.KEYCODE_MOVE_HOME: finalKey = "H"; break;
            case KeyEvent.KEYCODE_MOVE_END: finalKey = "F"; break;
            case KeyEvent.KEYCODE_INSERT: tildeKey = 2; break;
            case KeyEvent.KEYCODE_FORWARD_DEL: tildeKey = 3; break;
            case KeyEvent.KEYCODE_PAGE_UP: tildeKey = 5; break;
            case KeyEvent.KEYCODE_PAGE_DOWN: tildeKey = 6; break;
            default: break;
        }
        if (finalKey != null) {
            sequence = modifier != 1 ? "\u001b[1;" + modifier + finalKey
                    : (applicationCursorKeys.getAsBoolean() ? "\u001bO" : "\u001b[") + finalKey;
        } else if (tildeKey != 0) {
            sequence = "\u001b[" + tildeKey + (modifier == 1 ? "" : ";" + modifier) + "~";
        } else if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            int index = keyCode - KeyEvent.KEYCODE_F1;
            if (index < 4) sequence = (modifier == 1 ? "\u001bO" : "\u001b[1;" + modifier) + (char) ('P' + index);
            else {
                int number = new int[]{15, 17, 18, 19, 20, 21, 23, 24}[index - 4];
                sequence = "\u001b[" + number + (modifier == 1 ? "" : ";" + modifier) + "~";
            }
        }
        if (sequence != null) {
            finishComposingText();
            sink.sendBytes(sequence.getBytes(StandardCharsets.UTF_8));
            consumeModifiers();
            return true;
        }
        if (unicode == 0 && keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z)
            unicode = 'a' + keyCode - KeyEvent.KEYCODE_A;
        if (unicode == 0 || !Character.isValidCodePoint(unicode)) return false;
        finishComposingText();
        writeCodePoint(unicode, ctrl, alt);
        return true;
    }

    @Override
    public boolean performEditorAction(int editorAction) {
        return sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER);
    }

    @Override
    public CharSequence getTextBeforeCursor(int n, int flags) {
        return composition.before(n);
    }

    @Override
    public CharSequence getTextAfterCursor(int n, int flags) {
        return composition.after(n);
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
        if (closed) return false;
        composition.replace(text, newCursorPosition);
        return true;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        return true;
    }

    @Override
    public boolean finishComposingText() {
        if (closed) return false;
        String value = composition.value();
        if (value.isEmpty()) return true;
        return commitText(value, 1);
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
        return composition.select(start, end);
    }

    @Override
    public boolean performContextMenuAction(int id) {
        return !closed && contextMenuHandler.test(id);
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
        composition.clear();
        closed = true;
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
