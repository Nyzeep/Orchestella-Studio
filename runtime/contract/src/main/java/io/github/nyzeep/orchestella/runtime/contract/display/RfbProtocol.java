package io.github.nyzeep.orchestella.runtime.contract.display;

import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel.FrameData;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;

/** Original, deliberately small RFB 3.8 decoder. No compression, clipboard or CopyRect capability. */
final class RfbProtocol {
    static final int MAX_EDGE = 4096;
    static final int MAX_PIXELS = 4_194_304;
    static final int MAX_BYTES = 16_777_216;
    static final int MAX_RECTANGLES = 256;
    static final int MAX_NAME_BYTES = 4096;
    static final int MAX_CUT_TEXT_BYTES = 65_536;
    private static final byte[] VERSION = ascii("RFB 003.008\n");
    private static final byte[] PIXEL_FORMAT = {
        32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0
    };

    @FunctionalInterface
    interface Sender {
        void send(byte[] bytes) throws IOException;
    }

    static final class Rejected extends IOException {
        private static final long serialVersionUID = 1L;

        Rejected(String message) {
            super(message);
        }
    }

    private final DataInputStream input;
    private byte[] pixels;
    private final BitSet knownPixels = new BitSet();
    private int knownCount;
    private int width;
    private int height;
    private long pixelBytes;
    private long updates;
    private long rectangles;
    private long sizeGeneration;

    private RfbProtocol(DataInputStream input, int width, int height) throws IOException {
        this.input = input;
        resize(width, height);
    }

    static RfbProtocol negotiate(DataInputStream input, Sender sender, String token) throws IOException {
        sender.send(ascii("ORCHESTELLA/DISPLAY 1 " + token + "\n"));
        if (!"ORCHESTELLA/DISPLAY READY 1\n".equals(readLine(input, 128))) {
            throw new Rejected("display authentication or relay readiness rejected");
        }
        byte[] version = new byte[12];
        input.readFully(version);
        if (!Arrays.equals(VERSION, version)) {
            throw new Rejected("RFB version must be 3.8");
        }
        sender.send(VERSION);
        int count = input.readUnsignedByte();
        if (count == 0) {
            discard(input, checkedLength(input.readInt(), MAX_NAME_BYTES, "security reason"));
            throw new Rejected("RFB security negotiation rejected");
        }
        if (count > 32) {
            throw new Rejected("RFB security type count exceeds limit");
        }
        boolean hasNone = false;
        for (int index = 0; index < count; index++) {
            hasNone |= input.readUnsignedByte() == 1;
        }
        if (!hasNone) {
            throw new Rejected("private authenticated RFB None security unavailable");
        }
        sender.send(new byte[] {1});
        if (input.readInt() != 0) {
            discard(input, checkedLength(input.readInt(), MAX_NAME_BYTES, "security reason"));
            throw new Rejected("RFB security result rejected");
        }
        sender.send(new byte[] {1}); // shared ClientInit
        int width = input.readUnsignedShort();
        int height = input.readUnsignedShort();
        dimensions(width, height);
        byte[] offeredFormat = new byte[16];
        input.readFully(offeredFormat);
        validateFormat(offeredFormat);
        discard(input, checkedLength(input.readInt(), MAX_NAME_BYTES, "desktop name"));
        byte[] setFormat = new byte[20];
        System.arraycopy(PIXEL_FORMAT, 0, setFormat, 4, PIXEL_FORMAT.length);
        sender.send(setFormat);
        sender.send(new byte[] {2, 0, 0, 2, 0, 0, 0, 0, (byte) 255, (byte) 255, (byte) 255, 33});
        return new RfbProtocol(input, width, height);
    }

    /** Only a complete framebuffer whose every pixel came from Raw payload can be published. */
    boolean readMessage(Runnable beginUpdate) throws IOException {
        int type = input.readUnsignedByte();
        if (type == 2) { // Bell: no pixel update and no additional update request
            return false;
        }
        if (type == 3) { // Ignore bounded clipboard data; never expose clipboard access here.
            beginUpdate.run();
            discard(input, 3);
            discard(input, checkedLength(input.readInt(), MAX_CUT_TEXT_BYTES, "server cut text"));
            return false;
        }
        if (type != 0) {
            throw new Rejected("unsupported RFB server message: " + type);
        }
        beginUpdate.run();
        input.readUnsignedByte();
        int count = input.readUnsignedShort();
        if (count > MAX_RECTANGLES) {
            throw new Rejected("rectangle count exceeds limit");
        }
        boolean raw = false;
        boolean resized = false;
        long updateBytes = 0;
        for (int index = 0; index < count; index++) {
            int x = input.readUnsignedShort();
            int y = input.readUnsignedShort();
            int rectangleWidth = input.readUnsignedShort();
            int rectangleHeight = input.readUnsignedShort();
            int encoding = input.readInt();
            if (encoding == -223) {
                if (x != 0 || y != 0 || resized) {
                    throw new Rejected("invalid or repeated DesktopSize rectangle");
                }
                resize(rectangleWidth, rectangleHeight);
                raw = false;
                resized = true;
            } else if (encoding == 0) {
                dimensions(rectangleWidth, rectangleHeight);
                if ((long) x + rectangleWidth > width || (long) y + rectangleHeight > height) {
                    throw new Rejected("Raw rectangle outside framebuffer");
                }
                updateBytes += (long) rectangleWidth * rectangleHeight * 4;
                if (updateBytes > MAX_BYTES) {
                    throw new Rejected("update pixel bytes exceed limit");
                }
                readRaw(x, y, rectangleWidth, rectangleHeight);
                raw = true;
            } else {
                throw new Rejected("unsupported RFB encoding: " + encoding);
            }
            rectangles++;
        }
        updates++;
        return raw && complete();
    }

    private void readRaw(int x, int y, int rectangleWidth, int rectangleHeight) throws IOException {
        byte[] row = new byte[rectangleWidth * 4];
        for (int dy = 0; dy < rectangleHeight; dy++) {
            input.readFully(row);
            pixelBytes += row.length;
            int start = (y + dy) * width + x;
            int end = start + rectangleWidth;
            for (int missing = knownPixels.nextClearBit(start); missing < end;
                    missing = knownPixels.nextClearBit(missing + 1)) {
                knownCount++;
            }
            knownPixels.set(start, end);
            for (int pixel = 0; pixel < rectangleWidth; pixel++) {
                int source = pixel * 4;
                int destination = (start + pixel) * 4;
                pixels[destination] = row[source + 2];
                pixels[destination + 1] = row[source + 1];
                pixels[destination + 2] = row[source];
                pixels[destination + 3] = (byte) 255;
            }
        }
    }

    private void resize(int newWidth, int newHeight) throws IOException {
        dimensions(newWidth, newHeight);
        width = newWidth;
        height = newHeight;
        pixels = new byte[width * height * 4];
        knownPixels.clear();
        knownCount = 0;
        sizeGeneration++;
    }

    byte[] request() {
        return new byte[] {3, (byte) (complete() ? 1 : 0), 0, 0, 0, 0,
            (byte) (width >>> 8), (byte) width, (byte) (height >>> 8), (byte) height};
    }

    FrameData frame(long id, long timestampNs) {
        return new FrameData(width, height, width * 4, FrameData.FORMAT_RGBA_8888, pixels, id, timestampNs);
    }

    int width() { return width; }
    int height() { return height; }
    long pixelBytes() { return pixelBytes; }
    long updates() { return updates; }
    long rectangles() { return rectangles; }
    long sizeGeneration() { return sizeGeneration; }
    private boolean complete() { return knownCount == width * height; }

    private static void dimensions(int width, int height) throws Rejected {
        if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE
                || (long) width * height > MAX_PIXELS || (long) width * height * 4 > MAX_BYTES) {
            throw new Rejected("framebuffer or rectangle dimensions exceed limit");
        }
    }

    private static void validateFormat(byte[] format) throws Rejected {
        int bits = format[0] & 255;
        int depth = format[1] & 255;
        if ((bits != 8 && bits != 16 && bits != 32) || depth == 0 || depth > bits
                || (format[2] & 255) > 1 || (format[3] & 255) > 1) {
            throw new Rejected("invalid RFB server pixel format");
        }
        if (format[3] == 0) {
            return; // Server default can be indexed; SetPixelFormat explicitly replaces it.
        }
        long used = 0;
        int significant = 0;
        for (int component = 0; component < 3; component++) {
            int maximum = ((format[4 + component * 2] & 255) << 8) | (format[5 + component * 2] & 255);
            int shift = format[10 + component] & 255;
            if (maximum == 0 || (maximum & (maximum + 1)) != 0 || shift >= bits) {
                throw new Rejected("invalid RFB server color component");
            }
            long mask = (long) maximum << shift;
            if ((mask >>> bits) != 0 || (used & mask) != 0) {
                throw new Rejected("overlapping or out-of-range RFB color components");
            }
            used |= mask;
            significant += Integer.bitCount(maximum);
        }
        if (significant > depth) {
            throw new Rejected("RFB component depth exceeds pixel depth");
        }
    }

    private static int checkedLength(int length, int maximum, String label) throws Rejected {
        if (length < 0 || length > maximum) {
            throw new Rejected(label + " length exceeds limit");
        }
        return length;
    }

    private static void discard(DataInputStream input, int length) throws IOException {
        byte[] chunk = new byte[Math.min(length, 1024)];
        while (length > 0) {
            int count = Math.min(length, chunk.length);
            input.readFully(chunk, 0, count);
            length -= count;
        }
    }

    private static String readLine(DataInputStream input, int limit) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() < limit) {
            int next = input.read();
            if (next < 0) {
                throw new EOFException();
            }
            if (next > 127 || next == '\r' || (next < 32 && next != '\n')) {
                throw new Rejected("invalid display authentication response");
            }
            line.append((char) next);
            if (next == '\n') {
                return line.toString();
            }
        }
        throw new Rejected("display authentication response exceeds limit");
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
