package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.github.nyzeep.droidlayer.runtime.contract.BridgeControl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** The streams-based bridge session reused by the AVF vsock control channel. */
public final class BridgeConnectorStreamsTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void servesTheFullHostInitiatedSessionOverCallerOwnedStreams() throws Exception {
        String script = "DROIDLAYER/READY 1\nDROIDLAYER/PONG 1\nDROIDLAYER/OK resize\n";
        ByteArrayInputStream in =
                new ByteArrayInputStream(script.getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger pong = new AtomicInteger();
        AtomicInteger resize = new AtomicInteger();

        BridgeConnector.Session session = new BridgeConnector().serveStreams(
                in, out, TOKEN, 80, 24,
                new BridgeConnector.Events() {
                    @Override
                    public void onReady() {
                        ready.incrementAndGet();
                    }

                    @Override
                    public void onPong() {
                        pong.incrementAndGet();
                    }

                    @Override
                    public void onResize(int columns, int rows) {
                        resize.incrementAndGet();
                    }
                },
                null,
                null);

        assertEquals(0, session.outcome);
        assertEquals(null, session.socket);
        assertEquals(1, ready.get());
        assertEquals(1, pong.get());
        assertEquals(1, resize.get());
        assertEquals(
                "DROIDLAYER/HELLO 1 1 " + TOKEN + "\nDROIDLAYER/PING\nDROIDLAYER/RESIZE 80 24\n",
                out.toString(StandardCharsets.US_ASCII));
    }

    @Test
    public void rejectedHandshakeStopsBeforeAnyMilestone() throws Exception {
        ByteArrayInputStream in =
                new ByteArrayInputStream("DROIDLAYER/ERROR unauthorized\n".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        BridgeConnector.Session session = new BridgeConnector().serveStreams(
                in, out, TOKEN, 80, 24,
                new BridgeConnector.Events() {
                    @Override
                    public void onReady() {
                        throw new AssertionError("no milestone after rejection");
                    }

                    @Override
                    public void onPong() {}

                    @Override
                    public void onResize(int columns, int rows) {}
                },
                (BridgeControl.Notification) null,
                null);

        assertEquals(3, session.outcome);
        assertNull(session.socket);
    }
}
