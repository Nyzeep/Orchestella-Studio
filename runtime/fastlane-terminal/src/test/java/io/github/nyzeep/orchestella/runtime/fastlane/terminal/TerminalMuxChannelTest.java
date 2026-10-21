package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public final class TerminalMuxChannelTest {

    @Test
    public void pumpOnceDispatchesAndWrites() throws Exception {
        ByteArrayOutputStream output = new TrackingOutputStream();
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalMux.MAX_SESSIONS,
                null,
                new ByteArrayInputStream(helloOkFrame()),
                output);

        channel.mux().sendHello(new Fln1Payloads.Hello(1, 1, ""));
        assertTrue(channel.pumpOnce());
        assertTrue(channel.mux().helloReceived());
        assertFalse(channel.pumpOnce());

        channel.mux().sendPing(7L);
        Fln1TerminalCodec guest = new Fln1TerminalCodec(TerminalMux.Role.GUEST);
        byte[] wire = output.toByteArray();
        int firstLength = 14 + ByteBuffer.wrap(wire, 10, 4).getInt();
        assertEquals(TerminalFrameType.HELLO, guest.decode(
                java.util.Arrays.copyOf(wire, firstLength)).type());
        TerminalFrame ping = guest.decode(java.util.Arrays.copyOfRange(
                wire, firstLength, wire.length));
        assertEquals(TerminalFrameType.PING, ping.type());
        assertEquals(7L, ByteBuffer.wrap(ping.payload()).getLong());

        channel.close();
        assertTrue(channel.isClosed());
        assertTrue(((TrackingOutputStream) output).closed);
    }

    @Test
    public void pumpAllHandlesMultipleFrames() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write(frame(Fln1TerminalCodec.OP_PING, 0, nonce(1)));
        input.write(frame(Fln1TerminalCodec.OP_PING, 0, nonce(2)));
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.GUEST,
                TerminalMux.MAX_SESSIONS,
                null,
                new ByteArrayInputStream(input.toByteArray()),
                output);

        assertEquals(2, channel.pumpAll());
        Fln1TerminalCodec host = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        byte[] wire = output.toByteArray();
        int firstLength = 14 + ByteBuffer.wrap(wire, 10, 4).getInt();
        TerminalFrame first = host.decode(java.util.Arrays.copyOf(wire, firstLength));
        assertEquals(TerminalFrameType.PONG, first.type());
        assertEquals(1L, ByteBuffer.wrap(first.payload()).getLong());
        TerminalFrame second = host.decode(java.util.Arrays.copyOfRange(
                wire, firstLength, wire.length));
        assertEquals(2L, ByteBuffer.wrap(second.payload()).getLong());
    }

    @Test
    public void pumpAllHandlesTenThousandFrames() throws Exception {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        for (int index = 0; index < 10_000; index++) {
            input.write(frame(Fln1TerminalCodec.OP_PING, 0, nonce(index)));
        }
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.GUEST,
                TerminalMux.MAX_SESSIONS,
                null,
                new ByteArrayInputStream(input.toByteArray()),
                new ByteArrayOutputStream());

        assertEquals(10_000, channel.pumpAll());
        assertEquals(10_000, channel.mux().metrics().framesIn());
        assertEquals(10_000, channel.mux().metrics().framesOut());
    }

    @Test
    public void transportReadFailureAbortsSessions() throws Exception {
        Recorder listener = new Recorder();
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("transport reset");
            }
        };
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalMux.MAX_SESSIONS,
                listener,
                broken,
                new ByteArrayOutputStream());
        channel.mux().sendHello(new Fln1Payloads.Hello(1, 1, ""));
        int id = channel.mux().openSession(80, 24, "sh");

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
                TerminalMux.MAX_SESSIONS,
                null,
                input,
                output);

        channel.close();
        assertTrue(input.closed);
        assertTrue(output.closed);
        channel.close();
    }

    @Test
    public void truncatedFrameIsRejected() {
        byte[] frame = frame(Fln1TerminalCodec.OP_PING, 0, nonce(1));
        byte[] truncated = java.util.Arrays.copyOf(frame, 10);
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.GUEST,
                TerminalMux.MAX_SESSIONS,
                null,
                new ByteArrayInputStream(truncated),
                new ByteArrayOutputStream());
        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class, channel::pumpOnce);
        assertEquals(TerminalErrorCode.TRUNCATED_FRAME, failure.code());
    }

    @Test
    public void malformedMagicAbortsEverySessionAsProtocolError() throws Exception {
        Recorder listener = new Recorder();
        byte[] bad = frame(Fln1TerminalCodec.OP_PING, 0, nonce(1));
        bad[0] = 'X';
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalMux.MAX_SESSIONS,
                listener,
                new ByteArrayInputStream(bad),
                new ByteArrayOutputStream());
        channel.mux().sendHello(new Fln1Payloads.Hello(1, 1, ""));
        int id = channel.mux().openSession(80, 24, "sh");

        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class, channel::pumpOnce);
        assertEquals(TerminalErrorCode.PROTOCOL_MAGIC, failure.code());
        assertEquals(TerminalCloseReason.PROTOCOL_ERROR, listener.reason);
        assertEquals(TerminalSessionState.CLOSED, channel.mux().state(id));
    }

    @Test
    public void closeSendsCloseForOpenSessions() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Fln1TerminalCodec guest = new Fln1TerminalCodec(TerminalMux.Role.GUEST);
        byte[] opened = frame(Fln1TerminalCodec.OP_PTY_OPENED, 1,
                Fln1Payloads.encodePtyOpened(4242, 262_144));
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalMux.MAX_SESSIONS,
                null,
                new ByteArrayInputStream(opened),
                output);
        channel.mux().sendHello(new Fln1Payloads.Hello(1, 1, ""));
        output.reset();
        channel.mux().openSession(80, 24, "sh");
        byte[] open = output.toByteArray();
        output.reset();
        assertEquals(1, guest.decode(open).sessionId());
        assertTrue(channel.pumpOnce());

        channel.close();
        TerminalFrame close = guest.decode(output.toByteArray());
        assertEquals(TerminalFrameType.CLOSE, close.type());
        assertEquals(1, close.sessionId());
        assertFalse(Fln1Payloads.parseClose(close.payload()));
    }

    @Test
    public void cleanEofAbortsSessions() throws Exception {
        Recorder listener = new Recorder();
        TerminalMuxChannel channel = TerminalMuxChannel.open(
                TerminalMux.Role.HOST,
                TerminalMux.MAX_SESSIONS,
                listener,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream());
        channel.mux().sendHello(new Fln1Payloads.Hello(1, 1, ""));
        int id = channel.mux().openSession(80, 24, "sh");

        assertFalse(channel.pumpOnce());
        assertEquals(TerminalSessionState.CLOSED, channel.mux().state(id));
        assertEquals(TerminalCloseReason.TRANSPORT_CLOSED, listener.reason);
    }

    private static byte[] helloOkFrame() {
        Fln1TerminalCodec guest = new Fln1TerminalCodec(TerminalMux.Role.GUEST);
        try {
            return guest.encode(new TerminalFrame(
                    TerminalFrameType.HELLO, 0,
                    Fln1Payloads.encodeHelloOk(new Fln1Payloads.HelloOk(
                            1, Fln1Payloads.CAP_TERMINAL, 65_536, 262_144))));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] frame(int opcode, int session, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(14 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.put(Fln1TerminalCodec.MAGIC).put((byte) 1).put((byte) opcode)
                .putInt(session).putInt(payload.length).put(payload);
        return buffer.array();
    }

    private static byte[] nonce(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array();
    }

    private static final class Recorder implements TerminalMux.Listener {
        private TerminalCloseReason reason;

        @Override
        public void onSessionClosed(int sessionId, TerminalCloseReason closeReason) {
            reason = closeReason;
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream() {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
