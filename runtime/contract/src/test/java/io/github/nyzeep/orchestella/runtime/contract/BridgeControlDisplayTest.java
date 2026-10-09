package io.github.nyzeep.orchestella.runtime.contract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Wire protocol test suite for BridgeControl DISPLAY commands and BridgeClient display integration. */
public final class BridgeControlDisplayTest {
    public static void main(String[] arguments) throws IOException {
        dispatchesDisplayModeCommandSuccessfully();
        dispatchesDisplayResizeCommandSuccessfully();
        dispatchesDisplayFpsCommandSuccessfully();
        dispatchesDisplayLaunchCommandSuccessfully();
        handlesCrlfEndingsForDisplayCommands();
        acceptsExactBoundaryValues();
        rejectsOutOfBoundsDimensions();
        rejectsOutOfBoundsScale();
        rejectsOutOfBoundsFps();
        rejectsMalformedDisplayCommands();
        rejectsWhenDisplaySeamUnavailable();
        rejectsWhenDisplaySeamFails();
        dispatchesDisplayThroughCombinedSeams();
        bridgeClientSendsAndClassifiesDisplayCommands();
        System.out.println("BRIDGE_CONTROL_DISPLAY_TEST_OK");
    }

    private static void dispatchesDisplayModeCommandSuccessfully() {
        AtomicInteger widthRef = new AtomicInteger();
        AtomicInteger heightRef = new AtomicInteger();
        AtomicReference<Double> scaleRef = new AtomicReference<>();

        BridgeControl.Display display = new BridgeControl.Display() {
            @Override
            public boolean setMode(int width, int height, double scale) {
                widthRef.set(width);
                heightRef.set(height);
                scaleRef.set(scale);
                return true;
            }

            @Override
            public boolean resize(int width, int height) {
                return false;
            }

            @Override
            public boolean setFps(int fps) {
                return false;
            }
        };

        BridgeControl.Result result =
                BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY MODE 1920 1080 1.5\n", display);
        assertEquals("ORCHESTELLA/OK display\n", result.wireResponse(), "display mode ok response");
        assertTrue(result.accepted(), "display mode must be accepted");
        assertEquals(1920, widthRef.get(), "mode width");
        assertEquals(1080, heightRef.get(), "mode height");
        assertEquals(1.5, scaleRef.get(), "mode scale");
    }

    private static void dispatchesDisplayResizeCommandSuccessfully() {
        AtomicInteger widthRef = new AtomicInteger();
        AtomicInteger heightRef = new AtomicInteger();

        BridgeControl.Display display = new BridgeControl.Display() {
            @Override
            public boolean setMode(int width, int height, double scale) {
                return false;
            }

            @Override
            public boolean resize(int width, int height) {
                widthRef.set(width);
                heightRef.set(height);
                return true;
            }

            @Override
            public boolean setFps(int fps) {
                return false;
            }
        };

        BridgeControl.Result result =
                BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY RESIZE 1280 720\n", display);
        assertEquals("ORCHESTELLA/OK display\n", result.wireResponse(), "display resize ok response");
        assertTrue(result.accepted(), "display resize must be accepted");
        assertEquals(1280, widthRef.get(), "resize width");
        assertEquals(720, heightRef.get(), "resize height");
    }

    private static void dispatchesDisplayFpsCommandSuccessfully() {
        AtomicInteger fpsRef = new AtomicInteger();

        BridgeControl.Display display = new BridgeControl.Display() {
            @Override
            public boolean setMode(int width, int height, double scale) {
                return false;
            }

            @Override
            public boolean resize(int width, int height) {
                return false;
            }

            @Override
            public boolean setFps(int fps) {
                fpsRef.set(fps);
                return true;
            }
        };

        BridgeControl.Result result =
                BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY FPS 120\n", display);
        assertEquals("ORCHESTELLA/OK display\n", result.wireResponse(), "display fps ok response");
        assertTrue(result.accepted(), "display fps must be accepted");
        assertEquals(120, fpsRef.get(), "fps value");
    }

    private static void dispatchesDisplayLaunchCommandSuccessfully() {
        AtomicBoolean launchCalled = new AtomicBoolean(false);
        BridgeControl.Display display = new BridgeControl.Display() {
            @Override
            public boolean setMode(int width, int height, double scale) {
                return false;
            }

            @Override
            public boolean resize(int width, int height) {
                return false;
            }

            @Override
            public boolean setFps(int fps) {
                return false;
            }

            @Override
            public boolean launchDesktop() {
                launchCalled.set(true);
                return true;
            }
        };

        BridgeControl.Result result =
                BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY LAUNCH\n", display);
        assertEquals("ORCHESTELLA/OK display\n", result.wireResponse(), "display launch ok response");
        assertTrue(result.accepted(), "display launch must be accepted");
        assertTrue(launchCalled.get(), "launchDesktop callback must be invoked");

        BridgeControl.Result crlfResult =
                BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY LAUNCH\r\n", display);
        assertTrue(crlfResult.accepted(), "display launch with CRLF must be accepted");
    }

    private static void handlesCrlfEndingsForDisplayCommands() {
        BridgeControl.Display display = new TestDisplay();
        BridgeControl.Result modeResult =
                BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY MODE 1920 1080 1.0\r\n", display);
        assertEquals("ORCHESTELLA/OK display\n", modeResult.wireResponse(), "mode crlf response");
        assertTrue(modeResult.accepted(), "mode crlf accepted");

        BridgeControl.Result resizeResult =
                BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY RESIZE 1920 1080\r\n", display);
        assertEquals("ORCHESTELLA/OK display\n", resizeResult.wireResponse(), "resize crlf response");
        assertTrue(resizeResult.accepted(), "resize crlf accepted");

        BridgeControl.Result fpsResult =
                BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY FPS 60\r\n", display);
        assertEquals("ORCHESTELLA/OK display\n", fpsResult.wireResponse(), "fps crlf response");
        assertTrue(fpsResult.accepted(), "fps crlf accepted");
    }

    private static void acceptsExactBoundaryValues() {
        BridgeControl.Display display = new TestDisplay();

        assertTrue(BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY RESIZE 100 100\n", display).accepted(),
                "min dimensions 100x100 accepted");
        assertTrue(BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY RESIZE 7680 7680\n", display).accepted(),
                "max dimensions 7680x7680 accepted");

        assertTrue(BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY MODE 1920 1080 0.1\n", display).accepted(),
                "min scale 0.1 accepted");
        assertTrue(BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY MODE 1920 1080 5.0\n", display).accepted(),
                "max scale 5.0 accepted");

        assertTrue(BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY FPS 1\n", display).accepted(),
                "min fps 1 accepted");
        assertTrue(BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY FPS 240\n", display).accepted(),
                "max fps 240 accepted");
    }

    private static void rejectsOutOfBoundsDimensions() {
        BridgeControl.Display display = new TestDisplay();

        for (String invalid : new String[] {
            "ORCHESTELLA/DISPLAY RESIZE 99 1080\n",
            "ORCHESTELLA/DISPLAY RESIZE 7681 1080\n",
            "ORCHESTELLA/DISPLAY RESIZE 1920 99\n",
            "ORCHESTELLA/DISPLAY RESIZE 1920 7681\n",
            "ORCHESTELLA/DISPLAY MODE 99 1080 1.0\n",
            "ORCHESTELLA/DISPLAY MODE 7681 1080 1.0\n",
            "ORCHESTELLA/DISPLAY MODE 1920 99 1.0\n",
            "ORCHESTELLA/DISPLAY MODE 1920 7681 1.0\n"
        }) {
            BridgeControl.Result result = BridgeControl.dispatchDisplay(invalid, display);
            assertEquals("ORCHESTELLA/ERROR malformed-command\n", result.wireResponse(),
                    "out of bounds dimension must be rejected: " + invalid.trim());
            assertFalse(result.accepted(), "out of bounds dimension must not be accepted");
        }
    }

    private static void rejectsOutOfBoundsScale() {
        BridgeControl.Display display = new TestDisplay();

        for (String invalid : new String[] {
            "ORCHESTELLA/DISPLAY MODE 1920 1080 0.09\n",
            "ORCHESTELLA/DISPLAY MODE 1920 1080 5.01\n",
            "ORCHESTELLA/DISPLAY MODE 1920 1080 -1.0\n",
            "ORCHESTELLA/DISPLAY MODE 1920 1080 NaN\n",
            "ORCHESTELLA/DISPLAY MODE 1920 1080 Infinity\n"
        }) {
            BridgeControl.Result result = BridgeControl.dispatchDisplay(invalid, display);
            assertEquals("ORCHESTELLA/ERROR malformed-command\n", result.wireResponse(),
                    "out of bounds scale must be rejected: " + invalid.trim());
            assertFalse(result.accepted(), "out of bounds scale must not be accepted");
        }
    }

    private static void rejectsOutOfBoundsFps() {
        BridgeControl.Display display = new TestDisplay();

        for (String invalid : new String[] {
            "ORCHESTELLA/DISPLAY FPS 0\n",
            "ORCHESTELLA/DISPLAY FPS 241\n",
            "ORCHESTELLA/DISPLAY FPS -60\n"
        }) {
            BridgeControl.Result result = BridgeControl.dispatchDisplay(invalid, display);
            assertEquals("ORCHESTELLA/ERROR malformed-command\n", result.wireResponse(),
                    "out of bounds fps must be rejected: " + invalid.trim());
            assertFalse(result.accepted(), "out of bounds fps must not be accepted");
        }
    }

    private static void rejectsMalformedDisplayCommands() {
        BridgeControl.Display display = new TestDisplay();

        String[] malformed = new String[] {
            "ORCHESTELLA/DISPLAY\n",
            "ORCHESTELLA/DISPLAY \n",
            "ORCHESTELLA/DISPLAY UNKNOWN 1 2\n",
            "ORCHESTELLA/DISPLAY MODE 1920 1080\n",
            "ORCHESTELLA/DISPLAY MODE 1920 1080 1.0 extra\n",
            "ORCHESTELLA/DISPLAY MODE 1920 abc 1.0\n",
            "ORCHESTELLA/DISPLAY RESIZE\n",
            "ORCHESTELLA/DISPLAY RESIZE 1920\n",
            "ORCHESTELLA/DISPLAY RESIZE 1920 1080 extra\n",
            "ORCHESTELLA/DISPLAY RESIZE abc 1080\n",
            "ORCHESTELLA/DISPLAY FPS\n",
            "ORCHESTELLA/DISPLAY FPS 60 extra\n",
            "ORCHESTELLA/DISPLAY FPS abc\n",
            "ORCHESTELLA/DISPLAY RESIZE " + "1".repeat(2100) + "\n"
        };

        for (String command : malformed) {
            BridgeControl.Result result = BridgeControl.dispatchDisplay(command, display);
            assertEquals("ORCHESTELLA/ERROR malformed-command\n", result.wireResponse(),
                    "malformed display command rejected: " + command.trim());
            assertFalse(result.accepted(), "malformed display command must not be accepted");
        }
    }

    private static void rejectsWhenDisplaySeamUnavailable() {
        // null display seam passed to dispatch without display adapter
        BridgeControl.Result result =
                BridgeControl.dispatch("ORCHESTELLA/DISPLAY RESIZE 1920 1080\n", (columns, rows) -> true);
        assertEquals("ORCHESTELLA/ERROR display-unavailable\n", result.wireResponse(),
                "display without display seam must return display-unavailable");
        assertFalse(result.accepted(), "display without seam must not be accepted");
    }

    private static void rejectsWhenDisplaySeamFails() {
        BridgeControl.Display failingDisplay = new BridgeControl.Display() {
            @Override
            public boolean setMode(int width, int height, double scale) {
                return false;
            }

            @Override
            public boolean resize(int width, int height) {
                return false;
            }

            @Override
            public boolean setFps(int fps) {
                return false;
            }
        };

        BridgeControl.Result modeResult =
                BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY MODE 1920 1080 1.0\n", failingDisplay);
        assertEquals("ORCHESTELLA/ERROR display-unavailable\n", modeResult.wireResponse(), "mode failing response");
        assertFalse(modeResult.accepted(), "failing mode not accepted");

        BridgeControl.Result resizeResult =
                BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY RESIZE 1920 1080\n", failingDisplay);
        assertEquals("ORCHESTELLA/ERROR display-unavailable\n", resizeResult.wireResponse(), "resize failing response");
        assertFalse(resizeResult.accepted(), "failing resize not accepted");

        BridgeControl.Result fpsResult =
                BridgeControl.dispatchDisplay("ORCHESTELLA/DISPLAY FPS 60\n", failingDisplay);
        assertEquals("ORCHESTELLA/ERROR display-unavailable\n", fpsResult.wireResponse(), "fps failing response");
        assertFalse(fpsResult.accepted(), "failing fps not accepted");
    }

    private static void dispatchesDisplayThroughCombinedSeams() {
        AtomicInteger resizedW = new AtomicInteger();
        AtomicInteger resizedH = new AtomicInteger();

        BridgeControl.Terminal terminal = (cols, rws) -> true;
        BridgeControl.Power power = action -> true;
        BridgeControl.Notification notification = (title, msg) -> true;
        BridgeControl.UrlOpener urlOpener = url -> true;
        BridgeControl.Display display = new BridgeControl.Display() {
            @Override
            public boolean setMode(int width, int height, double scale) {
                return true;
            }

            @Override
            public boolean resize(int width, int height) {
                resizedW.set(width);
                resizedH.set(height);
                return true;
            }

            @Override
            public boolean setFps(int fps) {
                return true;
            }
        };

        BridgeControl.Seams seams = new BridgeControl.Seams(terminal, power, notification, urlOpener, display);
        BridgeControl.Result result =
                BridgeControl.dispatch("ORCHESTELLA/DISPLAY RESIZE 2560 1440\n", seams);
        assertEquals("ORCHESTELLA/OK display\n", result.wireResponse(), "combined display response");
        assertTrue(result.accepted(), "combined display accepted");
        assertEquals(2560, resizedW.get(), "dispatched width");
        assertEquals(1440, resizedH.get(), "dispatched height");
    }

    private static void bridgeClientSendsAndClassifiesDisplayCommands() throws IOException {
        ByteArrayOutputStream toAgent = new ByteArrayOutputStream();
        ByteArrayInputStream fromAgent = new ByteArrayInputStream(
                ("ORCHESTELLA/OK display\n"
                        + "ORCHESTELLA/OK display\n"
                        + "ORCHESTELLA/OK display\n"
                        + "ORCHESTELLA/OK display\n"
                        + "ORCHESTELLA/ERROR display-unavailable\n").getBytes(StandardCharsets.US_ASCII));

        BridgeClient client = new BridgeClient(fromAgent, toAgent);

        BridgeClient.Result modeResult = client.displayMode(1920, 1080, 1.5);
        assertTrue(modeResult.accepted(), "client displayMode accepted");
        assertEquals("ok-display", modeResult.responseKind(), "ok-display kind");

        BridgeClient.Result resizeResult = client.displayResize(1280, 720);
        assertTrue(resizeResult.accepted(), "client displayResize accepted");
        assertEquals("ok-display", resizeResult.responseKind(), "ok-display kind");

        BridgeClient.Result fpsResult = client.displayFps(60);
        assertTrue(fpsResult.accepted(), "client displayFps accepted");
        assertEquals("ok-display", fpsResult.responseKind(), "ok-display kind");

        BridgeClient.Result launchResult = client.desktopLaunch();
        assertTrue(launchResult.accepted(), "client desktopLaunch accepted");
        assertEquals("ok-display", launchResult.responseKind(), "ok-display kind");

        BridgeClient.Result failResult = client.displayFps(144);
        assertFalse(failResult.accepted(), "client displayFps fail rejected");
        assertEquals("display-unavailable", failResult.responseKind(), "display-unavailable kind");

        String outbound = toAgent.toString(StandardCharsets.US_ASCII);
        assertTrue(outbound.contains("ORCHESTELLA/DISPLAY MODE 1920 1080 1.5\n"), "contains mode wire command");
        assertTrue(outbound.contains("ORCHESTELLA/DISPLAY RESIZE 1280 720\n"), "contains resize wire command");
        assertTrue(outbound.contains("ORCHESTELLA/DISPLAY FPS 60\n"), "contains fps wire command");
        assertTrue(outbound.contains("ORCHESTELLA/DISPLAY LAUNCH\n"), "contains launch wire command");
    }

    private static final class TestDisplay implements BridgeControl.Display {
        @Override
        public boolean setMode(int width, int height, double scale) {
            return true;
        }

        @Override
        public boolean resize(int width, int height) {
            return true;
        }

        @Override
        public boolean setFps(int fps) {
            return true;
        }
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
