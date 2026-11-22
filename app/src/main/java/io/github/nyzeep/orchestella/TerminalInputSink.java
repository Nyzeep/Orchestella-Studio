package io.github.nyzeep.orchestella;

import java.nio.charset.StandardCharsets;

/**
 * Strategy-neutral transport sink interface for terminal keyboard and auxiliary key events.
 */
public interface TerminalInputSink {

    /**
     * Sends raw byte sequence directly to the guest terminal channel.
     */
    void sendBytes(byte[] bytes);

    /** Returns false when a bounded input transport cannot accept the payload. */
    default boolean trySendBytes(byte[] bytes) {
        sendBytes(bytes);
        return true;
    }

    /**
     * Helper method to send UTF-8 encoded text string.
     */
    default void sendText(String text) {
        if (text != null && !text.isEmpty()) {
            sendBytes(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
