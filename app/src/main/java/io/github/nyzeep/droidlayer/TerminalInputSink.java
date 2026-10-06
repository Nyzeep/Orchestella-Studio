package io.github.nyzeep.droidlayer;

import java.nio.charset.StandardCharsets;

/**
 * Strategy-neutral transport sink interface for terminal keyboard and auxiliary key events.
 */
public interface TerminalInputSink {

    /**
     * Sends raw byte sequence directly to the guest terminal channel.
     */
    void sendBytes(byte[] bytes);

    /**
     * Helper method to send UTF-8 encoded text string.
     */
    default void sendText(String text) {
        if (text != null && !text.isEmpty()) {
            sendBytes(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
