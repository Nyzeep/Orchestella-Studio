package io.github.nyzeep.droidlayer;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import io.github.nyzeep.droidlayer.runtime.contract.BridgeClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Connects the app to the guest Bridge over the abstract-socket control
 * channel served by the packaged QEMU (bounded retries while the guest agent
 * starts), then drives the v1 protocol as the host initiator through
 * {@link BridgeClient} with this instance's token (smoke-harness wire: the
 * host sends HELLO/PING/RESIZE and the guest agent answers READY/PONG/OK).
 */
public final class BridgeConnector {
    /** Bridge session milestones for the host UI. */
    public interface Events {
        void onReady();

        void onPong();

        void onResize(int columns, int rows);
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
            while (in.read() >= 0) {
                // session stays open until the guest closes the control stream
            }
            return new Session(0, socket);
        }
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
