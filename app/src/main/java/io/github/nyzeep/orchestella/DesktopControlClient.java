package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import java.io.IOException;

/**
 * JVM-testable command seam over the host-initiated BridgeClient surface the desktop
 * session needs (G-03 split). Production adapts the concrete final {@link BridgeClient};
 * coordinator and peripheral controller tests substitute a controllable fake.
 *
 * <p>Identity checks in the display guard compare {@link #raw()} against the live
 * session's client, preserving the pre-split {@code target.bridgeClient() == client}
 * semantics byte-for-byte.
 */
public interface DesktopControlClient {

    boolean isReady();

    void awaitReady(long timeoutMs) throws IOException, InterruptedException;

    BridgeClient.Result desktopLaunch() throws IOException;

    BridgeClient.Result feature(String action, String profile) throws IOException;

    BridgeClient.Result displayMode(int width, int height, double scale) throws IOException;

    BridgeClient.Result confirmDesktopFrame(String generation) throws IOException;

    BridgeClient.Result clipboardPut(String text) throws IOException;

    /** Underlying client for guard identity comparison; null when adapted from null. */
    BridgeClient raw();

    /** Adapts a live BridgeClient; null maps to null so call sites keep their null checks. */
    static DesktopControlClient of(BridgeClient client) {
        if (client == null) return null;
        return new DesktopControlClient() {
            @Override public boolean isReady() { return client.isReady(); }

            @Override public void awaitReady(long timeoutMs) throws IOException, InterruptedException {
                client.awaitReady(timeoutMs);
            }

            @Override public BridgeClient.Result desktopLaunch() throws IOException {
                return client.desktopLaunch();
            }

            @Override public BridgeClient.Result feature(String action, String profile) throws IOException {
                return client.feature(action, profile);
            }

            @Override public BridgeClient.Result displayMode(int width, int height, double scale) throws IOException {
                return client.displayMode(width, height, scale);
            }

            @Override public BridgeClient.Result confirmDesktopFrame(String generation) throws IOException {
                return client.confirmDesktopFrame(generation);
            }

            @Override public BridgeClient.Result clipboardPut(String text) throws IOException {
                return client.clipboardPut(text);
            }

            @Override public BridgeClient raw() { return client; }
        };
    }
}
