package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.audio.AudioDriftCompensator;
import io.github.nyzeep.orchestella.audio.AudioFocusSeam;
import io.github.nyzeep.orchestella.audio.AudioSourceSeam;
import io.github.nyzeep.orchestella.audio.AudioTrackSeam;
import io.github.nyzeep.orchestella.audio.FallbackAudioSource;
import io.github.nyzeep.orchestella.audio.LocalSocketAudioSource;
import io.github.nyzeep.orchestella.audio.LowLatencyAudioPlayer;
import io.github.nyzeep.orchestella.clipboard.ClipboardBridgeManager;
import io.github.nyzeep.orchestella.runtime.contract.AudioStreamContract;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        String audioSocketName;

        @Override public String audioConsumerSocketName() {
            return audioSocketName;
        }

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
    public void foregroundAudioResumesPausedAndStartsIdleOrRecoverableError() {
        assertEquals(DesktopPeripheralController.AudioForegroundAction.RESUME,
                DesktopPeripheralController.foregroundAudioActionFor(AudioStreamContract.State.PAUSED));
        assertEquals(DesktopPeripheralController.AudioForegroundAction.START,
                DesktopPeripheralController.foregroundAudioActionFor(AudioStreamContract.State.IDLE));
        assertEquals(DesktopPeripheralController.AudioForegroundAction.START,
                DesktopPeripheralController.foregroundAudioActionFor(AudioStreamContract.State.ERROR));
    }

    @Test
    public void foregroundAudioLeavesOtherStatesAlone() {
        assertEquals(DesktopPeripheralController.AudioForegroundAction.NONE,
                DesktopPeripheralController.foregroundAudioActionFor(AudioStreamContract.State.RUNNING));
        assertEquals(DesktopPeripheralController.AudioForegroundAction.NONE,
                DesktopPeripheralController.foregroundAudioActionFor(AudioStreamContract.State.STOPPED));
        assertEquals(DesktopPeripheralController.AudioForegroundAction.NONE,
                DesktopPeripheralController.foregroundAudioActionFor(AudioStreamContract.State.STARTING));
        assertEquals(DesktopPeripheralController.AudioForegroundAction.NONE,
                DesktopPeripheralController.foregroundAudioActionFor(null));
    }

    @Test
    public void createAudioSourceUsesSessionEndpointAndRejectsMissingEndpointHonestly() {
        assertNull("null session must remain honestly silent",
                DesktopPeripheralController.createAudioSourceFor(null));

        FakeSession silentSession = new FakeSession();
        assertNull("session without audioConsumerSocketName (e.g. AVF/UML) must stay silent",
                DesktopPeripheralController.createAudioSourceFor(silentSession));
        silentSession.audioSocketName = "   ";
        assertNull("blank audioConsumerSocketName must stay silent",
                DesktopPeripheralController.createAudioSourceFor(silentSession));

        FakeSession qemuSession = new FakeSession();
        qemuSession.audioSocketName = "orchestella-audio-0f1e2d3c4b5a-gen1";
        AudioSourceSeam source = DesktopPeripheralController.createAudioSourceFor(qemuSession);
        assertTrue(source instanceof FallbackAudioSource);
        FallbackAudioSource fallback = (FallbackAudioSource) source;
        assertTrue(fallback.getPrimary() instanceof LocalSocketAudioSource);
        assertNull("per-instance local socket must not fall back to shared TCP 9104",
                fallback.getSecondary());
        assertEquals("orchestella-audio-0f1e2d3c4b5a-gen1",
                ((LocalSocketAudioSource) fallback.getPrimary()).getSocketName());
    }

    @Test
    public void qemuSessionExposesUniquePerInstanceAndGenerationAudioSocketName() {
        String s1 = QemuInstanceLauncher.audioConsumerSocketName(
                "0f1e2d3c-4b5a-6978-8796-aabbccddeeff", "gen-aaa11111");
        String s2 = QemuInstanceLauncher.audioConsumerSocketName(
                "11223344-5566-7788-99aa-bbccddeeff00", "gen-aaa11111");
        String s1Restart = QemuInstanceLauncher.audioConsumerSocketName(
                "0f1e2d3c-4b5a-6978-8796-aabbccddeeff", "gen-bbb22222");

        assertNotEquals(AudioHostRelay.DEFAULT_CONSUMER_SOCKET_NAME, s1);
        assertNotEquals(s1, s2);
        assertNotEquals(s1, s1Restart);
        assertTrue(s1.startsWith("orchestella-audio-"));

        QemuProcessLauncher.ProcessHandle handle = new QemuProcessLauncher.ProcessHandle() {
            @Override public int waitFor() { return 0; }
            @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
            @Override public void destroy() { }
            @Override public void destroyForcibly() { }
            @Override public boolean isAlive() { return false; }
            @Override public long pid() { return -1L; }
        };
        QemuInstanceLauncher.Session session = new QemuInstanceLauncher.Session(
                "ctrl", "term", handle, "0f1e2d3c-4b5a-6978-8796-aabbccddeeff");
        assertNull(session.audioConsumerSocketName());
        session.attachAudioRelay(null, s1);
        assertEquals(s1, session.audioConsumerSocketName());
    }

    @Test(timeout = 10_000)
    public void onForegroundRecoversPlayerFromErrorAndStreamsPcm() throws Exception {
        byte[] pcm = new byte[8192];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) ((i % 120) + 1);
        }
        AtomicInteger openCalls = new AtomicInteger();
        AtomicInteger resetCalls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<CountDownLatch> currentGate =
                new java.util.concurrent.atomic.AtomicReference<>();
        AudioSourceSeam recoverableSource = new AudioSourceSeam() {
            @Override
            public InputStream openStream() throws IOException {
                if (openCalls.incrementAndGet() == 1) {
                    throw new IOException("early desktop open before audio relay ready");
                }
                CountDownLatch gate = new CountDownLatch(1);
                currentGate.set(gate);
                return new InputStream() {
                    private int offset = 0;

                    @Override
                    public int read() throws IOException {
                        byte[] one = new byte[1];
                        int n = read(one, 0, 1);
                        return n <= 0 ? -1 : (one[0] & 0xFF);
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        if (offset < pcm.length) {
                            int count = Math.min(len, pcm.length - offset);
                            System.arraycopy(pcm, offset, b, off, count);
                            offset += count;
                            return count;
                        }
                        try {
                            gate.await();
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        return -1;
                    }

                    @Override
                    public void close() {
                        gate.countDown();
                    }
                };
            }

            @Override
            public void resetConnection() {
                resetCalls.incrementAndGet();
                CountDownLatch gate = currentGate.getAndSet(null);
                if (gate != null) {
                    gate.countDown();
                }
            }

            @Override public boolean isConnected() { return openCalls.get() > 1; }
            @Override public void close() {
                CountDownLatch gate = currentGate.getAndSet(null);
                if (gate != null) {
                    gate.countDown();
                }
            }
        };
        AtomicInteger bytesWritten = new AtomicInteger();
        AudioTrackSeam track = new AudioTrackSeam() {
            private int playState = PLAYSTATE_STOPPED;
            @Override public int getState() { return STATE_INITIALIZED; }
            @Override public int getPlayState() { return playState; }
            @Override public void play() { playState = PLAYSTATE_PLAYING; }
            @Override public void pause() { playState = PLAYSTATE_PAUSED; }
            @Override public void stop() { playState = PLAYSTATE_STOPPED; }
            @Override public void flush() { }
            @Override public void release() { playState = PLAYSTATE_STOPPED; }
            @Override public int write(byte[] data, int off, int len) { return write(data, off, len, WRITE_BLOCKING); }
            @Override public int write(byte[] data, int off, int len, int mode) {
                bytesWritten.addAndGet(len);
                return len;
            }
            @Override public int setVolume(float gain) { return SUCCESS; }
            @Override public int getUnderrunCount() { return 0; }
            @Override public int getBufferSizeInFrames() { return 2048; }
        };
        AudioFocusSeam focus = new AudioFocusSeam() {
            @Override public int requestFocus(FocusChangeListener l) { return REQUEST_GRANTED; }
            @Override public int abandonFocus(FocusChangeListener l) { return REQUEST_GRANTED; }
        };
        LowLatencyAudioPlayer player = new LowLatencyAudioPlayer(
                recoverableSource, track, new AudioDriftCompensator(), focus);
        CountDownLatch enteredError = new CountDownLatch(1);
        player.addStateListener((oldState, newState) -> {
            if (newState == AudioStreamContract.State.ERROR) {
                enteredError.countDown();
            }
        });

        DesktopPeripheralController controller = DesktopPeripheralController.forAudioTesting(player);
        try {
            controller.onForeground();
            assertTrue("initial failed open must land player in ERROR",
                    enteredError.await(2, TimeUnit.SECONDS));
            assertEquals(AudioStreamContract.State.ERROR, player.state());

            controller.onForeground();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && bytesWritten.get() < pcm.length) {
                Thread.sleep(20);
            }
            assertTrue("onForeground from ERROR must recover and pump PCM bytes, got=" + bytesWritten.get(),
                    bytesWritten.get() >= pcm.length);
            assertTrue("recovery start must reset source connection", resetCalls.get() >= 1);
            assertEquals(AudioStreamContract.State.RUNNING, player.state());
        } finally {
            controller.onActivityDestroyed();
        }
    }

    @Test(timeout = 10_000)
    public void onForegroundRecoversPlayerFromCleanEof() throws Exception {
        byte[] pcm = new byte[8192];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) ((i % 100) + 1);
        }
        AtomicInteger openCalls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<CountDownLatch> currentGate =
                new java.util.concurrent.atomic.AtomicReference<>();
        AudioSourceSeam eofOnceSource = new AudioSourceSeam() {
            @Override
            public InputStream openStream() {
                if (openCalls.incrementAndGet() == 1) {
                    // Immediate clean EOF, simulating displaced/closed relay socket.
                    return new ByteArrayInputStream(new byte[0]);
                }
                CountDownLatch gate = new CountDownLatch(1);
                currentGate.set(gate);
                return new InputStream() {
                    private int offset = 0;

                    @Override
                    public int read() {
                        byte[] one = new byte[1];
                        int n = read(one, 0, 1);
                        return n <= 0 ? -1 : (one[0] & 0xFF);
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        if (offset < pcm.length) {
                            int count = Math.min(len, pcm.length - offset);
                            System.arraycopy(pcm, offset, b, off, count);
                            offset += count;
                            return count;
                        }
                        try {
                            gate.await();
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        return -1;
                    }

                    @Override
                    public void close() {
                        gate.countDown();
                    }
                };
            }

            @Override public void resetConnection() {
                CountDownLatch gate = currentGate.getAndSet(null);
                if (gate != null) {
                    gate.countDown();
                }
            }
            @Override public boolean isConnected() { return openCalls.get() > 1; }
            @Override public void close() { resetConnection(); }
        };
        AtomicInteger bytesWritten = new AtomicInteger();
        AudioTrackSeam track = new AudioTrackSeam() {
            private int playState = PLAYSTATE_STOPPED;
            @Override public int getState() { return STATE_INITIALIZED; }
            @Override public int getPlayState() { return playState; }
            @Override public void play() { playState = PLAYSTATE_PLAYING; }
            @Override public void pause() { playState = PLAYSTATE_PAUSED; }
            @Override public void stop() { playState = PLAYSTATE_STOPPED; }
            @Override public void flush() { }
            @Override public void release() { playState = PLAYSTATE_STOPPED; }
            @Override public int write(byte[] data, int off, int len) { return write(data, off, len, WRITE_BLOCKING); }
            @Override public int write(byte[] data, int off, int len, int mode) {
                bytesWritten.addAndGet(len);
                return len;
            }
            @Override public int setVolume(float gain) { return SUCCESS; }
            @Override public int getUnderrunCount() { return 0; }
            @Override public int getBufferSizeInFrames() { return 2048; }
        };
        AudioFocusSeam focus = new AudioFocusSeam() {
            @Override public int requestFocus(FocusChangeListener l) { return REQUEST_GRANTED; }
            @Override public int abandonFocus(FocusChangeListener l) { return REQUEST_GRANTED; }
        };
        LowLatencyAudioPlayer player = new LowLatencyAudioPlayer(
                eofOnceSource, track, new AudioDriftCompensator(), focus);
        CountDownLatch enteredError = new CountDownLatch(1);
        player.addStateListener((oldState, newState) -> {
            if (newState == AudioStreamContract.State.ERROR) {
                enteredError.countDown();
            }
        });

        DesktopPeripheralController controller = DesktopPeripheralController.forAudioTesting(player);
        try {
            controller.onForeground();
            assertTrue("unexpected clean EOF must move player into ERROR",
                    enteredError.await(2, TimeUnit.SECONDS));

            controller.onForeground();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && bytesWritten.get() < pcm.length) {
                Thread.sleep(20);
            }
            assertTrue("onForeground after EOF must reattach and stream PCM, got=" + bytesWritten.get(),
                    bytesWritten.get() >= pcm.length);
            assertEquals(AudioStreamContract.State.RUNNING, player.state());
        } finally {
            controller.onActivityDestroyed();
        }
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
