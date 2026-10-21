package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public final class Fln1TerminalCodecTest {
    private static final Fln1TerminalCodec HOST =
            new Fln1TerminalCodec(TerminalMux.Role.HOST);
    private static final Fln1TerminalCodec GUEST =
            new Fln1TerminalCodec(TerminalMux.Role.GUEST);

    @Test
    public void rejectsBadMagic() {
        byte[] frame = helloFrame();
        frame[0] = 'X';
        assertCode(TerminalErrorCode.PROTOCOL_MAGIC, () -> HOST.decode(frame));
    }

    @Test
    public void rejectsUnsupportedVersion() {
        byte[] frame = helloFrame();
        frame[4] = 2;
        assertCode(TerminalErrorCode.PROTOCOL_VERSION, () -> HOST.decode(frame));
    }

    @Test
    public void rejectsUnknownOpcode() {
        byte[] frame = header(0x11, 0, 0);
        assertCode(TerminalErrorCode.UNKNOWN_TYPE, () -> HOST.decode(frame));
    }

    @Test
    public void rejectsOversizedLength() {
        byte[] frame = header(Fln1TerminalCodec.OP_PING, 0, 65_537);
        assertCode(TerminalErrorCode.FRAME_TOO_LARGE, () -> HOST.decode(frame));
    }

    @Test
    public void rejectsNegativeLength() {
        byte[] frame = header(Fln1TerminalCodec.OP_PING, 0, -1);
        assertCode(TerminalErrorCode.PROTOCOL_LENGTH, () -> HOST.decode(frame));
    }

    @Test
    public void rejectsTruncatedHeaderAndPayload() {
        byte[] frame = helloFrame();
        assertCode(TerminalErrorCode.TRUNCATED_FRAME,
                () -> HOST.decode(Arrays.copyOf(frame, 13)));
        assertCode(TerminalErrorCode.PROTOCOL_LENGTH,
                () -> HOST.decode(Arrays.copyOf(frame, frame.length - 1)));
    }

    @Test
    public void rejectsTrailingBytesInSingleFrameDecode() {
        byte[] frame = helloFrame();
        byte[] trailing = Arrays.copyOf(frame, frame.length + 1);
        assertCode(TerminalErrorCode.PROTOCOL_LENGTH, () -> HOST.decode(trailing));
    }

    @Test
    public void rejectsEmptyAndOversizedData() throws Exception {
        byte[] unmapped = frame(Fln1TerminalCodec.OP_PTY_DATA, 1, new byte[] {1});
        assertCode(TerminalErrorCode.UNKNOWN_SESSION, () -> HOST.decode(unmapped));

        Fln1TerminalCodec host = primedHost();
        byte[] zero = frame(Fln1TerminalCodec.OP_PTY_DATA, 1, new byte[0]);
        assertCode(TerminalErrorCode.INVALID_PAYLOAD, () -> host.decode(zero));
        byte[] tooLarge = frame(
                Fln1TerminalCodec.OP_PTY_DATA, 1, new byte[Fln1Payloads.MAX_PTY_DATA + 1]);
        assertCode(TerminalErrorCode.INVALID_PAYLOAD, () -> host.decode(tooLarge));
    }

    @Test
    public void rejectsFramesForAnUnmappedWireSession() {
        byte[] data = frame(Fln1TerminalCodec.OP_PTY_DATA, 9, new byte[] {1});
        assertCode(TerminalErrorCode.UNKNOWN_SESSION, () -> HOST.decode(data));
    }

    @Test
    public void mapsHostSessionToPayloadAllocatedWireId() throws Exception {
        Fln1TerminalCodec host = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        byte[] open = host.encode(new TerminalFrame(
                TerminalFrameType.OPEN_PTY, 42,
                Fln1Payloads.encodeOpenPty(new Fln1Payloads.OpenPty(
                        80, 24, new String[] {"sh"}))));
        assertEquals(0, wireSession(open));

        byte[] opened = frame(
                Fln1TerminalCodec.OP_PTY_OPENED, 5,
                Fln1Payloads.encodePtyOpened(999, 262_144));
        TerminalFrame ack = host.decode(opened);
        assertEquals(42, ack.sessionId());

        byte[] data = host.encode(new TerminalFrame(
                TerminalFrameType.DATA, 42, new byte[] {7}));
        assertEquals(5, wireSession(data));
    }

    @Test
    public void guestAllocatesDistinctWireIds() throws Exception {
        Fln1TerminalCodec guest = new Fln1TerminalCodec(TerminalMux.Role.GUEST);
        byte[] payload = Fln1Payloads.encodeOpenPty(
                new Fln1Payloads.OpenPty(80, 24, new String[] {"sh"}));
        assertEquals(1, guest.decode(frame(Fln1TerminalCodec.OP_OPEN_PTY, 0, payload))
                .sessionId());
        assertEquals(2, guest.decode(frame(Fln1TerminalCodec.OP_OPEN_PTY, 0, payload))
                .sessionId());
    }

    @Test
    public void defersEofUntilExitArrives() throws Exception {
        Fln1TerminalCodec host = primedHost();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(frame(Fln1TerminalCodec.OP_EOF, 1, new byte[0]));
        stream.write(frame(Fln1TerminalCodec.OP_EXIT, 1,
                Fln1Payloads.encodeExit(new Fln1Payloads.Exit(3, 0))));

        TerminalFrame exit = host.read(new ByteArrayInputStream(stream.toByteArray()));
        assertEquals(TerminalFrameType.EXIT, exit.type());
        assertEquals(3, Fln1Payloads.parseExit(exit.payload()).exitCode());
        assertNull(host.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void emitsEofWithUnknownStatusWhenTransportEndsAfterEof() throws Exception {
        Fln1TerminalCodec host = primedHost();
        byte[] stream = frame(Fln1TerminalCodec.OP_EOF, 1, new byte[0]);
        TerminalFrame eof = host.read(new ByteArrayInputStream(stream));
        assertEquals(TerminalFrameType.EOF, eof.type());
        assertNull(host.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void hostRejectsGuestOnlyAndReplyOpcodes() {
        assertCode(TerminalErrorCode.UNKNOWN_TYPE, () -> HOST.decode(
                frame(Fln1TerminalCodec.OP_WINDOW, 1, Fln1Payloads.encodeWindow(1024))));
        assertCode(TerminalErrorCode.UNKNOWN_TYPE, () -> HOST.decode(
                frame(Fln1TerminalCodec.OP_OPEN_PTY, 1, new byte[] {0, 80, 0, 24, 1})));
        assertCode(TerminalErrorCode.UNKNOWN_TYPE, () -> HOST.decode(
                frame(Fln1TerminalCodec.OP_HELLO, 0, new byte[5])));
    }

    @Test
    public void hostCannotEncodeErrorFrames() {
        assertCode(TerminalErrorCode.WRONG_ROLE, () -> HOST.encode(new TerminalFrame(
                TerminalFrameType.ERROR, 0,
                Fln1Payloads.encodeError(new Fln1Payloads.Error(1, "x")))));
    }

    @Test
    public void decodesErrorPayloadAndMapsCanonicalCodes() throws Exception {
        Fln1TerminalCodec guest = new Fln1TerminalCodec(TerminalMux.Role.GUEST);
        byte[] error = guest.encode(new TerminalFrame(
                TerminalFrameType.ERROR, 0,
                Fln1Payloads.encodeError(new Fln1Payloads.Error(3, "bad token"))));
        TerminalFrame decoded = HOST.decode(error);
        assertEquals(TerminalFrameType.ERROR, decoded.type());
        Fln1Payloads.Error payload = Fln1Payloads.parseError(decoded.payload());
        assertEquals(3, payload.code());
        assertEquals("bad token", payload.message());
        assertEquals(TerminalErrorCode.PROTOCOL_VERSION,
                Fln1TerminalCodec.hostCode(3));
        assertEquals(1, Fln1TerminalCodec.wireCode(TerminalErrorCode.INVALID_PAYLOAD));
        assertEquals(6, Fln1TerminalCodec.wireCode(TerminalErrorCode.SESSION_LIMIT));
        assertEquals(14, Fln1TerminalCodec.wireCode(TerminalErrorCode.INTERNAL));
    }

    @Test
    public void connectionScopedErrorClearsThePendingOpenFifo() throws Exception {
        Fln1TerminalCodec host = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        host.encode(new TerminalFrame(
                TerminalFrameType.OPEN_PTY, 11,
                Fln1Payloads.encodeOpenPty(new Fln1Payloads.OpenPty(
                        80, 24, new String[] {"sh"}))));

        // The payload reports every OPEN_PTY failure as ERROR with session 0.
        host.decode(frame(Fln1TerminalCodec.OP_ERROR, 0,
                Fln1Payloads.encodeError(new Fln1Payloads.Error(6, "session limit"))));

        // The failed open must not consume the next payload-assigned wire id.
        assertCode(TerminalErrorCode.INVALID_PAYLOAD, () -> host.decode(
                frame(Fln1TerminalCodec.OP_PTY_OPENED, 9,
                        Fln1Payloads.encodePtyOpened(123, 262_144))));
    }

    @Test
    public void toleratesShortReadsForHeaderAndPayload() throws Exception {
        Fln1TerminalCodec host = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        host.encode(new TerminalFrame(
                TerminalFrameType.OPEN_PTY, 1,
                Fln1Payloads.encodeOpenPty(new Fln1Payloads.OpenPty(
                        80, 24, new String[] {"sh"}))));
        byte[] stream = frame(Fln1TerminalCodec.OP_PTY_OPENED, 3,
                Fln1Payloads.encodePtyOpened(7, 262_144));
        ByteArrayInputStream input = new ByteArrayInputStream(stream) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, 1));
            }
        };
        TerminalFrame opened = host.read(input);
        assertNotEquals(null, opened);
        assertEquals(1, opened.sessionId());
    }

    @Test
    public void validatesHelloAndHelloOkLayouts() throws Exception {
        assertCode(TerminalErrorCode.INVALID_PAYLOAD, () -> Fln1Payloads.parseHello(
                new byte[] {0, 1, 0, 1, 5, 1}));
        assertCode(TerminalErrorCode.INVALID_PAYLOAD, () -> Fln1Payloads.parseHelloOk(
                new byte[13]));
        Fln1Payloads.Hello hello = new Fln1Payloads.Hello(1, 1, "token");
        assertEquals(1, Fln1Payloads.parseHello(Fln1Payloads.encodeHello(hello)).minVersion());
        assertThrows(IllegalArgumentException.class,
                () -> new Fln1Payloads.Hello(1, 1, "x".repeat(256)));
    }

    @Test
    public void validatesPtyDataAndArgvLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new Fln1Payloads.OpenPty(80, 24, new String[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new Fln1Payloads.OpenPty(80, 24, new String[17]));
        assertThrows(IllegalArgumentException.class,
                () -> new Fln1Payloads.OpenPty(80, 24, new String[] {""}));
        assertThrows(IllegalArgumentException.class,
                () -> new Fln1Payloads.OpenPty(0, 24, new String[] {"sh"}));
        assertCode(TerminalErrorCode.INVALID_PAYLOAD, () -> {
            byte[] oversized = Fln1Payloads.encodeOpenPty(new Fln1Payloads.OpenPty(
                    80, 24, new String[] {"sh"}));
            byte[] bad = Arrays.copyOf(oversized, oversized.length - 1);
            Fln1Payloads.parseOpenPty(bad);
        });
    }

    @Test
    public void pingPayloadIsOpaqueAndBounded() throws Exception {
        assertThrows(TerminalProtocolException.class,
                () -> Fln1Payloads.ping(new byte[33]));
        assertArrayEquals(new byte[0], Fln1Payloads.ping(new byte[0]));
    }

    private static Fln1TerminalCodec primedHost() throws Exception {
        Fln1TerminalCodec host = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        host.encode(new TerminalFrame(
                TerminalFrameType.OPEN_PTY, 1,
                Fln1Payloads.encodeOpenPty(new Fln1Payloads.OpenPty(
                        80, 24, new String[] {"sh"}))));
        host.decode(frame(Fln1TerminalCodec.OP_PTY_OPENED, 1,
                Fln1Payloads.encodePtyOpened(7, 262_144)));
        return host;
    }

    private static byte[] helloFrame() {
        Fln1TerminalCodec host = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        try {
            return host.encode(new TerminalFrame(
                    TerminalFrameType.HELLO, 0,
                    Fln1Payloads.encodeHello(new Fln1Payloads.Hello(1, 1, "test"))));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] header(int opcode, int session, int length) {
        return ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
                .put(Fln1TerminalCodec.MAGIC)
                .put((byte) 1)
                .put((byte) opcode)
                .putInt(session)
                .putInt(length)
                .array();
    }

    private static byte[] frame(int opcode, int session, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(14 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.put(Fln1TerminalCodec.MAGIC).put((byte) 1).put((byte) opcode)
                .putInt(session).putInt(payload.length).put(payload);
        return buffer.array();
    }

    private static int wireSession(byte[] frame) {
        return ByteBuffer.wrap(frame, 6, 4).getInt();
    }

    private static void assertCode(TerminalErrorCode expected, ThrowingCall call) {
        TerminalProtocolException failure = assertThrows(TerminalProtocolException.class, call::run);
        assertEquals(expected, failure.code());
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    @SuppressWarnings("unused")
    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
