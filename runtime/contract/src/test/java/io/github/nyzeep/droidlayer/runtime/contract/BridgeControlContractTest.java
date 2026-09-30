package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.concurrent.atomic.AtomicInteger;

/** Strategy-neutral bridge-control behavior after a successful v1 handshake. */
public final class BridgeControlContractTest {
    public static void main(String[] arguments) {
        respondsToPingWithoutTouchingTheTerminal();
        resizesTheSeparateTerminalThroughTheContractSeam();
        rejectsMalformedResizeBeforeItReachesTheTerminal();
        rejectsUnavailableResizeWithoutPretendingSuccess();
        executesShutdownThroughThePowerSeam();
        executesPoweroffThroughThePowerSeam();
        rejectsMalformedPowerCommandsBeforeTheyReachThePowerSeam();
        rejectsUnavailablePowerWithoutPretendingSuccess();
        rejectsPowerWhenPowerSeamNotSupplied();
        dispatchesBothTerminalAndPowerSeams();
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

    private static void executesShutdownThroughThePowerSeam() {
        java.util.concurrent.atomic.AtomicReference<BridgeControl.PowerAction> actionRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        BridgeControl.Result result =
                BridgeControl.dispatch(
                        "DROIDLAYER/POWER shutdown\n",
                        action -> {
                            actionRef.set(action);
                            return true;
                        });

        assertEquals("DROIDLAYER/OK power\n", result.wireResponse(), "power shutdown response");
        assertTrue(result.accepted(), "a supported power shutdown must be accepted");
        assertEquals(BridgeControl.PowerAction.SHUTDOWN, actionRef.get(), "power action shutdown");
    }

    private static void executesPoweroffThroughThePowerSeam() {
        java.util.concurrent.atomic.AtomicReference<BridgeControl.PowerAction> actionRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        BridgeControl.Result result =
                BridgeControl.dispatch(
                        "DROIDLAYER/POWER poweroff\n",
                        action -> {
                            actionRef.set(action);
                            return true;
                        });

        assertEquals("DROIDLAYER/OK power\n", result.wireResponse(), "power poweroff response");
        assertTrue(result.accepted(), "a supported power poweroff must be accepted");
        assertEquals(BridgeControl.PowerAction.POWEROFF, actionRef.get(), "power action poweroff");
    }

    private static void rejectsMalformedPowerCommandsBeforeTheyReachThePowerSeam() {
        AtomicInteger invocations = new AtomicInteger();
        BridgeControl.Power power = action -> {
            invocations.incrementAndGet();
            return true;
        };

        for (String malformed : new String[] {
            "DROIDLAYER/POWER\n",
            "DROIDLAYER/POWER \n",
            "DROIDLAYER/POWER reboot\n",
            "DROIDLAYER/POWER shutdown now\n",
            "DROIDLAYER/POWER poweroff extra\n"
        }) {
            BridgeControl.Result result = BridgeControl.dispatch(malformed, power);
            assertEquals(
                    "DROIDLAYER/ERROR malformed-command\n",
                    result.wireResponse(),
                    "malformed power command rejection: " + malformed.trim());
            assertFalse(result.accepted(), "malformed power command must not be accepted");
        }
        assertEquals(0, invocations.get(), "malformed power commands must not reach the power seam");
    }

    private static void rejectsUnavailablePowerWithoutPretendingSuccess() {
        BridgeControl.Result result =
                BridgeControl.dispatch("DROIDLAYER/POWER shutdown\n", action -> false);

        assertEquals(
                "DROIDLAYER/ERROR power-unavailable\n",
                result.wireResponse(),
                "unavailable power rejection");
        assertFalse(result.accepted(), "unavailable power must not be reported as success");
    }

    private static void rejectsPowerWhenPowerSeamNotSupplied() {
        BridgeControl.Result result =
                BridgeControl.dispatch("DROIDLAYER/POWER shutdown\n", (columns, rows) -> true);

        assertEquals(
                "DROIDLAYER/ERROR power-unavailable\n",
                result.wireResponse(),
                "power without power seam rejection");
        assertFalse(result.accepted(), "power without power seam must not be accepted");
    }

    private static void dispatchesBothTerminalAndPowerSeams() {
        AtomicInteger columns = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<BridgeControl.PowerAction> actionRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        BridgeControl.Terminal terminal = (cols, rws) -> {
            columns.set(cols);
            rows.set(rws);
            return true;
        };
        BridgeControl.Power power = action -> {
            actionRef.set(action);
            return true;
        };

        BridgeControl.Result pingResult = BridgeControl.dispatch("DROIDLAYER/PING\n", terminal, power);
        assertEquals("DROIDLAYER/PONG 1\n", pingResult.wireResponse(), "combined ping response");
        assertTrue(pingResult.accepted(), "ping must succeed with combined seams");

        BridgeControl.Result resizeResult =
                BridgeControl.dispatch("DROIDLAYER/RESIZE 120 40\n", terminal, power);
        assertEquals("DROIDLAYER/OK resize\n", resizeResult.wireResponse(), "combined resize response");
        assertTrue(resizeResult.accepted(), "resize must succeed with combined seams");
        assertEquals(120, columns.get(), "combined columns");
        assertEquals(40, rows.get(), "combined rows");

        BridgeControl.Result powerResult =
                BridgeControl.dispatch("DROIDLAYER/POWER shutdown\n", terminal, power);
        assertEquals("DROIDLAYER/OK power\n", powerResult.wireResponse(), "combined power response");
        assertTrue(powerResult.accepted(), "power must succeed with combined seams");
        assertEquals(BridgeControl.PowerAction.SHUTDOWN, actionRef.get(), "combined power action");
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
