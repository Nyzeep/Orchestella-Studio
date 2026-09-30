package io.github.nyzeep.droidlayer.runtime.contract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Host-side Bridge v1 client contract. The smoke harness is the wire oracle:
 * the host initiates HELLO and the command lines; the guest agent answers
 * READY / PONG / OK resize, or DROIDLAYER/ERROR with a rejection kind.
 */
public final class BridgeClientContractTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        handshakeSendsHostInitiatedHelloAndAcceptsReady();
        handshakeSurfacesAgentErrorKinds();
        pingRoundTripsPong();
        resizeRoundTripsOkResize();
        rejectsNonCanonicalTokensBeforeSend();
    }

    private static void handshakeSendsHostInitiatedHelloAndAcceptsReady() throws Exception {
        Session session = new Session("DROIDLAYER/READY 1\n");
        BridgeClient.Result result = session.client.handshake(TOKEN);

        assertTrue(result.accepted(), "READY must be accepted");
        assertEquals("ready", result.responseKind(), "READY response kind");
        assertEquals("DROIDLAYER/HELLO 1 1 " + TOKEN + "\n", session.wire(),
                "host must initiate the exact smoke handshake line");
    }

    private static void handshakeSurfacesAgentErrorKinds() throws Exception {
        Session unauthorized = new Session("DROIDLAYER/ERROR unauthorized\n");
        BridgeClient.Result rejected = unauthorized.client.handshake(TOKEN);
        assertTrue(!rejected.accepted(), "agent error must not be accepted");
        assertEquals("unauthorized", rejected.responseKind(), "error kind surfaces");

        Session version = new Session("DROIDLAYER/ERROR unsupported-version\n");
        BridgeClient.Result unsupported = version.client.handshake(TOKEN);
        assertTrue(!unsupported.accepted(), "version rejection must not be accepted");
        assertEquals("unsupported-version", unsupported.responseKind(), "version kind surfaces");
    }

    private static void pingRoundTripsPong() throws Exception {
        Session session = new Session("DROIDLAYER/PONG 1\n");
        BridgeClient.Result result = session.client.ping();

        assertTrue(result.accepted(), "PONG must be accepted");
        assertEquals("DROIDLAYER/PING\n", session.wire(), "ping wire line");
    }

    private static void resizeRoundTripsOkResize() throws Exception {
        Session session = new Session("DROIDLAYER/OK resize\n");
        BridgeClient.Result result = session.client.resize(80, 24);

        assertTrue(result.accepted(), "OK resize must be accepted");
        assertEquals("DROIDLAYER/RESIZE 80 24\n", session.wire(), "resize wire line");
    }

    private static void rejectsNonCanonicalTokensBeforeSend() throws Exception {
        Session session = new Session("DROIDLAYER/READY 1\n");
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
