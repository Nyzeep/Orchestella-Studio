package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Protocol tests for AVF graphics vsock channel:
 * validates 1080p frame header encoding/decoding, Ping-Pong buffer alternation,
 * defensive truncation of malformed/corrupted packets, and dirty region detection.
 */
public final class AvfGraphicsVsockProtocolTest {

    public static void main(String[] arguments) {
        encodesAndDecodes1080pFrameHeader();
        pingPongBufferAlternatesSlotsWithoutSurge();
        defendsAgainstMalformedHeadersAndTruncatedData();
        detectsZeroDamageRegions();
        formatsControlLines();
        System.out.println(
                "AVF_GRAPHICS_VSOCK_PROTOCOL_TEST_OK 1080p_codec=1 ping_pong=1 malformed_defense=1"
                        + " dirty_detection=1 control_format=1");
    }

    private static void encodesAndDecodes1080pFrameHeader() {
        int width = 1920;
        int height = 1080;
        int stride = 1920 * 4;
        int format = WaylandChannel.FrameData.FORMAT_RGBA_8888;
        int frameNumber = 1001;
        long timestampNs = 5_000_000_000L;
        int payloadLength = stride * height; // 8,294,400 bytes

        byte[] headerBytes = AvfGraphicsVsockProtocol.encodeFrameHeader(
                frameNumber, width, height, stride, format, timestampNs, payloadLength);

        assertEquals(AvfGraphicsVsockProtocol.HEADER_SIZE, headerBytes.length, "header size is 36 bytes");

        AvfGraphicsVsockProtocol.FrameHeader header =
                AvfGraphicsVsockProtocol.decodeFrameHeader(headerBytes);

        assertTrue(header != null, "decoded header must not be null");
        assertEquals(AvfGraphicsVsockProtocol.FRAME_MAGIC, header.magic(), "magic must match 0x444C4652");
        assertEquals(frameNumber, header.frameNumber(), "frame number");
        assertEquals(width, header.width(), "width");
        assertEquals(height, header.height(), "height");
        assertEquals(stride, header.stride(), "stride");
        assertEquals(format, header.format(), "format");
        assertEquals(timestampNs, header.timestampNs(), "timestampNs");
        assertEquals(payloadLength, header.payloadLength(), "payloadLength");

        assertTrue(AvfGraphicsVsockProtocol.isValidFrameHeader(header), "header is valid");
    }

    private static void pingPongBufferAlternatesSlotsWithoutSurge() {
        int width = 1920;
        int height = 1080;
        int payloadSize = width * height * 4;

        AvfPingPongBuffer bufferPool = new AvfPingPongBuffer(payloadSize);

        assertEquals(0, bufferPool.activeSlot(), "initial slot is Ping (0)");
        byte[] pingBuf1 = bufferPool.activeBuffer();
        assertEquals(payloadSize, pingBuf1.length, "ping buffer capacity");

        // Write marker in Ping slot
        pingBuf1[0] = 0x11;

        // Swap to Pong (Slot 1)
        bufferPool.swap();
        assertEquals(1, bufferPool.activeSlot(), "swapped to Pong (1)");
        byte[] pongBuf1 = bufferPool.activeBuffer();
        assertEquals(payloadSize, pongBuf1.length, "pong buffer capacity");

        // Write marker in Pong slot
        pongBuf1[0] = 0x22;

        // Swap back to Ping (Slot 0)
        bufferPool.swap();
        assertEquals(0, bufferPool.activeSlot(), "swapped back to Ping (0)");
        byte[] pingBuf2 = bufferPool.activeBuffer();

        // Memory identity check: buffer was reused, zero reallocation
        assertTrue(pingBuf1 == pingBuf2, "buffer instances are reused without garbage surge");
        assertEquals((byte) 0x11, pingBuf2[0], "ping buffer data preserved across alternate swaps");

        bufferPool.swap();
        byte[] pongBuf2 = bufferPool.activeBuffer();
        assertTrue(pongBuf1 == pongBuf2, "pong buffer instance reused without surge");
        assertEquals((byte) 0x22, pongBuf2[0], "pong buffer data preserved");
    }

    private static void defendsAgainstMalformedHeadersAndTruncatedData() {
        // 1. Invalid magic
        byte[] badMagic = AvfGraphicsVsockProtocol.encodeFrameHeader(
                1, 1920, 1080, 7680, 1, 0L, 1920 * 1080 * 4);
        ByteBuffer.wrap(badMagic).order(ByteOrder.BIG_ENDIAN).putInt(0, 0x12345678);
        AvfGraphicsVsockProtocol.FrameHeader headerBadMagic =
                AvfGraphicsVsockProtocol.decodeFrameHeader(badMagic);
        assertTrue(!AvfGraphicsVsockProtocol.isValidFrameHeader(headerBadMagic), "bad magic is rejected");

        // 2. Out of bounds width / height (too small or too large)
        byte[] tooSmall = AvfGraphicsVsockProtocol.encodeFrameHeader(
                1, 50, 50, 200, 1, 0L, 50 * 50 * 4);
        AvfGraphicsVsockProtocol.FrameHeader headerTooSmall =
                AvfGraphicsVsockProtocol.decodeFrameHeader(tooSmall);
        assertTrue(!AvfGraphicsVsockProtocol.isValidFrameHeader(headerTooSmall), "dimensions < 100 rejected");

        byte[] tooLarge = AvfGraphicsVsockProtocol.encodeFrameHeader(
                1, 10000, 8000, 40000, 1, 0L, 10000 * 8000 * 4);
        AvfGraphicsVsockProtocol.FrameHeader headerTooLarge =
                AvfGraphicsVsockProtocol.decodeFrameHeader(tooLarge);
        assertTrue(!AvfGraphicsVsockProtocol.isValidFrameHeader(headerTooLarge), "dimensions > 7680 rejected");

        // 3. Stride less than width * 4
        byte[] badStride = AvfGraphicsVsockProtocol.encodeFrameHeader(
                1, 800, 600, 800, 1, 0L, 800 * 600 * 4);
        AvfGraphicsVsockProtocol.FrameHeader headerBadStride =
                AvfGraphicsVsockProtocol.decodeFrameHeader(badStride);
        assertTrue(!AvfGraphicsVsockProtocol.isValidFrameHeader(headerBadStride), "stride < width * 4 rejected");

        // 4. Invalid pixel format
        byte[] badFormat = AvfGraphicsVsockProtocol.encodeFrameHeader(
                1, 800, 600, 3200, 99, 0L, 800 * 600 * 4);
        AvfGraphicsVsockProtocol.FrameHeader headerBadFormat =
                AvfGraphicsVsockProtocol.decodeFrameHeader(badFormat);
        assertTrue(!AvfGraphicsVsockProtocol.isValidFrameHeader(headerBadFormat), "unknown format rejected");

        // 5. Truncated header bytes (< 36 bytes)
        byte[] truncated = new byte[20];
        AvfGraphicsVsockProtocol.FrameHeader headerTruncated =
                AvfGraphicsVsockProtocol.decodeFrameHeader(truncated);
        assertTrue(headerTruncated == null, "truncated header bytes decode to null");
    }

    private static void detectsZeroDamageRegions() {
        byte[] zeroPayload = AvfGraphicsVsockProtocol.encodeFrameHeader(
                10, 1920, 1080, 7680, 1, 1000L, 0);
        AvfGraphicsVsockProtocol.FrameHeader zeroHeader =
                AvfGraphicsVsockProtocol.decodeFrameHeader(zeroPayload);
        assertTrue(AvfGraphicsVsockProtocol.isValidFrameHeader(zeroHeader), "zero damage header is structurally valid");
        assertTrue(AvfGraphicsVsockProtocol.isDamageEmpty(zeroHeader), "isDamageEmpty is true when payload is 0");

        byte[] normalPayload = AvfGraphicsVsockProtocol.encodeFrameHeader(
                11, 1920, 1080, 7680, 1, 2000L, 1920 * 1080 * 4);
        AvfGraphicsVsockProtocol.FrameHeader normalHeader =
                AvfGraphicsVsockProtocol.decodeFrameHeader(normalPayload);
        assertTrue(!AvfGraphicsVsockProtocol.isDamageEmpty(normalHeader), "isDamageEmpty is false when payload > 0");
    }

    private static void formatsControlLines() {
        assertEquals("ORCHESTELLA/DISPLAY RESIZE 1920 1080\n",
                AvfGraphicsVsockProtocol.formatResizeCommand(1920, 1080), "resize line");
        assertEquals("ORCHESTELLA/DISPLAY MODE 1920 1080 1.50\n",
                AvfGraphicsVsockProtocol.formatModeCommand(1920, 1080, 1.5), "mode line");
        assertEquals("ORCHESTELLA/DISPLAY FPS 60\n",
                AvfGraphicsVsockProtocol.formatFpsCommand(60), "fps line");
        assertEquals("ORCHESTELLA/HELLO 1 1 test-token\n",
                AvfGraphicsVsockProtocol.formatHelloCommand("test-token"), "hello line");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
