package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Host-side codec for the canonical {@code FLN1} v1 wire contract
 * ({@code fastlane-payload/docs/protocol.md}). The C payload is normative.
 *
 * <p>This class owns three translations that the mux must not know about:
 *
 * <ul>
 *   <li>logical frame kind to FLN1 opcode, including request/reply asymmetry;
 *   <li>mux session id to wire session id. FLN1 lets the payload allocate the
 *       PTY id in {@code PTY_OPENED}; the host therefore opens with wire session
 *       0 and keeps a FIFO of pending opens. FLN1 v1 has no request correlation,
 *       so at most the frames already written are matched in order;
 *   <li>{@code EOF} (master closed, no status) followed by {@code EXIT} (status).
 *       The reader holds {@code EOF} until {@code EXIT} arrives so the mux sees
 *       one real exit code instead of an unobservable {@code -1}.
 * </ul>
 *
 * <p>The payload never accepts host-sent {@code ERROR}; a host-side violation is
 * a connection-fatal {@link TerminalProtocolException} and the channel closes
 * the stream, which is the FLN1 rule for a malformed frame.
 */
public final class Fln1TerminalCodec implements TerminalCodec {
    public static final byte[] MAGIC = {0x46, 0x4C, 0x4E, 0x31};
    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 14;
    public static final int MAX_PAYLOAD_BYTES = Fln1Payloads.MAX_FRAME_PAYLOAD;

    public static final int OP_HELLO = 0x01;
    public static final int OP_HELLO_OK = 0x81;
    public static final int OP_PING = 0x02;
    public static final int OP_PONG = 0x82;
    public static final int OP_OPEN_PTY = 0x03;
    public static final int OP_PTY_OPENED = 0x83;
    public static final int OP_PTY_DATA = 0x04;
    public static final int OP_RESIZE = 0x05;
    public static final int OP_RESIZED = 0x85;
    public static final int OP_SIGNAL = 0x06;
    public static final int OP_CLOSE = 0x07;
    public static final int OP_CLOSED = 0x87;
    public static final int OP_EOF = 0x08;
    public static final int OP_EXIT = 0x09;
    public static final int OP_WINDOW = 0x0A;
    public static final int OP_ERROR = 0xFF;

    private static final ByteOrder ORDER = ByteOrder.BIG_ENDIAN;

    private final TerminalMux.Role role;
    private final Map<Integer, Integer> localToWire = new HashMap<>();
    private final Map<Integer, Integer> wireToLocal = new HashMap<>();
    private final Deque<Integer> pendingOpens = new ArrayDeque<>();

    private TerminalFrame pendingEof;
    private TerminalFrame buffered;
    private int nextGuestWireId = 1;

    public Fln1TerminalCodec(TerminalMux.Role role) {
        this.role = Objects.requireNonNull(role, "role");
    }

    @Override
    public byte[] encode(TerminalFrame frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        byte[] payload = frame.payload();
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.FRAME_TOO_LARGE,
                    "frame payload exceeds " + MAX_PAYLOAD_BYTES);
        }
        int opcode;
        int session;
        switch (frame.type()) {
            case HELLO -> {
                opcode = role == TerminalMux.Role.HOST ? OP_HELLO : OP_HELLO_OK;
                session = 0;
            }
            case OPEN_PTY -> {
                session = openSessionForWire(frame.sessionId());
                opcode = role == TerminalMux.Role.HOST ? OP_OPEN_PTY : OP_PTY_OPENED;
            }
            case DATA -> {
                requireDataPayload(payload);
                opcode = OP_PTY_DATA;
                session = wireId(frame.sessionId());
            }
            case RESIZE -> {
                requireLength(payload, 4, "resize");
                requireRole(TerminalMux.Role.HOST, "RESIZE");
                opcode = OP_RESIZE;
                session = wireId(frame.sessionId());
            }
            case SIGNAL -> {
                requireLength(payload, 1, "signal");
                requireRole(TerminalMux.Role.HOST, "SIGNAL");
                opcode = OP_SIGNAL;
                session = wireId(frame.sessionId());
            }
            case CLOSE -> {
                requireLength(payload, 1, "close");
                requireRole(TerminalMux.Role.HOST, "CLOSE");
                opcode = OP_CLOSE;
                session = wireId(frame.sessionId());
            }
            case RESIZED -> {
                requireLength(payload, 0, "resized");
                requireRole(TerminalMux.Role.GUEST, "RESIZED");
                opcode = OP_RESIZED;
                session = wireId(frame.sessionId());
            }
            case CLOSED -> {
                requireLength(payload, 1, "closed");
                requireRole(TerminalMux.Role.GUEST, "CLOSED");
                opcode = OP_CLOSED;
                session = wireId(frame.sessionId());
            }
            case EOF -> {
                requireLength(payload, 0, "eof");
                requireRole(TerminalMux.Role.GUEST, "EOF");
                opcode = OP_EOF;
                session = wireId(frame.sessionId());
            }
            case EXIT -> {
                requireLength(payload, 5, "exit");
                requireRole(TerminalMux.Role.GUEST, "EXIT");
                opcode = OP_EXIT;
                session = wireId(frame.sessionId());
            }
            case WINDOW -> {
                requireLength(payload, 4, "window");
                requireRole(TerminalMux.Role.HOST, "WINDOW");
                opcode = OP_WINDOW;
                session = wireId(frame.sessionId());
            }
            case PING -> {
                Fln1Payloads.ping(payload);
                opcode = OP_PING;
                session = 0;
            }
            case PONG -> {
                Fln1Payloads.ping(payload);
                opcode = OP_PONG;
                session = 0;
            }
            case ERROR -> {
                requireRole(TerminalMux.Role.GUEST, "ERROR");
                Fln1Payloads.parseError(payload);
                opcode = OP_ERROR;
                session = frame.sessionId() == 0 ? 0 : wireId(frame.sessionId());
            }
            default -> throw new TerminalProtocolException(
                    TerminalErrorCode.UNKNOWN_TYPE, "unsupported logical frame: " + frame.type());
        }
        return header(opcode, session, payload);
    }

    @Override
    public void write(OutputStream output, TerminalFrame frame) throws IOException {
        Objects.requireNonNull(output, "output");
        output.write(encode(frame));
    }

    @Override
    public TerminalFrame decode(byte[] wire) throws IOException {
        Objects.requireNonNull(wire, "wire");
        if (wire.length < HEADER_BYTES) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.TRUNCATED_FRAME, "frame is shorter than the header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(wire).order(ORDER);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.PROTOCOL_MAGIC, "invalid frame magic");
        }
        int version = buffer.get() & 0xFF;
        if (version != VERSION) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.PROTOCOL_VERSION, "unsupported protocol version: " + version);
        }
        int opcode = buffer.get() & 0xFF;
        int wireSession = buffer.getInt();
        int length = buffer.getInt();
        if (wireSession < 0) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "negative wire session id");
        }
        if (length < 0) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.PROTOCOL_LENGTH, "negative frame length");
        }
        if (length > MAX_PAYLOAD_BYTES) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.FRAME_TOO_LARGE, "frame length exceeds limit: " + length);
        }
        if (wire.length != HEADER_BYTES + length) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.PROTOCOL_LENGTH, "frame length does not match the buffer");
        }
        byte[] payload = new byte[length];
        buffer.get(payload);
        return translate(opcode, wireSession, payload);
    }

    @Override
    public TerminalFrame read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        if (buffered != null) {
            TerminalFrame frame = buffered;
            buffered = null;
            return frame;
        }
        while (true) {
            TerminalFrame frame = readOne(input);
            if (frame == null) {
                if (pendingEof != null) {
                    TerminalFrame eof = pendingEof;
                    pendingEof = null;
                    return eof;
                }
                return null;
            }
            if (frame.type() == TerminalFrameType.EOF) {
                pendingEof = frame;
                continue;
            }
            if (pendingEof != null) {
                if (frame.type() == TerminalFrameType.EXIT) {
                    pendingEof = null;
                    return frame;
                }
                buffered = frame;
                TerminalFrame eof = pendingEof;
                pendingEof = null;
                return eof;
            }
            return frame;
        }
    }

    @Override
    public void releaseSession(int sessionId) {
        Integer wire = localToWire.remove(sessionId);
        if (wire != null) {
            wireToLocal.remove(wire);
        }
    }

    /** Logical to wire session id; fails when the session never reached the wire. */
    private int wireId(int sessionId) throws TerminalProtocolException {
        Integer wire = localToWire.get(sessionId);
        if (wire == null) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.UNKNOWN_SESSION,
                    "session " + sessionId + " is not open on the wire");
        }
        return wire;
    }

    private int openSessionForWire(int sessionId) throws TerminalProtocolException {
        if (role == TerminalMux.Role.HOST) {
            if (localToWire.containsKey(sessionId)) {
                throw new TerminalProtocolException(
                        TerminalErrorCode.SESSION_EXISTS, "session already opened on the wire");
            }
            pendingOpens.addLast(sessionId);
            return 0;
        }
        return wireId(sessionId);
    }

    private int localId(int wireSession) throws TerminalProtocolException {
        Integer local = wireToLocal.get(wireSession);
        if (local == null) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.UNKNOWN_SESSION,
                    "wire session " + wireSession + " was never opened");
        }
        return local;
    }

    private TerminalFrame translate(int opcode, int wireSession, byte[] payload)
            throws TerminalProtocolException {
        switch (opcode) {
            case OP_HELLO -> {
                if (role == TerminalMux.Role.GUEST) {
                    Fln1Payloads.Hello hello = Fln1Payloads.parseHello(payload);
                    return new TerminalFrame(TerminalFrameType.HELLO, 0,
                            Fln1Payloads.encodeHello(hello));
                }
                throw new TerminalProtocolException(
                        TerminalErrorCode.UNKNOWN_TYPE, "host received a HELLO request");
            }
            case OP_HELLO_OK -> {
                if (role != TerminalMux.Role.HOST) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.UNKNOWN_TYPE, "guest received HELLO_OK");
                }
                Fln1Payloads.HelloOk hello = Fln1Payloads.parseHelloOk(payload);
                if (hello.version() != VERSION) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.PROTOCOL_VERSION,
                            "payload negotiated version " + hello.version());
                }
                return new TerminalFrame(TerminalFrameType.HELLO, 0,
                        Fln1Payloads.encodeHelloOk(hello));
            }
            case OP_PING -> {
                Fln1Payloads.ping(payload);
                return new TerminalFrame(TerminalFrameType.PING, 0, payload);
            }
            case OP_PONG -> {
                Fln1Payloads.ping(payload);
                return new TerminalFrame(TerminalFrameType.PONG, 0, payload);
            }
            case OP_OPEN_PTY -> {
                if (role != TerminalMux.Role.GUEST) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.UNKNOWN_TYPE, "host received OPEN_PTY");
                }
                Fln1Payloads.parseOpenPty(payload);
                int wire = nextGuestWireId++;
                bind(wire, wire);
                return new TerminalFrame(TerminalFrameType.OPEN_PTY, wire, payload);
            }
            case OP_PTY_OPENED -> {
                if (role != TerminalMux.Role.HOST) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.UNKNOWN_TYPE, "guest received PTY_OPENED");
                }
                Fln1Payloads.parsePtyOpened(payload);
                Integer local = pendingOpens.pollFirst();
                if (local == null) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.INVALID_PAYLOAD, "PTY_OPENED without OPEN_PTY");
                }
                bind(local, wireSession);
                return new TerminalFrame(TerminalFrameType.OPEN_PTY, local, payload);
            }
            case OP_PTY_DATA -> {
                requireDataPayload(payload);
                return new TerminalFrame(TerminalFrameType.DATA, localId(wireSession), payload);
            }
            case OP_RESIZE -> {
                if (role != TerminalMux.Role.GUEST) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.UNKNOWN_TYPE, "host received RESIZE");
                }
                Fln1Payloads.parseResize(payload);
                return new TerminalFrame(TerminalFrameType.RESIZE, localId(wireSession), payload);
            }
            case OP_RESIZED -> {
                if (role != TerminalMux.Role.HOST) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.UNKNOWN_TYPE, "guest received RESIZED");
                }
                if (payload.length != 0) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.INVALID_PAYLOAD, "resized payload must be empty");
                }
                return new TerminalFrame(TerminalFrameType.RESIZED, localId(wireSession), payload);
            }
            case OP_SIGNAL -> {
                if (role != TerminalMux.Role.GUEST) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.UNKNOWN_TYPE, "host received SIGNAL");
                }
                Fln1Payloads.parseSignal(payload);
                return new TerminalFrame(TerminalFrameType.SIGNAL, localId(wireSession), payload);
            }
            case OP_CLOSE -> {
                if (role != TerminalMux.Role.GUEST) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.UNKNOWN_TYPE, "host received CLOSE");
                }
                Fln1Payloads.parseClose(payload);
                return new TerminalFrame(TerminalFrameType.CLOSE, localId(wireSession), payload);
            }
            case OP_CLOSED -> {
                if (role != TerminalMux.Role.HOST) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.UNKNOWN_TYPE, "guest received CLOSED");
                }
                Fln1Payloads.parseClosed(payload);
                return new TerminalFrame(TerminalFrameType.CLOSED, localId(wireSession), payload);
            }
            case OP_EOF -> {
                if (role != TerminalMux.Role.HOST) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.UNKNOWN_TYPE, "guest received EOF");
                }
                requireLength(payload, 0, "eof");
                return new TerminalFrame(TerminalFrameType.EOF, localId(wireSession), payload);
            }
            case OP_EXIT -> {
                if (role != TerminalMux.Role.HOST) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.UNKNOWN_TYPE, "guest received EXIT");
                }
                Fln1Payloads.parseExit(payload);
                return new TerminalFrame(TerminalFrameType.EXIT, localId(wireSession), payload);
            }
            case OP_WINDOW -> {
                if (role != TerminalMux.Role.GUEST) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.UNKNOWN_TYPE, "host received WINDOW");
                }
                Fln1Payloads.parseWindow(payload);
                return new TerminalFrame(TerminalFrameType.WINDOW, localId(wireSession), payload);
            }
            case OP_ERROR -> {
                if (role != TerminalMux.Role.HOST) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.UNKNOWN_TYPE, "guest received ERROR");
                }
                Fln1Payloads.Error error = Fln1Payloads.parseError(payload);
                int session = wireSession == 0 ? 0 : localId(wireSession);
                return new TerminalFrame(TerminalFrameType.ERROR, session, payload);
            }
            default -> throw new TerminalProtocolException(
                    TerminalErrorCode.UNKNOWN_TYPE,
                    "frame type " + opcode + " is not used on the terminal port");
        }
    }

    private TerminalFrame readOne(InputStream input) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        int first = input.read();
        if (first == -1) {
            return null;
        }
        header[0] = (byte) first;
        readFully(input, header, 1, HEADER_BYTES - 1);
        if (header[0] != MAGIC[0]
                || header[1] != MAGIC[1]
                || header[2] != MAGIC[2]
                || header[3] != MAGIC[3]) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.PROTOCOL_MAGIC, "invalid frame magic");
        }
        int version = header[4] & 0xFF;
        if (version != VERSION) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.PROTOCOL_VERSION, "unsupported protocol version: " + version);
        }
        int opcode = header[5] & 0xFF;
        int wireSession = ByteBuffer.wrap(header, 6, 4).order(ORDER).getInt();
        int length = ByteBuffer.wrap(header, 10, 4).order(ORDER).getInt();
        if (wireSession < 0) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "negative wire session id");
        }
        if (length < 0) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.PROTOCOL_LENGTH, "negative frame length");
        }
        if (length > MAX_PAYLOAD_BYTES) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.FRAME_TOO_LARGE, "frame length exceeds limit: " + length);
        }
        byte[] payload = new byte[length];
        readFully(input, payload, 0, length);
        return translate(opcode, wireSession, payload);
    }

    private void bind(int local, int wire) throws TerminalProtocolException {
        Integer existingWire = localToWire.get(local);
        if (existingWire != null && existingWire != wire) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_EXISTS, "local session already bound");
        }
        Integer existingLocal = wireToLocal.get(wire);
        if (existingLocal != null && existingLocal != local) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_EXISTS, "wire session already bound");
        }
        localToWire.put(local, wire);
        wireToLocal.put(wire, local);
    }

    private byte[] header(int opcode, int session, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ORDER);
        buffer.put(MAGIC);
        buffer.put((byte) VERSION);
        buffer.put((byte) opcode);
        buffer.putInt(session);
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    private void requireRole(TerminalMux.Role expected, String what) throws TerminalProtocolException {
        if (role != expected) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.WRONG_ROLE, what + " is not valid for " + role);
        }
    }

    private static void requireDataPayload(byte[] payload) throws TerminalProtocolException {
        if (payload.length == 0 || payload.length > Fln1Payloads.MAX_PTY_DATA) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD,
                    "PTY_DATA must be 1.." + Fln1Payloads.MAX_PTY_DATA + " bytes");
        }
    }

    private static void requireLength(byte[] payload, int expected, String what)
            throws TerminalProtocolException {
        if (payload.length != expected) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD,
                    what + " payload must be " + expected + " bytes");
        }
    }

    private static void readFully(InputStream input, byte[] target, int offset, int length)
            throws TerminalProtocolException {
        int read = 0;
        while (read < length) {
            int count;
            try {
                count = input.read(target, offset + read, length - read);
            } catch (IOException failure) {
                throw new TerminalProtocolException(
                        TerminalErrorCode.TRUNCATED_FRAME, "failed to read frame", failure);
            }
            if (count == -1) {
                throw new TerminalProtocolException(
                        TerminalErrorCode.TRUNCATED_FRAME, "truncated frame", new EOFException());
            }
            read += count;
        }
    }

    /** Maps a host error code to the canonical FLN1 code. */
    public static int wireCode(TerminalErrorCode code) {
        return switch (code) {
            case PROTOCOL_MAGIC, FRAME_TOO_LARGE, CREDIT_EXCEEDED, CREDIT_OVERFLOW,
                    INVALID_PAYLOAD, TRUNCATED_FRAME, PROTOCOL_LENGTH -> 1;
            case PROTOCOL_VERSION -> 2;
            case UNKNOWN_TYPE, WRONG_ROLE -> 4;
            case UNKNOWN_SESSION, SESSION_CLOSED -> 5;
            case SESSION_LIMIT -> 6;
            case SESSION_EXISTS, VM_STOPPED -> 12;
            case INTERNAL -> 14;
        };
    }

    /** Maps a canonical FLN1 code to the closest host classification. */
    public static TerminalErrorCode hostCode(int fln1Code) {
        return switch (fln1Code) {
            case 1 -> TerminalErrorCode.INVALID_PAYLOAD;
            case 2, 3 -> TerminalErrorCode.PROTOCOL_VERSION;
            case 4, 13 -> TerminalErrorCode.UNKNOWN_TYPE;
            case 5 -> TerminalErrorCode.UNKNOWN_SESSION;
            case 6 -> TerminalErrorCode.SESSION_LIMIT;
            default -> TerminalErrorCode.INTERNAL;
        };
    }

    /** Human-readable FLN1 error name; used in messages, never in frame bytes. */
    public static String wireCodeName(int code) {
        return switch (code) {
            case 1 -> "PROTOCOL_MALFORMED";
            case 2 -> "UNSUPPORTED_VERSION";
            case 3 -> "BAD_TOKEN";
            case 4 -> "UNKNOWN_COMMAND";
            case 5 -> "SESSION_NOT_FOUND";
            case 6 -> "SESSION_LIMIT";
            case 7 -> "EXEC_FAILED";
            case 8 -> "STORAGE_FAILED";
            case 9 -> "PATH_REJECTED";
            case 10 -> "EXPORT_BUSY";
            case 11 -> "EXPORT_IO";
            case 12 -> "PAYLOAD_BUSY";
            case 13 -> "UNSUPPORTED";
            case 14 -> "INTERNAL";
            default -> "UNKNOWN_" + code;
        };
    }
}
