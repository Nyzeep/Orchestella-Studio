package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class TerminalMuxChannelTest {

    @Test
    public void pumpOnceDispatchesAndWrites() throws Exception {
        ByteArrayOutputStream output = new TrackingOutputStream();
        byte[] hello = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.HELLO,
                0,
                TerminalProtocol.encodeHello(new TerminalProtocol.Hello(
                        4096, 4096, TerminalProtocol.FEATURE_RESIZE))));
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalProtocol.MAX_SESSIONS,
                null,
                new ByteArrayInputStream(hello),
                output);

        assertTrue(channel.pumpOnce());
        assertTrue(channel.mux().helloReceived());
        assertFalse(channel.pumpOnce());

        channel.mux().sendPing(7L);
        TerminalFrame ping = TerminalProtocol.decode(output.toByteArray());
        assertEquals(TerminalFrameType.PING, ping.type());
        assertEquals(7L, TerminalProtocol.parsePing(ping.payload()));

        channel.close();
        assertTrue(channel.isClosed());
        assertTrue(((TrackingOutputStream) output).closed);
    }

    @Test
    public void pumpAllHandlesMultipleFrames() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        TerminalProtocol.write(input, new TerminalFrame(
                TerminalFrameType.PING, 0, TerminalProtocol.encodePing(1L)));
        TerminalProtocol.write(input, new TerminalFrame(
                TerminalFrameType.PING, 0, TerminalProtocol.encodePing(2L)));
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalProtocol.MAX_SESSIONS,
                null,
                new ByteArrayInputStream(input.toByteArray()),
                output);

        assertEquals(2, channel.pumpAll());
        byte[] wire = output.toByteArray();
        ByteArrayInputStream responses = new ByteArrayInputStream(wire);
        assertEquals(1L, TerminalProtocol.parsePing(
                TerminalProtocol.read(responses).payload()));
        assertEquals(2L, TerminalProtocol.parsePing(
                TerminalProtocol.read(responses).payload()));
    }

    @Test
    public void pumpAllHandlesTenThousandFrames() throws Exception {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        for (int i = 0; i < 10_000; i++) {
            TerminalProtocol.write(input, new TerminalFrame(
                    TerminalFrameType.PING, 0, TerminalProtocol.encodePing(i)));
        }
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalProtocol.MAX_SESSIONS,
                null,
                new ByteArrayInputStream(input.toByteArray()),
                new ByteArrayOutputStream());

        assertEquals(10_000, channel.pumpAll());
        assertEquals(10_000, channel.mux().metrics().framesIn());
        assertEquals(10_000, channel.mux().metrics().framesOut());
    }

    @Test
    public void transportReadFailureAbortsSessions() throws Exception {
        RecordingListener listener = new RecordingListener();
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("transport reset");
            }
        };
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalProtocol.MAX_SESSIONS,
                listener,
                broken,
                new ByteArrayOutputStream());
        int id = channel.mux().openSession(80, 24, "main", 1024);

        assertThrows(IOException.class, channel::pumpOnce);
        assertEquals(TerminalSessionState.CLOSED, channel.mux().state(id));
        assertEquals(TerminalCloseReason.TRANSPORT_CLOSED, listener.reason);
    }

    @Test
    public void closeClosesBothStreams() throws Exception {
        TrackingInputStream input = new TrackingInputStream();
        TrackingOutputStream output = new TrackingOutputStream();
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalProtocol.MAX_SESSIONS,
                null,
                input,
                output);

        channel.close();
        assertTrue(input.closed);
        assertTrue(output.closed);
    }

    @Test
    public void truncatedFrameIsRejected() {
        byte[] frame = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.CLOSE, 1, new byte[0]));
        byte[] truncated = new byte[10];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalProtocol.MAX_SESSIONS,
                null,
                new ByteArrayInputStream(truncated),
                new ByteArrayOutputStream());
        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class, channel::pumpOnce);
        assertEquals(TerminalErrorCode.TRUNCATED_FRAME, failure.code());
    }

    @Test
    public void closeSendsCloseForOpenSessions() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalProtocol.MAX_SESSIONS,
                null,
                new ByteArrayInputStream(new byte[0]),
                output);
        int id = channel.mux().openSession(80, 24, "main", 1024);
        output.reset();

        channel.close();
        TerminalFrame close = TerminalProtocol.decode(output.toByteArray());
        assertEquals(TerminalFrameType.CLOSE, close.type());
        assertEquals(id, close.sessionId());
        assertEquals(TerminalSessionState.CLOSED, channel.mux().state(id));
    }

    @Test
    public void vmStoppedDoesNotWriteFrames() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalProtocol.MAX_SESSIONS,
                null,
                new ByteArrayInputStream(new byte[0]),
                output);
        int id = channel.mux().openSession(80, 24, "main", 1024);
        output.reset();

        channel.vmStopped();
        assertEquals(0, output.size());
        assertEquals(TerminalSessionState.CLOSED, channel.mux().state(id));
        assertTrue(channel.isClosed());
    }

    @Test
    public void closeIsIdempotent() throws Exception {
        ByteArrayOutputStream output = new TrackingOutputStream();
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalProtocol.MAX_SESSIONS,
                null,
                new ByteArrayInputStream(new byte[0]),
                output);
        channel.close();
        channel.close();
        assertTrue(channel.isClosed());
    }

    @Test
    public void cleanEofAbortsSessionsWithTransportClosed() throws Exception {
        RecordingListener listener = new RecordingListener();
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalProtocol.MAX_SESSIONS,
                listener,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream());
        int id = channel.mux().openSession(80, 24, "main", 1024);

        assertFalse(channel.pumpOnce());
        assertEquals(TerminalSessionState.CLOSED, channel.mux().state(id));
        assertEquals(TerminalCloseReason.TRANSPORT_CLOSED, listener.reason);
    }

    @Test
    public void malformedFrameAbortsSessionsWithProtocolError() throws Exception {
        RecordingListener listener = new RecordingListener();
        byte[] bad = new byte[TerminalProtocol.HEADER_BYTES];
        bad[4] = 1;
        bad[5] = 6;
        bad[9] = 1;
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalProtocol.MAX_SESSIONS,
                listener,
                new ByteArrayInputStream(bad),
                new ByteArrayOutputStream());
        int id = channel.mux().openSession(80, 24, "main", 1024);

        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class, channel::pumpOnce);
        assertEquals(TerminalErrorCode.PROTOCOL_MAGIC, failure.code());
        assertEquals(TerminalSessionState.CLOSED, channel.mux().state(id));
        assertEquals(TerminalCloseReason.PROTOCOL_ERROR, listener.reason);
    }

    private static final class RecordingListener implements TerminalMux.Listener {
        private TerminalCloseReason reason;

        @Override
        public void onSessionClosed(int sessionId, TerminalCloseReason value) {
            reason = value;
        }
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream() {
            super(new byte[0]);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
