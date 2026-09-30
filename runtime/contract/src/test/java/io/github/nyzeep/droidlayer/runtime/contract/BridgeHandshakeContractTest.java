package io.github.nyzeep.droidlayer.runtime.contract;

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
                BridgeHandshake.negotiate("DROIDLAYER/HELLO 1 1 " + TOKEN + "\n", TOKEN);

        assertEquals("DROIDLAYER/READY 1\n", result.wireResponse(), "accepted handshake response");
        assertTrue(result.accepted(), "valid token and version range must be accepted");
        assertEquals(1, result.negotiatedVersion(), "version one must be negotiated");
    }

    private static void rejectsAnIncorrectToken() {
        BridgeHandshake.Result result =
                BridgeHandshake.negotiate(
                        "DROIDLAYER/HELLO 1 1 "
                                + "1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                                + "\n",
                        TOKEN);

        assertEquals("DROIDLAYER/ERROR unauthorized\n", result.wireResponse(), "token rejection");
        assertFalse(result.accepted(), "incorrect token must not establish a bridge session");
    }

    private static void rejectsAnIncompatibleVersionRange() {
        BridgeHandshake.Result result =
                BridgeHandshake.negotiate("DROIDLAYER/HELLO 2 3 " + TOKEN + "\n", TOKEN);

        assertEquals(
                "DROIDLAYER/ERROR unsupported-version\n",
                result.wireResponse(),
                "version rejection");
        assertFalse(result.accepted(), "a range excluding v1 must not establish a bridge session");
    }

    private static void rejectsMalformedAndNonCanonicalTokens() {
        BridgeHandshake.Result malformed = BridgeHandshake.negotiate("hello\n", TOKEN);
        BridgeHandshake.Result uppercase =
                BridgeHandshake.negotiate(
                        "DROIDLAYER/HELLO 1 1 " + TOKEN.toUpperCase() + "\n", TOKEN);

        assertEquals(
                "DROIDLAYER/ERROR malformed-handshake\n",
                malformed.wireResponse(),
                "malformed wire input rejection");
        assertEquals(
                "DROIDLAYER/ERROR malformed-handshake\n",
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
