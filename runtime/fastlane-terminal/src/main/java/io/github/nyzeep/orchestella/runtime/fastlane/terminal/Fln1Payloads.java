package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Payload layouts for the canonical {@code FLN1} protocol v1
 * ({@code fastlane-payload/docs/protocol.md}).
 *
 * <p>Every method is a pure byte transformation: no state, no terminal content
 * retention. All integers are big-endian. The C payload is the normative
 * implementation; this class must not invent a layout it does not implement.
 */
public final class Fln1Payloads {
    public static final int MAX_FRAME_PAYLOAD = 65_536;
    public static final int MAX_PTY_DATA = 32_768;
    public static final int MAX_ARGV = 16;
    public static final int MAX_OPEN_PTY_PAYLOAD = 4_096;
    public static final int MAX_TOKEN_BYTES = 255;
    public static final int MAX_ERROR_MESSAGE_BYTES = 512;
    public static final int MAX_DIMENSION = 65_535;
    public static final int MAX_SIGNAL = 64;
    public static final int MIN_INITIAL_WINDOW = 1_024;
    public static final int MAX_INITIAL_WINDOW = 1_048_576;
    public static final int DEFAULT_INITIAL_WINDOW = 262_144;
    public static final int MAX_PING_BYTES = 32;

    public static final int CAP_TERMINAL = 1;
    public static final int CAP_PROXY = 1 << 1;
    public static final int CAP_EXPORT = 1 << 2;
    public static final int CAP_IMPORT = 1 << 3;
    public static final int CAP_DISTCC = 1 << 4;
    public static final int CAP_METRICS = 1 << 5;

    private static final ByteOrder ORDER = ByteOrder.BIG_ENDIAN;

    private Fln1Payloads() {}

    /** {@code HELLO}: version range and optional authentication token. */
    public record Hello(int minVersion, int maxVersion, String token) {
        public Hello {
            if (minVersion < 0 || minVersion > 0xFFFF || maxVersion < 0 || maxVersion > 0xFFFF) {
                throw new IllegalArgumentException("version out of range");
            }
            Objects.requireNonNull(token, "token");
            if (utf8(token).length > MAX_TOKEN_BYTES) {
                throw new IllegalArgumentException("token exceeds " + MAX_TOKEN_BYTES + " bytes");
            }
        }
    }

    /** {@code HELLO_OK}: negotiated version, capability bits and window. */
    public record HelloOk(int version, int caps, int maxFrameBytes, int initialWindow) {
        public HelloOk {
            if (version < 0 || version > 0xFFFF) {
                throw new IllegalArgumentException("version out of range");
            }
            if (caps < 0) {
                throw new IllegalArgumentException("caps must be unsigned");
            }
            if (maxFrameBytes <= 0 || maxFrameBytes > MAX_FRAME_PAYLOAD) {
                throw new IllegalArgumentException("maxFrameBytes out of range");
            }
            if (initialWindow < MIN_INITIAL_WINDOW || initialWindow > MAX_INITIAL_WINDOW) {
                throw new IllegalArgumentException("initialWindow out of range");
            }
        }
    }

    /** {@code OPEN_PTY}: terminal geometry and the command to execute. */
    public record OpenPty(int columns, int rows, String[] argv) {
        public OpenPty {
            if (columns < 1 || columns > MAX_DIMENSION) {
                throw new IllegalArgumentException("columns out of range");
            }
            if (rows < 1 || rows > MAX_DIMENSION) {
                throw new IllegalArgumentException("rows out of range");
            }
            Objects.requireNonNull(argv, "argv");
            if (argv.length < 1 || argv.length > MAX_ARGV) {
                throw new IllegalArgumentException("argv count out of range");
            }
            String[] copy = argv.clone();
            for (String argument : copy) {
                Objects.requireNonNull(argument, "argv element");
                if (argument.isEmpty()) {
                    throw new IllegalArgumentException("argv element must not be empty");
                }
                if (argument.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("argv element contains NUL");
                }
            }
            argv = copy;
        }

        @Override
        public String[] argv() {
            return argv.clone();
        }
    }

    /** {@code PTY_OPENED}: payload-assigned pid and output window. */
    public record PtyOpened(int pid, int initialWindow) {
        public PtyOpened {
            if (initialWindow < 1 || initialWindow > MAX_INITIAL_WINDOW) {
                throw new IllegalArgumentException("initialWindow out of range");
            }
        }
    }

    /** {@code RESIZE}: new PTY geometry. */
    public record Resize(int columns, int rows) {
        public Resize {
            if (columns < 1 || columns > MAX_DIMENSION) {
                throw new IllegalArgumentException("columns out of range");
            }
            if (rows < 1 || rows > MAX_DIMENSION) {
                throw new IllegalArgumentException("rows out of range");
            }
        }
    }

    /** {@code EXIT}: child status reported after {@code EOF}. */
    public record Exit(int exitCode, int termSignal) {
        public Exit {
            if (termSignal < 0 || termSignal > MAX_SIGNAL) {
                throw new IllegalArgumentException("termSignal out of range");
            }
        }
    }

    /** {@code ERROR}: FLN1 error code plus a short fixed message. */
    public record Error(int code, String message) {
        public Error {
            if (code < 0 || code > 0xFFFF) {
                throw new IllegalArgumentException("code out of range");
            }
            Objects.requireNonNull(message, "message");
            if (utf8(message).length > MAX_ERROR_MESSAGE_BYTES) {
                throw new IllegalArgumentException("error message too long");
            }
        }
    }

    public static byte[] encodeHello(Hello hello) {
        byte[] token = utf8(hello.token());
        ByteBuffer buffer = ByteBuffer.allocate(5 + token.length).order(ORDER);
        buffer.putShort((short) hello.minVersion());
        buffer.putShort((short) hello.maxVersion());
        buffer.put((byte) token.length);
        buffer.put(token);
        return buffer.array();
    }

    public static Hello parseHello(byte[] payload) throws TerminalProtocolException {
        if (payload == null || payload.length < 5) {
            throw invalid("hello payload is too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ORDER);
        int minimum = buffer.getShort() & 0xFFFF;
        int maximum = buffer.getShort() & 0xFFFF;
        int tokenLength = buffer.get() & 0xFF;
        if (payload.length != 5 + tokenLength) {
            throw invalid("hello token length is invalid");
        }
        byte[] token = new byte[tokenLength];
        buffer.get(token);
        try {
            return new Hello(minimum, maximum, new String(token, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            throw invalid(failure.getMessage());
        }
    }

    public static byte[] encodeHelloOk(HelloOk hello) {
        ByteBuffer buffer = ByteBuffer.allocate(14).order(ORDER);
        buffer.putShort((short) hello.version());
        buffer.putInt(hello.caps());
        buffer.putInt(hello.maxFrameBytes());
        buffer.putInt(hello.initialWindow());
        return buffer.array();
    }

    public static HelloOk parseHelloOk(byte[] payload) throws TerminalProtocolException {
        ByteBuffer buffer = fixed(payload, 14, "hello-ok");
        int version = buffer.getShort() & 0xFFFF;
        int caps = buffer.getInt();
        int maxFrame = buffer.getInt();
        int window = buffer.getInt();
        try {
            return new HelloOk(version, caps, maxFrame, window);
        } catch (IllegalArgumentException failure) {
            throw invalid(failure.getMessage());
        }
    }

    public static byte[] encodeOpenPty(OpenPty request) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, request.columns());
        writeU16(out, request.rows());
        String[] argv = request.argv();
        out.write(argv.length);
        for (String argument : argv) {
            byte[] bytes = utf8(argument);
            if (bytes.length > 0xFFFF) {
                throw new IllegalArgumentException("argv element is too long");
            }
            writeU16(out, bytes.length);
            out.writeBytes(bytes);
        }
        byte[] payload = out.toByteArray();
        if (payload.length > MAX_OPEN_PTY_PAYLOAD) {
            throw new IllegalArgumentException("open-pty payload exceeds " + MAX_OPEN_PTY_PAYLOAD);
        }
        return payload;
    }

    public static OpenPty parseOpenPty(byte[] payload) throws TerminalProtocolException {
        if (payload == null || payload.length < 5) {
            throw invalid("open-pty payload is too short");
        }
        if (payload.length > MAX_OPEN_PTY_PAYLOAD) {
            throw invalid("open-pty payload exceeds " + MAX_OPEN_PTY_PAYLOAD);
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ORDER);
        int columns = buffer.getShort() & 0xFFFF;
        int rows = buffer.getShort() & 0xFFFF;
        int count = buffer.get() & 0xFF;
        if (count < 1 || count > MAX_ARGV) {
            throw invalid("open-pty argv count out of range");
        }
        String[] argv = new String[count];
        for (int index = 0; index < count; index++) {
            if (buffer.remaining() < 2) {
                throw invalid("open-pty argv length is truncated");
            }
            int length = buffer.getShort() & 0xFFFF;
            if (buffer.remaining() < length) {
                throw invalid("open-pty argv is truncated");
            }
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            argv[index] = new String(bytes, StandardCharsets.UTF_8);
        }
        if (buffer.hasRemaining()) {
            throw invalid("open-pty payload has trailing bytes");
        }
        try {
            return new OpenPty(columns, rows, argv);
        } catch (IllegalArgumentException failure) {
            throw invalid(failure.getMessage());
        }
    }

    public static byte[] encodePtyOpened(int pid, int initialWindow) {
        if (initialWindow < 1 || initialWindow > MAX_INITIAL_WINDOW) {
            throw new IllegalArgumentException("initialWindow out of range");
        }
        return ByteBuffer.allocate(8).order(ORDER).putInt(pid).putInt(initialWindow).array();
    }

    public static PtyOpened parsePtyOpened(byte[] payload) throws TerminalProtocolException {
        ByteBuffer buffer = fixed(payload, 8, "pty-opened");
        int pid = buffer.getInt();
        int window = buffer.getInt();
        try {
            return new PtyOpened(pid, window);
        } catch (IllegalArgumentException failure) {
            throw invalid(failure.getMessage());
        }
    }

    public static byte[] encodeResize(Resize resize) {
        return ByteBuffer.allocate(4).order(ORDER)
                .putShort((short) resize.columns())
                .putShort((short) resize.rows())
                .array();
    }

    public static Resize parseResize(byte[] payload) throws TerminalProtocolException {
        ByteBuffer buffer = fixed(payload, 4, "resize");
        int columns = buffer.getShort() & 0xFFFF;
        int rows = buffer.getShort() & 0xFFFF;
        try {
            return new Resize(columns, rows);
        } catch (IllegalArgumentException failure) {
            throw invalid(failure.getMessage());
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
            throw invalid("signal payload must be one byte");
        }
        int signal = payload[0] & 0xFF;
        if (signal < 1 || signal > MAX_SIGNAL) {
            throw invalid("signal out of range");
        }
        return signal;
    }

    public static byte[] encodeClose(boolean force) {
        return new byte[] {(byte) (force ? 1 : 0)};
    }

    public static boolean parseClose(byte[] payload) throws TerminalProtocolException {
        if (payload == null || payload.length != 1) {
            throw invalid("close payload must be one byte");
        }
        int force = payload[0] & 0xFF;
        if (force > 1) {
            throw invalid("close force must be 0 or 1");
        }
        return force == 1;
    }

    public static byte[] encodeClosed(int reason) {
        if (reason < 0 || reason > 2) {
            throw new IllegalArgumentException("closed reason out of range");
        }
        return new byte[] {(byte) reason};
    }

    public static int parseClosed(byte[] payload) throws TerminalProtocolException {
        if (payload == null || payload.length != 1) {
            throw invalid("closed payload must be one byte");
        }
        int reason = payload[0] & 0xFF;
        if (reason > 2) {
            throw invalid("closed reason out of range");
        }
        return reason;
    }

    public static byte[] encodeExit(Exit exit) {
        return ByteBuffer.allocate(5).order(ORDER)
                .putInt(exit.exitCode())
                .put((byte) exit.termSignal())
                .array();
    }

    public static Exit parseExit(byte[] payload) throws TerminalProtocolException {
        ByteBuffer buffer = fixed(payload, 5, "exit");
        int exitCode = buffer.getInt();
        int termSignal = buffer.get() & 0xFF;
        try {
            return new Exit(exitCode, termSignal);
        } catch (IllegalArgumentException failure) {
            throw invalid(failure.getMessage());
        }
    }

    public static byte[] encodeWindow(int creditBytes) {
        if (creditBytes <= 0) {
            throw new IllegalArgumentException("window credit must be positive");
        }
        return ByteBuffer.allocate(4).order(ORDER).putInt(creditBytes).array();
    }

    public static int parseWindow(byte[] payload) throws TerminalProtocolException {
        ByteBuffer buffer = fixed(payload, 4, "window");
        int credit = buffer.getInt();
        if (credit <= 0) {
            throw invalid("window credit must be positive");
        }
        return credit;
    }

    /** Validates an opaque {@code PING}/{@code PONG} payload. */
    public static byte[] ping(byte[] payload) throws TerminalProtocolException {
        if (payload == null || payload.length > MAX_PING_BYTES) {
            throw invalid("ping payload exceeds " + MAX_PING_BYTES + " bytes");
        }
        return payload.clone();
    }

    public static byte[] encodeError(Error error) {
        byte[] message = utf8(error.message());
        ByteBuffer buffer = ByteBuffer.allocate(4 + message.length).order(ORDER);
        buffer.putShort((short) error.code());
        buffer.putShort((short) message.length);
        buffer.put(message);
        return buffer.array();
    }

    public static Error parseError(byte[] payload) throws TerminalProtocolException {
        if (payload == null || payload.length < 4) {
            throw invalid("error payload is too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ORDER);
        int code = buffer.getShort() & 0xFFFF;
        int length = buffer.getShort() & 0xFFFF;
        if (payload.length != 4 + length) {
            throw invalid("error message length is invalid");
        }
        byte[] message = new byte[length];
        buffer.get(message);
        try {
            return new Error(code, new String(message, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            throw invalid(failure.getMessage());
        }
    }

    private static ByteBuffer fixed(byte[] payload, int expected, String what)
            throws TerminalProtocolException {
        if (payload == null || payload.length != expected) {
            throw invalid(what + " payload must be " + expected + " bytes");
        }
        return ByteBuffer.wrap(payload).order(ORDER);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static TerminalProtocolException invalid(String message) {
        return new TerminalProtocolException(
                TerminalErrorCode.INVALID_PAYLOAD, message == null ? "invalid payload" : message);
    }
}
