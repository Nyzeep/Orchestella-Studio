package io.github.nyzeep.orchestella.runtime.contract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Host-side Bridge v1 client contract. The smoke harness is the wire oracle:
 * the host initiates HELLO and the command lines; the guest agent answers
 * READY / PONG / OK resize, or ORCHESTELLA/ERROR with a rejection kind.
 */
public final class BridgeClientContractTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        handshakeSendsHostInitiatedHelloAndAcceptsReady();
        handshakeSurfacesAgentErrorKinds();
        pingRoundTripsPong();
        resizeRoundTripsOkResize();
        powerRoundTripsOkPower();
        powerSurfacesAgentErrorKinds();
        rejectsNonCanonicalTokensBeforeSend();
        notifyRoundTripsOkNotify();
        notifySurfacesAgentErrorKinds();
        openUrlRoundTripsOkOpenUrl();
        openUrlSurfacesAgentErrorKinds();
        rejectsInvalidParametersBeforeSend();
    }

    private static void handshakeSendsHostInitiatedHelloAndAcceptsReady() throws Exception {
        Session session = new Session("ORCHESTELLA/READY 1\n");
        BridgeClient.Result result = session.client.handshake(TOKEN);

        assertTrue(result.accepted(), "READY must be accepted");
        assertEquals("ready", result.responseKind(), "READY response kind");
        assertEquals("ORCHESTELLA/HELLO 1 1 " + TOKEN + "\n", session.wire(),
                "host must initiate the exact smoke handshake line");
    }

    private static void handshakeSurfacesAgentErrorKinds() throws Exception {
        Session unauthorized = new Session("ORCHESTELLA/ERROR unauthorized\n");
        BridgeClient.Result rejected = unauthorized.client.handshake(TOKEN);
        assertTrue(!rejected.accepted(), "agent error must not be accepted");
        assertEquals("unauthorized", rejected.responseKind(), "error kind surfaces");

        Session version = new Session("ORCHESTELLA/ERROR unsupported-version\n");
        BridgeClient.Result unsupported = version.client.handshake(TOKEN);
        assertTrue(!unsupported.accepted(), "version rejection must not be accepted");
        assertEquals("unsupported-version", unsupported.responseKind(), "version kind surfaces");
    }

    private static void pingRoundTripsPong() throws Exception {
        Session session = new Session("ORCHESTELLA/PONG 1\n");
        BridgeClient.Result result = session.client.ping();

        assertTrue(result.accepted(), "PONG must be accepted");
        assertEquals("ORCHESTELLA/PING\n", session.wire(), "ping wire line");
    }

    private static void resizeRoundTripsOkResize() throws Exception {
        Session session = new Session("ORCHESTELLA/OK resize\n");
        BridgeClient.Result result = session.client.resize(80, 24);

        assertTrue(result.accepted(), "OK resize must be accepted");
        assertEquals("ORCHESTELLA/RESIZE 80 24\n", session.wire(), "resize wire line");
    }

    private static void powerRoundTripsOkPower() throws Exception {
        Session shutdownSession = new Session("ORCHESTELLA/OK power\n");
        BridgeClient.Result shutdownResult =
                shutdownSession.client.power(BridgeControl.PowerAction.SHUTDOWN);
        assertTrue(shutdownResult.accepted(), "OK power shutdown must be accepted");
        assertEquals("ok-power", shutdownResult.responseKind(), "ok-power kind");
        assertEquals("ORCHESTELLA/POWER shutdown\n", shutdownSession.wire(), "power shutdown wire line");

        Session poweroffSession = new Session("ORCHESTELLA/OK power\n");
        BridgeClient.Result poweroffResult =
                poweroffSession.client.power(BridgeControl.PowerAction.POWEROFF);
        assertTrue(poweroffResult.accepted(), "OK power poweroff must be accepted");
        assertEquals("ok-power", poweroffResult.responseKind(), "ok-power kind");
        assertEquals("ORCHESTELLA/POWER poweroff\n", poweroffSession.wire(), "power poweroff wire line");

        Session stringSession = new Session("ORCHESTELLA/OK power\n");
        BridgeClient.Result stringResult = stringSession.client.power("shutdown");
        assertTrue(stringResult.accepted(), "string power shutdown must be accepted");
        assertEquals("ORCHESTELLA/POWER shutdown\n", stringSession.wire(), "string power wire line");
    }

    private static void powerSurfacesAgentErrorKinds() throws Exception {
        Session errorSession = new Session("ORCHESTELLA/ERROR power-unavailable\n");
        BridgeClient.Result result =
                errorSession.client.power(BridgeControl.PowerAction.SHUTDOWN);
        assertTrue(!result.accepted(), "power error must not be accepted");
        assertEquals("power-unavailable", result.responseKind(), "power-unavailable error kind");

        Session session = new Session("ORCHESTELLA/OK power\n");
        try {
            session.client.power("invalid-action");
            fail("unsupported power action must throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // required
        }
    }

    private static void rejectsNonCanonicalTokensBeforeSend() throws Exception {
        Session session = new Session("ORCHESTELLA/READY 1\n");
        for (String bad : new String[] {
                "short",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
                "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
        }) {
            try {
                session.client.handshake(bad);
                fail("non-canonical token must be rejected before any byte is sent");
            } catch (IllegalArgumentException expected) {
                // required
            }
        }
        assertEquals("", session.wire(), "nothing may be written for invalid tokens");
    }

    private static void notifyRoundTripsOkNotify() throws Exception {
        Session session = new Session("ORCHESTELLA/OK notify\n");
        BridgeClient.Result result = session.client.notify("Task Done", "Build finished successfully");

        assertTrue(result.accepted(), "OK notify must be accepted");
        assertEquals("ok-notify", result.responseKind(), "ok-notify kind");
        assertEquals("ORCHESTELLA/NOTIFY Task+Done Build+finished+successfully\n", session.wire(),
                "notify encoded wire line");
    }

    private static void notifySurfacesAgentErrorKinds() throws Exception {
        Session errorSession = new Session("ORCHESTELLA/ERROR notify-unavailable\n");
        BridgeClient.Result result = errorSession.client.notify("Title", "Message");
        assertTrue(!result.accepted(), "notify error must not be accepted");
        assertEquals("notify-unavailable", result.responseKind(), "notify-unavailable error kind");
    }

    private static void openUrlRoundTripsOkOpenUrl() throws Exception {
        Session session = new Session("ORCHESTELLA/OK open-url\n");
        BridgeClient.Result result = session.client.openUrl("https://droidlayer.github.io");

        assertTrue(result.accepted(), "OK open-url must be accepted");
        assertEquals("ok-open-url", result.responseKind(), "ok-open-url kind");
        assertEquals("ORCHESTELLA/OPEN-URL https://droidlayer.github.io\n", session.wire(),
                "open-url wire line");
    }

    private static void openUrlSurfacesAgentErrorKinds() throws Exception {
        Session errorSession = new Session("ORCHESTELLA/ERROR open-url-unavailable\n");
        BridgeClient.Result result = errorSession.client.openUrl("https://example.com");
        assertTrue(!result.accepted(), "open-url error must not be accepted");
        assertEquals("open-url-unavailable", result.responseKind(), "open-url-unavailable error kind");
    }

    private static void rejectsInvalidParametersBeforeSend() throws Exception {
        Session session = new Session("ORCHESTELLA/OK notify\n");
        try {
            session.client.notify("", "msg");
            fail("empty title must throw");
        } catch (IllegalArgumentException expected) {
            // required
        }
        try {
            session.client.notify("title", "");
            fail("empty message must throw");
        } catch (IllegalArgumentException expected) {
            // required
        }
        try {
            session.client.openUrl("file:///etc/passwd");
            fail("unsafe url scheme must throw");
        } catch (IllegalArgumentException expected) {
            // required
        }
        assertEquals("", session.wire(), "nothing may be written for invalid parameters");
    }

    private static final class Session {
        final BridgeClient client;
        final ByteArrayOutputStream outbound = new ByteArrayOutputStream();

        Session(String response) {
            this.client = new BridgeClient(
                    new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII)),
                    outbound);
        }

        String wire() {
            return outbound.toString(StandardCharsets.US_ASCII);
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            fail(description);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void fail(String reason) {
        System.out.println("BRIDGE_CLIENT_CONTRACT_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
