package io.github.nyzeep.droidlayer;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import io.github.nyzeep.droidlayer.runtime.contract.BridgeClient;
import io.github.nyzeep.droidlayer.runtime.contract.BridgeControl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Connects the app to the guest Bridge over the abstract-socket control
 * channel served by the packaged QEMU (bounded retries while the guest agent
 * starts), then drives the v1 protocol as the host initiator through
 * {@link BridgeClient} with this instance's token (smoke-harness wire: the
 * host sends HELLO/PING/RESIZE and the guest agent answers READY/PONG/OK),
 * and continuously dispatches guest-initiated commands such as notify and open-url.
 */
public final class BridgeConnector {
    /** Bridge session milestones for the host UI. */
    public interface Events {
        void onReady();

        void onPong();

        void onResize(int columns, int rows);

        default void onNotification(String title, String message) {}

        default void onOpenUrl(String url) {}
    }

    /** Result of one bridge session. */
    public static final class Session {
        public final int outcome;
        public final LocalSocket socket;

        Session(int outcome, LocalSocket socket) {
            this.outcome = outcome;
            this.socket = socket;
        }
    }

    private static final int CONNECT_ATTEMPTS = 100;
    private static final long CONNECT_BACKOFF_MS = 200;

    /**
     * Drives one host-initiated bridge session: HELLO/READY, PING/PONG and
     * RESIZE, then holds the control channel open until the guest closes it.
     * Retries the socket connect while the guest boots.
     */
    public Session serve(String socketName, String bridgeToken, int columns, int rows,
            Events events) throws IOException, InterruptedException {
        return serve(socketName, bridgeToken, columns, rows, events, null, null);
    }

    /**
     * Drives one host-initiated bridge session with notification and url opener adapters.
     */
    public Session serve(String socketName, String bridgeToken, int columns, int rows,
            Events events, BridgeControl.Notification notification,
            BridgeControl.UrlOpener urlOpener) throws IOException, InterruptedException {
        LocalSocket socket = connectWithRetry(socketName);
        try (InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream()) {
            BridgeClient client = new BridgeClient(in, out);
            if (!client.handshake(bridgeToken).accepted()) {
                return new Session(3, socket);
            }
            events.onReady();
            if (client.ping().accepted()) {
                events.onPong();
            }
            if (columns > 0 && rows > 0 && client.resize(columns, rows).accepted()) {
                events.onResize(columns, rows);
            }
            serveControlLoop(in, out, events, notification, urlOpener);
            return new Session(0, socket);
        }
    }

    /**
     * Serves the bidirectional control loop for guest-initiated commands
     * (such as NOTIFY and OPEN-URL) until the guest closes the channel.
     */
    static void serveControlLoop(InputStream in, OutputStream out, Events events,
            BridgeControl.Notification notification, BridgeControl.UrlOpener urlOpener)
            throws IOException {
        BridgeControl.Seams seams = BridgeControl.Seams.of(
                (title, message) -> {
                    boolean handled = notification != null && notification.notify(title, message);
                    if (handled && events != null) {
                        events.onNotification(title, message);
                    }
                    return handled;
                },
                url -> {
                    boolean handled = urlOpener != null && urlOpener.openUrl(url);
                    if (handled && events != null) {
                        events.onOpenUrl(url);
                    }
                    return handled;
                });

        while (true) {
            String requestLine = readLine(in);
            if (requestLine == null) {
                break;
            }
            if (requestLine.isEmpty()) {
                continue;
            }
            BridgeControl.Result result = BridgeControl.dispatch(requestLine, seams);
            out.write(result.wireResponse().getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            buffer.write(b);
            if (b == '\n') {
                return new String(buffer.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (buffer.size() > 2048) {
                while ((b = in.read()) != -1 && b != '\n') {
                    // discard oversized line
                }
                return "";
            }
        }
        if (buffer.size() == 0) {
            return null;
        }
        return new String(buffer.toByteArray(), StandardCharsets.US_ASCII);
    }

    /** Opens a raw stream to the terminal channel (guest console ttyAMA0). */
    public LocalSocket connectTerminal(String socketName) throws IOException,
            InterruptedException {
        return connectWithRetry(socketName);
    }

    private LocalSocket connectWithRetry(String socketName) throws IOException,
            InterruptedException {
        LocalSocketAddress address = new LocalSocketAddress(socketName,
                LocalSocketAddress.Namespace.ABSTRACT);
        IOException last = null;
        for (int attempt = 0; attempt < CONNECT_ATTEMPTS; attempt++) {
            try {
                LocalSocket socket = new LocalSocket();
                socket.connect(address);
                return socket;
            } catch (IOException e) {
                last = e;
                Thread.sleep(CONNECT_BACKOFF_MS);
            }
        }
        throw last != null ? last : new IOException("bridge connect failed");
    }
}
