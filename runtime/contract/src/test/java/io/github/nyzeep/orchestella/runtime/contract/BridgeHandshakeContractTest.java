package io.github.nyzeep.orchestella.runtime.contract;

/**
 * Strategy-neutral contract v0 for the observable bridge handshake wire behavior.
 *
 * <p>This test intentionally has no QEMU, QMP, virtio, vsock, Android, or mock transport detail.
 */
public final class BridgeHandshakeContractTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) {
        authenticatesAndNegotiatesVersionOne();
        rejectsAnIncorrectToken();
        rejectsAnIncompatibleVersionRange();
        rejectsMalformedAndNonCanonicalTokens();
    }

    private static void authenticatesAndNegotiatesVersionOne() {
        BridgeHandshake.Result result =
                BridgeHandshake.negotiate("ORCHESTELLA/HELLO 1 1 " + TOKEN + "\n", TOKEN);

        assertEquals("ORCHESTELLA/READY 1\n", result.wireResponse(), "accepted handshake response");
        assertTrue(result.accepted(), "valid token and version range must be accepted");
        assertEquals(1, result.negotiatedVersion(), "version one must be negotiated");

        // Backward compatibility verification with legacy DROIDLAYER/HELLO
        BridgeHandshake.Result legacyResult =
                BridgeHandshake.negotiate("DROIDLAYER/HELLO 1 1 " + TOKEN + "\n", TOKEN);
        assertEquals("ORCHESTELLA/READY 1\n", legacyResult.wireResponse(), "legacy handshake accepted");
        assertTrue(legacyResult.accepted(), "legacy handshake must be accepted");
    }

    private static void rejectsAnIncorrectToken() {
        BridgeHandshake.Result result =
                BridgeHandshake.negotiate(
                        "ORCHESTELLA/HELLO 1 1 "
                                + "1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                                + "\n",
                        TOKEN);

        assertEquals("ORCHESTELLA/ERROR unauthorized\n", result.wireResponse(), "token rejection");
        assertFalse(result.accepted(), "incorrect token must not establish a bridge session");
    }

    private static void rejectsAnIncompatibleVersionRange() {
        BridgeHandshake.Result result =
                BridgeHandshake.negotiate("ORCHESTELLA/HELLO 2 3 " + TOKEN + "\n", TOKEN);

        assertEquals(
                "ORCHESTELLA/ERROR unsupported-version\n",
                result.wireResponse(),
                "version rejection");
        assertFalse(result.accepted(), "a range excluding v1 must not establish a bridge session");
    }

    private static void rejectsMalformedAndNonCanonicalTokens() {
        BridgeHandshake.Result malformed = BridgeHandshake.negotiate("hello\n", TOKEN);
        BridgeHandshake.Result uppercase =
                BridgeHandshake.negotiate(
                        "ORCHESTELLA/HELLO 1 1 " + TOKEN.toUpperCase() + "\n", TOKEN);

        assertEquals(
                "ORCHESTELLA/ERROR malformed-handshake\n",
                malformed.wireResponse(),
                "malformed wire input rejection");
        assertEquals(
                "ORCHESTELLA/ERROR malformed-handshake\n",
                uppercase.wireResponse(),
                "non-canonical token rejection");
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
