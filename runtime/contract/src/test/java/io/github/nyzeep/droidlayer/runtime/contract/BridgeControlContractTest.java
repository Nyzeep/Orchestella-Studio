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
        executesNotifyThroughTheNotificationSeam();
        rejectsMalformedNotifyCommandsBeforeTheyReachTheNotificationSeam();
        rejectsUnavailableNotifyWithoutPretendingSuccess();
        rejectsNotifyWhenNotificationSeamNotSupplied();
        executesOpenUrlThroughTheUrlOpenerSeam();
        rejectsMalformedOpenUrlCommandsBeforeTheyReachTheUrlOpenerSeam();
        rejectsUnavailableOpenUrlWithoutPretendingSuccess();
        rejectsOpenUrlWhenUrlOpenerSeamNotSupplied();
        rejectsDisallowedSchemesInOpenUrl();
        dispatchesAllCombinedSeams();
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

    private static void executesNotifyThroughTheNotificationSeam() {
        java.util.concurrent.atomic.AtomicReference<String> titleRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> messageRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        BridgeControl.Notification notification = (title, message) -> {
            titleRef.set(title);
            messageRef.set(message);
            return true;
        };

        BridgeControl.Result result =
                BridgeControl.dispatchNotification("DROIDLAYER/NOTIFY Build%20Done All%20tests%20passed\n", notification);
        assertEquals("DROIDLAYER/OK notify\n", result.wireResponse(), "notify ok response");
        assertTrue(result.accepted(), "notify must be accepted");
        assertEquals("Build Done", titleRef.get(), "decoded notification title");
        assertEquals("All tests passed", messageRef.get(), "decoded notification message");

        BridgeControl.Result simpleResult =
                BridgeControl.dispatchNotification("DROIDLAYER/NOTIFY SimpleTitle SimpleMessage\n", notification);
        assertEquals("DROIDLAYER/OK notify\n", simpleResult.wireResponse(), "simple notify response");
        assertTrue(simpleResult.accepted(), "simple notify must be accepted");
        assertEquals("SimpleTitle", titleRef.get(), "simple notification title");
        assertEquals("SimpleMessage", messageRef.get(), "simple notification message");

        BridgeControl.Result b64Result =
                BridgeControl.dispatch(
                        "DROIDLAYER/NOTIFY base64:VGl0bGU= base64:TWVzc2FnZQ==\n",
                        BridgeControl.Seams.of(notification));
        assertEquals("DROIDLAYER/OK notify\n", b64Result.wireResponse(), "base64 notify response");
        assertTrue(b64Result.accepted(), "base64 notify must be accepted");
        assertEquals("Title", titleRef.get(), "base64 decoded title");
        assertEquals("Message", messageRef.get(), "base64 decoded message");
    }

    private static void rejectsMalformedNotifyCommandsBeforeTheyReachTheNotificationSeam() {
        AtomicInteger invocations = new AtomicInteger();
        BridgeControl.Notification notification = (title, message) -> {
            invocations.incrementAndGet();
            return true;
        };

        String[] malformedCommands = new String[] {
            "DROIDLAYER/NOTIFY\n",
            "DROIDLAYER/NOTIFY \n",
            "DROIDLAYER/NOTIFY TitleOnly\n",
            "DROIDLAYER/NOTIFY Title Message ExtraWord\n",
            "DROIDLAYER/NOTIFY %ZZ InvalidPercent\n",
            "DROIDLAYER/NOTIFY ValidTitle %ZZ\n",
            "DROIDLAYER/NOTIFY " + "a".repeat(2100) + " msg\n"
        };

        for (String malformed : malformedCommands) {
            BridgeControl.Result result = BridgeControl.dispatchNotification(malformed, notification);
            assertEquals("DROIDLAYER/ERROR malformed-command\n", result.wireResponse(),
                    "malformed notify rejection: " + malformed.trim());
            assertFalse(result.accepted(), "malformed notify must not be accepted");
        }
        assertEquals(0, invocations.get(), "malformed notify must not reach notification seam");
    }

    private static void rejectsUnavailableNotifyWithoutPretendingSuccess() {
        BridgeControl.Result result =
                BridgeControl.dispatchNotification("DROIDLAYER/NOTIFY Title Msg\n", (title, message) -> false);
        assertEquals("DROIDLAYER/ERROR notify-unavailable\n", result.wireResponse(),
                "unavailable notify rejection");
        assertFalse(result.accepted(), "unavailable notify must not be accepted");
    }

    private static void rejectsNotifyWhenNotificationSeamNotSupplied() {
        BridgeControl.Result result =
                BridgeControl.dispatch("DROIDLAYER/NOTIFY Title Msg\n", (columns, rows) -> true);
        assertEquals("DROIDLAYER/ERROR notify-unavailable\n", result.wireResponse(),
                "notify without notification seam rejection");
        assertFalse(result.accepted(), "notify without seam must not be accepted");
    }

    private static void executesOpenUrlThroughTheUrlOpenerSeam() {
        java.util.concurrent.atomic.AtomicReference<String> urlRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        BridgeControl.UrlOpener opener = url -> {
            urlRef.set(url);
            return true;
        };

        BridgeControl.Result httpsResult =
                BridgeControl.dispatchOpenUrl("DROIDLAYER/OPEN-URL https://droidlayer.github.io\n", opener);
        assertEquals("DROIDLAYER/OK open-url\n", httpsResult.wireResponse(), "open-url https response");
        assertTrue(httpsResult.accepted(), "https url must be accepted");
        assertEquals("https://droidlayer.github.io", urlRef.get(), "opened url https");

        BridgeControl.Result httpResult =
                BridgeControl.dispatch(
                        "DROIDLAYER/OPEN-URL http://example.com/path?q=1&v=2#frag\n",
                        BridgeControl.Seams.of(opener));
        assertEquals("DROIDLAYER/OK open-url\n", httpResult.wireResponse(), "open-url http response");
        assertTrue(httpResult.accepted(), "http url must be accepted");
        assertEquals("http://example.com/path?q=1&v=2#frag", urlRef.get(), "opened url http");
    }

    private static void rejectsMalformedOpenUrlCommandsBeforeTheyReachTheUrlOpenerSeam() {
        AtomicInteger invocations = new AtomicInteger();
        BridgeControl.UrlOpener opener = url -> {
            invocations.incrementAndGet();
            return true;
        };

        String[] malformedCommands = new String[] {
            "DROIDLAYER/OPEN-URL\n",
            "DROIDLAYER/OPEN-URL \n",
            "DROIDLAYER/OPEN-URL https://example.com extra\n",
            "DROIDLAYER/OPEN-URL https://example.com/" + "a".repeat(2100) + "\n"
        };

        for (String malformed : malformedCommands) {
            BridgeControl.Result result = BridgeControl.dispatchOpenUrl(malformed, opener);
            assertEquals("DROIDLAYER/ERROR malformed-command\n", result.wireResponse(),
                    "malformed open-url rejection: " + malformed.trim());
            assertFalse(result.accepted(), "malformed open-url must not be accepted");
        }
        assertEquals(0, invocations.get(), "malformed open-url must not reach opener seam");
    }

    private static void rejectsUnavailableOpenUrlWithoutPretendingSuccess() {
        BridgeControl.Result result =
                BridgeControl.dispatchOpenUrl("DROIDLAYER/OPEN-URL https://example.com\n", url -> false);
        assertEquals("DROIDLAYER/ERROR open-url-unavailable\n", result.wireResponse(),
                "unavailable open-url rejection");
        assertFalse(result.accepted(), "unavailable open-url must not be accepted");
    }

    private static void rejectsOpenUrlWhenUrlOpenerSeamNotSupplied() {
        BridgeControl.Result result =
                BridgeControl.dispatch("DROIDLAYER/OPEN-URL https://example.com\n", (columns, rows) -> true);
        assertEquals("DROIDLAYER/ERROR open-url-unavailable\n", result.wireResponse(),
                "open-url without opener seam rejection");
        assertFalse(result.accepted(), "open-url without seam must not be accepted");
    }

    private static void rejectsDisallowedSchemesInOpenUrl() {
        AtomicInteger invocations = new AtomicInteger();
        BridgeControl.UrlOpener opener = url -> {
            invocations.incrementAndGet();
            return true;
        };

        String[] unsafeUrls = new String[] {
            "DROIDLAYER/OPEN-URL file:///etc/passwd\n",
            "DROIDLAYER/OPEN-URL file:///data/data/io.github.nyzeep.droidlayer/databases/app.db\n",
            "DROIDLAYER/OPEN-URL javascript:alert(1)\n",
            "DROIDLAYER/OPEN-URL intent:#Intent;action=android.intent.action.VIEW;end\n",
            "DROIDLAYER/OPEN-URL content://media/external/images\n",
            "DROIDLAYER/OPEN-URL ftp://ftp.example.com/file\n",
            "DROIDLAYER/OPEN-URL data:text/html,<html>hello</html>\n",
            "DROIDLAYER/OPEN-URL http://\n",
            "DROIDLAYER/OPEN-URL https://\n"
        };

        for (String unsafe : unsafeUrls) {
            BridgeControl.Result result = BridgeControl.dispatchOpenUrl(unsafe, opener);
            assertEquals("DROIDLAYER/ERROR malformed-command\n", result.wireResponse(),
                    "unsafe scheme must be rejected as malformed-command: " + unsafe.trim());
            assertFalse(result.accepted(), "unsafe url must not be accepted");
        }
        assertEquals(0, invocations.get(), "unsafe url must never reach url opener seam");
    }

    private static void dispatchesAllCombinedSeams() {
        AtomicInteger columns = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<BridgeControl.PowerAction> actionRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> titleRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> messageRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> urlRef =
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
        BridgeControl.Notification notification = (title, message) -> {
            titleRef.set(title);
            messageRef.set(message);
            return true;
        };
        BridgeControl.UrlOpener opener = url -> {
            urlRef.set(url);
            return true;
        };

        BridgeControl.Result pingResult =
                BridgeControl.dispatch("DROIDLAYER/PING\n", terminal, power, notification, opener);
        assertEquals("DROIDLAYER/PONG 1\n", pingResult.wireResponse(), "combined ping response");
        assertTrue(pingResult.accepted(), "ping must succeed with combined seams");

        BridgeControl.Result resizeResult =
                BridgeControl.dispatch("DROIDLAYER/RESIZE 100 50\n", terminal, power, notification, opener);
        assertEquals("DROIDLAYER/OK resize\n", resizeResult.wireResponse(), "combined resize response");
        assertTrue(resizeResult.accepted(), "resize must succeed with combined seams");
        assertEquals(100, columns.get(), "combined columns");
        assertEquals(50, rows.get(), "combined rows");

        BridgeControl.Result powerResult =
                BridgeControl.dispatch("DROIDLAYER/POWER poweroff\n", terminal, power, notification, opener);
        assertEquals("DROIDLAYER/OK power\n", powerResult.wireResponse(), "combined power response");
        assertTrue(powerResult.accepted(), "power must succeed with combined seams");
        assertEquals(BridgeControl.PowerAction.POWEROFF, actionRef.get(), "combined power action");

        BridgeControl.Result notifyResult =
                BridgeControl.dispatch("DROIDLAYER/NOTIFY Build%20Pass OK\n", terminal, power, notification, opener);
        assertEquals("DROIDLAYER/OK notify\n", notifyResult.wireResponse(), "combined notify response");
        assertTrue(notifyResult.accepted(), "notify must succeed with combined seams");
        assertEquals("Build Pass", titleRef.get(), "combined notify title");
        assertEquals("OK", messageRef.get(), "combined notify message");

        BridgeControl.Result openUrlResult =
                BridgeControl.dispatch("DROIDLAYER/OPEN-URL https://example.org\n", terminal, power, notification, opener);
        assertEquals("DROIDLAYER/OK open-url\n", openUrlResult.wireResponse(), "combined open-url response");
        assertTrue(openUrlResult.accepted(), "open-url must succeed with combined seams");
        assertEquals("https://example.org", urlRef.get(), "combined url");
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
