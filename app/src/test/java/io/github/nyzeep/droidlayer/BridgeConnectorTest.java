package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.droidlayer.runtime.contract.BridgeControl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class BridgeConnectorTest {
    @Test
    public void servesControlLoopDispatchingNotifyAndOpenUrlCommands() throws Exception {
        String inputScript =
                "DROIDLAYER/NOTIFY Build%20Done All%20tests%20passed\n"
                + "DROIDLAYER/OPEN-URL https://droidlayer.github.io\n";

        ByteArrayInputStream in = new ByteArrayInputStream(inputScript.getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        AtomicReference<String> notifyTitle = new AtomicReference<>();
        AtomicReference<String> notifyMsg = new AtomicReference<>();
        AtomicReference<String> eventTitle = new AtomicReference<>();
        AtomicReference<String> eventMsg = new AtomicReference<>();
        AtomicReference<String> openUrl = new AtomicReference<>();
        AtomicReference<String> eventUrl = new AtomicReference<>();

        BridgeControl.Notification notification = (title, message) -> {
            notifyTitle.set(title);
            notifyMsg.set(message);
            return true;
        };

        BridgeControl.UrlOpener urlOpener = url -> {
            openUrl.set(url);
            return true;
        };

        BridgeConnector.Events events = new BridgeConnector.Events() {
            @Override
            public void onReady() {}

            @Override
            public void onPong() {}

            @Override
            public void onResize(int columns, int rows) {}

            @Override
            public void onNotification(String title, String message) {
                eventTitle.set(title);
                eventMsg.set(message);
            }

            @Override
            public void onOpenUrl(String url) {
                eventUrl.set(url);
            }
        };

        BridgeConnector.serveControlLoop(in, out, events, notification, urlOpener);

        String wireOutput = out.toString(StandardCharsets.US_ASCII);
        assertEquals("DROIDLAYER/OK notify\nDROIDLAYER/OK open-url\n", wireOutput);
        assertEquals("Build Done", notifyTitle.get());
        assertEquals("All tests passed", notifyMsg.get());
        assertEquals("Build Done", eventTitle.get());
        assertEquals("All tests passed", eventMsg.get());
        assertEquals("https://droidlayer.github.io", openUrl.get());
        assertEquals("https://droidlayer.github.io", eventUrl.get());
    }

    @Test
    public void servesControlLoopRejectingDisallowedCommands() throws Exception {
        String inputScript =
                "DROIDLAYER/OPEN-URL file:///etc/passwd\n"
                + "DROIDLAYER/NOTIFY\n";

        ByteArrayInputStream in = new ByteArrayInputStream(inputScript.getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        AtomicInteger notifyCalls = new AtomicInteger();
        AtomicInteger urlCalls = new AtomicInteger();

        BridgeControl.Notification notification = (title, message) -> {
            notifyCalls.incrementAndGet();
            return true;
        };
        BridgeControl.UrlOpener urlOpener = url -> {
            urlCalls.incrementAndGet();
            return true;
        };

        BridgeConnector.serveControlLoop(in, out, null, notification, urlOpener);

        String wireOutput = out.toString(StandardCharsets.US_ASCII);
        assertEquals("DROIDLAYER/ERROR malformed-command\nDROIDLAYER/ERROR malformed-command\n", wireOutput);
        assertEquals(0, notifyCalls.get());
        assertEquals(0, urlCalls.get());
    }
}
