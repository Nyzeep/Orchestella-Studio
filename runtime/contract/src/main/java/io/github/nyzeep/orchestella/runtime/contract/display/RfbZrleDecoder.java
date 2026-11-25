package io.github.nyzeep.orchestella.runtime.contract.display;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Streaming RFC 6143 §7.7.6 ZRLE decoder with a persistent per-connection zlib {@link Inflater}.
 *
 * <p>Decodes 64x64 tiles directly into the caller's RGBA-8888 pixel buffer using fixed-size chunk
 * buffers so large rectangles never materialize an entire uncompressed frame in memory.
 */
final class RfbZrleDecoder implements AutoCloseable {
    static final int MAX_COMPRESSED_RECT_BYTES = RfbProtocol.MAX_BYTES;
    private static final int COMPRESSED_CHUNK_SIZE = 8192;
    private static final int INFLATED_CHUNK_SIZE = 16384;
    private static final long MAX_UNCOMPRESSED_CAP = 24L * 1024 * 1024;
    private static final int TILE_SIZE = 64;

    private final Inflater inflater = new Inflater(false);
    private final Object inflaterLock = new Object();
    private boolean closed;

    private final byte[] compressedChunk = new byte[COMPRESSED_CHUNK_SIZE];
    private final byte[] inflatedChunk = new byte[INFLATED_CHUNK_SIZE];
    private final byte[] paletteBgr = new byte[127 * 3];
    private final byte[] rawTileRowBgr = new byte[TILE_SIZE * 3];
    private final byte[] singleCpixelBgr = new byte[3];

    private int inflatedPos;
    private int inflatedLimit;
    private int compressedRemaining;
    private long uncompressedRead;
    private long maxUncompressed;

    void decodeRectangle(
            DataInputStream input, byte[] pixels, int fbWidth, int x, int y, int rectW, int rectH)
            throws IOException {
        synchronized (inflaterLock) {
            ensureOpen();
        }
        int compressedLength = input.readInt();
        if (compressedLength <= 0 || compressedLength > MAX_COMPRESSED_RECT_BYTES) {
            throw new RfbProtocol.Rejected("invalid ZRLE compressed length: " + compressedLength);
        }
        int tilesX = (rectW + TILE_SIZE - 1) / TILE_SIZE;
        int tilesY = (rectH + TILE_SIZE - 1) / TILE_SIZE;
        maxUncompressed = Math.min(
                MAX_UNCOMPRESSED_CAP,
                (long) rectW * rectH * 4L + (long) tilesX * tilesY * 512L);
        compressedRemaining = compressedLength;
        uncompressedRead = 0;
        inflatedPos = 0;
        inflatedLimit = 0;

        for (int ty = y; ty < y + rectH; ty += TILE_SIZE) {
            int th = Math.min(TILE_SIZE, y + rectH - ty);
            for (int tx = x; tx < x + rectW; tx += TILE_SIZE) {
                int tw = Math.min(TILE_SIZE, x + rectW - tx);
                decodeTile(input, pixels, fbWidth, tx, ty, tw, th);
            }
        }
        drainTrailing(input);
    }

    boolean isClosed() {
        synchronized (inflaterLock) {
            return closed;
        }
    }

    @Override
    public void close() {
        synchronized (inflaterLock) {
            if (!closed) {
                closed = true;
                inflater.end();
            }
        }
    }

    private void ensureOpen() throws RfbProtocol.Rejected {
        if (closed) {
            throw new RfbProtocol.Rejected("ZRLE decoder is closed");
        }
    }

    private void decodeTile(
            DataInputStream input, byte[] pixels, int fbWidth, int tx, int ty, int tw, int th)
            throws IOException {
        int sub = nextInflatedByte(input);
        if (sub == 0) {
            decodeRawTile(input, pixels, fbWidth, tx, ty, tw, th);
        } else if (sub == 1) {
            readInflatedBytes(input, singleCpixelBgr, 0, 3);
            fillSolidTile(
                    pixels, fbWidth, tx, ty, tw, th,
                    singleCpixelBgr[2], singleCpixelBgr[1], singleCpixelBgr[0]);
        } else if (sub >= 2 && sub <= 16) {
            decodePackedPaletteTile(input, pixels, fbWidth, tx, ty, tw, th, sub);
        } else if (sub == 128) {
            decodePlainRleTile(input, pixels, fbWidth, tx, ty, tw, th);
        } else if (sub >= 130) {
            decodePaletteRleTile(input, pixels, fbWidth, tx, ty, tw, th, sub - 128);
        } else {
            throw new RfbProtocol.Rejected("unsupported ZRLE subencoding: " + sub);
        }
    }

    private void decodeRawTile(
            DataInputStream input, byte[] pixels, int fbWidth, int tx, int ty, int tw, int th)
            throws IOException {
        int rowBytes = tw * 3;
        for (int dy = 0; dy < th; dy++) {
            readInflatedBytes(input, rawTileRowBgr, 0, rowBytes);
            int rowBase = (ty + dy) * fbWidth + tx;
            for (int dx = 0; dx < tw; dx++) {
                int src = dx * 3;
                int dst = (rowBase + dx) * 4;
                pixels[dst] = rawTileRowBgr[src + 2];
                pixels[dst + 1] = rawTileRowBgr[src + 1];
                pixels[dst + 2] = rawTileRowBgr[src];
                pixels[dst + 3] = (byte) 255;
            }
        }
    }

    private static void fillSolidTile(
            byte[] pixels, int fbWidth, int tx, int ty, int tw, int th, byte r, byte g, byte b) {
        for (int dy = 0; dy < th; dy++) {
            int rowBase = (ty + dy) * fbWidth + tx;
            for (int dx = 0; dx < tw; dx++) {
                int dst = (rowBase + dx) * 4;
                pixels[dst] = r;
                pixels[dst + 1] = g;
                pixels[dst + 2] = b;
                pixels[dst + 3] = (byte) 255;
            }
        }
    }

    private void decodePackedPaletteTile(
            DataInputStream input,
            byte[] pixels,
            int fbWidth,
            int tx,
            int ty,
            int tw,
            int th,
            int paletteSize)
            throws IOException {
        readInflatedBytes(input, paletteBgr, 0, paletteSize * 3);
        int bitsPerPixel = paletteSize == 2 ? 1 : (paletteSize <= 4 ? 2 : 4);
        int pixelsPerByte = 8 / bitsPerPixel;
        int mask = (1 << bitsPerPixel) - 1;
        for (int dy = 0; dy < th; dy++) {
            int rowBase = (ty + dy) * fbWidth + tx;
            int packed = 0;
            for (int dx = 0; dx < tw; dx++) {
                int slot = dx % pixelsPerByte;
                if (slot == 0) {
                    packed = nextInflatedByte(input);
                }
                int shift = 8 - bitsPerPixel * (slot + 1);
                int index = (packed >>> shift) & mask;
                if (index >= paletteSize) {
                    throw new RfbProtocol.Rejected("ZRLE palette index out of bounds: " + index);
                }
                int pOff = index * 3;
                int dst = (rowBase + dx) * 4;
                pixels[dst] = paletteBgr[pOff + 2];
                pixels[dst + 1] = paletteBgr[pOff + 1];
                pixels[dst + 2] = paletteBgr[pOff];
                pixels[dst + 3] = (byte) 255;
            }
        }
    }

    private void decodePlainRleTile(
            DataInputStream input, byte[] pixels, int fbWidth, int tx, int ty, int tw, int th)
            throws IOException {
        int remaining = tw * th;
        int pixelIdx = 0;
        while (remaining > 0) {
            readInflatedBytes(input, singleCpixelBgr, 0, 3);
            int runLength = readExtendedRunLength(input, remaining, "ZRLE Plain RLE");
            writeTileRun(
                    pixels, fbWidth, tx, ty, tw, pixelIdx, runLength,
                    singleCpixelBgr[2], singleCpixelBgr[1], singleCpixelBgr[0]);
            pixelIdx += runLength;
            remaining -= runLength;
        }
    }

    private void decodePaletteRleTile(
            DataInputStream input,
            byte[] pixels,
            int fbWidth,
            int tx,
            int ty,
            int tw,
            int th,
            int paletteSize)
            throws IOException {
        readInflatedBytes(input, paletteBgr, 0, paletteSize * 3);
        int remaining = tw * th;
        int pixelIdx = 0;
        while (remaining > 0) {
            int tag = nextInflatedByte(input);
            int index = tag & 0x7F;
            if (index >= paletteSize) {
                throw new RfbProtocol.Rejected("ZRLE palette RLE index out of bounds: " + index);
            }
            int runLength = (tag & 0x80) != 0
                    ? readExtendedRunLength(input, remaining, "ZRLE Palette RLE")
                    : 1;
            int pOff = index * 3;
            writeTileRun(
                    pixels, fbWidth, tx, ty, tw, pixelIdx, runLength,
                    paletteBgr[pOff + 2], paletteBgr[pOff + 1], paletteBgr[pOff]);
            pixelIdx += runLength;
            remaining -= runLength;
        }
    }

    private int readExtendedRunLength(DataInputStream input, int remaining, String label)
            throws IOException {
        long runLength = 1;
        int lenByte;
        do {
            lenByte = nextInflatedByte(input);
            runLength += lenByte;
            if (runLength > remaining) {
                throw new RfbProtocol.Rejected(label + " run exceeds tile bounds");
            }
        } while (lenByte == 255);
        return (int) runLength;
    }

    private static void writeTileRun(
            byte[] pixels,
            int fbWidth,
            int tx,
            int ty,
            int tw,
            int startPixelIdx,
            int runLength,
            byte r,
            byte g,
            byte b) {
        int dy = startPixelIdx / tw;
        int dx = startPixelIdx % tw;
        int left = runLength;
        while (left > 0) {
            int span = Math.min(left, tw - dx);
            int dst = ((ty + dy) * fbWidth + (tx + dx)) * 4;
            for (int i = 0; i < span; i++) {
                pixels[dst] = r;
                pixels[dst + 1] = g;
                pixels[dst + 2] = b;
                pixels[dst + 3] = (byte) 255;
                dst += 4;
            }
            left -= span;
            dx = 0;
            dy++;
        }
    }

    private int nextInflatedByte(DataInputStream input) throws IOException {
        if (inflatedPos == inflatedLimit) {
            fillInflated(input);
        }
        return inflatedChunk[inflatedPos++] & 0xFF;
    }

    private void readInflatedBytes(DataInputStream input, byte[] dst, int off, int len)
            throws IOException {
        int remaining = len;
        int cursor = off;
        while (remaining > 0) {
            if (inflatedPos == inflatedLimit) {
                fillInflated(input);
            }
            int copy = Math.min(remaining, inflatedLimit - inflatedPos);
            System.arraycopy(inflatedChunk, inflatedPos, dst, cursor, copy);
            inflatedPos += copy;
            cursor += copy;
            remaining -= copy;
        }
    }

    private void fillInflated(DataInputStream input) throws IOException {
        inflatedPos = 0;
        inflatedLimit = inflateNextChunk(input);
        if (inflatedLimit == 0) {
            throw new RfbProtocol.Rejected("truncated ZRLE rectangle payload");
        }
    }

    private void drainTrailing(DataInputStream input) throws IOException {
        if (inflatedPos < inflatedLimit) {
            throw new RfbProtocol.Rejected("trailing uncompressed bytes in ZRLE rectangle");
        }
        inflatedPos = 0;
        inflatedLimit = 0;
        if (inflateNextChunk(input) > 0) {
            throw new RfbProtocol.Rejected("trailing uncompressed bytes in ZRLE rectangle");
        }
    }

    private int inflateNextChunk(DataInputStream input) throws IOException {
        while (true) {
            int produced;
            boolean needInput;
            boolean finished;
            boolean needsDict;
            synchronized (inflaterLock) {
                ensureOpen();
                try {
                    produced = inflater.inflate(inflatedChunk, 0, inflatedChunk.length);
                } catch (DataFormatException e) {
                    throw new RfbProtocol.Rejected("corrupt ZRLE zlib stream: " + e.getMessage());
                }
                finished = inflater.finished();
                needsDict = inflater.needsDictionary();
                needInput = inflater.needsInput();
            }
            if (needsDict) {
                throw new RfbProtocol.Rejected("ZRLE zlib stream requires dictionary");
            }
            if (produced > 0) {
                uncompressedRead += produced;
                if (uncompressedRead > maxUncompressed) {
                    throw new RfbProtocol.Rejected("ZRLE uncompressed data exceeds rectangle budget");
                }
                return produced;
            }
            if (finished) {
                throw new RfbProtocol.Rejected("unexpected end of ZRLE zlib stream");
            }
            if (!needInput) {
                throw new RfbProtocol.Rejected("ZRLE inflater stalled");
            }
            if (!feedNextCompressedChunk(input)) {
                return 0;
            }
        }
    }

    private boolean feedNextCompressedChunk(DataInputStream input) throws IOException {
        if (compressedRemaining == 0) {
            return false;
        }
        int toRead = Math.min(compressedRemaining, compressedChunk.length);
        input.readFully(compressedChunk, 0, toRead);
        compressedRemaining -= toRead;
        synchronized (inflaterLock) {
            ensureOpen();
            inflater.setInput(compressedChunk, 0, toRead);
        }
        return true;
    }
}
