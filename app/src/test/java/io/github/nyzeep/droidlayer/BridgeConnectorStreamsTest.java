package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.github.nyzeep.droidlayer.runtime.contract.BridgeControl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * The AVF vsock control channel's full bidirectional story: guest-initiated
     * notify/open-url lines dispatch to the Android adapters, the exact
     * contract replies go back, and the url whitelist refuses unsafe schemes
     * with the same malformed-command code on every transport (Ticket 31 AC).
     */
    @Test
    public void dispatchesGuestInitiatedNotifyAndOpenUrlOverStreams() throws Exception {
        String script = "DROIDLAYER/READY 1\nDROIDLAYER/PONG 1\n"
                + "DROIDLAYER/NOTIFY Build+Done All+checks+passed\n"
                + "DROIDLAYER/OPEN-URL https://droidlayer.github.io\n"
                + "DROIDLAYER/OPEN-URL file:///etc/passwd\n"
                + "DROIDLAYER/OPEN-URL javascript:alert(1)\n"
                + "DROIDLAYER/NOTIFY \n";
        ByteArrayInputStream in =
                new ByteArrayInputStream(script.getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> notifications = new ArrayList<>();
        List<String> urls = new ArrayList<>();

        BridgeConnector.Session session = new BridgeConnector().serveStreams(
                in, out, TOKEN, 0, 0,
                new BridgeConnector.Events() {
                    @Override
                    public void onReady() {}

                    @Override
                    public void onPong() {}

                    @Override
                    public void onResize(int columns, int rows) {}

                    @Override
                    public void onNotification(String title, String message) {
                        notifications.add(title + "/" + message);
                    }

                    @Override
                    public void onOpenUrl(String url) {
                        urls.add(url);
                    }
                },
                (title, message) -> true,
                url -> true);

        assertEquals(0, session.outcome);
        assertEquals(1, notifications.size());
        assertEquals("Build Done/All checks passed", notifications.get(0));
        assertEquals(1, urls.size());
        assertEquals("https://droidlayer.github.io", urls.get(0));
        assertEquals(
                "DROIDLAYER/HELLO 1 1 " + TOKEN + "\n"
                        + "DROIDLAYER/PING\n"
                        + "DROIDLAYER/OK notify\n"
                        + "DROIDLAYER/OK open-url\n"
                        + "DROIDLAYER/ERROR malformed-command\n"
                        + "DROIDLAYER/ERROR malformed-command\n"
                        + "DROIDLAYER/ERROR malformed-command\n",
                out.toString(StandardCharsets.US_ASCII));
    }

    /**
     * An adapter that declines the work must surface as the honest
     * unavailable code, never a fabricated success (ADR-0010: no success
     * pretense on refused commands).
     */
    @Test
    public void surfacesAdapterFailuresAsUnavailableWithoutPretendingSuccess() throws Exception {
        String script = "DROIDLAYER/READY 1\nDROIDLAYER/PONG 1\n"
                + "DROIDLAYER/NOTIFY Title Message\nDROIDLAYER/OPEN-URL https://ok.example\n";
        ByteArrayInputStream in =
                new ByteArrayInputStream(script.getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicInteger posterCalls = new AtomicInteger();
        AtomicInteger openerCalls = new AtomicInteger();

        BridgeConnector.Session session = new BridgeConnector().serveStreams(
                in, out, TOKEN, 0, 0,
                new BridgeConnector.Events() {
                    @Override
                    public void onReady() {}

                    @Override
                    public void onPong() {}

                    @Override
                    public void onResize(int columns, int rows) {}
                },
                (title, message) -> {
                    posterCalls.incrementAndGet();
                    return false;
                },
                url -> {
                    openerCalls.incrementAndGet();
                    return false;
                });

        assertEquals(0, session.outcome);
        assertEquals(1, posterCalls.get());
        assertEquals(1, openerCalls.get());
        assertEquals(
                "DROIDLAYER/HELLO 1 1 " + TOKEN + "\n"
                        + "DROIDLAYER/PING\n"
                        + "DROIDLAYER/ERROR notify-unavailable\n"
                        + "DROIDLAYER/ERROR open-url-unavailable\n",
                out.toString(StandardCharsets.US_ASCII));
    }
}
