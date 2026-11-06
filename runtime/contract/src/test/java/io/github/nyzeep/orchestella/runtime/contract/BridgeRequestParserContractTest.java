package io.github.nyzeep.orchestella.runtime.contract;

/**
 * Locks the extracted {@link BridgeRequestParser} boundary: per-verb request-line field
 * parsing keeps its exact accept/reject behavior (verb prefix, field counts, value bounds,
 * URL safety) for both the ORCHESTELLA and DROIDLAYER wire prefixes. Routing itself stays
 * covered by {@link BridgeControlContractTest}; this test pins the parsing collaborator.
 */
public final class BridgeRequestParserContractTest {
    public static void main(String[] arguments) {
        parsesPowerOnBothWirePrefixesAndRejectsBadFields();
        parsesResizeBoundsAndRejectsOutOfRange();
        parsesNotifyWithEncodedFieldsAndRejectsEmpty();
        parsesOpenUrlAndRejectsUnsafeUrls();
        parsesEveryDisplaySubcommandAndRejectsBadValues();
        matchesVerbsOnlyAtLineBoundaries();
    }

    private static void parsesPowerOnBothWirePrefixesAndRejectsBadFields() {
        BridgeRequestParser.PowerRequest shutdown =
                BridgeRequestParser.parsePower("ORCHESTELLA/POWER shutdown\n");
        assertEquals(BridgeControl.PowerAction.SHUTDOWN, shutdown.action, "orchestella shutdown action");

        BridgeRequestParser.PowerRequest poweroff =
                BridgeRequestParser.parsePower("DROIDLAYER/POWER poweroff\r\n");
        assertEquals(BridgeControl.PowerAction.POWEROFF, poweroff.action, "droidlayer poweroff action");

        assertRejected(() -> BridgeRequestParser.parsePower("ORCHESTELLA/POWER reboot\n"),
                "unknown action is rejected");
        assertRejected(() -> BridgeRequestParser.parsePower("ORCHESTELLA/POWER shutdown extra\n"),
                "extra field is rejected");
        assertRejected(() -> BridgeRequestParser.parsePower(null), "null line is rejected");
        assertRejected(() -> BridgeRequestParser.parsePower("ORCHESTELLA/POWER shutdown"),
                "missing terminator is rejected");
    }

    private static void parsesResizeBoundsAndRejectsOutOfRange() {
        BridgeRequestParser.ResizeRequest resize =
                BridgeRequestParser.parseResize("ORCHESTELLA/RESIZE 1 1000\n");
        assertEquals(1, resize.columns, "lower bound column");
        assertEquals(1000, resize.rows, "upper bound row");

        assertRejected(() -> BridgeRequestParser.parseResize("ORCHESTELLA/RESIZE 0 24\n"),
                "zero column is rejected");
        assertRejected(() -> BridgeRequestParser.parseResize("ORCHESTELLA/RESIZE 80 1001\n"),
                "row above the bound is rejected");
        assertRejected(() -> BridgeRequestParser.parseResize("ORCHESTELLA/RESIZE eighty twentyfour\n"),
                "non-numeric fields are rejected");
    }

    private static void parsesNotifyWithEncodedFieldsAndRejectsEmpty() {
        BridgeRequestParser.NotifyRequest plain =
                BridgeRequestParser.parseNotify("ORCHESTELLA/NOTIFY title message\n");
        assertEquals("title", plain.title, "plain title");
        assertEquals("message", plain.message, "plain message");

        BridgeRequestParser.NotifyRequest encoded = BridgeRequestParser.parseNotify(
                "ORCHESTELLA/NOTIFY base64:" + java.util.Base64.getEncoder().encodeToString(
                        "标题".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + " base64:" + java.util.Base64.getEncoder().encodeToString(
                        "消息".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\n");
        assertEquals("标题", encoded.title, "base64 title decodes");
        assertEquals("消息", encoded.message, "base64 message decodes");

        BridgeRequestParser.NotifyRequest urlEncoded =
                BridgeRequestParser.parseNotify("ORCHESTELLA/NOTIFY hello%20world plain-message\n");
        assertEquals("hello world", urlEncoded.title, "percent-encoded title decodes");
        assertEquals("plain-message", urlEncoded.message, "plain message decodes");

        assertRejected(() -> BridgeRequestParser.parseNotify("ORCHESTELLA/NOTIFY  message\n"),
                "empty title is rejected");
        assertRejected(() -> BridgeRequestParser.parseNotify("ORCHESTELLA/NOTIFY title\n"),
                "missing message field is rejected");
    }

    private static void parsesOpenUrlAndRejectsUnsafeUrls() {
        BridgeRequestParser.OpenUrlRequest url =
                BridgeRequestParser.parseOpenUrl("ORCHESTELLA/OPEN-URL https://example.com/page?q=1\n");
        assertEquals("https://example.com/page?q=1", url.url, "https url accepted");

        assertRejected(() -> BridgeRequestParser.parseOpenUrl("ORCHESTELLA/OPEN-URL ftp://example.com\n"),
                "non-http scheme is rejected");
        assertRejected(() -> BridgeRequestParser.parseOpenUrl("ORCHESTELLA/OPEN-URL javascript:alert(1)\n"),
                "javascript url is rejected");
        assertRejected(() -> BridgeRequestParser.parseOpenUrl("ORCHESTELLA/OPEN-URL \n"),
                "empty url is rejected");
    }

    private static void parsesEveryDisplaySubcommandAndRejectsBadValues() {
        BridgeRequestParser.DisplayRequest mode =
                BridgeRequestParser.parseDisplay("ORCHESTELLA/DISPLAY MODE 1920 1080 1.5\n");
        assertEquals(BridgeRequestParser.DisplayCommandType.MODE, mode.type, "mode type");
        assertEquals(1920, mode.width, "mode width");
        assertEquals(1.5, mode.scale, "mode scale");

        BridgeRequestParser.DisplayRequest resize =
                BridgeRequestParser.parseDisplay("DROIDLAYER/DISPLAY RESIZE 2560 1440\n");
        assertEquals(BridgeRequestParser.DisplayCommandType.RESIZE, resize.type, "resize type");

        BridgeRequestParser.DisplayRequest fps =
                BridgeRequestParser.parseDisplay("ORCHESTELLA/DISPLAY FPS 120\n");
        assertEquals(BridgeRequestParser.DisplayCommandType.FPS, fps.type, "fps type");
        assertEquals(120, fps.fps, "fps value");

        BridgeRequestParser.DisplayRequest launch =
                BridgeRequestParser.parseDisplay("ORCHESTELLA/DISPLAY LAUNCH\n");
        assertEquals(BridgeRequestParser.DisplayCommandType.LAUNCH, launch.type, "launch type");

        assertRejected(() -> BridgeRequestParser.parseDisplay("ORCHESTELLA/DISPLAY MODE 99 1080 1.0\n"),
                "width below the bound is rejected");
        assertRejected(() -> BridgeRequestParser.parseDisplay("ORCHESTELLA/DISPLAY FPS 0\n"),
                "fps below the bound is rejected");
        assertRejected(() -> BridgeRequestParser.parseDisplay("ORCHESTELLA/DISPLAY MODE 1920 1080 9.0\n"),
                "scale above the bound is rejected");
        assertRejected(() -> BridgeRequestParser.parseDisplay("ORCHESTELLA/DISPLAY UNKNOWN\n"),
                "unknown subcommand is rejected");
    }

    private static void matchesVerbsOnlyAtLineBoundaries() {
        assertTrue(BridgeRequestParser.matchesVerb("ORCHESTELLA/POWER shutdown\n", "POWER"),
                "verb with arguments matches");
        assertTrue(BridgeRequestParser.matchesVerb("ORCHESTELLA/POWER\n", "POWER"),
                "bare verb line matches");
        assertFalse(BridgeRequestParser.matchesVerb(null, "POWER"), "null line never matches");
        assertFalse(BridgeRequestParser.matchesVerb("XORCHESTELLA/POWER shutdown\n", "POWER"),
                "verb embedded in another token never matches");
    }

    private static void assertRejected(ThrowingSupplier<?> operation, String description) {
        try {
            Object parsed = operation.get();
            if (parsed != null) {
                throw new AssertionError(description + ": expected a null parse result");
            }
        } catch (AssertionError failure) {
            throw failure;
        } catch (Throwable throwable) {
            throw new AssertionError(description + ": unexpected exception", throwable);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": values differ (redacted)");
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        if (condition) {
            throw new AssertionError(description);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
