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

    public ImeBridgeInputConnection(WaylandChannel channel) {
        this.channel = channel;
    }

    public void setChannel(WaylandChannel channel) {
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
        if (text == null || text.length() == 0) {
            return true;
        }
        if (channel == null || !channel.isConnected()) {
            return false;
        }

        String content = text.toString();
        int length = content.length();
        boolean allSent = true;

        if (length <= MAX_SAFE_CHUNK_CHARS) {
            TextCommitEvent event = new TextCommitEvent(content, System.nanoTime());
            return channel.sendInputEvent(event);
        } else {
            for (int i = 0; i < length; i += MAX_SAFE_CHUNK_CHARS) {
                int end = Math.min(length, i + MAX_SAFE_CHUNK_CHARS);
                String chunk = content.substring(i, end);
                TextCommitEvent event = new TextCommitEvent(chunk, System.nanoTime());
                if (!channel.sendInputEvent(event)) {
                    allSent = false;
                }
            }
            return allSent;
        }
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (channel == null || !channel.isConnected()) {
            return false;
        }

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
        if (channel == null || !channel.isConnected() || event == null) {
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
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        return deleteSurroundingText(beforeLength, afterLength);
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
        return true;
    }

    @Override
    public boolean performEditorAction(int editorAction) {
        return false;
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
    public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
        return null;
    }
}
