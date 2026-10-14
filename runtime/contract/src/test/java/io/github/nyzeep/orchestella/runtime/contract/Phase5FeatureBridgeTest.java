package io.github.nyzeep.orchestella.runtime.contract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class Phase5FeatureBridgeTest {
    @Test public void rejectedDesktopAndLegacyFeatureStillPermitPing() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        BridgeClient client = new BridgeClient(new ByteArrayInputStream(("ORCHESTELLA/READY 1\n"
                + "ORCHESTELLA/ERROR display-launch-unavailable\n"
                + "ORCHESTELLA/ERROR unsupported-command\n"
                + "ORCHESTELLA/PONG 1\n").getBytes(StandardCharsets.US_ASCII)), wire);
        assertTrue(client.handshake("a".repeat(64)).accepted());
        assertFalse(client.desktopLaunch().accepted());
        assertFalse(client.feature("PROBE", "desktop-labwc").accepted());
        assertTrue(client.ping().accepted());
        assertTrue(wire.toString(StandardCharsets.US_ASCII).contains("ORCHESTELLA/FEATURES 1 PROBE desktop-labwc\n"));
    }

    @Test public void requiresAuthenticationAndWhitelistedOperations() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        BridgeClient client = new BridgeClient(new ByteArrayInputStream(new byte[0]), wire);
        try { client.feature("INSTALL", "code-server"); fail(); } catch (IOException expected) { }
        try { client.feature("INSTALL", "code-server\nPOWER"); fail(); } catch (IllegalArgumentException expected) { }
        try { client.feature("EXEC", "code-server"); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(0, wire.size());
    }

    @Test public void displayBoundsMatchTheGuestWithoutWritingRejectedRequests() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        BridgeClient client = new BridgeClient(new ByteArrayInputStream(new byte[0]), wire);
        for (int[] size : new int[][]{{4097, 100}, {100, 4097}, {4096, 4096}, {99, 100}, {100, 99}}) {
            try { client.displayMode(size[0], size[1], 1.0); fail(); } catch (IllegalArgumentException expected) { }
            try { client.displayResize(size[0], size[1]); fail(); } catch (IllegalArgumentException expected) { }
        }
        assertEquals(0, wire.size());
    }

    @Test public void returnsBoundedJobStateWithoutInferringHealth() throws Exception {
        String json = "{\"state\":\"installing\",\"generation\":4}";
        BridgeClient client = new BridgeClient(new ByteArrayInputStream(("ORCHESTELLA/READY 1\n"
                + "ORCHESTELLA/OK features " + json + "\n").getBytes(StandardCharsets.US_ASCII)),
                new ByteArrayOutputStream());
        client.handshake("a".repeat(64));
        BridgeClient.Result result = client.feature("INSTALL", "code-server");
        assertTrue(result.accepted());
        assertEquals("ok-features", result.responseKind());
        assertEquals(json, result.payload());
    }
}
