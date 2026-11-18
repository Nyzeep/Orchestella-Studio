package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.clipboard.ClipboardBridgeManager;
import io.github.nyzeep.orchestella.runtime.contract.AudioStreamContract;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.junit.Test;

/**
 * G-03: the peripheral pure logic split out of DesktopActivity — the audio
 * foreground decision, the clipboard fallback send chain and the bridge serve
 * gate — keeps its exact pre-split behavior.
 */
public final class DesktopPeripheralControllerTest {

    // ---------------------------------------------------------------- fakes

    /** ExecutionSession fake recording markBridgeServing; the rest is inert. */
    private static final class FakeSession implements ExecutionSession {
        int markBridgeServingCalls;
        boolean servingMarked;

        @Override public int waitFor() {
            return 0;
        }

        @Override public OutputStream terminalInput() {
            return new ByteArrayOutputStream();
        }

        @Override public InputStream terminalOutput() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override public OutputStream controlInput() {
            return new ByteArrayOutputStream();
        }

        @Override public InputStream controlOutput() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override public void close() {
        }

        @Override public boolean markBridgeServing() {
            markBridgeServingCalls++;
            if (servingMarked) return false;
            servingMarked = true;
            return true;
        }
    }

    /**
     * Control seam backed by a real scripted BridgeClient, so clipboardPut results
     * come from the production classifier instead of a stub.
     */
    private static final class ScriptedControlClient implements DesktopControlClient {
        private final BridgeClient client;

        ScriptedControlClient(String reply) {
            this.client = new BridgeClient(
                    stream("ORCHESTELLA/READY 1\n" + reply + "\n"),
                    new ByteArrayOutputStream());
        }

        private ScriptedControlClient() {
            this.client = new BridgeClient(stream(""), new ByteArrayOutputStream());
        }

        private static ByteArrayInputStream stream(String script) {
            return new ByteArrayInputStream(script.getBytes(StandardCharsets.US_ASCII));
        }

        /** No READY line: the next exchange runs straight into the transport's end. */
        static ScriptedControlClient exhaustedTransport() {
            return new ScriptedControlClient();
        }

        void makeReady() throws java.io.IOException {
            assertTrue(client.handshake(TOKEN).accepted());
        }

        @Override public boolean isReady() {
            return client.isReady();
        }

        @Override public void awaitReady(long timeoutMs) {
            throw new UnsupportedOperationException();
        }

        @Override public BridgeClient.Result desktopLaunch() {
            throw new UnsupportedOperationException();
        }

        @Override public BridgeClient.Result feature(String action, String profile) {
            throw new UnsupportedOperationException();
        }

        @Override public BridgeClient.Result displayMode(int width, int height, double scale) {
            throw new UnsupportedOperationException();
        }

        @Override public BridgeClient.Result confirmDesktopFrame(String generation) {
            throw new UnsupportedOperationException();
        }

        @Override public BridgeClient.Result clipboardPut(String text) throws java.io.IOException {
            return client.clipboardPut(text);
        }

        @Override public BridgeClient raw() {
            return client;
        }
    }

    private static final String TOKEN =
            "0123abcd" + "4567ef01" + "2345abcd" + "6789ef01"
                    + "0123abcd" + "4567ef01" + "2345abcd" + "6789ef01";

    // ---------------------------------------------------------------- audio decision

    @Test
    public void foregroundAudioResumesPausedAndStartsIdle() {
        assertEquals(DesktopPeripheralController.AudioForegroundAction.RESUME,
                DesktopPeripheralController.foregroundAudioActionFor(AudioStreamContract.State.PAUSED));
        assertEquals(DesktopPeripheralController.AudioForegroundAction.START,
                DesktopPeripheralController.foregroundAudioActionFor(AudioStreamContract.State.IDLE));
    }

    @Test
    public void foregroundAudioLeavesOtherStatesAlone() {
        assertEquals(DesktopPeripheralController.AudioForegroundAction.NONE,
                DesktopPeripheralController.foregroundAudioActionFor(AudioStreamContract.State.RUNNING));
        assertEquals(DesktopPeripheralController.AudioForegroundAction.NONE,
                DesktopPeripheralController.foregroundAudioActionFor(AudioStreamContract.State.STOPPED));
        assertEquals(DesktopPeripheralController.AudioForegroundAction.NONE,
                DesktopPeripheralController.foregroundAudioActionFor(null));
    }

    // ---------------------------------------------------------------- clipboard fallback send

    @Test
    public void fallbackSendWithoutSessionOrClientIsRejected() {
        assertFalse(DesktopPeripheralController.sendClipboardThrough(null, s -> null, "text"));
        FakeSession session = new FakeSession();
        Function<ExecutionSession, DesktopControlClient> nullClient = s -> null;
        assertFalse(DesktopPeripheralController.sendClipboardThrough(session, nullClient, "text"));
    }

    @Test
    public void fallbackSendPassesGuestAcceptanceThrough() throws Exception {
        ScriptedControlClient accepted = new ScriptedControlClient("ORCHESTELLA/OK clipboard");
        accepted.makeReady();
        FakeSession session = new FakeSession();
        assertTrue(DesktopPeripheralController.sendClipboardThrough(session, s -> accepted, "剪贴板内容"));
    }

    @Test
    public void fallbackSendReportsGuestRejectionAsFalse() throws Exception {
        ScriptedControlClient rejected = new ScriptedControlClient("ORCHESTELLA/ERROR busy");
        rejected.makeReady();
        FakeSession session = new FakeSession();
        assertFalse(DesktopPeripheralController.sendClipboardThrough(session, s -> rejected, "剪贴板内容"));
    }

    @Test
    public void fallbackSendSurvivesTransportFailure() {
        // Exhausted scripted stream: clipboardPut raises IOException, send stays false.
        ScriptedControlClient dead = ScriptedControlClient.exhaustedTransport();
        FakeSession session = new FakeSession();
        assertFalse(DesktopPeripheralController.sendClipboardThrough(session, s -> dead, "剪贴板内容"));
    }

    @Test
    public void fallbackSenderIsTheManagerSeamShape() {
        // The fallback lambda must satisfy ClipboardBridgeManager.RemoteClipboardSender.
        ClipboardBridgeManager.RemoteClipboardSender sender =
                text -> DesktopPeripheralController.sendClipboardThrough(null, s -> null, text);
        assertFalse(sender.sendClipboardPut("text"));
    }

    // ---------------------------------------------------------------- serve gate

    @Test
    public void serveGateRequiresTokenAndUnclaimedSession() {
        FakeSession session = new FakeSession();
        assertTrue(DesktopPeripheralController.shouldStartBridgeServe(session, "token"));
        assertFalse("second serve attempt must see the claimed marker",
                DesktopPeripheralController.shouldStartBridgeServe(session, "token"));
        assertEquals(2, session.markBridgeServingCalls);
    }

    @Test
    public void serveGateNeverMarksWithoutToken() {
        FakeSession session = new FakeSession();
        assertFalse(DesktopPeripheralController.shouldStartBridgeServe(session, null));
        assertEquals("markBridgeServing must stay untouched without a token",
                0, session.markBridgeServingCalls);
        assertFalse(DesktopPeripheralController.shouldStartBridgeServe(null, "token"));
        assertFalse(DesktopPeripheralController.shouldStartBridgeServe(null, null));
    }
}
