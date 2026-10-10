package io.github.nyzeep.orchestella.terminal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Protocol for multiplexing multiple virtual terminal (PTY) streams over a single channel.
 *
 * <p>Defines a lightweight, robust framing protocol that encapsulates session identifier,
 * frame types, and payload. Prevents cross-session bleed (data contamination) across concurrent
 * background terminals.
 *
 * <p>Wire Format:
 * <pre>
 * +--------------+-----------+--------------------+------------------+------------------+-----------------+
 * | Magic (2B)   | Type (1B) | SessionIdLen (2B)  | SessionId (var)  | PayloadLen (4B)  | Payload (var)   |
 * | 'P' 'T'      | 0x01..0x03| unsigned short     | UTF-8 bytes      | signed int       | raw bytes       |
 * +--------------+-----------+--------------------+------------------+------------------+-----------------+
 * </pre>
 */
public final class PtyMultiplexerProtocol {

    public static final byte MAGIC_0 = 0x50; // 'P'
    public static final byte MAGIC_1 = 0x54; // 'T'

    public static final byte FRAME_TYPE_DATA = 0x01;
    public static final byte FRAME_TYPE_RESIZE = 0x02;
    public static final byte FRAME_TYPE_CLOSE = 0x03;
    public static final byte FRAME_TYPE_CREATE = 0x04;

    private PtyMultiplexerProtocol() {}

    /**
     * An immutable representation of a decoded multiplexer frame.
     */
    public static final class PtyFrame {
        private final byte type;
        private final String sessionId;
        private final byte[] payload;

        public PtyFrame(byte type, String sessionId, byte[] payload) {
            this.type = type;
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
            this.payload = payload != null ? payload : new byte[0];
        }

        public byte type() {
            return type;
        }

        public String sessionId() {
            return sessionId;
        }

        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }

        public String payloadAsString() {
            return new String(payload, StandardCharsets.UTF_8);
        }

        public int resizeColumns() {
            if (type != FRAME_TYPE_RESIZE || payload.length < 8) {
                return 80;
            }
            return ByteBuffer.wrap(payload, 0, 4).getInt();
        }

        public int resizeRows() {
            if (type != FRAME_TYPE_RESIZE || payload.length < 8) {
                return 24;
            }
            return ByteBuffer.wrap(payload, 4, 4).getInt();
        }

        @Override
        public String toString() {
            return "PtyFrame{type=" + type + ", sessionId='" + sessionId + "', payloadLen=" + payload.length + "}";
        }
    }

    /**
     * Encodes a data payload for a specific session.
     */
    public static byte[] encodeDataFrame(String sessionId, byte[] data) {
        return encodeFrame(FRAME_TYPE_DATA, sessionId, data);
    }

    /**
     * Encodes a text payload for a specific session.
     */
    public static byte[] encodeDataFrame(String sessionId, String text) {
        byte[] bytes = text != null ? text.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return encodeDataFrame(sessionId, bytes);
    }

    /**
     * Encodes a terminal window resize notification frame.
     */
    public static byte[] encodeResizeFrame(String sessionId, int columns, int rows) {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putInt(columns);
        buf.putInt(rows);
        return encodeFrame(FRAME_TYPE_RESIZE, sessionId, buf.array());
    }

    /**
     * Encodes a session close notification frame.
     */
    public static byte[] encodeCloseFrame(String sessionId) {
        return encodeFrame(FRAME_TYPE_CLOSE, sessionId, new byte[0]);
    }

    /**
     * Low-level frame encoder.
     */
    public static byte[] encodeFrame(byte type, String sessionId, byte[] payload) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        byte[] sessionBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        if (sessionBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("Session ID too long (max 65535 bytes)");
        }
        int payloadLen = payload != null ? payload.length : 0;

        ByteArrayOutputStream baos = new ByteArrayOutputStream(2 + 1 + 2 + sessionBytes.length + 4 + payloadLen);
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeByte(MAGIC_0);
            dos.writeByte(MAGIC_1);
            dos.writeByte(type);
            dos.writeShort(sessionBytes.length);
            dos.write(sessionBytes);
            dos.writeInt(payloadLen);
            if (payloadLen > 0) {
                dos.write(payload, 0, payloadLen);
            }
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Unexpected serialization failure", e);
        }
    }

    /**
     * Decodes a single frame from the provided wire bytes.
     */
    public static PtyFrame decodeFrame(byte[] wireBytes) throws IOException {
        if (wireBytes == null || wireBytes.length < 9) { // 2 magic + 1 type + 2 idLen + 0 id + 4 payloadLen
            throw new IOException("Frame is too short (length: " + (wireBytes == null ? 0 : wireBytes.length) + ")");
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(wireBytes);
        DataInputStream dis = new DataInputStream(bais);

        byte m0 = dis.readByte();
        byte m1 = dis.readByte();
        if (m0 != MAGIC_0 || m1 != MAGIC_1) {
            throw new IOException(String.format("Invalid magic bytes: 0x%02X 0x%02X, expected 'PT'", m0, m1));
        }

        byte type = dis.readByte();
        int sessionLen = dis.readUnsignedShort();
        byte[] sessionBuf = new byte[sessionLen];
        try {
            dis.readFully(sessionBuf);
        } catch (EOFException eof) {
            throw new IOException("Truncated sessionId: expected " + sessionLen + " bytes", eof);
        }
        String sessionId = new String(sessionBuf, StandardCharsets.UTF_8);

        int payloadLen = dis.readInt();
        if (payloadLen < 0 || payloadLen > 10 * 1024 * 1024) {
            throw new IOException("Invalid payload length: " + payloadLen);
        }
        byte[] payload = new byte[payloadLen];
        try {
            dis.readFully(payload);
        } catch (EOFException eof) {
            throw new IOException("Truncated payload: expected " + payloadLen + " bytes", eof);
        }

        return new PtyFrame(type, sessionId, payload);
    }

    /**
     * Parses all frames present in an input stream until EOF.
     */
    public static List<PtyFrame> parseFrames(InputStream in) throws IOException {
        List<PtyFrame> frames = new ArrayList<>();
        DataInputStream dis = new DataInputStream(in);
        while (true) {
            int first;
            try {
                first = dis.read();
            } catch (EOFException eof) {
                break;
            }
            if (first == -1) {
                break;
            }
            if ((byte) first != MAGIC_0) {
                continue; // search for sync magic
            }
            byte m1 = dis.readByte();
            if (m1 != MAGIC_1) {
                continue;
            }
            byte type = dis.readByte();
            int sessionLen = dis.readUnsignedShort();
            byte[] sessionBuf = new byte[sessionLen];
            dis.readFully(sessionBuf);
            String sessionId = new String(sessionBuf, StandardCharsets.UTF_8);

            int payloadLen = dis.readInt();
            if (payloadLen < 0 || payloadLen > 10 * 1024 * 1024) {
                throw new IOException("Invalid payload length: " + payloadLen);
            }
            byte[] payload = new byte[payloadLen];
            dis.readFully(payload);

            frames.add(new PtyFrame(type, sessionId, payload));
        }
        return frames;
    }

    /**
     * Parses frames from a contiguous chunk of bytes.
     */
    public static List<PtyFrame> parseFrames(byte[] chunk) throws IOException {
        if (chunk == null || chunk.length == 0) {
            return new ArrayList<>();
        }
        return parseFrames(new ByteArrayInputStream(chunk));
    }
}
