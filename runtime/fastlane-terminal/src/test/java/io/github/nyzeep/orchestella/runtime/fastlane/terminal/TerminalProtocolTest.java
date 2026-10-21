package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import org.junit.Test;

public final class TerminalProtocolTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    public void helloHasExactStableWireFormat() {
        byte[] encoded = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.HELLO,
                0,
                TerminalProtocol.encodeHello(new TerminalProtocol.Hello(
                        TerminalProtocol.MAX_PAYLOAD_BYTES,
                        TerminalProtocol.DEFAULT_CREDIT_BYTES,
                        TerminalProtocol.FEATURE_RESIZE
                                | TerminalProtocol.FEATURE_SIGNAL
                                | TerminalProtocol.FEATURE_METRICS
                                | TerminalProtocol.FEATURE_EXIT_CODE))));
        assertEquals(
                "4f53464c0101000000000000000c00100000000400000000000f",
                HEX.formatHex(encoded));
    }

    @Test
    public void roundTripsEveryFrameType() throws Exception {
        TerminalMuxMetrics metrics = new TerminalMuxMetrics(1, 2, 3, 4, 5, 6, 7, 8);
        TerminalFrame[] frames = {
            new TerminalFrame(TerminalFrameType.HELLO, 0,
                    TerminalProtocol.encodeHello(new TerminalProtocol.Hello(
                            TerminalProtocol.MAX_PAYLOAD_BYTES,
                            TerminalProtocol.DEFAULT_CREDIT_BYTES,
                            TerminalProtocol.FEATURE_RESIZE))),
            new TerminalFrame(TerminalFrameType.OPEN_PTY, 7,
                    TerminalProtocol.encodeOpenPty(new TerminalProtocol.OpenPty(
                            120, 40, 4096, "build"))),
            new TerminalFrame(TerminalFrameType.DATA, 7, "echo hi\n".getBytes(StandardCharsets.UTF_8)),
            new TerminalFrame(TerminalFrameType.RESIZE, 7,
                    TerminalProtocol.encodeResize(new TerminalProtocol.Resize(80, 24))),
            new TerminalFrame(TerminalFrameType.SIGNAL, 7, TerminalProtocol.encodeSignal(15)),
            new TerminalFrame(TerminalFrameType.CLOSE, 7, new byte[0]),
            new TerminalFrame(TerminalFrameType.EOF, 7, TerminalProtocol.encodeExitCode(0)),
            new TerminalFrame(TerminalFrameType.PING, 0, TerminalProtocol.encodePing(42L)),
            new TerminalFrame(TerminalFrameType.PONG, 0, TerminalProtocol.encodePing(42L)),
            new TerminalFrame(TerminalFrameType.METRICS, 0, TerminalProtocol.encodeMetrics(metrics)),
            new TerminalFrame(TerminalFrameType.ERROR, 7,
                    TerminalProtocol.encodeError(new TerminalProtocol.ErrorPayload(
                            TerminalErrorCode.UNKNOWN_SESSION, "gone"))),
            new TerminalFrame(TerminalFrameType.CREDIT, 7, TerminalProtocol.encodeCredit(4096))
        };
        for (TerminalFrame frame : frames) {
            TerminalFrame decoded = TerminalProtocol.decode(TerminalProtocol.encode(frame));
            assertEquals(frame, decoded);
        }
    }

    @Test
    public void readsFramesFromAStream() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TerminalProtocol.write(out, new TerminalFrame(TerminalFrameType.CLOSE, 1, new byte[0]));
        TerminalProtocol.write(out, new TerminalFrame(TerminalFrameType.PING, 0,
                TerminalProtocol.encodePing(9L)));
        InputStream input = new ByteArrayInputStream(out.toByteArray());
        assertEquals(TerminalFrameType.CLOSE, TerminalProtocol.read(input).type());
        assertEquals(TerminalFrameType.PING, TerminalProtocol.read(input).type());
        assertNull(TerminalProtocol.read(input));
    }

    @Test
    public void readToleratesOneByteShortReads() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TerminalProtocol.write(out, new TerminalFrame(TerminalFrameType.CLOSE, 1, new byte[0]));
        TerminalProtocol.write(out, new TerminalFrame(
                TerminalFrameType.PING, 0, TerminalProtocol.encodePing(42L)));
        InputStream slow = new FilterInputStream(new ByteArrayInputStream(out.toByteArray())) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                return super.read(bytes, offset, Math.min(1, length));
            }
        };

        assertEquals(TerminalFrameType.CLOSE, TerminalProtocol.read(slow).type());
        TerminalFrame ping = TerminalProtocol.read(slow);
        assertEquals(TerminalFrameType.PING, ping.type());
        assertEquals(42L, TerminalProtocol.parsePing(ping.payload()));
        assertNull(TerminalProtocol.read(slow));
    }

    @Test
    public void tenThousandFramesRoundTripWithStableBoundaries() throws Exception {
        TerminalFrame frame = new TerminalFrame(
                TerminalFrameType.DATA, 1, new byte[] {1, 2, 3, 4});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 10_000; i++) {
            TerminalProtocol.write(out, frame);
        }

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        for (int i = 0; i < 10_000; i++) {
            assertEquals(frame, TerminalProtocol.read(in));
        }
        assertNull(TerminalProtocol.read(in));
    }

    @Test
    public void rejectsInvalidMagic() {
        byte[] frame = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.CLOSE, 1, new byte[0]));
        frame[0] = 0x00;
        assertCode(TerminalErrorCode.PROTOCOL_MAGIC, frame);
    }

    @Test
    public void rejectsUnsupportedVersion() {
        byte[] frame = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.CLOSE, 1, new byte[0]));
        frame[4] = 2;
        assertCode(TerminalErrorCode.PROTOCOL_VERSION, frame);
    }

    @Test
    public void rejectsNegativeLength() {
        byte[] frame = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.CLOSE, 1, new byte[0]));
        frame[10] = (byte) 0xFF;
        frame[11] = (byte) 0xFF;
        frame[12] = (byte) 0xFF;
        frame[13] = (byte) 0xFF;
        assertCode(TerminalErrorCode.PROTOCOL_LENGTH, frame);
    }

    @Test
    public void rejectsOversizedLength() {
        byte[] frame = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.CLOSE, 1, new byte[0]));
        int tooLarge = TerminalProtocol.MAX_PAYLOAD_BYTES + 1;
        frame[10] = (byte) (tooLarge >>> 24);
        frame[11] = (byte) (tooLarge >>> 16);
        frame[12] = (byte) (tooLarge >>> 8);
        frame[13] = (byte) tooLarge;
        assertCode(TerminalErrorCode.FRAME_TOO_LARGE, frame);
    }

    @Test
    public void rejectsUnknownType() {
        byte[] frame = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.CLOSE, 1, new byte[0]));
        frame[5] = 0x7F;
        assertCode(TerminalErrorCode.UNKNOWN_TYPE, frame);
    }

    @Test
    public void rejectsTruncatedHeader() {
        byte[] frame = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.CLOSE, 1, new byte[0]));
        byte[] truncated = new byte[10];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);
        assertCode(TerminalErrorCode.TRUNCATED_FRAME, truncated);
    }

    @Test
    public void rejectsTruncatedPayload() {
        byte[] frame = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.DATA, 1, new byte[] {1, 2, 3, 4}));
        byte[] truncated = new byte[frame.length - 2];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);
        assertCode(TerminalErrorCode.TRUNCATED_FRAME, truncated);
    }

    @Test
    public void rejectsTrailingBytes() {
        byte[] frame = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.CLOSE, 1, new byte[0]));
        byte[] trailing = new byte[frame.length + 1];
        System.arraycopy(frame, 0, trailing, 0, frame.length);
        assertCode(TerminalErrorCode.PROTOCOL_LENGTH, trailing);
    }

    @Test
    public void rejectsSessionZeroForSessionFrame() {
        byte[] frame = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.CLOSE, 1, new byte[0]));
        frame[6] = 0;
        frame[7] = 0;
        frame[8] = 0;
        frame[9] = 0;
        assertCode(TerminalErrorCode.INVALID_PAYLOAD, frame);
    }

    @Test
    public void rejectsNonZeroSessionForConnectionFrame() {
        byte[] frame = TerminalProtocol.encode(new TerminalFrame(
                TerminalFrameType.HELLO, 0,
                TerminalProtocol.encodeHello(new TerminalProtocol.Hello(
                        1024, 1024, 0))));
        frame[6] = 0;
        frame[7] = 0;
        frame[8] = 0;
        frame[9] = 1;
        assertCode(TerminalErrorCode.INVALID_PAYLOAD, frame);
    }

    @Test
    public void readReturnsNullOnlyAtCleanEof() throws Exception {
        assertNull(TerminalProtocol.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void payloadHelpersValidateRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalProtocol.Hello(0, 1024, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalProtocol.Hello(1024, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalProtocol.OpenPty(0, 24, 1024, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalProtocol.OpenPty(
                        80, 24, 1024, "x".repeat(TerminalProtocol.MAX_NAME_BYTES + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalProtocol.Resize(80, 0));
        assertThrows(IllegalArgumentException.class, () -> TerminalProtocol.encodeSignal(0));
        assertThrows(IllegalArgumentException.class, () -> TerminalProtocol.encodeSignal(65));
        assertThrows(IllegalArgumentException.class, () -> TerminalProtocol.encodeCredit(0));
        assertThrows(IllegalArgumentException.class,
                () -> TerminalProtocol.encodeCredit(TerminalProtocol.MAX_CREDIT_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> new TerminalProtocol.ErrorPayload(
                TerminalErrorCode.INTERNAL, "x".repeat(513)));
    }

    @Test
    public void payloadParsersRejectMalformedBytes() {
        assertThrows(TerminalProtocolException.class,
                () -> TerminalProtocol.parseHello(new byte[11]));
        assertThrows(TerminalProtocolException.class,
                () -> TerminalProtocol.parseOpenPty(new byte[13]));
        assertThrows(TerminalProtocolException.class,
                () -> TerminalProtocol.parseResize(new byte[7]));
        assertThrows(TerminalProtocolException.class,
                () -> TerminalProtocol.parseSignal(new byte[] {0}));
        assertThrows(TerminalProtocolException.class,
                () -> TerminalProtocol.parseCredit(new byte[3]));
        assertThrows(TerminalProtocolException.class,
                () -> TerminalProtocol.parseExitCode(new byte[3]));
        assertThrows(TerminalProtocolException.class,
                () -> TerminalProtocol.parseMetrics(new byte[63]));
        assertThrows(TerminalProtocolException.class,
                () -> TerminalProtocol.parseError(new byte[1]));
    }

    @Test
    public void fixtureFramesDecodeAndReencode() throws Exception {
        InputStream resource = getClass().getResourceAsStream("/terminal-frames-v1.txt");
        assertTrue("fixture resource is missing", resource != null);
        int count = 0;
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(resource, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                assertEquals("fixture line: " + trimmed, 4, parts.length);
                TerminalFrameType type = TerminalFrameType.valueOf(
                        parts[0].toUpperCase(Locale.ROOT).replace('-', '_'));
                assertEquals(type.wire(), Integer.parseInt(parts[1]));
                int sessionId = Integer.parseInt(parts[2]);
                byte[] wire = HEX.parseHex(parts[3]);
                TerminalFrame frame = TerminalProtocol.decode(wire);
                assertEquals(type, frame.type());
                assertEquals(sessionId, frame.sessionId());
                assertArrayEquals(wire, TerminalProtocol.encode(frame));
                count++;
            }
        }
        assertTrue("fixture must contain the core frame types", count >= 9);
    }

    private static void assertCode(TerminalErrorCode expected, byte[] wire) {
        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class, () -> TerminalProtocol.decode(wire));
        assertEquals(expected, failure.code());
    }
}
