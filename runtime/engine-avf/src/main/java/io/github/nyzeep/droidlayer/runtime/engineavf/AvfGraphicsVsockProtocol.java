package io.github.nyzeep.droidlayer.runtime.engineavf;

import io.github.nyzeep.droidlayer.runtime.contract.WaylandChannel;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Protocol codec, constants, and frame validation utilities for the AVF vsock
 * graphics channel (port 9103).
 *
 * <p>Conforms to ADR-0017 and Ticket 42:
 * <ul>
 *   <li>Solidifies dedicated vsock graphics port ({@value #AVF_GRAPHICS_VSOCK_PORT});</li>
 *   <li>Encodes and decodes 36-byte binary frame headers with magic {@code 0x444C4652} ("DLFR");</li>
 *   <li>Enforces strict bounds to defend against corrupted or oversized payloads;</li>
 *   <li>Detects zero-damage (no change) frames for static desktop power optimization.</li>
 * </ul>
 */
public final class AvfGraphicsVsockProtocol {
    public static final int AVF_GRAPHICS_VSOCK_PORT = 9103;
    public static final int DEFAULT_PORT = AVF_GRAPHICS_VSOCK_PORT;

    public static final int FRAME_MAGIC = 0x444C4652; // "DLFR" in ASCII
    public static final int HEADER_SIZE = 36;

    public static final int MIN_DIMENSION = 100;
    public static final int MAX_DIMENSION = 7680;
    public static final double MIN_SCALE = 0.1;
    public static final double MAX_SCALE = 5.0;
    public static final int MIN_FPS = 1;
    public static final int MAX_FPS = 240;

    // Sanity upper bound on single-frame payload (e.g. 7680x4320x4 ~132MB, capped at 160MB)
    public static final int MAX_PAYLOAD_BYTES = 160 * 1024 * 1024;

    private AvfGraphicsVsockProtocol() {}

    /**
     * Immutable decoded representation of a 36-byte frame packet header.
     */
    public record FrameHeader(
            int magic,
            int frameNumber,
            int width,
            int height,
            int stride,
            int format,
            long timestampNs,
            int payloadLength) {}

    /**
     * Serializes a frame header into a 36-byte big-endian byte array.
     */
    public static byte[] encodeFrameHeader(
            int frameNumber,
            int width,
            int height,
            int stride,
            int format,
            long timestampNs,
            int payloadLength) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putInt(FRAME_MAGIC);
        buf.putInt(frameNumber);
        buf.putInt(width);
        buf.putInt(height);
        buf.putInt(stride);
        buf.putInt(format);
        buf.putLong(timestampNs);
        buf.putInt(payloadLength);
        return buf.array();
    }

    /**
     * Deserializes a 36-byte header from byte array, or returns null if length < 36.
     */
    public static FrameHeader decodeFrameHeader(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes, 0, HEADER_SIZE);
        buf.order(ByteOrder.BIG_ENDIAN);
        int magic = buf.getInt();
        int frameNumber = buf.getInt();
        int width = buf.getInt();
        int height = buf.getInt();
        int stride = buf.getInt();
        int format = buf.getInt();
        long timestampNs = buf.getLong();
        int payloadLength = buf.getInt();
        return new FrameHeader(magic, frameNumber, width, height, stride, format, timestampNs, payloadLength);
    }

    /**
     * Validates whether a frame header adheres to protocol dimensions, stride, format, and memory boundaries.
     */
    public static boolean isValidFrameHeader(FrameHeader header) {
        if (header == null) {
            return false;
        }
        if (header.magic() != FRAME_MAGIC) {
            return false;
        }
        if (header.width() < MIN_DIMENSION || header.width() > MAX_DIMENSION) {
            return false;
        }
        if (header.height() < MIN_DIMENSION || header.height() > MAX_DIMENSION) {
            return false;
        }
        if (header.stride() < header.width() * 4) {
            return false;
        }
        if (header.format() < WaylandChannel.FrameData.FORMAT_RGBA_8888
                || header.format() > WaylandChannel.FrameData.FORMAT_BGRX_8888) {
            return false;
        }
        if (header.payloadLength() < 0 || header.payloadLength() > MAX_PAYLOAD_BYTES) {
            return false;
        }
        return true;
    }

    /**
     * Returns true if this frame indicates empty damage regions (payload length 0), meaning
     * the guest desktop has no changes and rendering/dispatch can be skipped.
     */
    public static boolean isDamageEmpty(FrameHeader header) {
        return header != null && header.payloadLength() == 0;
    }

    /**
     * Reads exactly {@code length} bytes from an input stream into {@code dest} starting at {@code offset}.
     *
     * @throws EOFException if stream ends before all bytes are read
     */
    public static void readFully(InputStream in, byte[] dest, int offset, int length) throws IOException {
        int bytesRead = 0;
        while (bytesRead < length) {
            int count = in.read(dest, offset + bytesRead, length - bytesRead);
            if (count < 0) {
                throw new EOFException("Premature EOF while reading " + length + " bytes (read: " + bytesRead + ")");
            }
            bytesRead += count;
        }
    }

    /**
     * Formats the host Bridge v1 hello command.
     */
    public static String formatHelloCommand(String token) {
        return "DROIDLAYER/HELLO 1 1 " + token + "\n";
    }

    /**
     * Formats dynamic resolution resize command.
     */
    public static String formatResizeCommand(int width, int height) {
        return String.format(Locale.ROOT, "DROIDLAYER/DISPLAY RESIZE %d %d\n", width, height);
    }

    /**
     * Formats display mode command with UI scaling factor.
     */
    public static String formatModeCommand(int width, int height, double scale) {
        return String.format(Locale.ROOT, "DROIDLAYER/DISPLAY MODE %d %d %.2f\n", width, height, scale);
    }

    /**
     * Formats target frame rate command.
     */
    public static String formatFpsCommand(int fps) {
        return String.format(Locale.ROOT, "DROIDLAYER/DISPLAY FPS %d\n", fps);
    }

    /**
     * Writes a complete frame packet (header + payload) to output stream.
     */
    public static void writeFramePacket(
            OutputStream out,
            int frameNumber,
            int width,
            int height,
            int stride,
            int format,
            long timestampNs,
            byte[] pixelData) throws IOException {
        int payloadLen = (pixelData != null) ? pixelData.length : 0;
        byte[] header = encodeFrameHeader(frameNumber, width, height, stride, format, timestampNs, payloadLen);
        out.write(header);
        if (payloadLen > 0) {
            out.write(pixelData);
        }
        out.flush();
    }
}
