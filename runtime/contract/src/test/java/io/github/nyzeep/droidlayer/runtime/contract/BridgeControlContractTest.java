package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.concurrent.atomic.AtomicInteger;

/** Strategy-neutral bridge-control behavior after a successful v1 handshake. */
public final class BridgeControlContractTest {
    public static void main(String[] arguments) {
        respondsToPingWithoutTouchingTheTerminal();
        resizesTheSeparateTerminalThroughTheContractSeam();
        rejectsMalformedResizeBeforeItReachesTheTerminal();
        rejectsUnavailableResizeWithoutPretendingSuccess();
    }

    private static void respondsToPingWithoutTouchingTheTerminal() {
        BridgeControl.Result result =
                BridgeControl.dispatch("DROIDLAYER/PING\n");

        assertEquals("DROIDLAYER/PONG 1\n", result.wireResponse(), "ping response");
        assertTrue(result.accepted(), "ping must be accepted after the handshake");
    }

    private static void resizesTheSeparateTerminalThroughTheContractSeam() {
        AtomicInteger columns = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();

        BridgeControl.Result result =
                BridgeControl.dispatch(
                        "DROIDLAYER/RESIZE 80 24\n",
                        (requestedColumns, requestedRows) -> {
                            columns.set(requestedColumns);
                            rows.set(requestedRows);
                            return true;
                        });

        assertEquals("DROIDLAYER/OK resize\n", result.wireResponse(), "resize response");
        assertTrue(result.accepted(), "a supported resize must be accepted");
        assertEquals(80, columns.get(), "terminal columns");
        assertEquals(24, rows.get(), "terminal rows");
    }

    private static void rejectsMalformedResizeBeforeItReachesTheTerminal() {
        AtomicInteger invocations = new AtomicInteger();

        BridgeControl.Result result =
                BridgeControl.dispatch(
                        "DROIDLAYER/RESIZE 0 24\n",
                        (columns, rows) -> {
                            invocations.incrementAndGet();
                            return true;
                        });

        assertEquals(
                "DROIDLAYER/ERROR malformed-command\n",
                result.wireResponse(),
                "malformed resize rejection");
        assertEquals(0, invocations.get(), "malformed resize must not reach the terminal");
    }

    private static void rejectsUnavailableResizeWithoutPretendingSuccess() {
        BridgeControl.Result result =
                BridgeControl.dispatch("DROIDLAYER/RESIZE 80 24\n", (columns, rows) -> false);

        assertEquals(
                "DROIDLAYER/ERROR resize-unavailable\n",
                result.wireResponse(),
                "unavailable resize rejection");
        assertFalse(result.accepted(), "unavailable resize must not be reported as success");
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
