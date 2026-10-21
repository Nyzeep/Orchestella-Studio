package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Versioned frame codec shared by the host multiplexer and the payload.
 *
 * <pre>
 * magic(4)="OSFL" | version(1)=1 | type(1) | sessionId(4) | length(4) | payload(length)
 * </pre>
 *
 * <p>All integers are big-endian. Session 0 is reserved for connection-level
 * control. The codec rejects oversized, truncated, and session-inconsistent
 * frames; it never inspects terminal content.
 */
public final class TerminalProtocol {
    public static final byte[] MAGIC = {0x4F, 0x53, 0x46, 0x4C};
    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 14;
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    public static final int DEFAULT_CREDIT_BYTES = 262_144;
    public static final int MAX_CREDIT_BYTES = 4_194_304;
    public static final int MAX_SESSIONS = 128;
    public static final int MAX_NAME_BYTES = 1024;
    public static final int MAX_ERROR_MESSAGE_BYTES = 512;
    public static final int MAX_TERMINAL_DIMENSION = 10_000;
    public static final int MAX_SIGNAL = 64;

    public static final int FEATURE_RESIZE = 1;
    public static final int FEATURE_SIGNAL = 1 << 1;
    public static final int FEATURE_METRICS = 1 << 2;
    public static final int FEATURE_EXIT_CODE = 1 << 3;

    private static final ByteOrder ORDER = ByteOrder.BIG_ENDIAN;

    private TerminalProtocol() {}

    /** Host-to-guest capability and credit announcement. */
    public record Hello(int maxFrameBytes, int initialCredit, int features) {
        public Hello {
            if (maxFrameBytes <= 0 || maxFrameBytes > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("maxFrameBytes out of range");
            }
            if (initialCredit <= 0 || initialCredit > MAX_CREDIT_BYTES) {
                throw new IllegalArgumentException("initialCredit out of range");
            }
        }
    }

    /** Host request to create one PTY. */
    public record OpenPty(int columns, int rows, int outputCredit, String name) {
        public OpenPty {
            if (columns <= 0 || columns > MAX_TERMINAL_DIMENSION) {
                throw new IllegalArgumentException("columns out of range");
            }
            if (rows <= 0 || rows > MAX_TERMINAL_DIMENSION) {
                throw new IllegalArgumentException("rows out of range");
            }
            if (outputCredit <= 0 || outputCredit > MAX_CREDIT_BYTES) {
                throw new IllegalArgumentException("outputCredit out of range");
            }
            Objects.requireNonNull(name, "name");
            if (utf8(name).length > MAX_NAME_BYTES) {
                throw new IllegalArgumentException("name is longer than " + MAX_NAME_BYTES + " bytes");
            }
        }
    }

    public record Resize(int columns, int rows) {
        public Resize {
            if (columns <= 0 || columns > MAX_TERMINAL_DIMENSION) {
                throw new IllegalArgumentException("columns out of range");
            }
            if (rows <= 0 || rows > MAX_TERMINAL_DIMENSION) {
                throw new IllegalArgumentException("rows out of range");
            }
        }
    }

    public record ErrorPayload(TerminalErrorCode code, String message) {
        public ErrorPayload {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            if (utf8(message).length > MAX_ERROR_MESSAGE_BYTES) {
                throw new IllegalArgumentException("error message too long");
            }
        }
    }

    public static byte[] encode(TerminalFrame frame) {
        Objects.requireNonNull(frame, "frame");
        byte[] payload = frame.payload();
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ORDER);
        buffer.put(MAGIC);
        buffer.put((byte) VERSION);
        buffer.put((byte) frame.type().wire());
        buffer.putInt(frame.sessionId());
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    public static void write(OutputStream output, TerminalFrame frame) throws IOException {
        Objects.requireNonNull(output, "output");
        output.write(encode(frame));
    }

    /** Reads one frame; returns null only for a clean EOF at a frame boundary. */
    public static TerminalFrame read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] header = new byte[HEADER_BYTES];
        int first = input.read();
        if (first == -1) {
            return null;
        }
        header[0] = (byte) first;
        readFully(input, header, 1, HEADER_BYTES - 1, "frame header");
        if (!Arrays.equals(Arrays.copyOf(header, MAGIC.length), MAGIC)) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.PROTOCOL_MAGIC, "invalid frame magic");
        }
        int version = header[4] & 0xFF;
        if (version != VERSION) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.PROTOCOL_VERSION, "unsupported protocol version: " + version);
        }
        TerminalFrameType type = TerminalFrameType.fromWire(header[5] & 0xFF);
        int sessionId = ByteBuffer.wrap(header, 6, 4).order(ORDER).getInt();
        int length = ByteBuffer.wrap(header, 10, 4).order(ORDER).getInt();
        if (length < 0) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.PROTOCOL_LENGTH, "negative frame length");
        }
        if (length > MAX_PAYLOAD_BYTES) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.FRAME_TOO_LARGE, "frame length exceeds limit: " + length);
        }
        validateSession(type, sessionId);
        byte[] payload = new byte[length];
        readFully(input, payload, 0, length, "frame payload");
        return new TerminalFrame(type, sessionId, payload);
    }

    /** Decodes exactly one frame from a byte array. */
    public static TerminalFrame decode(byte[] wire) throws TerminalProtocolException {
        Objects.requireNonNull(wire, "wire");
        if (wire.length < HEADER_BYTES) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.TRUNCATED_FRAME, "frame is shorter than the header");
        }
        ByteArrayInputStream input = new ByteArrayInputStream(wire);
        try {
            TerminalFrame frame = read(input);
            if (frame == null || input.available() != 0) {
                throw new TerminalProtocolException(
                        TerminalErrorCode.PROTOCOL_LENGTH, "trailing bytes after one frame");
            }
            return frame;
        } catch (TerminalProtocolException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.TRUNCATED_FRAME, "frame decode failed", failure);
        }
    }

    public static byte[] encodeHello(Hello hello) {
        ByteBuffer buffer = ByteBuffer.allocate(12).order(ORDER);
        buffer.putInt(hello.maxFrameBytes());
        buffer.putInt(hello.initialCredit());
        buffer.putInt(hello.features());
        return buffer.array();
    }

    public static Hello parseHello(byte[] payload) throws TerminalProtocolException {
        ByteBuffer buffer = payload(payload, 12);
        int maxFrame = buffer.getInt();
        int credit = buffer.getInt();
        int features = buffer.getInt();
        try {
            return new Hello(maxFrame, credit, features);
        } catch (IllegalArgumentException failure) {
            throw invalid("hello", failure);
        }
    }

    public static byte[] encodeOpenPty(OpenPty request) {
        byte[] name = utf8(request.name());
        ByteBuffer buffer = ByteBuffer.allocate(14 + name.length).order(ORDER);
        buffer.putInt(request.columns());
        buffer.putInt(request.rows());
        buffer.putInt(request.outputCredit());
        buffer.putShort((short) name.length);
        buffer.put(name);
        return buffer.array();
    }

    public static OpenPty parseOpenPty(byte[] payload) throws TerminalProtocolException {
        if (payload == null || payload.length < 14) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "open-pty payload is too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ORDER);
        int columns = buffer.getInt();
        int rows = buffer.getInt();
        int credit = buffer.getInt();
        int nameLength = buffer.getShort() & 0xFFFF;
        if (nameLength > MAX_NAME_BYTES || payload.length != 14 + nameLength) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "open-pty name length is invalid");
        }
        byte[] nameBytes = new byte[nameLength];
        buffer.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);
        try {
            return new OpenPty(columns, rows, credit, name);
        } catch (IllegalArgumentException failure) {
            throw invalid("open-pty", failure);
        }
    }

    public static byte[] encodeResize(Resize resize) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ORDER);
        buffer.putInt(resize.columns());
        buffer.putInt(resize.rows());
        return buffer.array();
    }

    public static Resize parseResize(byte[] payload) throws TerminalProtocolException {
        ByteBuffer buffer = payload(payload, 8);
        int columns = buffer.getInt();
        int rows = buffer.getInt();
        try {
            return new Resize(columns, rows);
        } catch (IllegalArgumentException failure) {
            throw invalid("resize", failure);
        }
    }

    public static byte[] encodeSignal(int signal) {
        if (signal < 1 || signal > MAX_SIGNAL) {
            throw new IllegalArgumentException("signal out of range");
        }
        return new byte[] {(byte) signal};
    }

    public static int parseSignal(byte[] payload) throws TerminalProtocolException {
        if (payload == null || payload.length != 1) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "signal payload must be one byte");
        }
        int signal = payload[0] & 0xFF;
        if (signal < 1 || signal > MAX_SIGNAL) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "signal out of range");
        }
        return signal;
    }

    public static byte[] encodeExitCode(int exitCode) {
        return ByteBuffer.allocate(4).order(ORDER).putInt(exitCode).array();
    }

    public static int parseExitCode(byte[] payload) throws TerminalProtocolException {
        ByteBuffer buffer = payload(payload, 4);
        return buffer.getInt();
    }

    public static byte[] encodeCredit(int credit) {
        if (credit <= 0 || credit > MAX_CREDIT_BYTES) {
            throw new IllegalArgumentException("credit out of range");
        }
        return ByteBuffer.allocate(4).order(ORDER).putInt(credit).array();
    }

    public static int parseCredit(byte[] payload) throws TerminalProtocolException {
        ByteBuffer buffer = payload(payload, 4);
        int credit = buffer.getInt();
        if (credit <= 0 || credit > MAX_CREDIT_BYTES) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "credit out of range");
        }
        return credit;
    }

    public static byte[] encodePing(long nonce) {
        return ByteBuffer.allocate(8).order(ORDER).putLong(nonce).array();
    }

    public static long parsePing(byte[] payload) throws TerminalProtocolException {
        ByteBuffer buffer = payload(payload, 8);
        return buffer.getLong();
    }

    public static byte[] encodeError(ErrorPayload error) {
        byte[] message = utf8(error.message());
        ByteBuffer buffer = ByteBuffer.allocate(2 + message.length).order(ORDER);
        buffer.putShort((short) error.code().code());
        buffer.put(message);
        return buffer.array();
    }

    public static ErrorPayload parseError(byte[] payload) throws TerminalProtocolException {
        if (payload == null || payload.length < 2) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "error payload is too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ORDER);
        int code = buffer.getShort() & 0xFFFF;
        byte[] message = new byte[payload.length - 2];
        buffer.get(message);
        if (message.length > MAX_ERROR_MESSAGE_BYTES) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "error message is too long");
        }
        return new ErrorPayload(
                TerminalErrorCode.fromCode(code), new String(message, StandardCharsets.UTF_8));
    }

    public static byte[] encodeMetrics(TerminalMuxMetrics metrics) {
        ByteBuffer buffer = ByteBuffer.allocate(64).order(ORDER);
        buffer.putLong(metrics.sessionsOpened());
        buffer.putLong(metrics.sessionsClosed());
        buffer.putLong(metrics.framesIn());
        buffer.putLong(metrics.framesOut());
        buffer.putLong(metrics.bytesIn());
        buffer.putLong(metrics.bytesOut());
        buffer.putLong(metrics.creditStalls());
        buffer.putLong(metrics.protocolErrors());
        return buffer.array();
    }

    public static TerminalMuxMetrics parseMetrics(byte[] payload) throws TerminalProtocolException {
        ByteBuffer buffer = payload(payload, 64);
        try {
            return new TerminalMuxMetrics(
                    buffer.getLong(),
                    buffer.getLong(),
                    buffer.getLong(),
                    buffer.getLong(),
                    buffer.getLong(),
                    buffer.getLong(),
                    buffer.getLong(),
                    buffer.getLong());
        } catch (IllegalArgumentException failure) {
            throw invalid("metrics", failure);
        }
    }

    private static ByteBuffer payload(byte[] payload, int expectedLength)
            throws TerminalProtocolException {
        if (payload == null || payload.length != expectedLength) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD,
                    "expected " + expectedLength + " payload bytes");
        }
        return ByteBuffer.wrap(payload).order(ORDER);
    }

    private static void validateSession(TerminalFrameType type, int sessionId)
            throws TerminalProtocolException {
        if (sessionId < 0) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "negative session id");
        }
        if (type.connectionLevel() && sessionId != 0) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "connection frame needs session 0");
        }
        if (!type.connectionLevel() && type != TerminalFrameType.ERROR && sessionId == 0) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "session frame needs a positive session");
        }
    }

    private static void readFully(InputStream input, byte[] target, int offset, int length, String what)
            throws TerminalProtocolException {
        int read = 0;
        while (read < length) {
            int count;
            try {
                count = input.read(target, offset + read, length - read);
            } catch (IOException failure) {
                throw new TerminalProtocolException(
                        TerminalErrorCode.TRUNCATED_FRAME, "failed to read " + what, failure);
            }
            if (count == -1) {
                throw new TerminalProtocolException(
                        TerminalErrorCode.TRUNCATED_FRAME, "truncated " + what, new EOFException());
            }
            read += count;
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static TerminalProtocolException invalid(String what, IllegalArgumentException failure) {
        return new TerminalProtocolException(
                TerminalErrorCode.INVALID_PAYLOAD, "invalid " + what + " payload", failure);
    }
}
