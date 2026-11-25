package io.github.nyzeep.orchestella.runtime.contract.display;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.Test;

/** Unit tests and synthetic benchmarks for {@link RfbZrleDecoder} (RFC 6143 §7.7.6). */
public final class RfbZrleDecoderTest {

    @Test
    public void decodesAllSubencodingsInSingleAndMultiTileRectangles() throws Exception {
        // Test 1: Subencoding 0 (Raw CPIXEL) on 3x2
        assertSubencodingDecodes(3, 2, (out, expectedRgba) -> {
            out.write(0); // raw
            for (int i = 0; i < 6; i++) {
                byte r = (byte) (10 + i * 20);
                byte g = (byte) (30 + i * 15);
                byte b = (byte) (50 + i * 10);
                writeCpixel(out, r, g, b);
                writeRgba(expectedRgba, i * 4, r, g, b);
            }
        });

        // Test 2: Subencoding 1 (Solid) on 5x4
        assertSubencodingDecodes(5, 4, (out, expectedRgba) -> {
            out.write(1); // solid
            writeCpixel(out, (byte) 0xAA, (byte) 0x55, (byte) 0x11);
            for (int i = 0; i < 20; i++) {
                writeRgba(expectedRgba, i * 4, (byte) 0xAA, (byte) 0x55, (byte) 0x11);
            }
        });

        // Test 3: Subencoding 2 (Packed Palette 1 bpp, non-byte-aligned width 10x3)
        assertSubencodingDecodes(10, 3, (out, expectedRgba) -> {
            out.write(2); // palette size 2
            writeCpixel(out, (byte) 10, (byte) 20, (byte) 30);   // idx 0
            writeCpixel(out, (byte) 200, (byte) 210, (byte) 220); // idx 1
            // 10 pixels per row -> 2 bytes per row, MSB-first, 6 padding bits ignored
            for (int row = 0; row < 3; row++) {
                int byte0 = 0;
                int byte1 = 0;
                for (int col = 0; col < 10; col++) {
                    int idx = (row + col) & 1;
                    if (col < 8) {
                        byte0 |= (idx << (7 - col));
                    } else {
                        byte1 |= (idx << (7 - (col - 8)));
                    }
                    int off = (row * 10 + col) * 4;
                    if (idx == 0) {
                        writeRgba(expectedRgba, off, (byte) 10, (byte) 20, (byte) 30);
                    } else {
                        writeRgba(expectedRgba, off, (byte) 200, (byte) 210, (byte) 220);
                    }
                }
                out.write(byte0);
                out.write(byte1);
            }
        });

        // Test 4: Subencoding 4 (Packed Palette 2 bpp, width 5x2 -> 2 bytes/row)
        assertSubencodingDecodes(5, 2, (out, expectedRgba) -> {
            out.write(4); // palette size 4
            byte[][] pal = {{1, 2, 3}, {40, 50, 60}, {100, 110, 120}, {(byte) 250, (byte) 240, (byte) 230}};
            for (byte[] c : pal) {
                writeCpixel(out, c[0], c[1], c[2]);
            }
            for (int row = 0; row < 2; row++) {
                int[] rowIdx = {row % 4, (row + 1) % 4, (row + 2) % 4, (row + 3) % 4, (row + 1) % 4};
                out.write((rowIdx[0] << 6) | (rowIdx[1] << 4) | (rowIdx[2] << 2) | rowIdx[3]);
                out.write(rowIdx[4] << 6);
                for (int col = 0; col < 5; col++) {
                    byte[] c = pal[rowIdx[col]];
                    writeRgba(expectedRgba, (row * 5 + col) * 4, c[0], c[1], c[2]);
                }
            }
        });

        // Test 5: Subencoding 16 (Packed Palette 4 bpp, width 3x2 -> 2 bytes/row)
        assertSubencodingDecodes(3, 2, (out, expectedRgba) -> {
            out.write(16); // palette size 16
            byte[][] pal = new byte[16][3];
            for (int i = 0; i < 16; i++) {
                pal[i][0] = (byte) (i * 15);
                pal[i][1] = (byte) (255 - i * 15);
                pal[i][2] = (byte) (i * 7);
                writeCpixel(out, pal[i][0], pal[i][1], pal[i][2]);
            }
            // Row 0: indices 0, 15, 7 -> bytes [0x0F, 0x70]
            out.write(0x0F);
            out.write(0x70);
            // Row 1: indices 1, 8, 12 -> bytes [0x18, 0xC0]
            out.write(0x18);
            out.write(0xC0);
            int[] all = {0, 15, 7, 1, 8, 12};
            for (int i = 0; i < 6; i++) {
                byte[] c = pal[all[i]];
                writeRgba(expectedRgba, i * 4, c[0], c[1], c[2]);
            }
        });

        // Test 6: Subencoding 128 (Plain RLE) including multi-byte run length > 255
        assertSubencodingDecodes(64, 8, (out, expectedRgba) -> {
            out.write(128); // Plain RLE
            // Run 1: 300 pixels = 1 + 255 + 44
            writeCpixel(out, (byte) 0x12, (byte) 0x34, (byte) 0x56);
            out.write(255);
            out.write(44);
            // Run 2: 212 pixels = 1 + 211 (total 512)
            writeCpixel(out, (byte) 0x99, (byte) 0x88, (byte) 0x77);
            out.write(211);
            for (int i = 0; i < 512; i++) {
                if (i < 300) {
                    writeRgba(expectedRgba, i * 4, (byte) 0x12, (byte) 0x34, (byte) 0x56);
                } else {
                    writeRgba(expectedRgba, i * 4, (byte) 0x99, (byte) 0x88, (byte) 0x77);
                }
            }
        });

        // Test 7: Subencoding 131 (Palette RLE, paletteSize=3, both single-pixel and multi-pixel runs)
        assertSubencodingDecodes(64, 5, (out, expectedRgba) -> {
            out.write(131); // 128 + 3
            byte[][] pal = {{11, 22, 33}, {44, 55, 66}, {77, 88, 99}};
            for (byte[] c : pal) {
                writeCpixel(out, c[0], c[1], c[2]);
            }
            // Single pixel idx 0
            out.write(0);
            // Run of 256 of idx 1 -> (0x80 | 1), 255, 0
            out.write(0x80 | 1);
            out.write(255);
            out.write(0);
            // Run of 63 of idx 2 -> (0x80 | 2), 62 (total 1 + 256 + 63 = 320)
            out.write(0x80 | 2);
            out.write(62);
            for (int i = 0; i < 320; i++) {
                int idx = i == 0 ? 0 : (i <= 256 ? 1 : 2);
                writeRgba(expectedRgba, i * 4, pal[idx][0], pal[idx][1], pal[idx][2]);
            }
        });

        // Test 8: Subencoding 255 (Palette RLE max palette size 127)
        assertSubencodingDecodes(16, 8, (out, expectedRgba) -> {
            out.write(255); // 128 + 127
            byte[][] pal = new byte[127][3];
            for (int i = 0; i < 127; i++) {
                pal[i][0] = (byte) (i * 2);
                pal[i][1] = (byte) (255 - i * 2);
                pal[i][2] = (byte) (i + 10);
                writeCpixel(out, pal[i][0], pal[i][1], pal[i][2]);
            }
            for (int i = 0; i < 128; i++) {
                int idx = i % 127;
                out.write(idx);
                writeRgba(expectedRgba, i * 4, pal[idx][0], pal[idx][1], pal[idx][2]);
            }
        });

        // Test 9: Packed Palette boundary sizes 3 (2 bpp) and 5 (4 bpp)
        assertSubencodingDecodes(3, 1, (out, expectedRgba) -> {
            out.write(3);
            writeCpixel(out, (byte) 1, (byte) 2, (byte) 3);
            writeCpixel(out, (byte) 4, (byte) 5, (byte) 6);
            writeCpixel(out, (byte) 7, (byte) 8, (byte) 9);
            out.write((0 << 6) | (1 << 4) | (2 << 2));
            writeRgba(expectedRgba, 0, (byte) 1, (byte) 2, (byte) 3);
            writeRgba(expectedRgba, 4, (byte) 4, (byte) 5, (byte) 6);
            writeRgba(expectedRgba, 8, (byte) 7, (byte) 8, (byte) 9);
        });
        assertSubencodingDecodes(3, 1, (out, expectedRgba) -> {
            out.write(5);
            for (int i = 0; i < 5; i++) {
                writeCpixel(out, (byte) (i * 10), (byte) (i * 20), (byte) (i * 30));
            }
            out.write(0x42); // idx 4, idx 2
            out.write(0x10); // idx 1
            writeRgba(expectedRgba, 0, (byte) 40, (byte) 80, (byte) 120);
            writeRgba(expectedRgba, 4, (byte) 20, (byte) 40, (byte) 60);
            writeRgba(expectedRgba, 8, (byte) 10, (byte) 20, (byte) 30);
        });
    }

    @Test
    public void decodesNonMultipleOf64ResolutionsAndPartialOffsetsAcrossPersistentStream() throws Exception {
        try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
            try {
                // 101x103 odd dimensions (2x2 tiles: 64x64, 37x64, 64x39, 37x39)
                verifyFullFrameEncoding(decoder, deflater, 101, 103);

                // Partial sub-rectangle inside a 1080x2400 framebuffer
                int fbW = 1080;
                int fbH = 2400;
                byte[] fb = new byte[fbW * fbH * 4];
                int rx = 65;
                int ry = 130;
                int rw = 1015; // 1080 - 65 (non-multiple of 64)
                int rh = 200;
                byte[] rectExpected = syntheticPattern(rw, rh, 42);
                byte[] compressed = encodePatternZrle(deflater, rectExpected, rw, rh);
                decoder.decodeRectangle(frameStream(compressed), fb, fbW, rx, ry, rw, rh);
                assertSubRectMatches(fb, fbW, rx, ry, rw, rh, rectExpected);

                // Full 1390x3012 (<= 4,194,304 pixels: 1390*3012 = 4,186,680) across same persistent stream
                verifyFullFrameEncoding(decoder, deflater, 1390, 3012);
            } finally {
                deflater.end();
            }
        }
    }

    @Test
    public void rejectsIllegalSubencodingsAndOutOfBoundsPaletteOrRle() throws Exception {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        try {
            // Illegal subencodings 17..127 and 129
            for (int illegal : new int[] {17, 64, 127, 129}) {
                try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                    deflater.reset();
                    byte[] compressed = deflateRect(deflater, new byte[] {(byte) illegal, 0, 0, 0});
                    assertRejected(() -> decoder.decodeRectangle(frameStream(compressed), new byte[16], 2, 0, 0, 2, 2),
                            "subencoding");
                }
            }

            // Packed palette index out of bounds (paletteSize = 3, index = 3)
            try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                deflater.reset();
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                raw.write(3); // palette size 3
                writeCpixel(raw, (byte) 1, (byte) 2, (byte) 3);
                writeCpixel(raw, (byte) 4, (byte) 5, (byte) 6);
                writeCpixel(raw, (byte) 7, (byte) 8, (byte) 9);
                raw.write(0xC0); // first 2-bit pixel is index 3 (>= 3)
                byte[] compressed = deflateRect(deflater, raw.toByteArray());
                assertRejected(() -> decoder.decodeRectangle(frameStream(compressed), new byte[4], 1, 0, 0, 1, 1),
                        "palette index");
            }

            // Palette RLE index out of bounds (paletteSize = 2 -> sub 130, index = 2)
            try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                deflater.reset();
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                raw.write(130);
                writeCpixel(raw, (byte) 1, (byte) 2, (byte) 3);
                writeCpixel(raw, (byte) 4, (byte) 5, (byte) 6);
                raw.write(2); // index 2 >= 2
                byte[] compressed = deflateRect(deflater, raw.toByteArray());
                assertRejected(() -> decoder.decodeRectangle(frameStream(compressed), new byte[4], 1, 0, 0, 1, 1),
                        "palette RLE index");
            }

            // Plain RLE overflow (2x2 tile = 4 pixels, run length 5)
            try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                deflater.reset();
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                raw.write(128);
                writeCpixel(raw, (byte) 1, (byte) 2, (byte) 3);
                raw.write(4); // runLength = 5 > 4
                byte[] compressed = deflateRect(deflater, raw.toByteArray());
                assertRejected(() -> decoder.decodeRectangle(frameStream(compressed), new byte[16], 2, 0, 0, 2, 2),
                        "exceeds tile bounds");
            }

            // Plain RLE huge 255-chain overflow guard
            try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                deflater.reset();
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                raw.write(128);
                writeCpixel(raw, (byte) 1, (byte) 2, (byte) 3);
                raw.write(255);
                raw.write(255);
                byte[] compressed = deflateRect(deflater, raw.toByteArray());
                assertRejected(() -> decoder.decodeRectangle(frameStream(compressed), new byte[16], 2, 0, 0, 2, 2),
                        "exceeds tile bounds");
            }

            // Palette RLE overflow (2x2 tile = 4 pixels, run length 5)
            try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                deflater.reset();
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                raw.write(130);
                writeCpixel(raw, (byte) 1, (byte) 2, (byte) 3);
                writeCpixel(raw, (byte) 4, (byte) 5, (byte) 6);
                raw.write(0x80 | 1);
                raw.write(4); // runLength = 5 > 4
                byte[] compressed = deflateRect(deflater, raw.toByteArray());
                assertRejected(() -> decoder.decodeRectangle(frameStream(compressed), new byte[16], 2, 0, 0, 2, 2),
                        "exceeds tile bounds");
            }
        } finally {
            deflater.end();
        }
    }

    @Test
    public void rejectsZlibBombTrailingBytesTruncatedCorruptAndInvalidLengths() throws Exception {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, false);
        try {
            // Zlib bomb: 1x1 tile only allows 516 bytes uncompressed, feed 200KB of compressed zeros
            try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                byte[] bomb = new byte[200_000];
                bomb[0] = 1; // Solid subencoding header, followed by huge trailing zeros
                byte[] compressedBomb = deflateRect(deflater, bomb);
                assertRejected(() -> decoder.decodeRectangle(frameStream(compressedBomb), new byte[4], 1, 0, 0, 1, 1),
                        "budget");
            }

            // Trailing uncompressed bytes after valid 1x1 solid tile
            try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                deflater.reset();
                byte[] compressed = deflateRect(deflater, new byte[] {1, 10, 20, 30, 99});
                assertRejected(() -> decoder.decodeRectangle(frameStream(compressed), new byte[4], 1, 0, 0, 1, 1),
                        "trailing");
            }

            // Truncated compressed stream (valid header but missing pixels inside compressedLength)
            try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                deflater.reset();
                byte[] compressed = deflateRect(deflater, new byte[] {1, 10}); // missing G, R
                assertRejected(() -> decoder.decodeRectangle(frameStream(compressed), new byte[4], 1, 0, 0, 1, 1),
                        "truncated");
            }

            // Premature socket EOF before compressedLength bytes arrive
            try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                ByteArrayOutputStream wire = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(wire);
                dos.writeInt(100);
                dos.write(new byte[5]);
                DataInputStream eofStream = new DataInputStream(new ByteArrayInputStream(wire.toByteArray()));
                try {
                    decoder.decodeRectangle(eofStream, new byte[4], 1, 0, 0, 1, 1);
                    fail("expected EOFException");
                } catch (EOFException expected) {
                    // Expected premature EOF
                }
            }

            // Corrupt deflate bytes
            try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                byte[] corrupt = new byte[] {0x78, (byte) 0x9c, (byte) 0xff, (byte) 0xff, 0x01, 0x02};
                assertRejected(() -> decoder.decodeRectangle(frameStream(corrupt), new byte[4], 1, 0, 0, 1, 1),
                        "corrupt");
            }

            // Invalid compressed lengths (<= 0 and > MAX_COMPRESSED_RECT_BYTES)
            for (int badLen : new int[] {0, -1, RfbZrleDecoder.MAX_COMPRESSED_RECT_BYTES + 1}) {
                try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                    ByteArrayOutputStream wire = new ByteArrayOutputStream();
                    new DataOutputStream(wire).writeInt(badLen);
                    DataInputStream badStream = new DataInputStream(new ByteArrayInputStream(wire.toByteArray()));
                    assertRejected(() -> decoder.decodeRectangle(badStream, new byte[4], 1, 0, 0, 1, 1),
                            "compressed length");
                }
            }

            // Z_FINISH instead of Z_SYNC_FLUSH must be rejected as stream ended
            try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
                deflater.reset();
                deflater.setInput(new byte[] {1, 10, 20, 30});
                deflater.finish();
                byte[] buf = new byte[128];
                int n = deflater.deflate(buf);
                byte[] finishedBytes = Arrays.copyOf(buf, n);
                assertRejected(() -> decoder.decodeRectangle(frameStream(finishedBytes), new byte[4], 1, 0, 0, 1, 1),
                        "zlib stream");
            }

            // Idempotent close and reject after close
            RfbZrleDecoder decoder = new RfbZrleDecoder();
            assertFalse(decoder.isClosed());
            decoder.close();
            assertTrue(decoder.isClosed());
            decoder.close();
            assertTrue(decoder.isClosed());
            deflater.reset();
            byte[] valid = deflateRect(deflater, new byte[] {1, 1, 2, 3});
            assertRejected(() -> decoder.decodeRectangle(frameStream(valid), new byte[4], 1, 0, 0, 1, 1),
                    "closed");
        } finally {
            deflater.end();
        }
    }

    @Test
    public void syntheticBenchmarkRawVsZrleAt1080x2400And1390x3012() throws Exception {
        runSyntheticBenchmark(1080, 2400);
        runSyntheticBenchmark(1390, 3012);
    }

    private static void runSyntheticBenchmark(int width, int height) throws Exception {
        byte[] desktopRgba = syntheticUiFramebuffer(width, height);
        int rawWireBytes = width * height * 4;
        byte[] rawWire = new byte[rawWireBytes];
        for (int i = 0; i < width * height; i++) {
            rawWire[i * 4] = desktopRgba[i * 4 + 2];
            rawWire[i * 4 + 1] = desktopRgba[i * 4 + 1];
            rawWire[i * 4 + 2] = desktopRgba[i * 4];
            rawWire[i * 4 + 3] = 0;
        }

        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        byte[] zrleCompressed;
        try {
            zrleCompressed = encodePatternZrle(deflater, desktopRgba, width, height);
        } finally {
            deflater.end();
        }
        int zrleWireBytes = 4 + zrleCompressed.length;

        // Measure ZRLE decode
        byte[] zrleOut = new byte[rawWireBytes];
        long zrleStart = System.nanoTime();
        try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
            decoder.decodeRectangle(frameStream(zrleCompressed), zrleOut, width, 0, 0, width, height);
        }
        long zrleNanos = System.nanoTime() - zrleStart;
        assertArrayEquals(desktopRgba, zrleOut);

        // Measure Raw unpack
        byte[] rawOut = new byte[rawWireBytes];
        long rawStart = System.nanoTime();
        for (int i = 0; i < width * height; i++) {
            int off = i * 4;
            rawOut[off] = rawWire[off + 2];
            rawOut[off + 1] = rawWire[off + 1];
            rawOut[off + 2] = rawWire[off];
            rawOut[off + 3] = (byte) 255;
        }
        long rawNanos = System.nanoTime() - rawStart;
        assertArrayEquals(desktopRgba, rawOut);

        double ratio = (double) rawWireBytes / zrleWireBytes;
        System.out.printf("ZRLE Benchmark %dx%d: rawWire=%d B (%.2f ms), zrleWire=%d B (%.2f ms), compression=%.2fx%n",
                width, height, rawWireBytes, rawNanos / 1_000_000.0, zrleWireBytes, zrleNanos / 1_000_000.0, ratio);
        assertTrue("ZRLE should compress synthetic UI desktop significantly", zrleWireBytes < rawWireBytes / 5);
    }

    @FunctionalInterface
    private interface TileEncoder {
        void write(ByteArrayOutputStream out, byte[] expectedRgba) throws IOException;
    }

    private static void assertSubencodingDecodes(int w, int h, TileEncoder encoder) throws Exception {
        ByteArrayOutputStream uncompressed = new ByteArrayOutputStream();
        byte[] expected = new byte[w * h * 4];
        encoder.write(uncompressed, expected);

        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        try (RfbZrleDecoder decoder = new RfbZrleDecoder()) {
            byte[] compressed = deflateRect(deflater, uncompressed.toByteArray());
            byte[] actual = new byte[w * h * 4];
            decoder.decodeRectangle(frameStream(compressed), actual, w, 0, 0, w, h);
            assertArrayEquals(expected, actual);
        } finally {
            deflater.end();
        }
    }

    private static void verifyFullFrameEncoding(RfbZrleDecoder decoder, Deflater deflater, int width, int height)
            throws IOException {
        byte[] expected = syntheticPattern(width, height, 7);
        byte[] compressed = encodePatternZrle(deflater, expected, width, height);
        byte[] actual = new byte[width * height * 4];
        decoder.decodeRectangle(frameStream(compressed), actual, width, 0, 0, width, height);
        assertArrayEquals(expected, actual);
    }

    private static byte[] encodePatternZrle(Deflater deflater, byte[] rgba, int width, int height) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int ty = 0; ty < height; ty += 64) {
            int th = Math.min(64, height - ty);
            for (int tx = 0; tx < width; tx += 64) {
                int tw = Math.min(64, width - tx);
                // Check if tile is solid
                int firstOff = (ty * width + tx) * 4;
                byte r0 = rgba[firstOff];
                byte g0 = rgba[firstOff + 1];
                byte b0 = rgba[firstOff + 2];
                boolean solid = true;
                for (int dy = 0; dy < th && solid; dy++) {
                    for (int dx = 0; dx < tw; dx++) {
                        int off = ((ty + dy) * width + (tx + dx)) * 4;
                        if (rgba[off] != r0 || rgba[off + 1] != g0 || rgba[off + 2] != b0) {
                            solid = false;
                            break;
                        }
                    }
                }
                if (solid) {
                    raw.write(1);
                    writeCpixel(raw, r0, g0, b0);
                } else {
                    raw.write(128); // Plain RLE
                    int runCount = 0;
                    byte runR = 0, runG = 0, runB = 0;
                    for (int dy = 0; dy < th; dy++) {
                        for (int dx = 0; dx < tw; dx++) {
                            int off = ((ty + dy) * width + (tx + dx)) * 4;
                            byte r = rgba[off];
                            byte g = rgba[off + 1];
                            byte b = rgba[off + 2];
                            if (runCount == 0) {
                                runR = r; runG = g; runB = b; runCount = 1;
                            } else if (r == runR && g == runG && b == runB) {
                                runCount++;
                            } else {
                                writePlainRleRun(raw, runR, runG, runB, runCount);
                                runR = r; runG = g; runB = b; runCount = 1;
                            }
                        }
                    }
                    if (runCount > 0) {
                        writePlainRleRun(raw, runR, runG, runB, runCount);
                    }
                }
            }
        }
        return deflateRect(deflater, raw.toByteArray());
    }

    private static void writePlainRleRun(ByteArrayOutputStream out, byte r, byte g, byte b, int count) {
        writeCpixel(out, r, g, b);
        int rem = count - 1;
        while (rem >= 255) {
            out.write(255);
            rem -= 255;
        }
        out.write(rem);
    }

    private static byte[] syntheticPattern(int width, int height, int seed) {
        byte[] rgba = new byte[width * height * 4];
        Random rng = new Random(seed);
        byte[] bandColors = new byte[12];
        rng.nextBytes(bandColors);
        for (int y = 0; y < height; y++) {
            int band = (y / 32) % 4;
            for (int x = 0; x < width; x++) {
                int off = (y * width + x) * 4;
                if ((x + y) % 97 == 0) {
                    rgba[off] = (byte) (x & 0xFF);
                    rgba[off + 1] = (byte) (y & 0xFF);
                    rgba[off + 2] = (byte) ((x ^ y) & 0xFF);
                } else {
                    rgba[off] = bandColors[band * 3];
                    rgba[off + 1] = bandColors[band * 3 + 1];
                    rgba[off + 2] = bandColors[band * 3 + 2];
                }
                rgba[off + 3] = (byte) 255;
            }
        }
        return rgba;
    }

    private static byte[] syntheticUiFramebuffer(int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            boolean topBar = y < 96;
            boolean dock = y >= height - 120;
            for (int x = 0; x < width; x++) {
                int off = (y * width + x) * 4;
                boolean card = !topBar && !dock && x >= 80 && x < width - 80 && y >= 240 && y < height - 240;
                if (topBar) {
                    writeRgba(rgba, off, (byte) 30, (byte) 34, (byte) 42);
                } else if (dock) {
                    writeRgba(rgba, off, (byte) 22, (byte) 24, (byte) 30);
                } else if (card) {
                    boolean textRow = (y % 48) >= 16 && (y % 48) < 26 && x >= 140 && x < width - 200 && ((x / 12) % 3 != 0);
                    if (textRow) {
                        writeRgba(rgba, off, (byte) 32, (byte) 36, (byte) 44);
                    } else {
                        writeRgba(rgba, off, (byte) 248, (byte) 249, (byte) 251);
                    }
                } else {
                    writeRgba(rgba, off, (byte) 45, (byte) 85, (byte) 140);
                }
            }
        }
        return rgba;
    }

    private static void assertSubRectMatches(byte[] fb, int fbW, int rx, int ry, int rw, int rh, byte[] expected) {
        for (int dy = 0; dy < rh; dy++) {
            int fbOff = ((ry + dy) * fbW + rx) * 4;
            int expOff = dy * rw * 4;
            for (int dx = 0; dx < rw * 4; dx++) {
                assertEquals("mismatch at dy=" + dy + " byte=" + dx, expected[expOff + dx], fb[fbOff + dx]);
            }
        }
    }

    private static void writeCpixel(ByteArrayOutputStream out, byte r, byte g, byte b) {
        out.write(b & 0xFF);
        out.write(g & 0xFF);
        out.write(r & 0xFF);
    }

    private static void writeRgba(byte[] dst, int offset, byte r, byte g, byte b) {
        dst[offset] = r;
        dst[offset + 1] = g;
        dst[offset + 2] = b;
        dst[offset + 3] = (byte) 255;
    }

    private static byte[] deflateRect(Deflater deflater, byte[] uncompressed) {
        deflater.setInput(uncompressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (true) {
            int n = deflater.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH);
            if (n > 0) {
                out.write(buf, 0, n);
            }
            if (deflater.needsInput() && n < buf.length) {
                break;
            }
        }
        return out.toByteArray();
    }

    private static DataInputStream frameStream(byte[] compressed) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(wire);
        dos.writeInt(compressed.length);
        dos.write(compressed);
        dos.flush();
        return new DataInputStream(new ByteArrayInputStream(wire.toByteArray()));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertRejected(ThrowingRunnable action, String expectedSubstring) throws Exception {
        try {
            action.run();
            fail("Expected RfbProtocol.Rejected containing: " + expectedSubstring);
        } catch (RfbProtocol.Rejected rejected) {
            assertTrue("Expected '" + expectedSubstring + "' in: " + rejected.getMessage(),
                    rejected.getMessage().toLowerCase().contains(expectedSubstring.toLowerCase()));
        }
    }
}
