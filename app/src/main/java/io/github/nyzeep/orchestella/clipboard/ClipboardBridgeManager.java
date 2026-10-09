package io.github.nyzeep.orchestella.clipboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Android host bi-directional clipboard synchronization bridge with echo suppression state machine.
 *
 * <p>Conforms to ADR-0018 and Ticket 46:
 * <ul>
 *   <li><b>Foreground Focus-Driven</b>: Listens to system clipboard only when the window has focus;
 *       automatically unregisters upon losing focus or app backgrounding (mitigating Android 14/15/16 restrictions);</li>
 *   <li><b>Echo Suppression State Machine</b>: Tracks SHA-256 digests of synchronized text payloads and marks
 *       {@code isApplyingRemote = true} during remote injection to 100% eliminate ping-pong loops;</li>
 *   <li><b>Payload Guard</b>: Enforces strict 256KB (262,144 bytes) UTF-8 ceiling, rejecting oversized payloads;</li>
 *   <li><b>Decoupled Seams</b>: Pure JVM testable via {@link SystemClipboardSeam} and {@link RemoteClipboardSender}.</li>
 * </ul>
 */
public class ClipboardBridgeManager implements BridgeControl.Clipboard {

    public static final int MAX_PAYLOAD_BYTES = BridgeControl.MAX_CLIPBOARD_PAYLOAD_BYTES;

    /** Seam interface abstracting Android system clipboard operations. */
    public interface SystemClipboardSeam {
        void setText(String text);
        String getText();
        void setListener(Runnable listener);
        void clearListener();
    }

    /** Seam interface for dispatching clipboard content to the guest Bridge. */
    @FunctionalInterface
    public interface RemoteClipboardSender {
        boolean sendClipboardPut(String text);
    }

    private SystemClipboardSeam systemClipboard;
    private final RemoteClipboardSender remoteSender;

    private boolean isWindowFocused = false;
    private boolean isApplyingRemote = false;
    private boolean isListenerRegistered = false;

    private String lastHostHash = null;
    private String lastRemoteHash = null;
    private String lastSyncedHash = null;

    private int suppressionCount = 0;
    private int syncCount = 0;
    private int droppedPayloadCount = 0;

    /**
     * Constructs a manager with explicitly injected seams (pure JVM test friendly).
     */
    public ClipboardBridgeManager(SystemClipboardSeam systemClipboard, RemoteClipboardSender remoteSender) {
        this.systemClipboard = systemClipboard;
        this.remoteSender = Objects.requireNonNull(remoteSender, "remoteSender");
    }

    /**
     * Constructs a manager with an Android Context and strategy-neutral BridgeClient.
     */
    public ClipboardBridgeManager(Context context, BridgeClient bridgeClient) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(bridgeClient, "bridgeClient");
        this.systemClipboard = new AndroidSystemClipboard(context);
        this.remoteSender = text -> {
            try {
                BridgeClient.Result res = bridgeClient.clipboardPut(text);
                return res != null && res.accepted();
            } catch (IOException e) {
                return false;
            }
        };
    }

    /**
     * Attaches an Android context to this clipboard bridge manager.
     */
    public void attach(Context context) {
        if (context == null) {
            return;
        }
        detach();
        this.systemClipboard = new AndroidSystemClipboard(context);
        if (isWindowFocused) {
            registerListenerInternal();
        }
    }

    /**
     * Detaches this manager and releases clipboard listeners.
     */
    public void detach() {
        unregisterListenerInternal();
    }

    /**
     * Handles window focus changes from the host Activity.
     *
     * @param hasFocus true when foreground window acquires focus, false when lost or backgrounded
     */
    public void onWindowFocusChanged(boolean hasFocus) {
        this.isWindowFocused = hasFocus;
        if (hasFocus) {
            registerListenerInternal();
            // Probe local clipboard immediately on gaining focus
            checkAndSyncLocalClip();
        } else {
            unregisterListenerInternal();
        }
    }

    private void registerListenerInternal() {
        if (systemClipboard != null && !isListenerRegistered) {
            systemClipboard.setListener(this::onSystemClipChanged);
            isListenerRegistered = true;
        }
    }

    private void unregisterListenerInternal() {
        if (systemClipboard != null && isListenerRegistered) {
            systemClipboard.clearListener();
            isListenerRegistered = false;
        }
    }

    /**
     * Invoked when the local Android primary clip changes.
     */
    public void onSystemClipChanged() {
        if (isApplyingRemote) {
            // Echo suppression: local listener triggered by our own remote write
            isApplyingRemote = false;
            suppressionCount++;
            return;
        }
        checkAndSyncLocalClip();
    }

    private void checkAndSyncLocalClip() {
        if (systemClipboard == null) {
            return;
        }
        String localText = systemClipboard.getText();
        if (localText == null || localText.isEmpty()) {
            return;
        }
        byte[] utf8Bytes = localText.getBytes(StandardCharsets.UTF_8);
        if (utf8Bytes.length > MAX_PAYLOAD_BYTES) {
            droppedPayloadCount++;
            return;
        }
        String hash = computeSha256(localText);
        if (hash.equals(lastSyncedHash)) {
            // Echo suppression: duplicate local copy or already synced
            suppressionCount++;
            return;
        }
        lastHostHash = hash;
        lastSyncedHash = hash;
        syncCount++;
        remoteSender.sendClipboardPut(localText);
    }

    /**
     * BridgeControl.Clipboard implementation: handles incoming remote clipboard text from Guest.
     *
     * @param text remote clipboard text
     * @return true if accepted and applied, false otherwise
     */
    @Override
    public boolean onClipboardPut(String text) {
        if (text == null) {
            return false;
        }
        byte[] utf8Bytes = text.getBytes(StandardCharsets.UTF_8);
        if (utf8Bytes.length > MAX_PAYLOAD_BYTES) {
            droppedPayloadCount++;
            return false;
        }
        String hash = computeSha256(text);
        if (hash.equals(lastSyncedHash)) {
            // Already in sync with remote
            return true;
        }

        // Set applying remote flag before writing to system clipboard
        isApplyingRemote = true;
        lastRemoteHash = hash;
        lastSyncedHash = hash;
        syncCount++;

        if (systemClipboard != null) {
            systemClipboard.setText(text);
        }
        return true;
    }

    /**
     * BridgeControl.Clipboard implementation: retrieves current local clipboard text.
     */
    @Override
    public String onClipboardGet() {
        return systemClipboard != null ? systemClipboard.getText() : null;
    }

    public static String computeSha256(String text) {
        if (text == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }

    public boolean isWindowFocused() {
        return isWindowFocused;
    }

    public boolean isApplyingRemote() {
        return isApplyingRemote;
    }

    public boolean isListenerRegistered() {
        return isListenerRegistered;
    }

    public String getLastHostHash() {
        return lastHostHash;
    }

    public String getLastRemoteHash() {
        return lastRemoteHash;
    }

    public String getLastSyncedHash() {
        return lastSyncedHash;
    }

    public int getSuppressionCount() {
        return suppressionCount;
    }

    public int getSyncCount() {
        return syncCount;
    }

    public int getDroppedPayloadCount() {
        return droppedPayloadCount;
    }

    /**
     * Android framework clipboard seam implementation.
     */
    public static final class AndroidSystemClipboard
            implements SystemClipboardSeam, ClipboardManager.OnPrimaryClipChangedListener {

        private final ClipboardManager clipboardManager;
        private Runnable clipChangedCallback;

        public AndroidSystemClipboard(Context context) {
            this.clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        }

        public AndroidSystemClipboard(ClipboardManager clipboardManager) {
            this.clipboardManager = clipboardManager;
        }

        @Override
        public void setText(String text) {
            if (clipboardManager != null) {
                ClipData clip = ClipData.newPlainText("Orchestella", text);
                clipboardManager.setPrimaryClip(clip);
            }
        }

        @Override
        public String getText() {
            if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
                return null;
            }
            ClipData clip = clipboardManager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return null;
            }
            CharSequence seq = clip.getItemAt(0).getText();
            return seq != null ? seq.toString() : null;
        }

        @Override
        public void setListener(Runnable listener) {
            this.clipChangedCallback = listener;
            if (clipboardManager != null) {
                clipboardManager.addPrimaryClipChangedListener(this);
            }
        }

        @Override
        public void clearListener() {
            if (clipboardManager != null) {
                clipboardManager.removePrimaryClipChangedListener(this);
            }
            this.clipChangedCallback = null;
        }

        @Override
        public void onPrimaryClipChanged() {
            if (clipChangedCallback != null) {
                clipChangedCallback.run();
            }
        }
    }
}
