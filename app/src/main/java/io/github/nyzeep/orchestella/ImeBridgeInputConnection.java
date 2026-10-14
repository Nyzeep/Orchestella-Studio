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
import io.github.nyzeep.orchestella.runtime.contract.TextCommitEvent;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import java.util.Objects;

/**
 * Custom Android {@link InputConnection} bridge intercepting IME text input and injecting
 * Unicode character streams directly into the Wayland graphics and control channel.
 *
 * <p>Conforms to ADR-0017 and Ticket 40:
 * <ul>
 *   <li>Intercepts {@link #commitText(CharSequence, int)} for arbitrary Unicode (CJK, Emoji, symbols);</li>
 *   <li>Safely chunks text to conform to {@link io.github.nyzeep.orchestella.runtime.contract.InputEvent#MAX_WIRE_LENGTH};</li>
 *   <li>Translates {@link #deleteSurroundingText(int, int)} to native Linux Backspace / Delete key events;</li>
 *   <li>Provides static focus pan offset calculation to prevent soft keyboard occlusion;</li>
 *   <li>100% pure JVM testable without relying on Android framework stubs.</li>
 * </ul>
 */
public class ImeBridgeInputConnection implements InputConnection {

    public static final int LINUX_KEY_BACKSPACE = 14;
    public static final int LINUX_KEY_ENTER = 28;
    public static final int LINUX_KEY_DELETE = 111;
    public static final int MAX_SAFE_CHUNK_CHARS = 400;

    private WaylandChannel channel;
    private final io.github.nyzeep.orchestella.terminal.ImeCompositionBuffer composition =
            new io.github.nyzeep.orchestella.terminal.ImeCompositionBuffer();
    private boolean closed;

    public ImeBridgeInputConnection(WaylandChannel channel) {
        this.channel = channel;
    }

    public void setChannel(WaylandChannel channel) {
        if (this.channel != channel) composition.clear();
        this.channel = channel;
    }

    public WaylandChannel getChannel() {
        return channel;
    }

    /**
     * Calculates the viewport vertical pan offset (translationY) required to keep the input focus
     * visible above the soft keyboard.
     *
     * @param viewportFocusY vertical coordinate of the input focus in viewport coordinates
     * @param keyboardHeight height of the soft keyboard in pixels
     * @param viewportHeight total height of the viewport in pixels
     * @param bottomMargin safety margin between focus and the top of the soft keyboard
     * @return negative offset (translationY in pixels) to shift the view upwards, or 0 if no occlusion
     */
    public static float calculateFocusPanOffset(
            float viewportFocusY, int keyboardHeight, int viewportHeight, float bottomMargin) {
        if (keyboardHeight <= 0) {
            return 0.0f;
        }
        float keyboardTop = (float) viewportHeight - keyboardHeight;
        float safeCeiling = keyboardTop - bottomMargin;
        if (viewportFocusY > safeCeiling) {
            return safeCeiling - viewportFocusY;
        }
        return 0.0f;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (closed) return false;
        if (text == null || text.length() == 0) { composition.clear(); return true; }
        if (channel == null || !channel.isConnected()) return false;
        composition.clear();
        String content = text.toString();
        for (int offset = 0; offset < content.length();) {
            int end = Math.min(content.length(), offset + MAX_SAFE_CHUNK_CHARS);
            if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))
                    && Character.isLowSurrogate(content.charAt(end))) end--;
            if (!channel.sendInputEvent(new TextCommitEvent(content.substring(offset, end), System.nanoTime())))
                return false;
            offset = end;
        }
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        return deleteText(beforeLength, afterLength, false);
    }

    private boolean deleteText(int beforeLength, int afterLength, boolean codePoints) {
        if (closed || channel == null || !channel.isConnected() || beforeLength < 0 || afterLength < 0) return false;
        int[] remaining = composition.delete(beforeLength, afterLength, codePoints);
        beforeLength = remaining[0]; afterLength = remaining[1];
        long now = System.nanoTime();
        boolean success = true;

        for (int i = 0; i < beforeLength; i++) {
            boolean p = channel.sendInputEvent(
                    io.github.nyzeep.orchestella.runtime.contract.KeyEvent.press(
                            LINUX_KEY_BACKSPACE, 0, now + (i * 2L)));
            boolean r = channel.sendInputEvent(
                    io.github.nyzeep.orchestella.runtime.contract.KeyEvent.release(
                            LINUX_KEY_BACKSPACE, 0, now + (i * 2L) + 1L));
            if (!p || !r) {
                success = false;
            }
        }

        for (int i = 0; i < afterLength; i++) {
            boolean p = channel.sendInputEvent(
                    io.github.nyzeep.orchestella.runtime.contract.KeyEvent.press(
                            LINUX_KEY_DELETE, 0, now + (i * 2L)));
            boolean r = channel.sendInputEvent(
                    io.github.nyzeep.orchestella.runtime.contract.KeyEvent.release(
                            LINUX_KEY_DELETE, 0, now + (i * 2L) + 1L));
            if (!p || !r) {
                success = false;
            }
        }

        return success;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        if (closed || channel == null || !channel.isConnected() || event == null) {
            return false;
        }
        try {
            int keyCode = event.getKeyCode();
            int action = event.getAction();
            long now = System.nanoTime();

            int linuxKey;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DEL:
                    linuxKey = LINUX_KEY_BACKSPACE;
                    break;
                case KeyEvent.KEYCODE_ENTER:
                    linuxKey = LINUX_KEY_ENTER;
                    break;
                case KeyEvent.KEYCODE_FORWARD_DEL:
                    linuxKey = LINUX_KEY_DELETE;
                    break;
                default:
                    return false;
            }

            if (action == KeyEvent.ACTION_DOWN) {
                return channel.sendInputEvent(
                        io.github.nyzeep.orchestella.runtime.contract.KeyEvent.press(linuxKey, 0, now));
            } else if (action == KeyEvent.ACTION_UP) {
                return channel.sendInputEvent(
                        io.github.nyzeep.orchestella.runtime.contract.KeyEvent.release(linuxKey, 0, now));
            }
        } catch (Exception ignored) {
        }
        return false;
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
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        return deleteText(beforeLength, afterLength, true);
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
        String text = composition.value();
        return text.isEmpty() || commitText(text, 1);
    }

    @Override
    public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) {
        return false;
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
    public boolean performEditorAction(int editorAction) {
        if (closed || channel == null || !channel.isConnected() || !finishComposingText()) return false;
        long now = System.nanoTime();
        return channel.sendInputEvent(io.github.nyzeep.orchestella.runtime.contract.KeyEvent.press(LINUX_KEY_ENTER, 0, now))
                && channel.sendInputEvent(io.github.nyzeep.orchestella.runtime.contract.KeyEvent.release(LINUX_KEY_ENTER, 0, now + 1));
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
        closed = true;
        composition.clear();
    }

    @Override
    public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
        return null;
    }
}
