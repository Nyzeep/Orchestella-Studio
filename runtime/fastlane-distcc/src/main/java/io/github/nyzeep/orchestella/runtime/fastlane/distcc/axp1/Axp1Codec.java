package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * AXP1 v1 wire codec.
 *
 * <pre>
 * header (12 bytes, big-endian): "AXP1" | u8 version | u8 type | u16 flags | u32 payload length
 * </pre>
 *
 * <p>The length is checked against the negotiated maximum before allocation, so
 * a malicious 0xffffffff header cannot exhaust memory.
 */
public final class Axp1Codec {
    public static final byte[] MAGIC = {0x41, 0x58, 0x50, 0x31};
    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 12;
    public static final int DEFAULT_MAX_FRAME = 1_048_576;
    public static final int ABSOLUTE_MAX_FRAME = 16 * 1_048_576;

    private Axp1Codec() {}

    public static byte[] encode(Axp1Frame frame) throws Axp1Exception {
        return encode(frame, DEFAULT_MAX_FRAME);
    }

    public static byte[] encode(Axp1Frame frame, int maxFrame) throws Axp1Exception {
        if (frame.payloadLength() > maxFrame) {
            throw new Axp1Exception(Axp1Error.FRAME_TOO_LARGE, "frame exceeds the negotiated limit");
        }
        byte[] payload = frame.payload();
        byte[] wire = new byte[HEADER_BYTES + payload.length];
        System.arraycopy(MAGIC, 0, wire, 0, MAGIC.length);
        wire[4] = VERSION;
        wire[5] = (byte) frame.type().wireCode();
        wire[6] = 0;
        wire[7] = 0;
        int length = payload.length;
        wire[8] = (byte) (length >>> 24);
        wire[9] = (byte) (length >>> 16);
        wire[10] = (byte) (length >>> 8);
        wire[11] = (byte) length;
        System.arraycopy(payload, 0, wire, HEADER_BYTES, payload.length);
        return wire;
    }

    /** Returns {@code null} on a clean EOF before any header byte. */
    public static Axp1Frame read(InputStream input, int maxFrame) throws Axp1Exception {
        byte[] header = new byte[HEADER_BYTES];
        int first;
        try {
            first = input.read();
        } catch (IOException failure) {
            throw new Axp1Exception(Axp1Error.CLOSED, "transport read failed", failure);
        }
        if (first < 0) {
            return null;
        }
        header[0] = (byte) first;
        readFully(input, header, 1, HEADER_BYTES - 1);
        for (int index = 0; index < MAGIC.length; index++) {
            if (header[index] != MAGIC[index]) {
                throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "bad frame magic");
            }
        }
        int version = header[4] & 0xff;
        if (version != VERSION) {
            throw new Axp1Exception(Axp1Error.VERSION_MISMATCH, "unsupported protocol version");
        }
        Axp1Frame.Type type = Axp1Frame.Type.fromWire(header[5] & 0xff);
        if (type == null) {
            throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "unknown frame type");
        }
        int length = ((header[8] & 0xff) << 24)
                | ((header[9] & 0xff) << 16)
                | ((header[10] & 0xff) << 8)
                | (header[11] & 0xff);
        if (length < 0 || length > maxFrame) {
            throw new Axp1Exception(Axp1Error.FRAME_TOO_LARGE, "frame length outside the limit");
        }
        byte[] payload = new byte[length];
        readFully(input, payload, 0, length);
        return Axp1Frame.of(type, payload);
    }

    /** Decodes one complete frame from {@code wire}; used by golden-vector tests. */
    public static Axp1Frame decode(byte[] wire) throws Axp1Exception {
        Axp1Frame frame = read(new java.io.ByteArrayInputStream(wire), ABSOLUTE_MAX_FRAME);
        if (frame == null) {
            throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "empty frame");
        }
        return frame;
    }

    public static String tokenAscii(byte[] token) {
        return new String(token, StandardCharsets.US_ASCII);
    }

    private static void readFully(InputStream input, byte[] target, int offset, int length)
            throws Axp1Exception {
        int filled = 0;
        while (filled < length) {
            int read;
            try {
                read = input.read(target, offset + filled, length - filled);
            } catch (IOException failure) {
                throw new Axp1Exception(Axp1Error.CLOSED, "transport read failed", failure);
            }
            if (read < 0) {
                throw new Axp1Exception(Axp1Error.PROTOCOL_ERROR, "truncated frame");
            }
            filled += read;
        }
    }
}
